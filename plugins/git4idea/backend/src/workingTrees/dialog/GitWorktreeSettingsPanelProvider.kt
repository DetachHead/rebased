// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees.dialog

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Panel
import git4idea.workingTrees.GitWorktreeConfigCopyContext

/**
 * Applies one [GitWorktreeSettingsPanelProvider]'s settings once the worktree exists.
 */
interface GitWorktreeSettingsPanelHandle {
  suspend fun apply(context: GitWorktreeConfigCopyContext)
}

/**
 * Contributes a settings section to the New Worktree dialog. Register an implementation to let the user
 * configure something else about the new worktree, such as a bigger settings panel of your own.
 */
interface GitWorktreeSettingsPanelProvider {
  companion object {
    private val EP_NAME: ExtensionPointName<GitWorktreeSettingsPanelProvider> =
      ExtensionPointName.create("Git4Idea.worktreeSettingsPanelProvider")

    /** Runs [action] for every registered [GitWorktreeSettingsPanelProvider] extension. */
    internal fun forEachExtensionSafe(action: (GitWorktreeSettingsPanelProvider) -> Unit) {
      EP_NAME.forEachExtensionSafe(action)
    }
  }

  /**
   * Adds this provider's UI to [panel] and returns a handle that applies the user's choices once the
   * worktree is created.
   */
  fun createPanel(panel: Panel, project: Project): GitWorktreeSettingsPanelHandle
}
