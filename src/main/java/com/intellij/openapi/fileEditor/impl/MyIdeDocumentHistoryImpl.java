package com.intellij.openapi.fileEditor.impl;

import com.intellij.openapi.components.*;
import com.intellij.openapi.fileEditor.ex.FileEditorWithProvider;
import com.intellij.openapi.project.Project;
import kotlinx.coroutines.CoroutineScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

@Service(Service.Level.PROJECT)
//@State(name = "IdeDocumentHistory", storages = @Storage(StoragePathMacros.PRODUCT_WORKSPACE_FILE), reportStatistic = false)
public final class MyIdeDocumentHistoryImpl extends IdeDocumentHistoryImpl {
  private final Project myProject;

  public MyIdeDocumentHistoryImpl(@NotNull final Project project, @NotNull final CoroutineScope coroutineScope) {
    super(project, coroutineScope);
    myProject = project;
  }

  public void backInCurrentDocument(boolean shiftPressed) {
    if (shiftPressed) {
      super.back();
    } else {
      superRemoveInvalidFilesFromStacks();
      getBackInCurrentDocumentPlace().ifPresent(place -> {
        superBackPlaces().remove(place);
        myProject.getMessageBus().syncPublisher(RecentPlacesListener.TOPIC).recentPlaceRemoved(place, false);

        var current = getCurrentPlaceInfo();
        if (current != null) superForwardPlaces().add(current);

        setSuperBackInProgress(true);
        try {
          executeCommand(() -> gotoPlaceInfo(place), "", null);
        } finally {
          setSuperBackInProgress(false);
        }
      });
    }
  }

  public void forwardInCurrentDocument(boolean shiftPressed) {
    if (shiftPressed) {
      super.forward();
    } else {
      superRemoveInvalidFilesFromStacks();
      getForwardInCurrentDocumentPlace().ifPresent(place -> {
        superForwardPlaces().remove(place);
        setSuperForwardInProgress(true);
        try {
          executeCommand(() -> gotoPlaceInfo(place), "", null);
        } finally {
          setSuperForwardInProgress(false);
        }
      });
    }
  }

  public boolean canGoBackInCurrentDocument(boolean shiftPressed) {
    if (shiftPressed) {
      return super.isBackAvailable();
    } else {
      return getBackInCurrentDocumentPlace().isPresent();
    }
  }

  public Optional<PlaceInfo> getBackInCurrentDocumentPlace() {
    var curEditor = getSelectedEditor();

    if (curEditor != null) {
      var backPlaces = superBackPlaces();
      var currentPlace = getCurrentPlaceInfo();
      var currentWindow = currentPlace == null ? null : currentPlace.getWindow();

      return backPlaces
          .reversed()
          .stream()
          .filter(p -> p.getFile().equals(curEditor.getFileEditor().getFile()))
          .filter(p -> currentWindow == null || currentWindow == p.getWindow())
          .findFirst();
    } else {
      return Optional.empty();
    }
  }

  public boolean canGoForwardInCurrentDocument(boolean shiftPressed) {
    if (shiftPressed) {
      return super.isForwardAvailable();
    } else {
      return getForwardInCurrentDocumentPlace().isPresent();
    }
  }

  public Optional<PlaceInfo> getForwardInCurrentDocumentPlace() {
    var curEditor = getSelectedEditor();
    if (curEditor != null) {
      var forwardPlaces = superForwardPlaces();
      var currentPlace = getCurrentPlaceInfo();
      var currentWindow = currentPlace == null ? null : currentPlace.getWindow();

      return forwardPlaces
          .reversed()
          .stream()
          .filter(p -> p.getFile().equals(curEditor.getFileEditor().getFile()))
          .filter(p -> currentWindow == null || currentWindow == p.getWindow())
          .findFirst();
    } else {
      return Optional.empty();
    }
  }

  private ArrayDeque<PlaceInfo> superBackPlaces() {
    var sup = getClass().getSuperclass();
    RuntimeException savedException = null;
    for (var name : new String[]{"myBackPlaces", "backPlaces"}) {
      try {
        var fld = sup.getDeclaredField(name);
        fld.setAccessible(true);
        //noinspection unchecked
        return (ArrayDeque<PlaceInfo>) fld.get(this);
      } catch (NoSuchFieldException | IllegalAccessException e) {
        savedException = new RuntimeException(e);
      }
    }
    throw savedException;
  }

  private ArrayDeque<PlaceInfo> superForwardPlaces() {
    var sup = getClass().getSuperclass();
    RuntimeException savedException = null;
    for (var name : new String[]{"myForwardPlaces", "forwardPlaces"}) {
      try {
        var fld = sup.getDeclaredField(name);
        fld.setAccessible(true);
        //noinspection unchecked
        return (ArrayDeque<PlaceInfo>) fld.get(this);
      } catch (NoSuchFieldException | IllegalAccessException e) {
        savedException = new RuntimeException(e);
      }
    }
    throw savedException;
  }

  private void setSuperBackInProgress(boolean p) {
    var sup = getClass().getSuperclass();
    RuntimeException savedException = null;
    for (var name : new String[]{"myBackInProgress", "backInProgress"}) {
      try {
        var fld = sup.getDeclaredField(name);
        fld.setAccessible(true);
        fld.setBoolean(this, p);
        return;
      } catch (NoSuchFieldException | IllegalAccessException e) {
        savedException = new RuntimeException(e);
      }
    }
    throw savedException;
  }

  private void setSuperForwardInProgress(boolean p) {
    var sup = getClass().getSuperclass();
    RuntimeException savedException = null;
    for (var name : new String[]{"myForwardInProgress", "forwardInProgress"}) {
      try {
        var fld = sup.getDeclaredField(name);
        fld.setAccessible(true);
        fld.setBoolean(this, p);
        return;
      } catch (NoSuchFieldException | IllegalAccessException e) {
        savedException = new RuntimeException(e);
      }
    }
    throw savedException;
  }

  private void superRemoveInvalidFilesFromStacks() {
    try {
      var m = getClass().getSuperclass().getDeclaredMethod("removeInvalidFilesFromStacks");
      m.setAccessible(true);
      m.invoke(this);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  private @Nullable PlaceInfo getCurrentPlaceInfo() {
    FileEditorWithProvider selectedEditorWithProvider = getSelectedEditor();
    if (selectedEditorWithProvider == null) {
      return null;
    }
    return createPlaceInfo(selectedEditorWithProvider.getFileEditor(), selectedEditorWithProvider.getProvider());
  }
}
