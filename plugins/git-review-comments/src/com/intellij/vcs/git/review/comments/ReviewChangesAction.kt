// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import kotlinx.coroutines.launch

/**
 * In-app entry point for a review session, mirroring the `review` CLI command
 * ([ReviewApplication]) but triggered from the Local Changes view instead of a terminal, and
 * running inside the already-open project rather than spawning a new process. Both paths
 * share [openReviewSession], so the diff chain, gutter/inline comment UI, and Finish Review
 * export behave identically either way.
 *
 * Reviews uncommitted changes only (no ref/range argument, unlike the CLI command) -- selecting
 * an arbitrary changelist or historical ref for the in-app action is out of scope for now.
 */
internal class ReviewChangesAction : DumbAwareAction() {
  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    project.service<ReviewCoroutineScopeService>().cs.launch {
      val repoRoot = ReviewApplication.resolveRepoRoot(project.basePath)
      openReviewSession(project, repoRoot, ref = null)
    }
  }
}
