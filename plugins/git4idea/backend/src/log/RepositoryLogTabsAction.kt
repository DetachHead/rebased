// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.log

import com.intellij.CommonBundle
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import git4idea.i18n.GitBundle
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent

internal class RepositoryLogTabsAction : DumbAwareAction(), CustomComponentAction {
  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.service<GitRepositoryLogTabs>()?.isAvailable == true
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val tabs = project.service<GitRepositoryLogTabs>()
    e.coroutineScope.launch {
      val branches = readAction { GitRepositoryManager.getInstance(project).repositories.branchNames() }
      withContext(Dispatchers.EDT) {
        val dialog = RepositoryLogTabsDialog(project, branches, tabs.selectedBranch, tabs.showRepositoryPaths)
        if (dialog.showAndGet()) tabs.setFilter(dialog.branch, dialog.showRepositoryPaths)
      }
    }
  }

  override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
    ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

private class RepositoryLogTabsDialog(
  project: Project,
  branches: List<String>,
  selectedBranch: String?,
  showPaths: Boolean,
) : DialogWrapper(project) {
  private val branchField = TextFieldWithAutoCompletion.create(project, branches, true, selectedBranch).apply {
    setPreferredWidth(JBUI.scale(300))
  }
  val branch: String get() = branchField.text
  var showRepositoryPaths: Boolean = showPaths
    private set

  init {
    title = GitBundle.message("action.Git.Log.Repository.Tabs.text")
    setOKButtonText(CommonBundle.getApplyButtonText())
    init()
  }

  override fun createCenterPanel(): JComponent = panel {
    row(GitBundle.message("git.log.repository.tabs.branch")) {
      cell(branchField).align(AlignX.FILL).resizableColumn()
    }
    row {
      checkBox(GitBundle.message("git.log.repository.tabs.show.paths")).bindSelected(::showRepositoryPaths)
    }
    row {
      comment(GitBundle.message("git.log.repository.tabs.description"))
    }
  }

  override fun getPreferredFocusedComponent(): JComponent = branchField

  override fun createLeftSideActions(): Array<Action> = arrayOf(
    object : DialogWrapperAction(GitBundle.message("git.log.repository.tabs.clear")) {
      override fun doAction(e: ActionEvent) {
        branchField.text = ""
        doOKAction()
      }
    }
  )
}
