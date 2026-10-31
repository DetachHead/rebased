// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.terminal;

import org.jetbrains.annotations.Nullable;

import java.awt.Component;

public final class TerminalUtils {

  private TerminalUtils() { }

  public static boolean isTerminalComponent(@Nullable Component component) {
    TerminalUtilsBridge bridge = TerminalUtilsBridge.getInstance();
    return bridge != null && bridge.isTerminalComponent(component);
  }

  public static boolean hasSelectionInTerminal(@Nullable Component component) {
    TerminalUtilsBridge bridge = TerminalUtilsBridge.getInstance();
    return bridge != null && bridge.hasSelectionInTerminal(component);
  }

  public static @Nullable String getSelectedTextInTerminal(@Nullable Component component) {
    TerminalUtilsBridge bridge = TerminalUtilsBridge.getInstance();
    return bridge != null ? bridge.getSelectedTextInTerminal(component) : null;
  }

  public static @Nullable String getTextInTerminal(@Nullable Component component) {
    TerminalUtilsBridge bridge = TerminalUtilsBridge.getInstance();
    return bridge != null ? bridge.getTextInTerminal(component) : null;
  }
}
