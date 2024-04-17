package com.intellij.openapi.fileEditor.impl;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.IdeEventQueue;

import java.awt.event.KeyEvent;

public class ShiftPressedListener implements AppLifecycleListener {
  static boolean shiftPressed;

  @Override
  public void appStarted() {
    IdeEventQueue.getInstance().addDispatcher(e -> {
      if (e instanceof final KeyEvent ke && ke.getKeyCode() == KeyEvent.VK_SHIFT) {
        if (ke.getID() == KeyEvent.KEY_PRESSED) {
          shiftPressed = true;
        } else if (ke.getID() == KeyEvent.KEY_RELEASED) {
          shiftPressed = false;
        }
      }
      return false;
    }, null);
  }
}
