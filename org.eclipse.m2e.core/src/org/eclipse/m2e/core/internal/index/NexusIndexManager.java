/*******************************************************************************
 * Copyright (c) 2008-2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Sonatype, Inc. - initial API and implementation
 *******************************************************************************/

package org.eclipse.m2e.core.internal.index;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.osgi.util.NLS;

import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;

import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FilteredQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryWrapperFilter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import org.apache.maven.index.ArtifactContext;
import org.apache.maven.index.ArtifactContextProducer;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.Field;
import org.apache.maven.index.IteratorSearchRequest;
import org.apache.maven.index.IteratorSearchResponse;
import org.apache.maven.index.MAVEN;
import org.apache.maven.index.NexusIndexer;
import org.apache.maven.index.SearchType;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.index.artifact.IllegalArtifactCoordinateException;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.creator.JarFileContentsIndexCreator;
import org.apache.maven.index.creator.MavenArchetypeArtifactInfoIndexCreator;
import org.apache.maven.index.creator.MavenPluginArtifactInfoIndexCreator;
import org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator;
import org.apache.maven.index.fs.Lock;
import org.apache.maven.index.updater.IndexUpdateRequest;
import org.apache.maven.index.updater.IndexUpdateResult;
import org.apache.maven.index.updater.IndexUpdater;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.proxy.ProxyInfo;

import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.core.IMavenConstants;
import org.eclipse.m2e.core.embedder.ArtifactKey;
import org.eclipse.m2e.core.embedder.ArtifactRepositoryRef;
import org.eclipse.m2e.core.embedder.IMaven;
import org.eclipse.m2e.core.embedder.IMavenConfiguration;
import org.eclipse.m2e.core.index.IIndex;
import org.eclipse.m2e.core.index.IndexListener;
import org.eclipse.m2e.core.index.IndexManager;
import org.eclipse.m2e.core.index.IndexedArtifact;
import org.eclipse.m2e.core.index.IndexedArtifactFile;
import org.eclipse.m2e.core.index.MatchTyped;
import org.eclipse.m2e.core.index.MatchTyped.MatchType;
import org.eclipse.m2e.core.index.SearchExpression;
import org.eclipse.m2e.core.index.SourcedSearchExpression;
import org.eclipse.m2e.core.internal.Messages;
import org.eclipse.m2e.core.internal.index.IndexUpdaterJob.IndexCommand;
import org.eclipse.m2e.core.internal.repository.IRepositoryIndexer;
import org.eclipse.m2e.core.project.IMavenProjectChangedListener;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.MavenProjectChangedEvent;
import org.eclipse.m2e.core.project.MavenProjectManager;
import org.eclipse.m2e.core.repository.IRepository;
import org.eclipse.m2e.core.repository.IRepositoryRegistry;


/**
 * @author Eugene Kuleshov
 */
public class NexusIndexManager implements IndexManager, IMavenProjectChangedListener, IRepositoryIndexer {
  private static final Logger log = LoggerFactory.getLogger(NexusIndexManager.class);

  public static final int MIN_CLASS_QUERY_LENGTH = 6;

  /**
   * Lazy instantiated nexus indexer instance.
   */
  private NexusIndexer indexer;

  /**
   * Lazy instantiated nexus indexer's contextProducer.
   */
  private ArtifactContextProducer artifactContextProducer;

  /**
   * Lock guarding lazy instantiation of indexerLock instance
   */
  private final Object indexerLock = new Object();

  /**
   * Lock guarding lazy instantiation of contextProducer instance
   */
  private final Object contextProducerLock = new Object();

  private IMaven maven;

  private MavenProjectManager projectManager;

  private IRepositoryRegistry repositoryRegistry;

  private ArrayList<IndexCreator> fullCreators = null;

  private ArrayList<IndexCreator> minCreators = null;

  private final File baseIndexDir;

  private final List<IndexListener> indexListeners = new ArrayList<IndexListener>();

  private NexusIndex localIndex;

  private final NexusIndex workspaceIndex;

  private final IndexUpdaterJob updaterJob;

  private Properties indexDetails = new Properties();

  private Set<String> updatingIndexes = new HashSet<String>();

  private IndexUpdater indexUpdater;

  private static final EquinoxLocker locker = new EquinoxLocker();

  /**
   * Maps repository UID to the lock object associated with the repository. Entries are only added but never directly
   * removed from the map, although jvm garbage collector may remove otherwise unused entries to reclaim the little
   * memory they use. Never access this map directly. #getIndexLock must be used to get repository lock object.
   */
  private final Map<String, Object> indexLocks = new WeakHashMap<String, Object>();

  public NexusIndexManager(MavenProjectManager projectManager,
      IRepositoryRegistry repositoryRegistry, File stateDir) {
    this.projectManager = projectManager;
    this.repositoryRegistry = repositoryRegistry;
    this.baseIndexDir = new File(stateDir, "nexus"); //$NON-NLS-1$

    this.maven = MavenPlugin.getDefault().getMaven();
    this.indexUpdater = MavenPlugin.getDefault().getIndexUpdater();

    this.updaterJob = new IndexUpdaterJob(this);

    this.workspaceIndex = new NexusIndex(this, repositoryRegistry.getWorkspaceRepository(), NexusIndex.DETAILS_MIN);
  }

  private NexusIndex newLocalIndex(IRepository localRepository) {
    return new NexusIndex(this, localRepository, NexusIndex.DETAILS_FULL);
  }

  private ArrayList<IndexCreator> getFullCreator() {
    if(fullCreators == null) {
      try {
        PlexusContainer container = MavenPlugin.getDefault().getPlexusContainer();
        IndexCreator min = container.lookup(IndexCreator.class, MinimalArtifactInfoIndexCreator.ID);
        IndexCreator mavenPlugin = container.lookup(IndexCreator.class, MavenPluginArtifactInfoIndexCreator.ID);
        IndexCreator mavenArchetype = container.lookup(IndexCreator.class, MavenArchetypeArtifactInfoIndexCreator.ID);
        IndexCreator jar = container.lookup(IndexCreator.class, JarFileContentsIndexCreator.ID);

        fullCreators = new ArrayList<IndexCreator>();
        fullCreators.add(min);
        fullCreators.add(jar);
        fullCreators.add(mavenPlugin);
        fullCreators.add(mavenArchetype);
      } catch(ComponentLookupException ce) {
        String msg = "Error looking up component ";
        log.error(msg, ce);
      }
    }
    return fullCreators;
  }

  private ArrayList<IndexCreator> getMinCreator() {
    if(minCreators == null) {
      try {
        PlexusContainer container = MavenPlugin.getDefault().getPlexusContainer();
        IndexCreator min = container.lookup(IndexCreator.class, MinimalArtifactInfoIndexCreator.ID);
        IndexCreator mavenArchetype = container.lookup(IndexCreator.class, MavenArchetypeArtifactInfoIndexCreator.ID);
        minCreators = new ArrayList<IndexCreator>();
        minCreators.add(min);
        minCreators.add(mavenArchetype);
      } catch(ComponentLookupException ce) {
        String msg = "Error looking up component ";
        log.error(msg, ce);
      }

    }
    return minCreators;
  }

  /** for Unit test */
  public IndexedArtifactFile getIndexedArtifactFile(IRepository repository, ArtifactKey gav) throws CoreException {

    try {
      BooleanQuery query = new BooleanQuery();
      query.add(constructQuery(MAVEN.GROUP_ID, gav.getGroupId(), SearchType.EXACT), BooleanClause.Occur.MUST);
      query.add(constructQuery(MAVEN.ARTIFACT_ID, gav.getArtifactId(), SearchType.EXACT), BooleanClause.Occur.MUST);
      query.add(constructQuery(MAVEN.VERSION, gav.getVersion(), SearchType.EXACT), BooleanClause.Occur.MUST);

      if(gav.getClassifier() != null) {
        query.add(constructQuery(MAVEN.CLASSIFIER, gav.getClassifier(), SearchType.EXACT), BooleanClause.Occur.MUST);
      }

      synchronized(getIndexLock(repository)) {
        ArtifactInfo artifactInfo = getIndexer().identify(query, Collections.singleton(getIndexingContext(repository)));
        if(artifactInfo != null) {
          return getIndexedArtifactFile(artifactInfo);
        }
      }
    } catch(Exception ex) {
      String msg = "Illegal artifact coordinate " + ex.getMessage();
      log.error(msg, ex);
      throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1,
          Messages.NexusIndexManager_error_search, ex));
    }
    return null;
  }

  /** for Unit test */
  public IndexedArtifactFile getIndexedArtifactFile(ArtifactInfo artifactInfo) {
    String groupId = artifactInfo.groupId;
    String artifactId = artifactInfo.artifactId;
    String repository = artifactInfo.repository;
    String version = artifactInfo.version;
    String classifier = artifactInfo.classifier;
    String packaging = artifactInfo.packaging;
    String fname = artifactInfo.fname;
    if(fname == null) {
      fname = artifactId + '-' + version
          + (classifier != null ? '-' + classifier : "") + (packaging != null ? ('.' + packaging) : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    long size = artifactInfo.size;
    Date date = new Date(artifactInfo.lastModified);

    int sourcesExists = artifactInfo.sourcesExists.ordinal();
    int javadocExists = artifactInfo.javadocExists.ordinal();

    String prefix = artifactInfo.prefix;
    List<String> goals = artifactInfo.goals;

    return new IndexedArtifactFile(repository, groupId, artifactId, version, packaging, classifier, fname, size, date,
        sourcesExists, javadocExists, prefix, goals);
  }

  public IndexedArtifactFile identify(File file) throws CoreException {
    try {
      ArtifactInfo artifactInfo = getIndexer().identify(file);
      return artifactInfo == null ? null : getIndexedArtifactFile(artifactInfo);
    } catch(IOException ex) {
      throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1,
          Messages.NexusIndexManager_error_search, ex));
    }
  }

  protected IndexedArtifactFile identify(IRepository repository, File file) throws CoreException {
    try {
      IndexingContext context = getIndexingContext(repository);
      ArtifactInfo artifactInfo = identify(file, Collections.singleton(context));
      return artifactInfo == null ? null : getIndexedArtifactFile(artifactInfo);
    } catch(IOException ex) {
      throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1,
          Messages.NexusIndexManager_error_search, ex));
    }
  }

  /**
   * Method to construct Lucene Queries without need to actually know the structure and details (field names, analyze
   * details, etc) of the underlying index. Also, using this methods makes you "future proof". Naturally, at caller
   * level you can still combine these queries using BooleanQuery to suit your needs.
   * 
   * @param field
   * @param query
   * @param type
   * @return
   */
  public Query constructQuery(Field field, SearchExpression query) {
    // let the default be "scored" search
    SearchType st = SearchType.SCORED;

    if(query instanceof MatchTyped) {
      MatchType mt = ((MatchTyped) query).getMatchType();

      if(MatchType.EXACT.equals(mt)) {
        st = SearchType.EXACT;
      }
    }

    return constructQuery(field, query.getStringValue(), st);
  }

  private Query constructQuery(Field field, String query, SearchType searchType) {
    return getIndexer().constructQuery(field, query, searchType);
  }

  public Map<String, IndexedArtifact> search(SearchExpression term, String type) throws CoreException {
    return search(null, term, type, IIndex.SEARCH_ALL);
  }

  public Map<String, IndexedArtifact> search(SearchExpression term, String type, int classifier) throws CoreException {
    return search(null, term, type, classifier);
  }

  private void addClassifiersToQuery(BooleanQuery bq, int classifier) {
    boolean includeJavaDocs = (classifier & IIndex.SEARCH_JAVADOCS) > 0;
    Query tq = null;
    if(!includeJavaDocs) {
      tq = constructQuery(MAVEN.CLASSIFIER, "javadoc", SearchType.EXACT); //$NON-NLS-1$
      bq.add(tq, Occur.MUST_NOT);
    }
    boolean includeSources = (classifier & IIndex.SEARCH_SOURCES) > 0;
    if(!includeSources) {
      tq = constructQuery(MAVEN.CLASSIFIER, "sources", SearchType.EXACT); //$NON-NLS-1$
      bq.add(tq, Occur.MUST_NOT);
    }
    boolean includeTests = (classifier & IIndex.SEARCH_TESTS) > 0;
    if(!includeTests) {
      tq = constructQuery(MAVEN.CLASSIFIER, "tests", SearchType.EXACT); //$NON-NLS-1$
      bq.add(tq, Occur.MUST_NOT);
    }
  }

  /**
   * @return Map<String, IndexedArtifact>
   */
  protected Map<String, IndexedArtifact> search(IRepository repository, SearchExpression term, String type,
      int classifier) throws CoreException {
    Query query;
    if(IIndex.SEARCH_GROUP.equals(type)) {
      query = constructQuery(MAVEN.GROUP_ID, term);

      // query = new TermQuery(new Term(ArtifactInfo.GROUP_ID, term));
      // query = new PrefixQuery(new Term(ArtifactInfo.GROUP_ID, term));
    } else if(IIndex.SEARCH_ARTIFACT.equals(type)) {
      BooleanQuery bq = new BooleanQuery();
      bq.add(constructQuery(MAVEN.GROUP_ID, term), Occur.SHOULD); //$NON-NLS-1$
      bq.add(constructQuery(MAVEN.ARTIFACT_ID, term), Occur.SHOULD); //$NON-NLS-1$
      bq.add(
          constructQuery(MAVEN.SHA1, term.getStringValue(), term.getStringValue().length() == 40 ? SearchType.EXACT
              : SearchType.SCORED), Occur.SHOULD);
      addClassifiersToQuery(bq, classifier);
      query = bq;

    } else if(IIndex.SEARCH_PARENTS.equals(type)) {
      if(term == null) {
        query = constructQuery(MAVEN.PACKAGING, "pom", SearchType.EXACT); //$NON-NLS-1$
      } else {
        BooleanQuery bq = new BooleanQuery();
        bq.add(constructQuery(MAVEN.GROUP_ID, term), Occur.SHOULD); //$NON-NLS-1$
        bq.add(constructQuery(MAVEN.ARTIFACT_ID, term), Occur.SHOULD); //$NON-NLS-1$
        bq.add(
            constructQuery(MAVEN.SHA1, term.getStringValue(), term.getStringValue().length() == 40 ? SearchType.EXACT
                : SearchType.SCORED), Occur.SHOULD);
        Query tq = constructQuery(MAVEN.PACKAGING, "pom", SearchType.EXACT); //$NON-NLS-1$
        query = new FilteredQuery(tq, new QueryWrapperFilter(bq));
      }

    } else if(IIndex.SEARCH_PLUGIN.equals(type)) {
      if(term == null) {
        query = constructQuery(MAVEN.PACKAGING, "maven-plugin", SearchType.EXACT); //$NON-NLS-1$
      } else {
        BooleanQuery bq = new BooleanQuery();
        bq.add(constructQuery(MAVEN.GROUP_ID, term), Occur.SHOULD); //$NON-NLS-1$
        bq.add(constructQuery(MAVEN.ARTIFACT_ID, term), Occur.SHOULD); //$NON-NLS-1$
        Query tq = constructQuery(MAVEN.PACKAGING, "maven-plugin", SearchType.EXACT); //$NON-NLS-1$
        query = new FilteredQuery(tq, new QueryWrapperFilter(bq));
      }

    } else if(IIndex.SEARCH_ARCHETYPE.equals(type)) {
      BooleanQuery bq = new BooleanQuery();
      bq.add(constructQuery(MAVEN.GROUP_ID, term), Occur.SHOULD); //$NON-NLS-1$
      bq.add(constructQuery(MAVEN.ARTIFACT_ID, term), Occur.SHOULD); //$NON-NLS-1$
      Query tq = constructQuery(MAVEN.PACKAGING, "maven-archetype", SearchType.EXACT); //$NON-NLS-1$
      query = new FilteredQuery(tq, new QueryWrapperFilter(bq));

    } else if(IIndex.SEARCH_PACKAGING.equals(type)) {
      query = constructQuery(MAVEN.PACKAGING, term);
    } else if(IIndex.SEARCH_SHA1.equals(type)) {
      // if hash is 40 chars, it is "complete", otherwise assume prefix
      query = constructQuery(MAVEN.SHA1, term.getStringValue(), term.getStringValue().length() == 40 ? SearchType.EXACT
          : SearchType.SCORED);
    } else {
      return Collections.emptyMap();
    }

    Map<String, IndexedArtifact> result = new TreeMap<String, IndexedArtifact>();

    try {
      IteratorSearchResponse response;

      synchronized(getIndexLock(repository)) {
        IndexingContext context = getIndexingContext(repository);
        if(context == null) {
          response = getIndexer().searchIterator(new IteratorSearchRequest(query));
        } else {
          response = getIndexer().searchIterator(new IteratorSearchRequest(query, context));
        }

        for(ArtifactInfo artifactInfo : response.getResults()) {
          addArtifactFile(result, getIndexedArtifactFile(artifactInfo), null, null, artifactInfo.packaging);
        }

        // https://issues.sonatype.org/browse/MNGECLIPSE-1630
        // lucene can't handle prefix queries that match many index entries.
        // to workaround, use term query to locate group artifacts and manually
        // match subgroups
        if(IIndex.SEARCH_GROUP.equals(type) && context != null) {
          Set<String> groups = context.getAllGroups();
          for(String group : groups) {
            if(term == null || group.startsWith(term.getStringValue()) && !group.equals(term.getStringValue())) {
              String key = getArtifactFileKey(group, group, null, null);
              result.put(key, new IndexedArtifact(group, group, null, null, null));
            }
          }
        }
      }
    } catch(IOException ex) {
      throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1,
          Messages.NexusIndexManager_error_search, ex));
    }

    return result;
  }

  /**
   * @return Map<String, IndexedArtifact>
   */
  protected Map<String, IndexedArtifact> search(IRepository repository, SearchExpression term, String type)
      throws CoreException {
    return search(repository, term, type, IIndex.SEARCH_ALL);
  }

  /**
   * @return Map<String, IndexedArtifact>
   */
  protected Map<String, IndexedArtifact> search(IRepository repository, Query query) throws CoreException {
    Map<String, IndexedArtifact> result = new TreeMap<String, IndexedArtifact>();
    try {
      IteratorSearchResponse response;

      synchronized(getIndexLock(repository)) {
        IndexingContext context = getIndexingContext(repository);
        if(context == null) {
          response = getIndexer().searchIterator(new IteratorSearchRequest(query));
        } else {
          response = getIndexer().searchIterator(new IteratorSearchRequest(query, context));
        }
      }

      for(ArtifactInfo artifactInfo : response.getResults()) {
        addArtifactFile(result, getIndexedArtifactFile(artifactInfo), null, null, artifactInfo.packaging);
      }

    } catch(IOException ex) {
      throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1,
          Messages.NexusIndexManager_error_search, ex));
    }
    return result;
  }

  private void addArtifactFile(Map<String, IndexedArtifact> result, IndexedArtifactFile af, String className,
      String packageName, String packaging) {
    String group = af.group;
    String artifact = af.artifact;
    String key = getArtifactFileKey(group, artifact, packageName, className);
    IndexedArtifact indexedArtifact = result.get(key);
    if(indexedArtifact == null) {
      indexedArtifact = new IndexedArtifact(group, artifact, packageName, className, packaging);
      result.put(key, indexedArtifact);
    }
    indexedArtifact.addFile(af);
  }

  protected String getArtifactFileKey(String group, String artifact, String packageName, String className) {
    return className + " : " + packageName + " : " + group + " : " + artifact; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
  }

  private void purgeCurrentIndex(IndexingContext context) throws IOException {
    context.purge();
  }

  private void reindexLocalRepository(IRepository repository, boolean force, final IProgressMonitor monitor)
      throws CoreException {
    if(!force) {
      return;
    }
    try {
      fireIndexUpdating(repository);
      //IndexInfo indexInfo = getIndexInfo(indexName);
      IndexingContext context = getIndexingContext(repository);
      purgeCurrentIndex(context);
      if(context.getRepository().isDirectory()) {
        getIndexer().scan(context, new ArtifactScanningMonitor(context.getRepository(), monitor), false);
      }
      log.info("Updated local repository index");
    } catch(Exception ex) {
      log.error("Unable to re-index " + repository.toString(), ex);
      throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1,
          Messages.NexusIndexManager_error_reindexing, ex));
    } finally {
      fireIndexChanged(repository);
    }
  }

  private void reindexWorkspace(boolean force, IProgressMonitor monitor) throws CoreException {
    IRepository workspaceRepository = repositoryRegistry.getWorkspaceRepository();
    if(!force)
      return;
    try {
      IndexingContext context = getIndexingContext(workspaceRepository);
      purgeCurrentIndex(context);
      for(IMavenProjectFacade facade : projectManager.getProjects()) {
        addDocument(workspaceRepository, facade.getPomFile(), //
            facade.getArtifactKey());
      }
    } catch(Exception ex) {
      log.error("Unable to re-index " + workspaceRepository.toString(), ex);
      throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1,
          Messages.NexusIndexManager_error_reindexing, ex));
    } finally {
      fireIndexChanged(workspaceRepository);
    }
  }

  protected void addDocument(IRepository repository, File file, ArtifactKey key) {
    synchronized(getIndexLock(repository)) {
      IndexingContext context = getIndexingContext(repository);
      if(context == null) {
        // TODO log
        return;
      }
      try {
        ArtifactContext artifactContext;
        if(repository.isScope(IRepositoryRegistry.SCOPE_WORKSPACE)) {
          IMavenProjectFacade facade = getProjectByArtifactKey(key);
          artifactContext = getWorkspaceArtifactContext(facade, context);
        } else {
          artifactContext = getArtifactContext(file, context);
        }
        getIndexer().addArtifactToIndex(artifactContext, context);
      } catch(Exception ex) {
        String msg = "Unable to add " + getDocumentKey(key);
        log.error(msg, ex);
      }
    }
  }

  private IMavenProjectFacade getProjectByArtifactKey(ArtifactKey artifactKey) throws CoreException {
    for(IMavenProjectFacade facade : projectManager.getProjects()) {
      if(facade.getArtifactKey().equals(artifactKey)) {
        return facade;
      }
    }

    throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1,
        Messages.NexusIndexManager_error_unexpected, new IllegalArgumentException(String.format(
            "Workspace project with key %s not found!", new Object[] {artifactKey})))); //$NON-NLS-1$ 
  }

  protected void removeDocument(IRepository repository, File file, ArtifactKey key, IMavenProjectFacade facade) {
    synchronized(getIndexLock(repository)) {
      try {
        IndexingContext context = getIndexingContext(repository);
        if(context == null) {
          String msg = "Unable to find document to remove" + getDocumentKey(key);
          log.error(msg); //$NON-NLS-1$
          return;
        }
        ArtifactContext artifactContext;
        if(repository.isScope(IRepositoryRegistry.SCOPE_WORKSPACE)) {
          if(facade == null) {
            // try to get one, but you MUST have facade in case of project deletion, see mavenProjectChanged()
            facade = getProjectByArtifactKey(key);
          }
          artifactContext = getWorkspaceArtifactContext(facade, context);
        } else {
          artifactContext = getArtifactContext(file, context);
        }
        getIndexer().deleteArtifactFromIndex(artifactContext, context);
      } catch(Exception ex) {
        String msg = "Unable to remove " + getDocumentKey(key);
        log.error(msg, ex);
      }
    }

    fireIndexChanged(repository);
  }

  private ArtifactContext getArtifactContext(File file, IndexingContext context)
      throws IllegalArtifactCoordinateException {
    return getArtifactContextProducer().getArtifactContext(context, file);
  }

  private ArtifactContext getWorkspaceArtifactContext(IMavenProjectFacade facade, IndexingContext context)
      throws CoreException {
    IRepository workspaceRepository = repositoryRegistry.getWorkspaceRepository();
    ArtifactKey key = facade.getArtifactKey();
    ArtifactInfo ai = new ArtifactInfo(workspaceRepository.getUid(), key.getGroupId(), key.getArtifactId(),
        key.getVersion(), null);
    ai.packaging = facade.getPackaging();
    File pomFile = facade.getPomFile();
    File artifactFile = (pomFile != null) ? pomFile.getParentFile() : null;
    try {
      Gav gav = new Gav(key.getGroupId(), key.getArtifactId(), key.getVersion());
      return new ArtifactContext(pomFile, artifactFile, null, ai, gav);
    } catch(IllegalArtifactCoordinateException ex) {
      throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1,
          Messages.NexusIndexManager_error_unexpected, ex));
    }
  }

  protected void scheduleIndexUpdate(final IRepository repository, final boolean force) {
    if(repository != null) {
      IndexCommand command = new IndexUpdaterJob.IndexCommand() {
        public void run(IProgressMonitor monitor) throws CoreException {
          updateIndex(repository, force, monitor);
        }
      };
      updaterJob.addCommand(command);
      updaterJob.schedule(1000L);
    }
  }

  /** for unit tests */
  public IndexedArtifactGroup[] getRootIndexedArtifactGroups(IRepository repository) throws CoreException {
    synchronized(getIndexLock(repository)) {
      IndexingContext context = getIndexingContext(repository);
      if(context != null) {
        try {
          Set<String> rootGroups = context.getRootGroups();
          IndexedArtifactGroup[] groups = new IndexedArtifactGroup[rootGroups.size()];
          int i = 0;
          for(String group : rootGroups) {
            groups[i++ ] = new IndexedArtifactGroup(repository, group);
          }
          return groups;
        } catch(IOException ex) {
          throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1, //
              NLS.bind(Messages.NexusIndexManager_error_root_grp, repository.toString()), ex));
        }
      }
      return new IndexedArtifactGroup[0];
    }
  }

  /** public for unit tests only! */
  public IndexingContext getIndexingContext(IRepository repository) {
    return repository == null ? null : getIndexer().getIndexingContexts().get(repository.getUid());
  }

  private NexusIndexer getIndexer() {
    synchronized(indexerLock) {
      if(indexer == null) {
        indexer = MavenPlugin.getDefault().getNexusIndexer();
      }
    }
    return indexer;
  }

  private ArtifactContextProducer getArtifactContextProducer() {
    synchronized(contextProducerLock) {
      if(artifactContextProducer == null) {
        artifactContextProducer = MavenPlugin.getDefault().getArtifactContextProducer();
      }
    }
    return artifactContextProducer;
  }

  public static String getDocumentKey(ArtifactKey artifact) {
    String groupId = artifact.getGroupId();
    if(groupId == null) {
      groupId = Messages.NexusIndexManager_inherited;
    }

    String artifactId = artifact.getArtifactId();

    String version = artifact.getVersion();
    if(version == null) {
      version = Messages.NexusIndexManager_inherited;
    }

    String key = groupId.replace('.', '/') + '/' + artifactId + '/' + version + '/' + artifactId + "-" + version; //$NON-NLS-1$

    String classifier = artifact.getClassifier();
    if(classifier != null) {
      key += "-" + classifier; //$NON-NLS-1$
    }

    // TODO use artifact handler to retrieve extension
    // cstamas: will not work since ArtifactKey misses type
    // either get packaging from POM or store/honor extension
    return key + ".pom"; //$NON-NLS-1$
  }

  public void mavenProjectChanged(MavenProjectChangedEvent[] events, IProgressMonitor monitor) {
    /*
     * This method is called while holding workspace lock. Avoid long-running operations if possible. 
     */

    synchronized(getIndexLock(repositoryRegistry.getWorkspaceRepository())) {
      IndexingContext context = getIndexingContext(repositoryRegistry.getWorkspaceRepository());

      if(context != null) {
        // workspace indexing context can by null during startup due to MNGECLIPSE-1633
        for(MavenProjectChangedEvent event : events) {
          IMavenProjectFacade oldFacade = event.getOldMavenProject();
          if(oldFacade != null) {
            removeDocument(repositoryRegistry.getWorkspaceRepository(), oldFacade.getPomFile(),
                oldFacade.getArtifactKey(), oldFacade);
            fireIndexRemoved(repositoryRegistry.getWorkspaceRepository());
          }
          IMavenProjectFacade facade = event.getMavenProject();
          if(facade != null) {
            addDocument(repositoryRegistry.getWorkspaceRepository(), facade.getPomFile(), facade.getArtifactKey());
            fireIndexAdded(repositoryRegistry.getWorkspaceRepository());
          }
        }
      }
    }
  }

  public NexusIndex getWorkspaceIndex() {
    return workspaceIndex;
  }

  public NexusIndex getLocalIndex() {
    IRepository localRepository = repositoryRegistry.getLocalRepository();
    synchronized(getIndexLock(localRepository)) {
      if(localIndex == null) {
        localIndex = newLocalIndex(localRepository);
      }
    }
    return localIndex;
  }

  public IIndex getIndex(IProject project) {
    IMavenProjectFacade projectFacade = project != null ? projectManager.getProject(project) : null;

    ArrayList<IIndex> indexes = new ArrayList<IIndex>();
    indexes.add(getWorkspaceIndex());
    indexes.add(getLocalIndex());

    if(projectFacade != null) {
      LinkedHashSet<ArtifactRepositoryRef> repositories = new LinkedHashSet<ArtifactRepositoryRef>();
      repositories.addAll(projectFacade.getArtifactRepositoryRefs());
      repositories.addAll(projectFacade.getPluginArtifactRepositoryRefs());

      for(ArtifactRepositoryRef repositoryRef : repositories) {
        IRepository repository = repositoryRegistry.getRepository(repositoryRef);
        indexes.add(getIndex(repository));
      }
    } else {
      for(IRepository repository : repositoryRegistry.getRepositories(IRepositoryRegistry.SCOPE_SETTINGS)) {
        indexes.add(getIndex(repository));
      }
    }

    return new CompositeIndex(indexes);
  }

  public IIndex getAllIndexes() {
    ArrayList<IIndex> indexes = new ArrayList<IIndex>();
    indexes.add(getWorkspaceIndex());
    indexes.add(getLocalIndex());

    for(IMavenProjectFacade facade : projectManager.getProjects()) {
      LinkedHashSet<ArtifactRepositoryRef> repositories = new LinkedHashSet<ArtifactRepositoryRef>();
      repositories.addAll(facade.getArtifactRepositoryRefs());
      repositories.addAll(facade.getPluginArtifactRepositoryRefs());

      for(ArtifactRepositoryRef repositoryRef : repositories) {
        IRepository repository = repositoryRegistry.getRepository(repositoryRef);
        indexes.add(getIndex(repository));
      }
    }

    return new CompositeIndex(indexes);
  }

  public NexusIndex getIndex(IRepository repository) {
    String details = getIndexDetails(repository);
    return new NexusIndex(this, repository, details);
  }

  protected File getIndexDirectoryFile(IRepository repository) {
    return new File(baseIndexDir, repository.getUid());
  }

  protected Directory getIndexDirectory(IRepository repository) throws IOException {
    return FSDirectory.getDirectory(getIndexDirectoryFile(repository));
  }

  public IndexedArtifactGroup resolveGroup(IndexedArtifactGroup group) {
    IRepository repository = group.getRepository();
    String prefix = group.getPrefix();
    try {
      IndexedArtifactGroup g = new IndexedArtifactGroup(repository, prefix);
      for(IndexedArtifact a : search(repository, new SourcedSearchExpression(prefix), IIndex.SEARCH_GROUP).values()) {
        String groupId = a.getGroupId();
        if(groupId.equals(prefix)) {
          g.getFiles().put(a.getArtifactId(), a);
        } else if(groupId.startsWith(prefix + ".")) { //$NON-NLS-1$
          int start = prefix.length() + 1;
          int end = groupId.indexOf('.', start);
          String key = end > -1 ? groupId.substring(0, end) : groupId;
          g.getNodes().put(key, new IndexedArtifactGroup(repository, key));
        }
      }

      return g;

    } catch(CoreException ex) {
      log.error("Can't retrieve groups for " + repository.toString() + ":" + prefix, ex); //$NON-NLS-2$
      return group;
    }
  }

  public void repositoryAdded(IRepository repository, IProgressMonitor monitor) throws CoreException {
    String details = getIndexDetails(repository);

    // for consistency, always process indexes using our background thread
    setIndexDetails(repository, null, details, null/*async*/);
  }

  /** For tests only */
  public String getIndexDetails(IRepository repository) {
    String details = indexDetails.getProperty(repository.getUid());

    if(details == null) {
      if(repository.isScope(IRepositoryRegistry.SCOPE_SETTINGS) && repository.getMirrorId() == null) {
        details = NexusIndex.DETAILS_MIN;
      } else if(repository.isScope(IRepositoryRegistry.SCOPE_LOCAL)) {
        details = NexusIndex.DETAILS_MIN;
      } else if(repository.isScope(IRepositoryRegistry.SCOPE_WORKSPACE)) {
        details = NexusIndex.DETAILS_MIN;
      } else {
        details = NexusIndex.DETAILS_DISABLED;
      }
    }

    return details;
  }

  /**
   * Updates index synchronously if monitor!=null. Schedules index update otherwise. ... and yes, I know this ain't
   * kosher. Public for unit tests only!
   */
  public void setIndexDetails(IRepository repository, String details, IProgressMonitor monitor) throws CoreException {
    setIndexDetails(repository, details, details, monitor);
  }

  private void setIndexDetails(IRepository repository, String details, String defaultDetails, IProgressMonitor monitor)
      throws CoreException {
    if(details != null) {
      indexDetails.setProperty(repository.getUid(), details);

      writeIndexDetails();
    } else {
      details = defaultDetails;
    }

    synchronized(getIndexLock(repository)) {
      IndexingContext indexingContext = getIndexingContext(repository);

      try {
        if(NexusIndex.DETAILS_DISABLED.equals(details)) {
          if(indexingContext != null) {
            getIndexer().removeIndexingContext(indexingContext, false /*removeFiles*/);
            fireIndexRemoved(repository);
          }
        } else {
          if(indexingContext != null) {
            getIndexer().removeIndexingContext(indexingContext, false);
          }

          createIndexingContext(repository, details);

          fireIndexAdded(repository);

          if(monitor != null) {
            updateIndex(repository, false, monitor);
          } else {
            scheduleIndexUpdate(repository, false);
          }
        }
      } catch(IOException ex) {
        String msg = "Error changing index details " + repository.toString();
        log.error(msg, ex);
        throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1,
            Messages.NexusIndexManager_error_add_repo, ex));
      }

      if(repository.isScope(IRepositoryRegistry.SCOPE_LOCAL)) {
        // note that we are still synchronized on repository lock at this point
        this.localIndex = newLocalIndex(repositoryRegistry.getLocalRepository());
      }
    }
  }

  protected IndexingContext createIndexingContext(IRepository repository, String details) throws IOException {
    IndexingContext indexingContext;
    Directory directory = getIndexDirectory(repository);

    File repositoryPath = null;
    if(repository.getBasedir() != null) {
      repositoryPath = repository.getBasedir().getCanonicalFile();
    }

    ArrayList<IndexCreator> indexers = getIndexers(details);

    indexingContext = getIndexer().addIndexingContextForced(repository.getUid(), //
        repository.getUrl(), //
        repositoryPath, //
        directory, //
        repository.getUrl(), null, //
        indexers);

    indexingContext.setSearchable(false);

    return indexingContext;
  }

  protected ArrayList<IndexCreator> getIndexers(String details) {
    boolean fullIndex = NexusIndex.DETAILS_FULL.equals(details);
    ArrayList<IndexCreator> indexers = fullIndex ? getFullCreator() : getMinCreator();
    return indexers;
  }

  public void repositoryRemoved(IRepository repository, IProgressMonitor monitor) {
    synchronized(getIndexLock(repository)) {
      try {
        IndexingContext context = getIndexingContext(repository);
        if(context == null) {
          return;
        }
        getIndexer().removeIndexingContext(context, false);
      } catch(IOException ie) {
        String msg = "Unable to delete files for index";
        log.error(msg, ie);
      }
    }

    fireIndexRemoved(repository);
  }

  protected void fireIndexAdded(IRepository repository) {
    synchronized(indexListeners) {
      for(IndexListener listener : indexListeners) {
        listener.indexAdded(repository);
      }
    }
  }

  protected void fireIndexRemoved(IRepository repository) {
    synchronized(updatingIndexes) {
      if(repository != null) {
        //since workspace index can be null at startup, guard against nulls
        updatingIndexes.remove(repository.getUid());
      }
    }
    synchronized(indexListeners) {
      for(IndexListener listener : indexListeners) {
        listener.indexRemoved(repository);
      }
    }
  }

  protected boolean isUpdatingIndex(IRepository repository) {
    synchronized(updatingIndexes) {
      return updatingIndexes.contains(repository.getUid());
    }
  }

  protected void fireIndexUpdating(IRepository repository) {
    synchronized(updatingIndexes) {
      if(repository != null) {
        //since workspace index can be null at startup, guard against nulls
        updatingIndexes.add(repository.getUid());
      }
    }
    synchronized(indexListeners) {
      for(IndexListener listener : indexListeners) {
        listener.indexUpdating(repository);
      }
    }
  }

  protected void fireIndexChanged(IRepository repository) {
    if(repository == null) {
      return;
    }
    synchronized(updatingIndexes) {
      updatingIndexes.remove(repository.getUid());
    }
    synchronized(indexListeners) {
      for(IndexListener listener : indexListeners) {
        listener.indexChanged(repository);
      }
    }
  }

  public void removeIndexListener(IndexListener listener) {
    synchronized(indexListeners) {
      indexListeners.remove(listener);
    }
  }

  public void addIndexListener(IndexListener listener) {
    synchronized(indexListeners) {
      if(!indexListeners.contains(listener)) {
        indexListeners.add(listener);
      }
    }
  }

  //Public for testing purpose.
  public void updateIndex(IRepository repository, boolean force, IProgressMonitor monitor) throws CoreException {
    synchronized(getIndexLock(repository)) {
      if(repository.isScope(IRepositoryRegistry.SCOPE_WORKSPACE)) {
        reindexWorkspace(force, monitor);
      } else {
        IndexingContext context = getIndexingContext(repository);
        if(context != null) {
          if(context.getRepository() != null) {
            reindexLocalRepository(repository, force, monitor);
          } else {
            if(!force) {
              //if 'force' is not set, then only do the remote update if this value is set
              IMavenConfiguration mavenConfig = MavenPlugin.getDefault().getMavenConfiguration();
              if(mavenConfig.isUpdateIndexesOnStartup()) {
                updateRemoteIndex(repository, force, monitor);
              }
            } else {
              updateRemoteIndex(repository, force, monitor);
            }
          }
        }
      }
      IndexingContext context = getIndexingContext(repository);
      if(context != null) {
        context.setSearchable(true);
      }
    }
  }

  /*
   * Callers must hold repository access synchronisation lock 
   */
  private void updateRemoteIndex(IRepository repository, boolean force, IProgressMonitor monitor) {
    if(repository == null) {
      return;
    }

    long start = System.currentTimeMillis();

    if(monitor != null) {
      monitor.setTaskName(NLS.bind(Messages.NexusIndexManager_task_updating, repository.toString()));
    }
    log.info("Updating index for repository: {}", repository.toString()); //$NON-NLS-1$
    try {
      fireIndexUpdating(repository);

      IndexingContext context = getIndexingContext(repository);

      if(context != null) {
        IndexUpdateRequest request = newIndexUpdateRequest(repository, context, monitor);
        request.setForceFullUpdate(force);

        Lock cacheLock = locker.lock(request.getLocalIndexCacheDir());
        try {
          boolean updated;

          request.setCacheOnly(true);
          IndexUpdateResult result = indexUpdater.fetchAndUpdateIndex(request);
          if(result.isFullUpdate() || !context.isSearchable()) {
            // need to fully recreate index

            // 1. process index gz into cached/shared lucene index. this can be a noop if cache is uptodate
            String details = getIndexDetails(repository);
            String id = repository.getUid() + "-cache"; //$NON-NLS-1$
            File luceneCache = new File(request.getLocalIndexCacheDir(), details);
            Directory directory = FSDirectory.getDirectory(luceneCache);
            IndexingContext cacheCtx = getIndexer().addIndexingContextForced(id, id, null, directory, null, null,
                getIndexers(details));
            request = newIndexUpdateRequest(repository, cacheCtx, monitor);
            request.setOffline(true);
            indexUpdater.fetchAndUpdateIndex(request);

            // 2. copy cached/shared (this is not very elegant, oh well)
            getIndexer().removeIndexingContext(context, true); // nuke workspace index files
            getIndexer().removeIndexingContext(cacheCtx, false); // keep the cache!
            FileUtils.cleanDirectory(context.getIndexDirectoryFile());
            FileUtils.copyDirectory(luceneCache, context.getIndexDirectoryFile()); // copy cached lucene index
            context = createIndexingContext(repository, details); // re-create indexing context

            updated = true;
          } else {
            // incremental change
            request = newIndexUpdateRequest(repository, context, monitor);
            request.setOffline(true); // local cache is already uptodate, no need to
            result = indexUpdater.fetchAndUpdateIndex(request);
            updated = result.getTimestamp() != null;
          }

          if(updated) {
            log.info("Updated index for repository: {} in {} ms", repository.toString(), System.currentTimeMillis()
                - start);
          } else {
            log.info("No index update available for repository: {}", repository.toString());
          }
        } finally {
          cacheLock.release();
        }
      }
    } catch(FileNotFoundException e) {
      String msg = "Unable to update index for " + repository.toString() + ": " + e.getMessage(); //$NON-NLS-2$
      log.error(msg, e);
    } catch(Exception ie) {
      String msg = "Unable to update index for " + repository.toString();
      log.error(msg, ie);
    } finally {
      fireIndexChanged(repository);
    }
  }

  protected IndexUpdateRequest newIndexUpdateRequest(IRepository repository, IndexingContext context,
      IProgressMonitor monitor) throws IOException, CoreException {
    ProxyInfo proxyInfo = maven.getProxyInfo(repository.getProtocol());
    AuthenticationInfo authenticationInfo = repository.getAuthenticationInfo();

    IndexUpdateRequest request = new IndexUpdateRequest(context, new AsyncFetcher(authenticationInfo, proxyInfo,
        monitor));
    File localRepo = repositoryRegistry.getLocalRepository().getBasedir();
    File indexCacheBasedir = new File(localRepo, ".cache/m2e/" + MavenPlugin.getVersion()).getCanonicalFile(); //$NON-NLS-1$
    File indexCacheDir = new File(indexCacheBasedir, repository.getUid());
    indexCacheDir.mkdirs();
    request.setLocalIndexCacheDir(indexCacheDir);
    return request;
  }

  public void initialize(IProgressMonitor monitor) throws CoreException {
    try {
      BufferedInputStream is = new BufferedInputStream(new FileInputStream(getIndexDetailsFile()));
      try {
        indexDetails.load(is);
      } finally {
        is.close();
      }
    } catch(FileNotFoundException e) {
      // that's quite alright
    } catch(IOException e) {
      throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1,
          Messages.NexusIndexManager_error_read_index, e));
    }
  }

  protected void writeIndexDetails() throws CoreException {
    try {
      File indexDetailsFile = getIndexDetailsFile();
      indexDetailsFile.getParentFile().mkdirs();
      BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(indexDetailsFile));
      try {
        indexDetails.store(os, null);
      } finally {
        os.close();
      }
    } catch(IOException e) {
      throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1,
          Messages.NexusIndexManager_error_write_index, e));
    }
  }

  private File getIndexDetailsFile() {
    return new File(baseIndexDir, "indexDetails.properties"); //$NON-NLS-1$
  }

  /** for unit tests only */
  public Job getIndexUpdateJob() {
    return updaterJob;
  }

  public String getIndexerId() {
    return Messages.NexusIndexManager_78;
  }

  private Object getIndexLock(IRepository repository) {
    if(repository == null) {
      return new Object();
    }
    // NOTE: We ultimately want to prevent concurrent access to the IndexingContext so we sync on the repo UID and not on the repo instance.
    synchronized(indexLocks) {
      Object lock = indexLocks.get(repository.getUid());
      if(lock == null) {
        lock = new Object();
        indexLocks.put(repository.getUid(), lock);
      }
      return lock;
    }
  }

  /// REMOVE THIS BELOW ONCE Maven Indexer upgraded to 3.2.0-SNAPSHOT
  /// In that moment this code becomes duplicated and already in place, this method added

  protected ArtifactInfo identify(File artifact, Collection<IndexingContext> contexts) throws IOException {

    FileInputStream is = null;

    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");

      is = new FileInputStream(artifact);

      byte[] buff = new byte[4096];

      int n;

      while((n = is.read(buff)) > -1) {
        sha1.update(buff, 0, n);
      }

      byte[] digest = sha1.digest();

      Query q = getIndexer().constructQuery(MAVEN.SHA1, encode(digest), SearchType.EXACT);

      return getIndexer().identify(q, contexts);

    } catch(NoSuchAlgorithmException ex) {
      throw new IOException("Unable to calculate digest");
    } finally {
      IOUtil.close(is);
    }

  }

  private static final char[] DIGITS = "0123456789abcdef".toCharArray();

  private static String encode(byte[] digest) {
    char[] buff = new char[digest.length * 2];

    int n = 0;

    for(byte b : digest) {
      buff[n++ ] = DIGITS[(0xF0 & b) >> 4];
      buff[n++ ] = DIGITS[0x0F & b];
    }

    return new String(buff);
  }
}
