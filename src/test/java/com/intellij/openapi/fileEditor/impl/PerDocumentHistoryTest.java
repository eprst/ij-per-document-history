package com.intellij.openapi.fileEditor.impl;

import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Runs against the real platform IdeDocumentHistoryImpl, so it also covers the reflective accessors in
 * {@link MyIdeDocumentHistoryImpl}: removeInvalidFilesFromStacks(), backPlaces, forwardPlaces, backInProgress,
 * forwardInProgress.
 */
public class PerDocumentHistoryTest extends BasePlatformTestCase {
  private MyIdeDocumentHistoryImpl history;
  private VirtualFile a;
  private VirtualFile b;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    history = (MyIdeDocumentHistoryImpl) IdeDocumentHistory.getInstance(getProject());
    // places closer than a few lines are merged by the platform, so jumps below are far apart
    a = myFixture.addFileToProject("A.txt", "line\n".repeat(100)).getVirtualFile();
    b = myFixture.addFileToProject("B.txt", "line\n".repeat(100)).getVirtualFile();
  }

  public void testServiceIsOverridden() {
    assertInstanceOf(IdeDocumentHistory.getInstance(getProject()), MyIdeDocumentHistoryImpl.class);
  }

  public void testBackAndForwardInCurrentDocument() {
    switchTo(a);
    jump(20);
    jump(40);

    history.backInCurrentDocument(false);
    assertEquals(20, caretLine());
    history.backInCurrentDocument(false);
    assertEquals(0, caretLine());
    assertFalse(history.canGoBackInCurrentDocument(false));

    history.forwardInCurrentDocument(false);
    assertEquals(20, caretLine());
    history.forwardInCurrentDocument(false);
    assertEquals(40, caretLine());
    assertFalse(history.canGoForwardInCurrentDocument(false));
  }

  public void testBackSkipsPlaceWhereFileWasLeft() {
    switchTo(a);
    jump(20);
    jump(40);
    switchTo(b);  // records A:40, where A is left
    jump(20);
    switchTo(a);  // caret is restored at A:40

    history.backInCurrentDocument(false);
    assertEquals(20, caretLine());
    history.backInCurrentDocument(false);
    assertEquals(0, caretLine());
    assertFalse(history.canGoBackInCurrentDocument(false));

    history.forwardInCurrentDocument(false);
    assertEquals(20, caretLine());
    history.forwardInCurrentDocument(false);
    assertEquals(40, caretLine());
    assertFalse("no duplicate forward place", history.canGoForwardInCurrentDocument(false));
  }

  public void testBackUnavailableWhenOnlyPlaceIsCurrentPosition() {
    switchTo(a);
    switchTo(b);  // records A:0
    switchTo(a);  // caret is restored at A:0

    assertFalse(history.canGoBackInCurrentDocument(false));
  }

  /** Same shape as EditorTabbedContainer's tab selection: a command calling onSelectionChanged(). */
  private void switchTo(VirtualFile file) {
    CommandProcessor.getInstance().executeCommand(getProject(), () -> {
      FileEditorManager.getInstance(getProject()).openFile(file, true);
      history.onSelectionChanged();
    }, "switch", new Object());
  }

  private void jump(int line) {
    CommandProcessor.getInstance().executeCommand(getProject(), () -> {
      var editor = FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
      editor.getCaretModel().moveToOffset(editor.getDocument().getLineStartOffset(line));
      history.includeCurrentCommandAsNavigation();
    }, "jump", new Object());
  }

  private int caretLine() {
    return FileEditorManager.getInstance(getProject()).getSelectedTextEditor().getCaretModel().getLogicalPosition().line;
  }
}
