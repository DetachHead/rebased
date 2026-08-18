// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl

import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.WINDOW_INFO_DEFAULT_TOOL_WINDOW_PANE_ID
import com.intellij.toolWindow.InternalDecoratorImpl
import com.intellij.toolWindow.ToolWindowPane
import com.intellij.ui.ClientProperty
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal class CentralVcsController {
  private var pane: ToolWindowPane? = null
  private var component: JComponent? = null
  private val surface = CentralVcsSurface()

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
  fun install(pane: ToolWindowPane, dirtyMode: Boolean) {
    if (this.pane !== pane) {
      this.pane?.let { previousPane ->
        if (previousPane.getDocumentComponent() === surface) {
          previousPane.setDocumentComponent(null)
        }
      }
      this.pane = pane
    }

    if (pane.getDocumentComponent() !== surface) {
      pane.setDocumentComponent(surface)
    }
    if (!dirtyMode) {
      pane.validateAndRepaint()
    }
  }

  @RequiresEdt
  fun attach(pane: ToolWindowPane, component: JComponent, dirtyMode: Boolean) {
    install(pane, dirtyMode = true)

    if (this.component === component) {
      if (!dirtyMode) {
        pane.validateAndRepaint()
      }
      return
    }

    this.component?.let {
      ClientProperty.put(it, InternalDecoratorImpl.HIDE_COMMON_TOOLWINDOW_BUTTONS, null)
      surface.remove(it)
    }
    this.component = component
    ClientProperty.put(component, InternalDecoratorImpl.HIDE_COMMON_TOOLWINDOW_BUTTONS, true)
    surface.add(component, BorderLayout.CENTER)
    if (!dirtyMode) {
      pane.validateAndRepaint()
    }
  }

  @RequiresEdt
  fun detach(expectedComponent: JComponent?, dirtyMode: Boolean) {
    val currentComponent = component
    if (currentComponent == null || expectedComponent != null && currentComponent !== expectedComponent) {
      return
    }

    ClientProperty.put(currentComponent, InternalDecoratorImpl.HIDE_COMMON_TOOLWINDOW_BUTTONS, null)
    surface.remove(currentComponent)
    component = null
    if (!dirtyMode) {
      pane?.validateAndRepaint()
    }
  }

  @RequiresEdt
  fun dispose(dirtyMode: Boolean) {
    detach(expectedComponent = null, dirtyMode = true)
    val currentPane = pane
    if (currentPane?.getDocumentComponent() === surface) {
      currentPane.setDocumentComponent(null)
    }
    pane = null
    if (!dirtyMode) {
      currentPane?.validateAndRepaint()
    }
  }

  fun getSurface(): JComponent = surface
}

private class CentralVcsSurface : JPanel(BorderLayout()) {
  init {
    name = "CentralVcsSurface"
  }
}
