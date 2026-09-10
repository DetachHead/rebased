// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.collaboration.ui.codereview.diff.DiffLineLocation
import com.intellij.collaboration.ui.codereview.diff.viewer.showCodeReview
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorGutterControlsModel
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorInlaysModel
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorModel
import com.intellij.collaboration.ui.codereview.editor.CodeReviewInlayModel
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.ComponentInlayRenderer
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.ui.components.JBLabel
import com.intellij.util.cancelOnDispose
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Attaches a per-line gutter "add comment" affordance, and a visible inline comment display
 * directly under the commented line (like a GitHub/GitLab PR review), to every diff viewer
 * opened during a `review` session, backed by an [InMemoryReviewCommentStore].
 *
 * Mirrors [org.jetbrains.plugins.github.pullrequest.ui.diff.GHPRReviewDiffExtension] and
 * [org.jetbrains.plugins.gitlab.mergerequest.diff.GitLabMergeRequestDiffExtension]: reads a
 * review view model off the [DiffContext]'s user data, then calls
 * [com.intellij.collaboration.ui.codereview.diff.viewer.showCodeReview] to wire it into the
 * viewer's editor(s) -- both the gutter "add comment" control and the inline comment text
 * inlay are handled by that single call once [InMemoryReviewEditorModel] implements both
 * [CodeReviewEditorGutterControlsModel] and [CodeReviewEditorInlaysModel].
 *
 * `ReviewApplication`, which drives the `review` CLI command (and `ReviewChangesAction`, which
 * drives the in-app "Review Changes" action), is responsible for:
 *  - creating one [InMemoryReviewCommentStore] per review session and attaching it to the
 *    session's [DiffContext] under [InMemoryReviewCommentStore.KEY];
 *  - attaching each file's repo-relative path to its [DiffRequest] under [FILE_PATH_KEY], so
 *    this extension knows which file's comments to show/collect in a given viewer;
 *  - always opening the diff in [com.intellij.openapi.ui.WindowWrapper.Mode.FRAME] -- the
 *    diff viewer's rediff-completion signal this extension's [showCodeReview] call depends on
 *    was confirmed (via manual testing) to never fire in
 *    [com.intellij.openapi.ui.WindowWrapper.Mode.MODAL] with no project open, leaving the
 *    gutter/inlays permanently unrendered with no error.
 *
 * Scope note: [isLineCommentable] currently allows commenting on any line present in the
 * document, rather than being restricted to changed-line ranges. Computing "changed-line
 * ranges" generically across every supported viewer type
 * ([com.intellij.diff.tools.simple.SimpleDiffViewer],
 * [com.intellij.diff.tools.simple.SimpleOnesideDiffViewer],
 * [com.intellij.diff.tools.fragmented.UnifiedDiffViewer]) would require reaching into each
 * viewer's internal diff-change representation (there is no shared, viewer-type-agnostic API
 * for it) -- deferred as a follow-up.
 */
class ReviewDiffExtension : DiffExtension() {
  override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
    if (viewer !is DiffViewerBase) return
    val store = context.getUserData(InMemoryReviewCommentStore.KEY) ?: return
    val filePath = request.getUserData(FILE_PATH_KEY) ?: return

    // GHPRReviewDiffExtension/GitLabMergeRequestDiffExtension launch from a project-level
    // @Service's CoroutineScope; this extension has no such service, so it owns a standalone
    // scope instead. The scope's root job (not just the one child job launched below) is
    // cancelled when the viewer is disposed, so no part of the scope survives it.
    val cs = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    cs.coroutineContext.job.cancelOnDispose(viewer)
    cs.launch {
      try {
        viewer.showCodeReview(
          modelFactory = { locationToLine, lineToLocation ->
            InMemoryReviewEditorModel(this, store, filePath, locationToLine, lineToLocation) {
              Messages.showInputDialog(
                GitReviewCommentsBundle.message("review.comment.dialog.message"),
                GitReviewCommentsBundle.message("review.comment.dialog.title"),
                null,
                "",
                null,
              )
            }
          },
          rendererFactory = { inlay ->
            ComponentInlayRenderer(
              JBLabel(inlay.text).apply {
                foreground = JBUI.CurrentTheme.Label.foreground()
                border = JBUI.Borders.empty(2, 8)
              },
            )
          },
        )
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Throwable) {
        LOG.warn("Failed to attach review comment UI for $filePath", e)
      }
    }
  }

  companion object {
    private val LOG = logger<ReviewDiffExtension>()

    /**
     * The repo-relative path of the file being diffed in a given viewer, attached to the
     * [DiffRequest] by `ReviewApplication`/`ReviewChangesAction` so [ReviewDiffExtension]
     * knows which file's comments to show/collect in a given viewer.
     */
    val FILE_PATH_KEY: Key<String> = Key.create("com.intellij.vcs.git.review.comments.FilePath")
  }
}

/**
 * [CodeReviewEditorModel] backed by [InMemoryReviewCommentStore], filtered to [filePath]:
 * combines the gutter "add comment" control ([CodeReviewEditorGutterControlsModel]) with a
 * visible inline text inlay per comment ([CodeReviewEditorInlaysModel]), so an added comment
 * is immediately shown in the diff, not just marked with a gutter icon.
 *
 * [requestNewComment] captures the comment's actual text via [requestCommentText] -- a plain
 * callback (rather than importing [com.intellij.openapi.ui.Messages] directly here) so this
 * class stays free of any platform-UI dependency and testable with a plain fake, the same way
 * [locationToLine]/[lineToLocation] are. [ReviewDiffExtension] wires the real callback to a
 * modal input dialog.
 *
 * @param requestCommentText invoked synchronously when the user requests a new comment;
 *   returns the entered text, or `null`/blank if the user cancelled -- in which case no
 *   comment is added. Defaults to always returning an empty string, which is only correct for
 *   tests that don't care about the actual text captured (production wiring always supplies a
 *   real, UI-backed callback).
 */
internal class InMemoryReviewEditorModel(
  cs: CoroutineScope,
  private val store: InMemoryReviewCommentStore,
  private val filePath: String,
  private val locationToLine: (DiffLineLocation) -> Int?,
  private val lineToLocation: (Int) -> DiffLineLocation?,
  private val requestCommentText: () -> String? = { "" },
) : CodeReviewEditorModel<InMemoryCommentInlay> {

  override val gutterControlsState: StateFlow<CodeReviewEditorGutterControlsModel.ControlsState?> =
    store.comments.map { comments ->
      val linesWithComments = comments
        .filter { it.filePath == filePath }
        .mapNotNullTo(mutableSetOf()) { locationToLine(DiffLineLocation(it.side, it.line)) }
      InMemoryControlsState(linesWithComments, lineToLocation)
    }.stateIn(cs, SharingStarted.Eagerly, null)

  override val inlays: StateFlow<Collection<InMemoryCommentInlay>> =
    store.comments.map { comments ->
      comments
        .filter { it.filePath == filePath && it.text.isNotEmpty() }
        .mapNotNull { comment ->
          val lineIdx = locationToLine(DiffLineLocation(comment.side, comment.line)) ?: return@mapNotNull null
          InMemoryCommentInlay(comment, lineIdx)
        }
    }.stateIn(cs, SharingStarted.Eagerly, emptyList())

  override fun requestNewComment(lineIdx: Int) {
    val (side, line) = lineToLocation(lineIdx) ?: return
    val text = requestCommentText() ?: return
    if (text.isBlank()) return
    store.addComment(ReviewComment(filePath, line, side, text = text))
  }

  override fun cancelNewComment(lineIdx: Int) {
    val (side, line) = lineToLocation(lineIdx) ?: return
    // Only remove an empty-text placeholder, never a real, already-written comment that
    // happens to share this file/line/side -- see the doc on
    // [InMemoryReviewCommentStore.removeCommentsAt].
    store.removeCommentsAt(filePath, line, side, onlyIfTextEmpty = true)
  }

  override fun toggleComments(lineIdx: Int) {
    // No collapsible comment threads in this extension -- comments are always shown once
    // added, so there is nothing to toggle.
  }

  private data class InMemoryControlsState(
    override val linesWithComments: Set<Int>,
    val lineToLocation: (Int) -> DiffLineLocation?,
  ) : CodeReviewEditorGutterControlsModel.ControlsState {
    override fun isLineCommentable(lineIdx: Int): Boolean = lineToLocation(lineIdx) != null
  }
}

/**
 * A single [comment]'s visible inline text inlay, anchored to [lineIdx] (the document line
 * index the comment's [ReviewComment.side]/[ReviewComment.line] maps to in this editor).
 * The comment's file/line/side/text are immutable once added (this extension has no comment
 * editing), so [line]/[isVisible] are fixed at construction rather than reactive.
 */
internal class InMemoryCommentInlay(comment: ReviewComment, lineIdx: Int) : CodeReviewInlayModel {
  override val key: Any = comment
  val text: String = comment.text
  override val line: StateFlow<Int?> = MutableStateFlow(lineIdx)
  override val isVisible: StateFlow<Boolean> = MutableStateFlow(true)
}
