// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.diff.util.Side
import com.intellij.openapi.util.Key
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A mutable collection of [ReviewComment]s, shared across every file diff that reads/writes
 * it. Two lifetimes use this same class: a `review` CLI session's store lives only for that
 * session (see [KEY]); [ProjectReviewCommentStore]'s store instead persists for the whole
 * project session, shared across independently-opened local-change diffs.
 *
 * Where `GHPRReviewDiffExtension`/`GitLabMergeRequestDiffExtension` back their gutter models
 * with a real backend-synced review view model, this is intentionally just an in-memory list:
 * the "backend" here is a local JSON file written once, at "Finish Review" time (see
 * `ReviewCommentsJsonExporter`, added in a later task).
 *
 * One instance is created per `review` session (by `ReviewApplication`, added in a later
 * task) and attached to the session's [com.intellij.diff.DiffContext] under [KEY], the same
 * way `GHPRDiffViewModel.KEY`/`GitLabMergeRequestDiffViewModel.KEY` are attached to a PR/MR
 * review's `DiffContext` -- so every [ReviewDiffExtension]-backed viewer opened in the
 * session reads and writes the same store.
 *
 * Deliberately not a platform `@Service`: this class has no dependency on the platform
 * (no `ApplicationManager`/`Project` access), so it can be constructed and exercised directly
 * in plain unit tests.
 */
class InMemoryReviewCommentStore {
  private val _comments = MutableStateFlow<List<ReviewComment>>(emptyList())

  /** All comments currently in the store, across every file in the session. */
  val comments: StateFlow<List<ReviewComment>> = _comments.asStateFlow()

  /** Appends [comment] to the store. Duplicate comments (same file/line/side/text) are allowed. */
  fun addComment(comment: ReviewComment) {
    _comments.value = _comments.value + comment
  }

  /**
   * Removes the first occurrence of [comment] equal to the given value, if any.
   * No-op if [comment] is not present.
   */
  fun removeComment(comment: ReviewComment) {
    val current = _comments.value
    val index = current.indexOf(comment)
    if (index < 0) return
    _comments.value = current.subList(0, index) + current.subList(index + 1, current.size)
  }

  /**
   * Removes comments on the given [filePath]/[line]/[side], if any.
   *
   * @param onlyIfTextEmpty when `true`, only removes matching comments whose [ReviewComment.text]
   *   is empty -- used by [ReviewDiffExtension]'s `cancelNewComment` to discard an
   *   in-progress/placeholder comment without also deleting a real, already-written comment
   *   that happens to share the same file/line/side.
   */
  fun removeCommentsAt(filePath: String, line: Int, side: Side, onlyIfTextEmpty: Boolean = false) {
    _comments.value = _comments.value.filterNot {
      it.filePath == filePath && it.line == line && it.side == side && (!onlyIfTextEmpty || it.text.isEmpty())
    }
  }

  /** Snapshot of the comments currently anchored to [filePath], in insertion order. */
  fun commentsForFile(filePath: String): List<ReviewComment> =
    _comments.value.filter { it.filePath == filePath }

  /** True if the store has no comments at all, across every file. */
  fun isEmpty(): Boolean = _comments.value.isEmpty()

  /**
   * Removes every comment from the store. Used by [FinishGlobalReviewAction] after exporting
   * [ProjectReviewCommentStore]'s comments, so the next round of local-change diffs starts
   * from an empty store rather than re-exporting already-handled comments.
   */
  fun clear() {
    _comments.value = emptyList()
  }

  companion object {
    val KEY: Key<InMemoryReviewCommentStore> = Key.create(InMemoryReviewCommentStore::class.java.name)
  }
}
