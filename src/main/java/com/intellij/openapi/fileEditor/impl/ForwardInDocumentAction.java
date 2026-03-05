package com.intellij.openapi.fileEditor.impl;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public class ForwardInDocumentAction extends AnAction {
  @Override
  public void actionPerformed(@NotNull final AnActionEvent e) {
    getHistory(e).forwardInCurrentDocument(ShiftPressedListener.shiftPressed);
  }

  @Override
  public void update(@NotNull final AnActionEvent e) {
    super.update(e);
    MyIdeDocumentHistoryImpl history = getHistory(e);
    e.getPresentation().setEnabled(history != null && history.canGoForwardInCurrentDocument(ShiftPressedListener.shiftPressed));
  }

  private MyIdeDocumentHistoryImpl getHistory(final AnActionEvent e) {
    if (e.getProject() == null) {
      return null;
    }
    return ((MyIdeDocumentHistoryImpl) MyIdeDocumentHistoryImpl.getInstance(e.getProject()));
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT; // we're accessing current editor
  }
}
