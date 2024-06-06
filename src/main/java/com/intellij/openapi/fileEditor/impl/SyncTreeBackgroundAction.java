package com.intellij.openapi.fileEditor.impl;

import com.intellij.ide.ui.LafManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class SyncTreeBackgroundAction extends AnAction {
  @Override
  public void actionPerformed(@NotNull final AnActionEvent e) {
    var editorColorScheme = EditorColorsManager.getInstance().getGlobalScheme();
    var editorBackgroundColor = editorColorScheme.getAttributes(HighlighterColors.TEXT).getBackgroundColor();

    var defaults = UIManager.getDefaults();
    defaults.remove("Tree.background");
    defaults.put("Tree.background", editorBackgroundColor);

    LafManager.getInstance().repaintUI();
  }
}
