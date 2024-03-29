package com.intellij.openapi.fileEditor.impl;

import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandEvent;
import com.intellij.openapi.command.CommandListener;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.impl.CommandMerger;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.EditorEventListener;
import com.intellij.openapi.editor.event.EditorEventMulticaster;
import com.intellij.openapi.extensions.ExtensionPointListener;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.ex.FileEditorWithProvider;
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.ExternalChangeAction;
import com.intellij.reference.SoftReference;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.concurrency.SynchronizedClearableLazy;
import com.intellij.util.io.*;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.messages.Topic;
import com.intellij.util.text.DateFormatUtil;
import com.intellij.util.xmlb.annotations.XCollection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

@State(name = "IdeDocumentHistory", storages = @Storage(StoragePathMacros.PRODUCT_WORKSPACE_FILE), reportStatistic = false)
public class MyIdeDocumentHistoryImpl extends IdeDocumentHistory
    implements Disposable, PersistentStateComponent<MyIdeDocumentHistoryImpl.RecentlyChangedFilesState> {
  private static final Logger LOG = Logger.getInstance(IdeDocumentHistoryImpl.class);

  private static final int BACK_QUEUE_LIMIT = Registry.intValue("editor.navigation.history.stack.size");
  private static final int CHANGE_QUEUE_LIMIT = Registry.intValue("editor.navigation.history.stack.size");

  private final Project myProject;

  private FileDocumentManager myFileDocumentManager;

  private final LinkedList<IdeDocumentHistoryImpl.PlaceInfo> myBackPlaces = new LinkedList<>();
      // LinkedList of IdeDocumentHistoryImpl.PlaceInfo's
  private final LinkedList<IdeDocumentHistoryImpl.PlaceInfo> myForwardPlaces = new LinkedList<>();
      // LinkedList of IdeDocumentHistoryImpl.PlaceInfo's
  private boolean myBackInProgress;
  private boolean myForwardInProgress;
  private Reference<Object> myLastGroupId;
      // weak reference to avoid memleaks when clients pass some exotic objects as commandId
  private boolean myRegisteredBackPlaceInLastGroup;

  // change's navigation
  private final LinkedList<IdeDocumentHistoryImpl.PlaceInfo> myChangePlaces = new LinkedList<>();
      // LinkedList of IdeDocumentHistoryImpl.PlaceInfo's
  private int myCurrentIndex;

  private IdeDocumentHistoryImpl.PlaceInfo myCommandStartPlace;
  private boolean myCurrentCommandIsNavigation;
  private boolean myCurrentCommandHasChanges;
  private final Set<VirtualFile> myChangedFilesInCurrentCommand = new HashSet<>();
  private boolean myCurrentCommandHasMoves;

  private final SynchronizedClearableLazy<PersistentHashMap<String, Long>> recentFileTimestampMap;

  private final RecentlyChangedFilesState state = new RecentlyChangedFilesState();

  public MyIdeDocumentHistoryImpl(@NotNull Project project) {
    myProject = project;

    MessageBusConnection busConnection = project.getMessageBus().connect(this);
    busConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
      @Override
      public void selectionChanged(@NotNull FileEditorManagerEvent e) {
        onSelectionChanged();
      }
    });
    busConnection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
      @Override
      public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
        for (VFileEvent event : events) {
          if (event instanceof VFileDeleteEvent) {
            removeInvalidFilesFromStacks();
            return;
          }
        }
      }
    });
    busConnection.subscribe(CommandListener.TOPIC, new CommandListener() {
      @Override
      public void commandStarted(@NotNull CommandEvent event) {
        onCommandStarted();
      }

      @Override
      public void commandFinished(@NotNull CommandEvent event) {
        onCommandFinished(event.getProject(), event.getCommandGroupId());
      }
    });

    EditorEventListener listener = new EditorEventListener() {
      @Override
      public void documentChanged(@NotNull DocumentEvent e) {
        Document document = e.getDocument();
        final VirtualFile file = getFileDocumentManager().getFile(document);
        if (file != null && !(file instanceof LightVirtualFile) &&
            !ApplicationManager.getApplication().hasWriteAction(ExternalChangeAction.class)) {
          if (!ApplicationManager.getApplication().isDispatchThread()) {
            LOG.error("Document update for physical file not in EDT: " + file);
          }
          myCurrentCommandHasChanges = true;
          myChangedFilesInCurrentCommand.add(file);
        }
      }

      @Override
      public void caretPositionChanged(@NotNull CaretEvent e) {
        if (e.getOldPosition().line == e.getNewPosition().line) {
          return;
        }

        Document document = e.getEditor().getDocument();
        if (getFileDocumentManager().getFile(document) != null) {
          myCurrentCommandHasMoves = true;
        }
      }
    };

    recentFileTimestampMap = new SynchronizedClearableLazy<>(() -> initRecentFilesTimestampMap(myProject));

    EditorEventMulticaster multicaster = EditorFactory.getInstance().getEventMulticaster();
    multicaster.addDocumentListener(listener, this);
    multicaster.addCaretListener(listener, this);

    FileEditorProvider.EP_FILE_EDITOR_PROVIDER.addExtensionPointListener(new ExtensionPointListener<>() {
      @Override
      public void extensionRemoved(@NotNull FileEditorProvider provider, @NotNull PluginDescriptor pluginDescriptor) {
        String editorTypeId = provider.getEditorTypeId();
        Predicate<IdeDocumentHistoryImpl.PlaceInfo> clearStatePredicate = e -> editorTypeId.equals(e.getEditorTypeId());
        if (myChangePlaces.removeIf(clearStatePredicate)) {
          myCurrentIndex = myChangePlaces.size();
        }
        myBackPlaces.removeIf(clearStatePredicate);
        myForwardPlaces.removeIf(clearStatePredicate);
        if (myCommandStartPlace != null && myCommandStartPlace.getEditorTypeId().equals(editorTypeId)) {
          myCommandStartPlace = null;
        }
      }
    }, this);
  }

  protected FileEditorManagerEx getFileEditorManager() {
    return FileEditorManagerEx.getInstanceEx(myProject);
  }

  private @NotNull
  static PersistentHashMap<String, Long> initRecentFilesTimestampMap(@NotNull Project project) {
    Path file = ProjectUtil.getProjectCachePath(project, "recentFilesTimeStamps.dat");
    try {
      return IOUtil.openCleanOrResetBroken(() -> createMap(file), file);
    } catch (IOException e) {
      LOG.error("Cannot create PersistentHashMap in " + file, e);
      throw new RuntimeException(e);
    }
  }

  private static @NotNull PersistentHashMap<String, Long> createMap(@NotNull Path file) throws IOException {
    return new PersistentHashMap<>(
        file,
        EnumeratorStringDescriptor.INSTANCE,
        EnumeratorLongDescriptor.INSTANCE,
        256,
        0,
        new StorageLockContext()
    );
  }

  private void registerViewed(@NotNull VirtualFile file) {
    if (ApplicationManager.getApplication().isUnitTestMode() || !UISettings.getInstance().getShowInplaceComments()) {
      return;
    }

    try {
      recentFileTimestampMap.getValue().put(file.getPath(), System.currentTimeMillis());
    } catch (IOException e) {
      LOG.info("Cannot put a timestamp from a persistent hash map", e);
    }
  }

  public static void appendTimestamp(
      @NotNull Project project,
      @NotNull SimpleColoredComponent component,
      @NotNull VirtualFile file) {
    if (!UISettings.getInstance().getShowInplaceComments()) {
      return;
    }

    try {
      Long timestamp =
          ((MyIdeDocumentHistoryImpl) getInstance(project)).recentFileTimestampMap.getValue().get(file.getPath());
      if (timestamp != null) {
        component.append(" ")
            .append(DateFormatUtil.formatPrettyDateTime(timestamp), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
      }
    } catch (IOException e) {
      LOG.info("Cannot get a timestamp from a persistent hash map", e);
    }
  }

  static final class RecentlyChangedFilesState {
    @XCollection(style = XCollection.Style.v2)
    public final List<String> changedPaths = new ArrayList<>();

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      return changedPaths.equals(((RecentlyChangedFilesState) o).changedPaths);
    }

    @Override
    public int hashCode() {
      return changedPaths.hashCode();
    }
  }

  @Override
  public RecentlyChangedFilesState getState() {
    synchronized (state) {
      RecentlyChangedFilesState stateSnapshot = new RecentlyChangedFilesState();
      stateSnapshot.changedPaths.addAll(state.changedPaths);
      return stateSnapshot;
    }
  }

  @Override
  public void loadState(@NotNull RecentlyChangedFilesState state) {
    synchronized (this.state) {
      this.state.changedPaths.clear();
      this.state.changedPaths.addAll(state.changedPaths);
    }
  }

  public final void onSelectionChanged() {
    myCurrentCommandIsNavigation = true;
    myCurrentCommandHasMoves = true;
  }

  final void onCommandStarted() {
    myCommandStartPlace = getCurrentPlaceInfo();
    myCurrentCommandIsNavigation = false;
    myCurrentCommandHasChanges = false;
    myCurrentCommandHasMoves = false;
    myChangedFilesInCurrentCommand.clear();
  }

  private @Nullable IdeDocumentHistoryImpl.PlaceInfo getCurrentPlaceInfo() {
    FileEditorWithProvider selectedEditorWithProvider = getSelectedEditor();
    if (selectedEditorWithProvider == null) {
      return null;
    }
    return createPlaceInfo(selectedEditorWithProvider.getFileEditor(), selectedEditorWithProvider.getProvider());
  }

  private static @Nullable IdeDocumentHistoryImpl.PlaceInfo getPlaceInfoFromFocus() {
    FileEditor fileEditor = new FocusBasedCurrentEditorProvider().getCurrentEditor();
    if (fileEditor instanceof TextEditor && fileEditor.isValid()) {
      VirtualFile file = fileEditor.getFile();
      if (file != null) {
        return new IdeDocumentHistoryImpl.PlaceInfo(file,
            fileEditor.getState(FileEditorStateLevel.NAVIGATION),
            TextEditorProvider.getInstance().getEditorTypeId(),
            null, false,
            getCaretPosition(fileEditor), System.currentTimeMillis()
        );
      }
    }
    return null;
  }

  final void onCommandFinished(Project project, Object commandGroupId) {
    Object lastGroupId = SoftReference.dereference(myLastGroupId);
    if (!CommandMerger.canMergeGroup(commandGroupId, lastGroupId)) myRegisteredBackPlaceInLastGroup = false;
    if (commandGroupId != lastGroupId) {
      myLastGroupId = commandGroupId == null ? null : new WeakReference<>(commandGroupId);
    }

    if (myCommandStartPlace != null && myCurrentCommandIsNavigation && myCurrentCommandHasMoves) {
      if (!myBackInProgress) {
        if (!myRegisteredBackPlaceInLastGroup) {
          myRegisteredBackPlaceInLastGroup = true;
          putLastOrMerge(myCommandStartPlace, BACK_QUEUE_LIMIT, false);
          registerViewed(getPlaceVirtualFile(myCommandStartPlace));
        }
        if (!myForwardInProgress) {
          myForwardPlaces.clear();
        }
      }
      removeInvalidFilesFromStacks();
    }

    if (myCurrentCommandHasChanges) {
      setCurrentChangePlace(project == myProject);
    } else if (myCurrentCommandHasMoves) {
      myCurrentIndex = myChangePlaces.size();
    }
  }

  private static VirtualFile getPlaceVirtualFile(IdeDocumentHistoryImpl.PlaceInfo placeInfo) {
    try {
      var myFile = placeInfo.getClass().getDeclaredField("myFile");
      myFile.setAccessible(true);
      return (VirtualFile) myFile.get(placeInfo);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e); // shouldn't happen
    }
  }

  @Override
  public final void includeCurrentCommandAsNavigation() {
    myCurrentCommandIsNavigation = true;
  }

  @Override
  public void setCurrentCommandHasMoves() {
    myCurrentCommandHasMoves = true;
  }

  @Override
  public final void includeCurrentPlaceAsChangePlace() {
    setCurrentChangePlace(false);
  }

  private void setCurrentChangePlace(boolean acceptPlaceFromFocus) {
    IdeDocumentHistoryImpl.PlaceInfo placeInfo = getCurrentPlaceInfo();
    if (placeInfo != null && !myChangedFilesInCurrentCommand.contains(placeInfo.getFile())) {
      placeInfo = null;
    }
    if (placeInfo == null && acceptPlaceFromFocus) {
      placeInfo = getPlaceInfoFromFocus();
    }
    if (placeInfo != null && !myChangedFilesInCurrentCommand.contains(placeInfo.getFile())) {
      placeInfo = null;
    }
    if (placeInfo == null) {
      return;
    }

    int limit = UISettings.getInstance().getRecentFilesLimit() + 1;
    synchronized (state) {
      String path = placeInfo.getFile().getPath();
      List<String> changedPaths = state.changedPaths;
      changedPaths.remove(path);
      changedPaths.add(path);
      while (changedPaths.size() > limit) {
        changedPaths.remove(0);
      }
    }

    putLastOrMerge(placeInfo, CHANGE_QUEUE_LIMIT, true);
    myCurrentIndex = myChangePlaces.size();
  }

  @Override
  public @NotNull List<VirtualFile> getChangedFiles() {
    List<VirtualFile> files = new ArrayList<>();
    List<String> paths;
    synchronized (state) {
      paths = state.changedPaths.isEmpty() ? Collections.emptyList() : new ArrayList<>(state.changedPaths);
    }

    LocalFileSystem lfs = LocalFileSystem.getInstance();
    for (String path : paths) {
      VirtualFile file = lfs.findFileByPath(path);
      if (file != null) {
        files.add(file);
      }
    }
    return files;
  }

  boolean isRecentlyChanged(@NotNull VirtualFile file) {
    synchronized (state) {
      return state.changedPaths.contains(file.getPath());
    }
  }

  @Override
  public final void clearHistory() {
    myBackPlaces.clear();
    myForwardPlaces.clear();
    myChangePlaces.clear();

    myLastGroupId = null;

    myCurrentIndex = 0;
    myCommandStartPlace = null;
  }

  @Override
  public final void back() {
    removeInvalidFilesFromStacks();
    if (myBackPlaces.isEmpty()) return;
    final IdeDocumentHistoryImpl.PlaceInfo info = myBackPlaces.removeLast();
    myProject.getMessageBus().syncPublisher(RecentPlacesListener.TOPIC).recentPlaceRemoved(info, false);

    IdeDocumentHistoryImpl.PlaceInfo current = getCurrentPlaceInfo();
    if (current != null) myForwardPlaces.add(current);

    myBackInProgress = true;
    try {
      executeCommand(() -> gotoPlaceInfo(info), "", null);
    } finally {
      myBackInProgress = false;
    }
  }

  @Override
  public final void forward() {
    removeInvalidFilesFromStacks();

    final IdeDocumentHistoryImpl.PlaceInfo target = getTargetForwardInfo();
    if (target == null) return;

    myForwardInProgress = true;
    try {
      executeCommand(() -> gotoPlaceInfo(target), "", null);
    } finally {
      myForwardInProgress = false;
    }
  }

  private IdeDocumentHistoryImpl.PlaceInfo getTargetForwardInfo() {
    if (myForwardPlaces.isEmpty()) return null;

    IdeDocumentHistoryImpl.PlaceInfo target = myForwardPlaces.removeLast();
    IdeDocumentHistoryImpl.PlaceInfo current = getCurrentPlaceInfo();

    while (!myForwardPlaces.isEmpty()) {
      if (current != null && isSame(current, target)) {
        target = myForwardPlaces.removeLast();
      } else {
        break;
      }
    }
    return target;
  }

  @Override
  public final boolean isBackAvailable() {
    return !myBackPlaces.isEmpty();
  }

  @Override
  public final boolean isForwardAvailable() {
    return !myForwardPlaces.isEmpty();
  }

  @Override
  public final void navigatePreviousChange() {
    removeInvalidFilesFromStacks();
    if (myCurrentIndex == 0) return;
    IdeDocumentHistoryImpl.PlaceInfo currentPlace = getCurrentPlaceInfo();
    for (int i = myCurrentIndex - 1; i >= 0; i--) {
      IdeDocumentHistoryImpl.PlaceInfo info = myChangePlaces.get(i);
      if (currentPlace == null || !isSame(currentPlace, info)) {
        executeCommand(() -> gotoPlaceInfo(info), "", null);
        myCurrentIndex = i;
        break;
      }
    }
  }

  @Override
  public @NotNull List<IdeDocumentHistoryImpl.PlaceInfo> getBackPlaces() {
    return Collections.unmodifiableList(myBackPlaces);
  }

  @Override
  public List<IdeDocumentHistoryImpl.PlaceInfo> getChangePlaces() {
    return Collections.unmodifiableList(myChangePlaces);
  }

  @Override
  public void removeBackPlace(@NotNull IdeDocumentHistoryImpl.PlaceInfo placeInfo) {
    removePlaceInfo(placeInfo, myBackPlaces, false);
  }

  @Override
  public void removeChangePlace(@NotNull IdeDocumentHistoryImpl.PlaceInfo placeInfo) {
    removePlaceInfo(placeInfo, myChangePlaces, true);
  }

  private void removePlaceInfo(
      @NotNull IdeDocumentHistoryImpl.PlaceInfo placeInfo,
      @NotNull Collection<IdeDocumentHistoryImpl.PlaceInfo> places,
      boolean changed) {
    boolean removed = places.remove(placeInfo);
    if (removed) {
      myProject.getMessageBus().syncPublisher(RecentPlacesListener.TOPIC).recentPlaceRemoved(placeInfo, changed);
    }
  }

  @Override
  public final boolean isNavigatePreviousChangeAvailable() {
    return myCurrentIndex > 0;
  }

  void removeInvalidFilesFromStacks() {
    removeInvalidFilesFrom(myBackPlaces);

    removeInvalidFilesFrom(myForwardPlaces);
    if (removeInvalidFilesFrom(myChangePlaces)) {
      myCurrentIndex = myChangePlaces.size();
    }
  }

  @Override
  public void navigateNextChange() {
    removeInvalidFilesFromStacks();
    if (myCurrentIndex >= myChangePlaces.size()) return;
    IdeDocumentHistoryImpl.PlaceInfo currentPlace = getCurrentPlaceInfo();
    for (int i = myCurrentIndex; i < myChangePlaces.size(); i++) {
      IdeDocumentHistoryImpl.PlaceInfo info = myChangePlaces.get(i);
      if (currentPlace == null || !isSame(currentPlace, info)) {
        executeCommand(() -> gotoPlaceInfo(info), "", null);
        myCurrentIndex = i + 1;
        break;
      }
    }
  }

  @Override
  public boolean isNavigateNextChangeAvailable() {
    return myCurrentIndex < myChangePlaces.size();
  }

  private static boolean removeInvalidFilesFrom(@NotNull List<IdeDocumentHistoryImpl.PlaceInfo> backPlaces) {
    return backPlaces.removeIf(info -> (getPlaceVirtualFile(info) instanceof SkipFromDocumentHistory) || !getPlaceVirtualFile(info).isValid());
  }

  public interface SkipFromDocumentHistory {
  }

  @Override
  public void gotoPlaceInfo(@NotNull IdeDocumentHistoryImpl.PlaceInfo info) {
    gotoPlaceInfo(info, ToolWindowManager.getInstance(myProject).isEditorComponentActive());
  }

  @Override
  public void gotoPlaceInfo(@NotNull IdeDocumentHistoryImpl.PlaceInfo info, boolean wasActive) {
    EditorWindow wnd = info.getWindow();
    FileEditorManagerEx editorManager = getFileEditorManager();
    FileEditorOpenOptions openOptions = new FileEditorOpenOptions()
        .withUsePreviewTab(info.isPreviewTab())
        .withRequestFocus(wasActive);
    final Pair<FileEditor[], FileEditorProvider[]> editorsWithProviders =
        editorManager.openFileWithProviders(info.getFile(), wnd, openOptions);

    editorManager.setSelectedEditor(info.getFile(), info.getEditorTypeId());

    final FileEditor[] editors = editorsWithProviders.getFirst();
    final FileEditorProvider[] providers = editorsWithProviders.getSecond();
    for (int i = 0; i < editors.length; i++) {
      String typeId = providers[i].getEditorTypeId();
      if (typeId.equals(info.getEditorTypeId())) {
        editors[i].setState(info.getNavigationState());
      }
    }
  }

  /**
   * @return currently selected FileEditor or null.
   */
  protected @Nullable FileEditorWithProvider getSelectedEditor() {
    FileEditorManagerEx editorManager = getFileEditorManager();
    VirtualFile file = editorManager != null ? editorManager.getCurrentFile() : null;
    return file == null ? null : editorManager.getSelectedEditorWithProvider(file);
  }

  // used by Rider
  @SuppressWarnings("WeakerAccess")
  protected IdeDocumentHistoryImpl.PlaceInfo createPlaceInfo(
      @NotNull FileEditor fileEditor,
      FileEditorProvider fileProvider) {
    if (!fileEditor.isValid()) {
      return null;
    }

    FileEditorManagerEx editorManager = getFileEditorManager();
    VirtualFile file = fileEditor.getFile();
    LOG.assertTrue(file != null, fileEditor.getClass().getName() + " getFile() returned null");
    FileEditorState state = fileEditor.getState(FileEditorStateLevel.NAVIGATION);

    EditorWindow window = editorManager.getCurrentWindow();
    EditorComposite composite = window != null ? window.getComposite(file) : null;
    return new IdeDocumentHistoryImpl.PlaceInfo(file,
        state,
        fileProvider.getEditorTypeId(),
        window,
        composite != null && composite.isPreview(),
        getCaretPosition(fileEditor),
        System.currentTimeMillis()
    );
  }

  private static @Nullable RangeMarker getCaretPosition(@NotNull FileEditor fileEditor) {
    if (!(fileEditor instanceof TextEditor)) {
      return null;
    }

    Editor editor = ((TextEditor) fileEditor).getEditor();
    int offset = editor.getCaretModel().getOffset();

    return editor.getDocument().createRangeMarker(offset, offset);
  }

  private void putLastOrMerge(@NotNull IdeDocumentHistoryImpl.PlaceInfo next, int limit, boolean isChanged) {
    LinkedList<IdeDocumentHistoryImpl.PlaceInfo> list = isChanged ? myChangePlaces : myBackPlaces;
    MessageBus messageBus = myProject.getMessageBus();
    RecentPlacesListener listener = messageBus.syncPublisher(RecentPlacesListener.TOPIC);
    if (!list.isEmpty()) {
      IdeDocumentHistoryImpl.PlaceInfo prev = list.getLast();
      if (isSame(prev, next)) {
        IdeDocumentHistoryImpl.PlaceInfo removed = list.removeLast();
        listener.recentPlaceRemoved(removed, isChanged);
      }
    }

    list.add(next);
    listener.recentPlaceAdded(next, isChanged);
    if (list.size() > limit) {
      IdeDocumentHistoryImpl.PlaceInfo first = list.removeFirst();
      listener.recentPlaceRemoved(first, isChanged);
    }
  }

  private FileDocumentManager getFileDocumentManager() {
    if (myFileDocumentManager == null) {
      myFileDocumentManager = FileDocumentManager.getInstance();
    }
    return myFileDocumentManager;
  }

  @Override
  public final void dispose() {
    myLastGroupId = null;
    PersistentHashMap<String, Long> map = recentFileTimestampMap.getValueIfInitialized();
    if (map != null) {
      try {
        map.close();
      } catch (IOException e) {
        LOG.info("Cannot close persistent viewed files timestamps hash map", e);
      }
    }
  }

  protected void executeCommand(Runnable runnable, @NlsContexts.Command String name, Object groupId) {
    CommandProcessor.getInstance().executeCommand(myProject, runnable, name, groupId);
  }

  public static boolean isSame(
      @NotNull IdeDocumentHistoryImpl.PlaceInfo first,
      @NotNull IdeDocumentHistoryImpl.PlaceInfo second) {
    if (first.getFile().equals(second.getFile())) {
      FileEditorState firstState = first.getNavigationState();
      FileEditorState secondState = second.getNavigationState();
      return firstState.equals(secondState) || firstState.canBeMergedWith(secondState, FileEditorStateLevel.NAVIGATION);
    }

    return false;
  }

  /**
   * {@link RecentPlacesListener} listens recently viewed or changed place adding and removing events.
   */
  public interface RecentPlacesListener {
    @Topic.ProjectLevel
    Topic<RecentPlacesListener> TOPIC = new Topic<>(RecentPlacesListener.class, Topic.BroadcastDirection.NONE);

    /**
     * Fires on a new place info adding into {@link #myChangePlaces} or {@link #myBackPlaces} infos list
     *
     * @param changePlace new place info
     * @param isChanged   true if place info was added into the changed infos list {@link #myChangePlaces};
     *                    false if place info was added into the back infos list {@link #myBackPlaces}
     */
    void recentPlaceAdded(@NotNull IdeDocumentHistoryImpl.PlaceInfo changePlace, boolean isChanged);

    /**
     * Fires on a place info removing from the {@link #myChangePlaces} or the {@link #myBackPlaces} infos list
     *
     * @param changePlace place info that was removed
     * @param isChanged   true if place info was removed from the changed infos list {@link #myChangePlaces};
     *                    false if place info was removed from the back infos list {@link #myBackPlaces}
     */
    void recentPlaceRemoved(@NotNull IdeDocumentHistoryImpl.PlaceInfo changePlace, boolean isChanged);
  }
}
