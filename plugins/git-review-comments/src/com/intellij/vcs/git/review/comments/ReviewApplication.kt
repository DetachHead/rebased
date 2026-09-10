// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.review.comments

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManagerEx
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffPlaces
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.ide.CliResult
import com.intellij.openapi.application.ApplicationStarterBase
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
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
 * `review` CLI command: opens a **multi-file** diff review session for a git changeset,
 * unlike `diff`/[com.intellij.diff.applications.DiffApplication], which only ever compares
 * exactly two file paths.
 *
 * Accepts zero or one argument: no argument reviews uncommitted changes, one argument is a
 * ref/range handed straight to [ChangesetResolver] (see its class doc for the accepted
 * shapes). [ChangesetResolver] resolves the changed-file list and content; this class is
 * only responsible for turning that into a [DiffRequestChain] and showing it, mirroring
 * `DiffApplication`'s own `showDiffBuiltin` usage as closely as possible.
 *
 * Multi-file UX decision (per the plan's own "verify during implementation" note):
 * [SimpleDiffRequestChain] (via [SimpleDiffRequestChain.fromProducers]) natively supports
 * more than one [DiffRequestProducer] and is already used this way for commit/changelist
 * diffs elsewhere in the platform (see
 * `com.intellij.diff.impl.CacheDiffRequestChainProcessor`, which every `showDiffBuiltin`
 * window is backed by) -- it provides "next/prev file" browsing for free. So this class
 * uses one chain covering every changed file, exactly as originally planned; no fallback to
 * looping `showDiffBuiltin` calls per file was needed.
 */
internal class ReviewApplication : ApplicationStarterBase(/* possibleArgumentsCount = */ 0, 1) {
  override val commandName: String get() = "review"
  override val usageMessage: String
    get() = GitReviewCommentsBundle.message("review.application.usage")

  override suspend fun executeCommand(args: List<String>, currentDirectory: String?): CliResult {
    val ref = parseRef(args)
    val repoRoot = File(currentDirectory ?: System.getProperty("user.dir"))
    val changedFiles = ChangesetResolver(repoRoot).resolveChangedFiles(ref)
    val project = ProjectManager.getInstance().openProjects.firstOrNull()

    return withContext(Dispatchers.EDT) {
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
      CliResult.OK
    }
  }

  companion object {
    /**
     * `args[0]` is always the command name itself (`"review"`), matching
     * `DiffApplication.executeCommand`'s own `args.drop(1)` convention -- so `args.size == 1`
     * means "no ref given" (uncommitted changes) and `args.size == 2` means one ref/range
     * argument. Internal (not private) so [ReviewApplicationTest] can exercise this without
     * touching the diff-opening side effect.
     */
    internal fun parseRef(args: List<String>): String? = args.drop(1).firstOrNull()

    /**
     * The repo root a `review` session was opened against, attached to the session's
     * [DiffRequestChain]/[com.intellij.diff.DiffContext] user data the same way
     * [InMemoryReviewCommentStore.KEY] is, so [FinishReviewAction] can resolve the same
     * `<repo-root>/.git/review-comments.json` path that [ChangesetResolver] resolved paths
     * relative to -- without re-deriving the repo root from scratch (e.g. from the current
     * working directory, which may differ by the time "Finish Review" is invoked).
     */
    internal val REPO_ROOT_KEY: Key<File> = Key.create("com.intellij.vcs.git.review.comments.RepoRoot")
  }
}

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
