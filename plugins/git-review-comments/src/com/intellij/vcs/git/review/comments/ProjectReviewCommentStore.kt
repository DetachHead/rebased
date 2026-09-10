// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.openapi.components.Service
import java.io.File

/**
 * Project-scoped [InMemoryReviewCommentStore], shared by every diff [ReviewDiffExtension]
 * recognizes as a local/uncommitted change (see [ReviewDiffExtension.isLocalChangeDiff]) --
 * unlike the `review` CLI command's session-scoped store, which is attached per-invocation to
 * one [com.intellij.diff.DiffContext] (see [InMemoryReviewCommentStore.KEY]), this store
 * persists for the project's lifetime so comments left across *any* number of independently
 * opened diffs (double-click a changed file, "Show Diff", etc.) accumulate in one place until
 * "Finish Review" ([FinishGlobalReviewAction]) exports and clears them.
 *
 * [repoRoot] is resolved lazily (via `git rev-parse --show-toplevel`, same as the CLI path)
 * the first time a local-change diff is opened, and cached for the rest of the project's
 * session -- every local change in one project is assumed to belong to the same git repo.
 */
@Service(Service.Level.PROJECT)
internal class ProjectReviewCommentStore {
  val store: InMemoryReviewCommentStore = InMemoryReviewCommentStore()

  @Volatile
  var repoRoot: File? = null
}
