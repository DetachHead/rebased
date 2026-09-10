// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.diff.DiffContext
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import java.awt.event.WindowEvent
import java.io.IOException
import javax.swing.SwingUtilities

/**
 * "Finish Review" action: exports every comment collected so far in the current `review`
 * session (see [ReviewApplication]) to `<repo-root>/.git/review-comments.json` via
 * [ReviewCommentsJsonExporter], then closes the review window -- ending the session the same
 * way the user closing the window manually would (see the `windowClosed` listener
 * [ReviewApplication] installs).
 *
 * Per the `actions` skill conventions (see `plugins/git4idea/backend/src/actions/GitInit.java`):
 * no-arg constructor, no [com.intellij.openapi.actionSystem.Presentation] built in the
 * constructor, text/description sourced from the message bundle
 * (`action.Git.Review.FinishReview.text`/`.description` in
 * `GitReviewCommentsBundle.properties`), [getActionUpdateThread] returns
 * [ActionUpdateThread.BGT].
 */
class FinishReviewAction : DumbAwareAction() {
  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = isEnabled(e.getData(DiffDataKeys.DIFF_CONTEXT))
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent) {
    val context = e.getData(DiffDataKeys.DIFF_CONTEXT) ?: return
    val store = context.getUserData(InMemoryReviewCommentStore.KEY) ?: return
    val repoRoot = context.getUserData(REPO_ROOT_KEY) ?: return

    try {
      ReviewCommentsJsonExporter.export(store.comments.value, repoRoot)
    }
    catch (ex: IOException) {
      Messages.showErrorDialog(
        context.project,
        GitReviewCommentsBundle.message("finish.review.export.failed", ex.message ?: ex.toString()),
        GitReviewCommentsBundle.message("action.Git.Review.FinishReview.text"),
      )
      return
    }

    closeReviewWindow(e, context)
  }

  /**
   * Closes the review window the same way a manual close would: dispatches a `WINDOW_CLOSING`
   * event rather than disposing directly, so the `windowClosed` listener [ReviewApplication]
   * installs (which completes the `review` CLI command's await) fires normally.
   */
  private fun closeReviewWindow(e: AnActionEvent, context: DiffContext) {
    val component = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
    val window = component?.let { SwingUtilities.getWindowAncestor(it) }
    if (window == null) {
      // The comments were already exported successfully at this point -- failing to also
      // close the window shouldn't look like data loss, but it should be visible rather than
      // a silent no-op (the `review` CLI invocation would otherwise hang indefinitely).
      LOG.warn("Finish Review: could not resolve a window to close from the action event's context component")
      Messages.showErrorDialog(
        context.project,
        GitReviewCommentsBundle.message("finish.review.window.not.found"),
        GitReviewCommentsBundle.message("action.Git.Review.FinishReview.text"),
      )
      return
    }
    window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
  }

  companion object {
    private val LOG = logger<FinishReviewAction>()

    /**
     * A `review` session is considered "active" (and this action enabled) if [context] is a
     * diff viewer created within one -- signaled by [InMemoryReviewCommentStore.KEY] being
     * present on it (the "session marker"), regardless of whether the store is currently
     * empty: finishing a review with zero comments left is a valid outcome (e.g. an empty
     * changeset, or a re-review after fixes with nothing left to flag), not a disabled state.
     *
     * Internal (not private) so [FinishReviewActionTest] can exercise this against a fake
     * [DiffContext], without needing a full [AnActionEvent]/platform fixture.
     */
    internal fun isEnabled(context: DiffContext?): Boolean =
      context?.getUserData(InMemoryReviewCommentStore.KEY) != null
  }
}
