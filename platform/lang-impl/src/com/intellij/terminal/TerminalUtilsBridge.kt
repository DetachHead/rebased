// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.terminal

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.serviceOrNull
import org.jetbrains.annotations.ApiStatus
import java.awt.Component

@ApiStatus.Internal
interface TerminalUtilsBridge {
  companion object {
    @JvmStatic
    fun getInstance(): TerminalUtilsBridge? = serviceOrNull<TerminalUtilsBridge>()
  }

  fun isTerminalComponent(component: Component?): Boolean
  fun hasSelectionInTerminal(component: Component?): Boolean
  fun getSelectedTextInTerminal(component: Component?): String?
  fun getTextInTerminal(component: Component?): String?
  fun getSelectedTextInTerminal(context: DataContext): String?
}