// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManagerEx
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffPlaces
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.WindowWrapper
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.platform.ide.bootstrap.hideSplashBeforeShow
import com.intellij.ui.AppIcon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File

/**
 * Opens one `review` session for [ref] (`null` means uncommitted changes) against [repoRoot],
 * driving the `review` CLI command ([ReviewApplication]): builds the multi-file diff chain,
 * attaches an [InMemoryReviewCommentStore] for [ReviewDiffExtension]'s gutter+inline comment
 * UI, and exports to `.git/review-comments.json` on Finish Review.
 *
 * Always opens in [WindowWrapper.Mode.FRAME] -- see the note on [ReviewDiffExtension] for why
 * [WindowWrapper.Mode.MODAL] silently breaks the comment UI.
 *
 * Suspends until the diff window is closed.
 */
internal suspend fun openReviewSession(project: Project?, repoRoot: File, ref: String?) {
  // Clear any pre-existing export from an earlier/interrupted session *before* opening the
  // diff viewer -- see the equivalent note this replaced in `ReviewApplication.executeCommand`.
  ReviewCommentsJsonExporter.clear(repoRoot)
  val changedFiles = ChangesetResolver(repoRoot).resolveChangedFiles(ref)

  withContext(Dispatchers.EDT) {
    val store = InMemoryReviewCommentStore()
    val chain: DiffRequestChain =
      if (changedFiles.isEmpty()) {
        SimpleDiffRequestChain.fromProducer(NothingToReviewProducer)
      }
      else {
        SimpleDiffRequestChain.fromProducers(changedFiles.map { ChangedFileDiffRequestProducer(project, it) })
      }
    chain.putUserData(InMemoryReviewCommentStore.KEY, store)
    chain.putUserData(REPO_ROOT_KEY, repoRoot)
    chain.putUserData(DiffUserDataKeys.PLACE, DiffPlaces.EXTERNAL)

    // FrameWrapper.getFrame() does `WindowManager.getInstance().getIdeFrame(project)!!` --
    // FRAME mode crashes with an NPE unless an IDE frame for `project` already exists (i.e.
    // some project is already open). MODAL mode creates a standalone dialog with no such
    // requirement, but was confirmed (via manual testing) not to reliably run the diff
    // viewer's rediff-completion signal ReviewDiffExtension's comment UI depends on. FRAME
    // is safe and correct whenever a project is open (validated manually); MODAL is the only
    // option with none, at the cost of the comment UI not showing up in that specific case.
    val mode = if (project != null) WindowWrapper.Mode.FRAME else WindowWrapper.Mode.MODAL
    val task = CompletableDeferred<Unit>()
    val dialogHints = DiffDialogHints(mode, null) { wrapper ->
      val window = wrapper.window
      hideSplashBeforeShow(window)
      AppIcon.getInstance().requestFocus(window)
      window.addWindowListener(object : WindowAdapter() {
        override fun windowClosed(e: WindowEvent) {
          e.window.removeWindowListener(this)
          task.complete(Unit)
        }
      })
    }
    DiffManagerEx.getInstance().showDiffBuiltin(project, chain, dialogHints)
    task.await()
  }
}

/**
 * The repo root a `review` session was opened against, attached to the session's
 * [DiffRequestChain]/[com.intellij.diff.DiffContext] user data the same way
 * [InMemoryReviewCommentStore.KEY] is, so [FinishReviewAction] can resolve the same
 * `<repo-root>/.git/review-comments.json` path that [ChangesetResolver] resolved paths
 * relative to -- without re-deriving the repo root from scratch (e.g. from the current
 * working directory, which may differ by the time "Finish Review" is invoked).
 */
internal val REPO_ROOT_KEY: Key<File> = Key.create("com.intellij.vcs.git.review.comments.RepoRoot")

/** [DiffRequestProducer] shown when the resolved changeset has no changed files. */
private object NothingToReviewProducer : DiffRequestProducer {
  override fun getName(): String = GitReviewCommentsBundle.message("review.application.nothing.to.review")
  override fun getContentType(): FileType? = null
  override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
    val contentFactory = DiffContentFactory.getInstance()
    return SimpleDiffRequest(
      GitReviewCommentsBundle.message("review.application.nothing.to.review"),
      contentFactory.createEmpty(),
      contentFactory.createEmpty(),
      null,
      null,
    )
  }
}

/**
 * Builds one [DiffRequest] for a single [ChangedFile], analogous to `DiffApplication.kt`'s
 * private `MyDiffRequestProducer`. Attaches [ReviewDiffExtension.FILE_PATH_KEY] to the
 * produced request so [ReviewDiffExtension] knows which file's comments in the shared
 * [InMemoryReviewCommentStore] belong to this viewer.
 */
private class ChangedFileDiffRequestProducer(
  private val project: Project?,
  private val changedFile: ChangedFile,
) : DiffRequestProducer {
  override fun getName(): String = changedFile.repoRelativePath

  override fun getContentType(): FileType =
    FileTypeManager.getInstance().getFileTypeByFileName(File(changedFile.repoRelativePath).name)

  override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
    val contentFactory = DiffContentFactory.getInstance()
    val fileType = getContentType()
    val oldContent = changedFile.oldContent?.let { contentFactory.create(project, it, fileType) } ?: contentFactory.createEmpty()
    val newContent = changedFile.newContent?.let { contentFactory.create(project, it, fileType) } ?: contentFactory.createEmpty()
    val request = SimpleDiffRequest(changedFile.repoRelativePath, oldContent, newContent, null, null)
    request.putUserData(ReviewDiffExtension.FILE_PATH_KEY, changedFile.repoRelativePath)
    return request
  }
}
