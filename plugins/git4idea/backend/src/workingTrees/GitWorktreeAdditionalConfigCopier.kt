// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees

import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.Nls
import java.nio.file.Path

/**
 * The source project and the target worktree directory of an in-progress worktree creation.
 */
data class GitWorktreeConfigCopyContext(val project: Project, val sourceRoot: VirtualFile, val targetWorktreeDir: Path)

/**
 * Copies extra local files into a new worktree, as part of "Copy local project setup" in the New Worktree
 * dialog. Register an implementation to copy a file this plugin does not already know about.
 *
 * The dialog shows one checkbox per registered extension, labeled with [checkboxText]. [copyAdditionalConfig]
 * runs only if the user leaves that checkbox checked.
 */
interface GitWorktreeAdditionalConfigCopier {
  companion object {
    private val EP_NAME: ExtensionPointName<GitWorktreeAdditionalConfigCopier> =
      ExtensionPointName.create("Git4Idea.worktreeAdditionalConfigCopier")

    private val LOG = logger<GitWorktreeAdditionalConfigCopier>()

    /** Every registered [GitWorktreeAdditionalConfigCopier] extension. */
    internal fun getExtensions(): List<GitWorktreeAdditionalConfigCopier> = EP_NAME.extensionList

    /** Runs every extension in [enabled]. Returns `true` only when every one of them succeeded. */
    internal suspend fun copyAll(context: GitWorktreeConfigCopyContext, enabled: Set<GitWorktreeAdditionalConfigCopier>): Boolean {
      var allSucceeded = true
      for (extension in EP_NAME.extensionList) {
        if (extension !in enabled) continue
        try {
          extension.copyAdditionalConfig(context)
        }
        catch (e: Throwable) {
          rethrowControlFlowException(e)
          LOG.error("${extension.javaClass.name} failed to copy additional worktree config", e)
          allSucceeded = false
        }
      }
      return allSucceeded
    }
  }

  /** The label of this extension's checkbox in the New Worktree dialog. */
  val checkboxText: @Nls String

  suspend fun copyAdditionalConfig(context: GitWorktreeConfigCopyContext)
}
