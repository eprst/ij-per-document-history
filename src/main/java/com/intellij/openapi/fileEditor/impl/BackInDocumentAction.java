package com.intellij.openapi.fileEditor.impl;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class BackInDocumentAction extends AnAction {
  @Override
  public void actionPerformed(@NotNull final AnActionEvent e) {
    getHistory(e).backInCurrentDocument();
  }

  @Override
  public void update(@NotNull final AnActionEvent e) {
    super.update(e);
    e.getPresentation().setEnabled(getHistory(e).getBackInCurrentDocumentPlace().isPresent());
  }

  private MyIdeDocumentHistoryImpl getHistory(final AnActionEvent e) {
    return ((MyIdeDocumentHistoryImpl) MyIdeDocumentHistoryImpl.getInstance(Objects.requireNonNull(e.getProject())));
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT; // we're accessing current editor
  }
}
