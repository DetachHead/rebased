// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.collaboration.ui.codereview.diff.DiffLineLocation
import com.intellij.collaboration.ui.codereview.diff.viewer.showCodeReview
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorGutterControlsModel
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorGutterControlsRenderer
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.openapi.util.Key
import com.intellij.util.cancelOnDispose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Attaches a per-line gutter "add comment" affordance to every diff viewer opened during a
 * `review` session, backed by an [InMemoryReviewCommentStore].
 *
 * Mirrors [org.jetbrains.plugins.github.pullrequest.ui.diff.GHPRReviewDiffExtension] and
 * [org.jetbrains.plugins.gitlab.mergerequest.diff.GitLabMergeRequestDiffExtension]: reads a
 * review view model off the [DiffContext]'s user data, then calls
 * [com.intellij.collaboration.ui.codereview.diff.viewer.showCodeReview] to wire it into the
 * viewer's editor(s). Unlike those two, this extension needs no inlay/comment-thread UI (that
 * is out of scope for this plan -- see the plan's Overview) so it renders only the gutter
 * controls, via [CodeReviewEditorGutterControlsRenderer.render] directly, instead of also
 * building an inlay model.
 *
 * `ReviewApplication` (added in a later task), which drives the `review` CLI command, is
 * responsible for:
 *  - creating one [InMemoryReviewCommentStore] per review session and attaching it to the
 *    session's [DiffContext] under [InMemoryReviewCommentStore.KEY];
 *  - attaching each file's repo-relative path to its [DiffRequest] under [FILE_PATH_KEY], so
 *    this extension knows which file's comments to show/collect in a given viewer.
 *
 * Scope note: [isLineCommentable] currently allows commenting on any line present in the
 * document, rather than being restricted to changed-line ranges. Computing "changed-line
 * ranges" generically across every supported viewer type
 * ([com.intellij.diff.tools.simple.SimpleDiffViewer],
 * [com.intellij.diff.tools.simple.SimpleOnesideDiffViewer],
 * [com.intellij.diff.tools.fragmented.UnifiedDiffViewer]) would require reaching into each
 * viewer's internal diff-change representation (there is no shared, viewer-type-agnostic API
 * for it) -- deferred as a follow-up; see the plan's Testing Strategy note allowing a narrower
 * scope here if a full integration proves impractical.
 */
class ReviewDiffExtension : DiffExtension() {
  override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
    if (viewer !is DiffViewerBase) return
    val store = context.getUserData(InMemoryReviewCommentStore.KEY) ?: return
    val filePath = request.getUserData(FILE_PATH_KEY) ?: return

    // GHPRReviewDiffExtension/GitLabMergeRequestDiffExtension launch from a project-level
    // @Service's CoroutineScope; this extension has no such service (Task 2 introduces no new
    // platform services), so it owns a standalone scope instead, cancelled when the viewer is
    // disposed via cancelOnDispose below.
    val cs = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    cs.launch {
      viewer.showCodeReview { editor, _, locationToLine, lineToLocation, _ ->
        coroutineScope {
          val model = InMemoryGutterControlsModel(this, store, filePath, locationToLine, lineToLocation)
          CodeReviewEditorGutterControlsRenderer.render(model, editor)
        }
      }
    }.cancelOnDispose(viewer)
  }

  companion object {
    /**
     * The repo-relative path of the file being diffed in a given viewer, attached to the
     * [DiffRequest] by `ReviewApplication` so [ReviewDiffExtension] can filter
     * [InMemoryReviewCommentStore] to the comments relevant to this file.
     */
    val FILE_PATH_KEY: Key<String> = Key.create("com.intellij.vcs.git.review.comments.FilePath")
  }
}

/**
 * [CodeReviewEditorGutterControlsModel] backed by [InMemoryReviewCommentStore], filtered to
 * [filePath]. Constructing/adding a comment here only updates the in-memory store -- there is
 * no comment bubble/text-field UI wired up in this extension (see the class doc on
 * [ReviewDiffExtension]); [requestNewComment] exists so the gutter renderer has something to
 * call, and is expected to be replaced with real UI (e.g. reusing
 * `CodeReviewCommentTextFieldFactory`) in a later task.
 */
internal class InMemoryGutterControlsModel(
  cs: CoroutineScope,
  private val store: InMemoryReviewCommentStore,
  private val filePath: String,
  private val locationToLine: (DiffLineLocation) -> Int?,
  private val lineToLocation: (Int) -> DiffLineLocation?,
) : CodeReviewEditorGutterControlsModel {

  override val gutterControlsState: StateFlow<CodeReviewEditorGutterControlsModel.ControlsState?> =
    store.comments.map { comments ->
      val linesWithComments = comments
        .filter { it.filePath == filePath }
        .mapNotNullTo(mutableSetOf()) { locationToLine(DiffLineLocation(it.side, it.line)) }
      InMemoryControlsState(linesWithComments, lineToLocation)
    }.stateIn(cs, SharingStarted.Eagerly, null)

  override fun requestNewComment(lineIdx: Int) {
    val (side, line) = lineToLocation(lineIdx) ?: return
    store.addComment(ReviewComment(filePath, line, side, text = ""))
  }

  override fun cancelNewComment(lineIdx: Int) {
    val (side, line) = lineToLocation(lineIdx) ?: return
    store.removeCommentsAt(filePath, line, side)
  }

  override fun toggleComments(lineIdx: Int) {
    // No collapsible comment threads in this extension (no inlay UI) -- nothing to toggle.
  }

  private data class InMemoryControlsState(
    override val linesWithComments: Set<Int>,
    val lineToLocation: (Int) -> DiffLineLocation?,
  ) : CodeReviewEditorGutterControlsModel.ControlsState {
    override fun isLineCommentable(lineIdx: Int): Boolean = lineToLocation(lineIdx) != null
  }
}
