// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("HardCodedStringLiteral")

package com.intellij.devkit.compose.showcase.swing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.intellij.CommonBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.NlsSafe
import com.intellij.platform.compose.swing.composeSwingPanel
import com.intellij.platform.ide.productMode.IdeProductMode
import com.intellij.util.ui.JBUI
import org.jetbrains.compose.swing.components.layout.TabbedPane
import org.jetbrains.compose.swing.modifier.SwingModifier
import org.jetbrains.compose.swing.tooling.Preview
import javax.swing.Action
import javax.swing.JComponent

/** Shows the components, the layouts and the animations of the Swing Compose library. */
internal class SwingComposeShowcaseAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    // The dialog is a Swing component of the backend, so a remote frontend cannot show it.
    e.presentation.isEnabledAndVisible = IdeProductMode.isMonolith
  }

  override fun actionPerformed(e: AnActionEvent) {
    SwingComposeShowcaseDialog(e.project, e.presentation.text).show()
  }
}

@Suppress("SplitModeApiUsage")
private class SwingComposeShowcaseDialog(project: Project?, @NlsSafe dialogTitle: String) :
  DialogWrapper(project, null, true, IdeModalityType.MODELESS, false) {

  init {
    title = dialogTitle
    setCancelButtonText(CommonBundle.getCloseButtonText())
    init()
  }

  override fun createCenterPanel(): JComponent =
    JBUI.Panels.simplePanel(composeSwingPanel(disposable) { SwingComposeShowcase() }).apply {
      preferredSize = JBUI.size(900, 700)
    }

  override fun createActions(): Array<Action> = arrayOf(cancelAction)

  override fun getDimensionServiceKey(): String = "SWING_COMPOSE_SHOWCASE_DIALOG"
}

@Composable
private fun SwingComposeShowcase() {
  var selectedTab by remember { mutableIntStateOf(0) }
  TabbedPane(selectedIndex = selectedTab, onSelectedIndexChange = { selectedTab = it }) {
    AnimationsTab(SwingModifier.tab("Animations"))
    LayoutsTab(SwingModifier.tab("Layouts"))
  }
}

@Preview(width = 900, height = 700)
@Composable
private fun SwingComposeShowcasePreview() {
  SwingComposeShowcase()
}
