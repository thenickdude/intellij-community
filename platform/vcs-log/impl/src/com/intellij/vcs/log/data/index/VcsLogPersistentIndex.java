/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.vcs.log.data.index;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener.Adapter;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.PerformInBackgroundOption;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.VcsNotifier;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.EmptyConsumer;
import com.intellij.util.Processor;
import com.intellij.util.ThrowableRunnable;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.EmptyIntHashSet;
import com.intellij.util.indexing.StorageException;
import com.intellij.util.io.*;
import com.intellij.vcs.log.*;
import com.intellij.vcs.log.data.*;
import com.intellij.vcs.log.impl.FatalErrorHandler;
import com.intellij.vcs.log.impl.HeavyAwareExecutor;
import com.intellij.vcs.log.impl.VcsIndexableDetails;
import com.intellij.vcs.log.ui.filter.VcsLogTextFilterImpl;
import com.intellij.vcs.log.util.PersistentSet;
import com.intellij.vcs.log.util.PersistentSetImpl;
import com.intellij.vcs.log.util.StopWatch;
import com.intellij.vcs.log.util.TroveUtil;
import gnu.trove.TIntHashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.event.HyperlinkEvent;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static com.intellij.vcs.log.data.index.VcsLogFullDetailsIndex.INDEX;
import static com.intellij.vcs.log.util.PersistentUtil.*;

public class VcsLogPersistentIndex implements VcsLogIndex, Disposable {
  private static final Logger LOG = Logger.getInstance(VcsLogPersistentIndex.class);
  private static final int VERSION = 3;

  @NotNull private final Project myProject;
  @NotNull private final FatalErrorHandler myFatalErrorsConsumer;
  @NotNull private final VcsLogProgress myProgress;
  @NotNull private final Map<VirtualFile, VcsLogProvider> myProviders;
  @NotNull private final VcsLogStorage myStorage;
  @NotNull private final VcsUserRegistryImpl myUserRegistry;
  @NotNull private final Set<VirtualFile> myRoots;
  @NotNull private final VcsLogBigRepositoriesList myBigRepositoriesList;

  @Nullable private final IndexStorage myIndexStorage;
  @Nullable private final IndexDataGetter myDataGetter;

  @NotNull private final SingleTaskController<IndexingRequest, Void> mySingleTaskController;
  @NotNull private final Map<VirtualFile, AtomicInteger> myNumberOfTasks = ContainerUtil.newHashMap();
  @NotNull private final Map<VirtualFile, AtomicLong> myIndexingTime = ContainerUtil.newHashMap();
  @NotNull private final Map<VirtualFile, AtomicInteger> myIndexingLimit = ContainerUtil.newHashMap();

  @NotNull private final List<IndexingFinishedListener> myListeners = ContainerUtil.createLockFreeCopyOnWriteList();

  @NotNull private Map<VirtualFile, TIntHashSet> myCommitsToIndex = ContainerUtil.newHashMap();

  public VcsLogPersistentIndex(@NotNull Project project,
                               @NotNull VcsLogStorage storage,
                               @NotNull VcsLogProgress progress,
                               @NotNull Map<VirtualFile, VcsLogProvider> providers,
                               @NotNull FatalErrorHandler fatalErrorsConsumer,
                               @NotNull Disposable disposableParent) {
    myStorage = storage;
    myProject = project;
    myProgress = progress;
    myProviders = providers;
    myFatalErrorsConsumer = fatalErrorsConsumer;
    myRoots = ContainerUtil.newLinkedHashSet();
    mySingleTaskController = new MySingleTaskController(project);
    myBigRepositoriesList = VcsLogBigRepositoriesList.getInstance();

    for (Map.Entry<VirtualFile, VcsLogProvider> entry : providers.entrySet()) {
      if (VcsLogProperties.get(entry.getValue(), VcsLogProperties.SUPPORTS_INDEXING)) {
        myRoots.add(entry.getKey());
      }
    }

    myUserRegistry = (VcsUserRegistryImpl)ServiceManager.getService(myProject, VcsUserRegistry.class);

    myIndexStorage = createIndexStorage(fatalErrorsConsumer, calcLogId(myProject, providers));
    if (myIndexStorage != null) {
      myDataGetter = new IndexDataGetter(myProject, myRoots, myIndexStorage, myStorage, myFatalErrorsConsumer);
    }
    else {
      myDataGetter = null;
    }

    for (VirtualFile root : myRoots) {
      myNumberOfTasks.put(root, new AtomicInteger());
      myIndexingTime.put(root, new AtomicLong());
      myIndexingLimit.put(root, new AtomicInteger(getIndexingLimit()));
    }

    Disposer.register(disposableParent, this);
  }

  private static int getIndexingLimit() {
    return Registry.intValue("vcs.log.index.limit.minutes");
  }

  protected IndexStorage createIndexStorage(@NotNull FatalErrorHandler fatalErrorHandler, @NotNull String logId) {
    try {
      return IOUtil.openCleanOrResetBroken(() -> new IndexStorage(logId, myUserRegistry, myRoots, fatalErrorHandler, this),
                                           () -> IndexStorage.cleanup(logId));
    }
    catch (IOException e) {
      myFatalErrorsConsumer.consume(this, e);
    }
    return null;
  }

  public static int getVersion() {
    return VcsLogStorageImpl.VERSION + VERSION;
  }

  @Override
  public synchronized void scheduleIndex(boolean full) {
    if (Disposer.isDisposed(this)) return;
    if (myCommitsToIndex.isEmpty() || myIndexStorage == null) return;
    // for fresh index, wait for complete log to load and index everything in one command
    if (myIndexStorage.isFresh() && !full) return;
    Map<VirtualFile, TIntHashSet> commitsToIndex = myCommitsToIndex;

    for (VirtualFile root : commitsToIndex.keySet()) {
      myNumberOfTasks.get(root).incrementAndGet();
    }
    myCommitsToIndex = ContainerUtil.newHashMap();

    boolean isFull = full && myIndexStorage.isFresh();
    if (isFull) LOG.debug("Index storage for project " + myProject.getName() + " is fresh, scheduling full reindex");
    for (VirtualFile root : commitsToIndex.keySet()) {
      TIntHashSet commits = commitsToIndex.get(root);
      if (commits.isEmpty()) continue;

      if (myBigRepositoriesList.isBig(root)) {
        myCommitsToIndex.put(root, commits); // put commits back in order to be able to reindex
        LOG.info("Indexing repository " + root.getName() + " is skipped since it is too big");
        continue;
      }

      mySingleTaskController.request(new IndexingRequest(root, commits, isFull, false));
    }
    if (isFull) myIndexStorage.unmarkFresh();
  }

  private void storeDetail(@NotNull VcsFullCommitDetails detail) {
    if (myIndexStorage == null) return;
    try {
      int index = myStorage.getCommitIndex(detail.getId(), detail.getRoot());

      myIndexStorage.messages.put(index, detail.getFullMessage());
      myIndexStorage.trigrams.update(index, detail);
      myIndexStorage.users.update(index, detail);
      myIndexStorage.paths.update(index, detail);
      myIndexStorage.parents.put(index, ContainerUtil.map(detail.getParents(), p -> myStorage.getCommitIndex(p, detail.getRoot())));
      // we know the whole graph without timestamps now
      if (!(detail instanceof VcsIndexableDetails) || ((VcsIndexableDetails)detail).hasRenames()) {
        myIndexStorage.renames.put(index);
      }

      myIndexStorage.commits.put(index);
    }
    catch (IOException e) {
      myFatalErrorsConsumer.consume(this, e);
    }
  }

  private void flush() {
    try {
      if (myIndexStorage != null) {
        myIndexStorage.messages.force();
        myIndexStorage.trigrams.flush();
        myIndexStorage.users.flush();
        myIndexStorage.paths.flush();
        myIndexStorage.parents.force();
        myIndexStorage.renames.flush();
        myIndexStorage.commits.flush();
      }
    }
    catch (StorageException e) {
      myFatalErrorsConsumer.consume(this, e);
    }
  }

  public void markCorrupted() {
    if (myIndexStorage != null) myIndexStorage.commits.markCorrupted();
  }

  @Override
  public boolean isIndexed(int commit) {
    try {
      return myIndexStorage == null || myIndexStorage.commits.contains(commit);
    }
    catch (IOException e) {
      myFatalErrorsConsumer.consume(this, e);
    }
    return false;
  }

  private boolean hasRenames(int commit) {
    try {
      return myIndexStorage == null || myIndexStorage.renames.contains(commit);
    }
    catch (IOException e) {
      myFatalErrorsConsumer.consume(this, e);
    }
    return false;
  }

  @Override
  public synchronized boolean isIndexed(@NotNull VirtualFile root) {
    return myRoots.contains(root) &&
           !(myBigRepositoriesList.isBig(root)) &&
           (!myCommitsToIndex.containsKey(root) && myNumberOfTasks.get(root).get() == 0);
  }

  @Override
  public synchronized void markForIndexing(int index, @NotNull VirtualFile root) {
    if (isIndexed(index) || !myRoots.contains(root)) return;
    TroveUtil.add(myCommitsToIndex, root, index);
  }

  @Override
  public synchronized void reindexWithRenames(int commit, @NotNull VirtualFile root) {
    LOG.assertTrue(myRoots.contains(root));
    if (hasRenames(commit)) return;
    mySingleTaskController.request(new IndexingRequest(root, TroveUtil.singleton(commit), false, true));
  }

  @NotNull
  private <T> TIntHashSet filter(@NotNull PersistentMap<Integer, T> map, @NotNull Condition<T> condition) {
    TIntHashSet result = new TIntHashSet();
    if (myIndexStorage == null) return result;
    try {
      Processor<Integer> processor = integer -> {
        try {
          T value = map.get(integer);
          if (value != null) {
            if (condition.value(value)) {
              result.add(integer);
            }
          }
        }
        catch (IOException e) {
          myFatalErrorsConsumer.consume(this, e);
          return false;
        }
        return true;
      };
      if (myIndexStorage.messages instanceof PersistentHashMap) {
        ((PersistentHashMap<Integer, T>)myIndexStorage.messages).processKeysWithExistingMapping(processor);
      }
      else {
        myIndexStorage.messages.processKeys(processor);
      }
    }
    catch (IOException e) {
      myFatalErrorsConsumer.consume(this, e);
    }

    return result;
  }

  @NotNull
  private TIntHashSet filterUsers(@NotNull Set<VcsUser> users) {
    if (myIndexStorage != null) {
      try {
        return myIndexStorage.users.getCommitsForUsers(users);
      }
      catch (IOException | StorageException e) {
        myFatalErrorsConsumer.consume(this, e);
      }
      catch (RuntimeException e) {
        processRuntimeException(e);
      }
    }
    return new TIntHashSet();
  }

  @NotNull
  private TIntHashSet filterPaths(@NotNull Collection<FilePath> paths) {
    if (myIndexStorage != null) {
      try {
        return myIndexStorage.paths.getCommitsForPaths(paths);
      }
      catch (IOException | StorageException e) {
        myFatalErrorsConsumer.consume(this, e);
      }
      catch (RuntimeException e) {
        processRuntimeException(e);
      }
    }
    return new TIntHashSet();
  }

  @NotNull
  public TIntHashSet filterMessages(@NotNull VcsLogTextFilter filter) {
    if (myIndexStorage != null) {
      try {
        if (!filter.isRegex()) {
          TIntHashSet commitsForSearch = myIndexStorage.trigrams.getCommitsForSubstring(filter.getText());
          if (commitsForSearch != null) {
            TIntHashSet result = new TIntHashSet();
            commitsForSearch.forEach(commit -> {
              try {
                String value = myIndexStorage.messages.get(commit);
                if (value != null) {
                  if (VcsLogTextFilterImpl.matches(filter, value)) {
                    result.add(commit);
                  }
                }
              }
              catch (IOException e) {
                myFatalErrorsConsumer.consume(this, e);
                return false;
              }
              return true;
            });
            return result;
          }
        }
      }
      catch (StorageException e) {
        myFatalErrorsConsumer.consume(this, e);
      }
      catch (RuntimeException e) {
        processRuntimeException(e);
      }

      return filter(myIndexStorage.messages, message -> VcsLogTextFilterImpl.matches(filter, message));
    }

    return EmptyIntHashSet.INSTANCE;
  }

  private void processRuntimeException(@NotNull RuntimeException e) {
    if (e instanceof ProcessCanceledException) throw e;
    if (myIndexStorage != null) myIndexStorage.markCorrupted();
    if (e.getCause() instanceof IOException || e.getCause() instanceof StorageException) {
      myFatalErrorsConsumer.consume(this, e);
    }
    else {
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean canFilter(@NotNull List<VcsLogDetailsFilter> filters) {
    if (filters.isEmpty() || myIndexStorage == null) return false;
    for (VcsLogDetailsFilter filter : filters) {
      if (filter instanceof VcsLogTextFilter ||
          filter instanceof VcsLogUserFilter ||
          filter instanceof VcsLogStructureFilter) {
        continue;
      }
      return false;
    }
    return true;
  }

  @Override
  @NotNull
  public Set<Integer> filter(@NotNull List<VcsLogDetailsFilter> detailsFilters) {
    VcsLogTextFilter textFilter = ContainerUtil.findInstance(detailsFilters, VcsLogTextFilter.class);
    VcsLogUserFilter userFilter = ContainerUtil.findInstance(detailsFilters, VcsLogUserFilter.class);
    VcsLogStructureFilter pathFilter = ContainerUtil.findInstance(detailsFilters, VcsLogStructureFilter.class);

    TIntHashSet filteredByMessage = null;
    if (textFilter != null) {
      filteredByMessage = filterMessages(textFilter);
    }

    TIntHashSet filteredByUser = null;
    if (userFilter != null) {
      Set<VcsUser> users = ContainerUtil.newHashSet();
      for (VirtualFile root : myRoots) {
        users.addAll(userFilter.getUsers(root));
      }

      filteredByUser = filterUsers(users);
    }

    TIntHashSet filteredByPath = null;
    if (pathFilter != null) {
      filteredByPath = filterPaths(pathFilter.getFiles());
    }

    return TroveUtil.intersect(filteredByMessage, filteredByPath, filteredByUser);
  }

  @Nullable
  @Override
  public IndexDataGetter getDataGetter() {
    if (myIndexStorage == null) return null;
    return myDataGetter;
  }

  @Override
  public void addListener(@NotNull IndexingFinishedListener l) {
    myListeners.add(l);
  }

  @Override
  public void removeListener(@NotNull IndexingFinishedListener l) {
    myListeners.remove(l);
  }

  @Override
  public void dispose() {
  }

  static class IndexStorage {
    private static final String COMMITS = "commits";
    private static final String MESSAGES = "messages";
    private static final String PARENTS = "parents";
    private static final String RENAMES = "renames";
    private static final int MESSAGES_VERSION = 0;
    @NotNull public final PersistentSet<Integer> commits;
    @NotNull public final PersistentMap<Integer, String> messages;
    @NotNull public final PersistentMap<Integer, List<Integer>> parents;
    @NotNull public final PersistentSet<Integer> renames;
    @NotNull public final VcsLogMessagesTrigramIndex trigrams;
    @NotNull public final VcsLogUserIndex users;
    @NotNull public final VcsLogPathsIndex paths;

    private volatile boolean myIsFresh;

    public IndexStorage(@NotNull String logId,
                        @NotNull VcsUserRegistryImpl userRegistry,
                        @NotNull Set<VirtualFile> roots,
                        @NotNull FatalErrorHandler fatalErrorHandler,
                        @NotNull Disposable parentDisposable)
      throws IOException {
      Disposable disposable = Disposer.newDisposable();
      Disposer.register(parentDisposable, disposable);

      try {
        int version = getVersion();

        File commitsStorage = getStorageFile(INDEX, COMMITS, logId, version);
        myIsFresh = !commitsStorage.exists();
        commits = new PersistentSetImpl<>(commitsStorage, EnumeratorIntegerDescriptor.INSTANCE, Page.PAGE_SIZE, null, version);
        Disposer.register(disposable, () -> catchAndWarn(commits::close));

        File messagesStorage = getStorageFile(INDEX, MESSAGES, logId, VcsLogStorageImpl.VERSION + MESSAGES_VERSION);
        messages = new PersistentHashMap<>(messagesStorage, new IntInlineKeyDescriptor(), EnumeratorStringDescriptor.INSTANCE,
                                           Page.PAGE_SIZE);
        Disposer.register(disposable, () -> catchAndWarn(messages::close));

        trigrams = new VcsLogMessagesTrigramIndex(logId, fatalErrorHandler, disposable);
        users = new VcsLogUserIndex(logId, userRegistry, fatalErrorHandler, disposable);
        paths = new VcsLogPathsIndex(logId, roots, fatalErrorHandler, disposable);

        File parentsStorage = getStorageFile(INDEX, PARENTS, logId, version);
        parents = new PersistentHashMap<>(parentsStorage, EnumeratorIntegerDescriptor.INSTANCE,
                                          new IntListDataExternalizer(), Page.PAGE_SIZE, version);
        Disposer.register(disposable, () -> catchAndWarn(parents::close));

        File renamesStorage = getStorageFile(INDEX, RENAMES, logId, version);
        renames = new PersistentSetImpl<>(renamesStorage, EnumeratorIntegerDescriptor.INSTANCE, Page.PAGE_SIZE, null, version);
        Disposer.register(disposable, () -> catchAndWarn(renames::close));
      }
      catch (Throwable t) {
        Disposer.dispose(disposable);
        throw t;
      }
    }

    void markCorrupted() {
      catchAndWarn(commits::markCorrupted);
    }

    private static void catchAndWarn(@NotNull ThrowableRunnable<IOException> runnable) {
      try {
        runnable.run();
      }
      catch (IOException e) {
        LOG.warn(e);
      }
    }

    private static void cleanup(@NotNull String logId) {
      if (!cleanupStorageFiles(INDEX, logId)) {
        LOG.error("Could not clean up storage files in " + new File(LOG_CACHE, INDEX) + " starting with " + logId);
      }
    }

    public void unmarkFresh() {
      myIsFresh = false;
    }

    public boolean isFresh() {
      return myIsFresh;
    }
  }

  private class MySingleTaskController extends SingleTaskController<IndexingRequest, Void> {
    private static final int LOW_PRIORITY = Thread.MIN_PRIORITY;
    @NotNull private final HeavyAwareExecutor myHeavyAwareExecutor;

    public MySingleTaskController(@NotNull Project project) {
      super(EmptyConsumer.getInstance(), false);
      myHeavyAwareExecutor = new HeavyAwareExecutor(project, 50, 100, VcsLogPersistentIndex.this);
    }

    @NotNull
    @Override
    protected ProgressIndicator startNewBackgroundTask() {
      ProgressIndicator indicator = myProgress.createProgressIndicator(false);
      ApplicationManager.getApplication().invokeLater(() -> {
        Task.Backgroundable task = new Task.Backgroundable(VcsLogPersistentIndex.this.myProject, "Indexing Commit Data", true,
                                                           PerformInBackgroundOption.ALWAYS_BACKGROUND) {

          @Override
          public void run(@NotNull ProgressIndicator indicator) {
            int previousPriority = setMinimumPriority();
            try {
              IndexingRequest request;
              while ((request = popRequest()) != null) {
                try {
                  request.run(indicator);
                  indicator.checkCanceled();
                }
                catch (ProcessCanceledException reThrown) {
                  throw reThrown;
                }
                catch (Throwable t) {
                  LOG.error("Error while indexing", t);
                }
              }
            }
            finally {
              taskCompleted(null);
              resetPriority(previousPriority);
            }
          }
        };
        myHeavyAwareExecutor.executeOutOfHeavyOrPowerSave(task, indicator);
      });
      return indicator;
    }

    public void resetPriority(int previousPriority) {
      if (Thread.currentThread().getPriority() == LOW_PRIORITY) Thread.currentThread().setPriority(previousPriority);
    }

    public int setMinimumPriority() {
      int previousPriority = Thread.currentThread().getPriority();
      try {
        Thread.currentThread().setPriority(LOW_PRIORITY);
      } catch (SecurityException ignored) {
        LOG.debug("Could not set indexing thread priority", ignored);
      }
      return previousPriority;
    }
  }

  private class IndexingRequest {
    private static final int BATCH_SIZE = 20000;
    private static final int FLUSHED_COMMITS_NUMBER = 15000;
    @NotNull private final VirtualFile myRoot;
    @NotNull private final TIntHashSet myCommits;
    private final boolean myFull;
    private final boolean myReindex;

    @NotNull private final AtomicInteger myNewIndexedCommits = new AtomicInteger();
    @NotNull private final AtomicInteger myOldCommits = new AtomicInteger();
    private volatile long myStartTime;

    public IndexingRequest(@NotNull VirtualFile root, @NotNull TIntHashSet commits, boolean full, boolean reindex) {
      myRoot = root;
      myCommits = commits;
      myFull = full;
      myReindex = reindex;
      LOG.assertTrue(!myFull || !myReindex);
    }

    public void run(@NotNull ProgressIndicator indicator) {
      if (myBigRepositoriesList.isBig(myRoot)) {
        LOG.info("Indexing repository " + myRoot.getName() + " is skipped since it is too big");
        return;
      }

      indicator.setIndeterminate(false);
      indicator.setFraction(0);

      myStartTime = getCurrentTimeMillis();

      LOG.debug("Indexing " + (myFull ? "full repository" : myCommits.size() + " commits") + " in " + myRoot.getName());

      try {
        try {
          if (myFull) {
            indexAll(indicator);
          }
          else {
            IntStream commits = TroveUtil.stream(myCommits).filter(c -> {
              if (myReindex ? hasRenames(c) : isIndexed(c)) {
                myOldCommits.incrementAndGet();
                return false;
              }
              return true;
            });

            indexOneByOne(commits, indicator);
          }
        }
        catch (ProcessCanceledException e) {
          scheduleReindex();
          throw e;
        }
        catch (VcsException e) {
          LOG.error(e);
          scheduleReindex();
        }
      }
      finally {
        if (!myReindex) myNumberOfTasks.get(myRoot).decrementAndGet();

        if (isIndexed(myRoot)) {
          myIndexingTime.get(myRoot).set(0);
          myListeners.forEach(listener -> listener.indexingFinished(myRoot));
        }
        else {
          myIndexingTime.get(myRoot).updateAndGet(t -> t + (getCurrentTimeMillis() - myStartTime));
        }

        report();

        flush();
      }
    }

    private long getCurrentTimeMillis() {
      return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    private void report() {
      String formattedTime = StopWatch.formatTime(getCurrentTimeMillis() - myStartTime);
      if (myFull) {
        LOG.debug(formattedTime +
                  " for indexing " +
                  myNewIndexedCommits + " commits in " + myRoot.getName());
      }
      else {
        int leftCommits = myCommits.size() - myNewIndexedCommits.get() - myOldCommits.get();
        String leftCommitsMessage = (leftCommits > 0) ? ". " + leftCommits + " commits left" : "";

        LOG.debug(formattedTime +
                  " for indexing " +
                  myNewIndexedCommits +
                  " new commits out of " +
                  myCommits.size() + " in " + myRoot.getName() + leftCommitsMessage);
      }
    }

    private void scheduleReindex() {
      LOG.debug("Schedule reindexing of " +
                (myCommits.size() - myNewIndexedCommits.get() - myOldCommits.get()) +
                " commits in " +
                myRoot.getName());
      if (myReindex) {
        myCommits.forEach(value -> {
          reindexWithRenames(value, myRoot);
          return true;
        });
      }
      else {
        myCommits.forEach(value -> {
          markForIndexing(value, myRoot);
          return true;
        });
        scheduleIndex(false);
      }
    }

    private void indexOneByOne(@NotNull IntStream commits, @NotNull ProgressIndicator indicator) throws VcsException {
      // We pass hashes to VcsLogProvider#readFullDetails in batches
      // in order to avoid allocating too much memory for these hashes
      // a batch of 20k will occupy ~2.4Mb
      TroveUtil.processBatches(commits, BATCH_SIZE, batch -> {
        indicator.checkCanceled();

        List<String> hashes = TroveUtil.map(batch, value -> myStorage.getCommitId(value).getHash().asString());
        myProviders.get(myRoot).readFullDetails(myRoot, hashes, detail -> {
          storeDetail(detail);
          myNewIndexedCommits.incrementAndGet();

          checkRunningTooLong(indicator);
        }, !myReindex);

        displayProgress(indicator);
      });
    }

    public void indexAll(@NotNull ProgressIndicator indicator) throws VcsException {
      displayProgress(indicator);

      myProviders.get(myRoot).readAllFullDetails(myRoot, details -> {
        storeDetail(details);

        if (myNewIndexedCommits.incrementAndGet() % FLUSHED_COMMITS_NUMBER == 0) flush();

        checkRunningTooLong(indicator);
        displayProgress(indicator);
      });
    }

    private void checkRunningTooLong(@NotNull ProgressIndicator indicator) {
      long time = myIndexingTime.get(myRoot).get() + (getCurrentTimeMillis() - myStartTime);
      int limit = myIndexingLimit.get(myRoot).get();
      if (time >= Math.max(limit, 1) * 60 * 1000 && !myBigRepositoriesList.isBig(myRoot)) {
        LOG.warn("Indexing " + myRoot.getName() + " was cancelled after " + StopWatch.formatTime(time));
        myBigRepositoriesList.addRepository(myRoot);
        indicator.cancel();
        showIndexingNotification(time);
      }
    }

    public void displayProgress(@NotNull ProgressIndicator indicator) {
      indicator.setFraction(((double)myNewIndexedCommits.get() + myOldCommits.get()) / myCommits.size());
    }

    @Override
    public String toString() {
      return "IndexingRequest of " + myCommits.size() + " commits in " + myRoot.getName() + (myFull ? " (full)" : "");
    }

    private void showIndexingNotification(long time) {
      Runnable notification = () -> {
        Adapter notificationListener = new Adapter() {
          @Override
          protected void hyperlinkActivated(@NotNull Notification notification,
                                            @NotNull HyperlinkEvent e) {
            if (myBigRepositoriesList.isBig(myRoot)) {
              LOG.info("Resuming indexing " + myRoot.getName());
              myIndexingLimit.get(myRoot).updateAndGet(l -> l + getIndexingLimit());
              myBigRepositoriesList.removeRepository(myRoot);
              scheduleIndex(false);
            }
          }
        };
        VcsNotifier.getInstance(myProject).notifyImportantWarning("Log Indexing for \"" + myRoot.getName() + "\" Stopped",
                                                                  "Indexing was taking too long (" +
                                                                  StopWatch.formatTime(time - time % 1000) +
                                                                  ")<p/><a href='resume'>Resume</a>",
                                                                  notificationListener);
      };
      ApplicationManager.getApplication().invokeLater(notification);
    }
  }
}
