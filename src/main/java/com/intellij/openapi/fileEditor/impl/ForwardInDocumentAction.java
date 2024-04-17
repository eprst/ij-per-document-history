package com.intellij.openapi.fileEditor.impl;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

import static java.awt.event.ActionEvent.SHIFT_MASK;

public class ForwardInDocumentAction extends AnAction {
  @Override
  public void actionPerformed(@NotNull final AnActionEvent e) {
    getHistory(e).forwardInCurrentDocument(ShiftPressedListener.shiftPressed);
  }

  @Override
  public void update(@NotNull final AnActionEvent e) {
    super.update(e);
    e.getPresentation().setEnabled(getHistory(e).canGoForwardInCurrentDocument(ShiftPressedListener.shiftPressed));
  }

  private MyIdeDocumentHistoryImpl getHistory(final AnActionEvent e) {
    return ((MyIdeDocumentHistoryImpl) MyIdeDocumentHistoryImpl.getInstance(Objects.requireNonNull(e.getProject())));
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT; // we're accessing current editor
  }
}
