// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl

import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.WINDOW_INFO_DEFAULT_TOOL_WINDOW_PANE_ID
import com.intellij.toolWindow.InternalDecoratorImpl
import com.intellij.toolWindow.ToolWindowPane
import com.intellij.ui.ClientProperty
import com.intellij.util.concurrency.annotations.RequiresEdt
import javax.swing.JComponent

internal class CentralVcsHost {
  private var pane: ToolWindowPane? = null
  private var component: JComponent? = null

  fun normalize(info: WindowInfoImpl) {
    info.type = ToolWindowType.DOCKED
    info.internalType = ToolWindowType.DOCKED
    info.anchor = ToolWindowAnchor.BOTTOM
    info.isAutoHide = false
    info.isSplit = false
    info.toolWindowPaneId = WINDOW_INFO_DEFAULT_TOOL_WINDOW_PANE_ID
    info.floatingBounds = null
    info.isMaximized = false
    info.isShowStripeButton = true
  }

  @RequiresEdt
  fun attach(pane: ToolWindowPane, component: JComponent, dirtyMode: Boolean) {
    if (this.pane !== pane || this.component !== component) {
      detach(expectedComponent = null, dirtyMode = true)
      this.pane = pane
      this.component = component
    }

    ClientProperty.put(component, InternalDecoratorImpl.HIDE_COMMON_TOOLWINDOW_BUTTONS, true)
    if (pane.getDocumentComponent() !== component) {
      pane.setDocumentComponent(component)
    }
    if (!dirtyMode) {
      pane.validateAndRepaint()
    }
  }

  @RequiresEdt
  fun detach(expectedComponent: JComponent?, dirtyMode: Boolean) {
    val currentComponent = component ?: return
    if (expectedComponent != null && currentComponent !== expectedComponent) {
      return
    }

    val currentPane = pane
    if (currentPane?.getDocumentComponent() === currentComponent) {
      currentPane.setDocumentComponent(null)
    }
    ClientProperty.put(currentComponent, InternalDecoratorImpl.HIDE_COMMON_TOOLWINDOW_BUTTONS, null)
    pane = null
    component = null
    if (!dirtyMode) {
      currentPane?.validateAndRepaint()
    }
  }
}
