// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.IOException

/**
 * "Finish Review" action for comments left on ordinary local-change diffs (Local Changes view,
 * "Show Diff", etc. -- see the class doc on [ReviewDiffExtension]), as opposed to
 * [FinishReviewAction], which finishes one `review` CLI session. There is no single owning
 * diff window for this path (comments can come from any number of independently opened
 * diffs), so this action neither requires nor closes one: it just exports whatever has
 * accumulated in [ProjectReviewCommentStore] to `<repo-root>/.git/review-comments.json` and
 * clears the store, so the next round of comments starts fresh.
 *
 * Per the `actions` skill conventions (see `plugins/git4idea/backend/src/actions/GitInit.java`):
 * no-arg constructor, no [com.intellij.openapi.actionSystem.Presentation] built in the
 * constructor, text/description sourced from the message bundle
 * (`action.Git.Review.FinishGlobalReview.text`/`.description` in
 * `GitReviewCommentsBundle.properties`), [getActionUpdateThread] returns
 * [ActionUpdateThread.BGT].
 */
internal class FinishGlobalReviewAction : DumbAwareAction() {
  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabledAndVisible = project != null && isEnabled(project)
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val projectStore = project.service<ProjectReviewCommentStore>()
    val repoRoot = projectStore.repoRoot ?: return

    try {
      ReviewCommentsJsonExporter.export(projectStore.store.comments.value, repoRoot)
    }
    catch (ex: IOException) {
      Messages.showErrorDialog(
        project,
        GitReviewCommentsBundle.message("finish.review.export.failed", ex.message ?: ex.toString()),
        GitReviewCommentsBundle.message("action.Git.Review.FinishGlobalReview.text"),
      )
      return
    }

    projectStore.store.clear()
  }

  companion object {
    /**
     * Enabled once a local-change diff has resolved a repo root for this project (i.e. at
     * least one such diff has been opened) and there is at least one comment to export.
     * Deliberately requires a real comment, unlike [FinishReviewAction.isEnabled]: this button
     * has no session to "end" the way a CLI review's window-owning action does, so a stray
     * click with nothing to say shouldn't be able to produce a "review complete" signal on its
     * own -- the "I'm done, no more comments" exit is handled conversationally instead (the
     * `diff-review` skill asks the user directly rather than depending on this button being
     * clickable with an empty store).
     */
    internal fun isEnabled(project: Project): Boolean {
      val projectStore = project.service<ProjectReviewCommentStore>()
      return projectStore.repoRoot != null && !projectStore.store.isEmpty()
    }
  }
}
