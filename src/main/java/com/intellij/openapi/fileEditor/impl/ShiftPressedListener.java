package com.intellij.openapi.fileEditor.impl;

import com.intellij.ide.AppLifecycleListener;

import java.awt.*;
import java.awt.event.KeyEvent;

public class ShiftPressedListener implements AppLifecycleListener {
  static volatile boolean shiftPressed;

  @Override
  public void appStarted() {
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventPostProcessor(e -> {
      if (e.getKeyCode() == KeyEvent.VK_SHIFT) {
        shiftPressed = e.getID() == KeyEvent.KEY_PRESSED;
      }
      return false;
    });
  }
}
