// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.mergerequest.ui.details.model

import com.intellij.collaboration.async.withInitial
import com.intellij.collaboration.ui.Either
import com.intellij.dvcs.repo.Repository
import com.intellij.openapi.project.Project
import git4idea.remote.GitRemoteUrlCoordinates
import git4idea.remote.hosting.GitRemoteBranchesUtil
import git4idea.remote.hosting.findFirstRemoteBranchTrackedByCurrent
import git4idea.remote.hosting.infoFlow
import git4idea.remote.hosting.isInCurrentHistory
import git4idea.remote.hosting.ui.ResolveConflictsLocallyCoordinates
import git4idea.remote.hosting.ui.ResolveConflictsLocallyViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import org.jetbrains.plugins.gitlab.mergerequest.data.GitLabMergeRequest
import org.jetbrains.plugins.gitlab.mergerequest.data.GitLabMergeStatus
import org.jetbrains.plugins.gitlab.mergerequest.data.getRemoteDescriptor
import org.jetbrains.plugins.gitlab.mergerequest.ui.details.model.GitLabResolveConflictsLocallyError.AlreadyResolvedLocally
import org.jetbrains.plugins.gitlab.mergerequest.ui.details.model.GitLabResolveConflictsLocallyError.DetailsNotLoaded
import org.jetbrains.plugins.gitlab.mergerequest.ui.details.model.GitLabResolveConflictsLocallyError.MergeInProgress
import org.jetbrains.plugins.gitlab.mergerequest.ui.details.model.GitLabResolveConflictsLocallyError.SourceRepositoryNotFound

typealias GitLabResolveConflictsLocallyViewModel = ResolveConflictsLocallyViewModel<GitLabResolveConflictsLocallyError>

private val REPO_MERGING_STATES = setOf(Repository.State.REBASING, Repository.State.MERGING)

fun GitLabResolveConflictsLocallyViewModel(
  parentCs: CoroutineScope,
  project: Project,
  gitRemote: GitRemoteUrlCoordinates,
  mergeRequest: GitLabMergeRequest,
): GitLabResolveConflictsLocallyViewModel = ResolveConflictsLocallyViewModel.createIn(
  parentCs, project, gitRemote.repository,
  hasConflicts =
    mergeRequest.details.map {
      when (it.mergeStatus) {
        GitLabMergeStatus.CHECKING, GitLabMergeStatus.CANNOT_BE_MERGED_RECHECK, GitLabMergeStatus.UNCHECKED -> null
        GitLabMergeStatus.CAN_BE_MERGED -> false
        GitLabMergeStatus.CANNOT_BE_MERGED -> true
      }
    },
  requestOrError =
    combine(
      gitRemote.repository.isInCurrentHistory(
        rev = mergeRequest.details.map { it.diffRefs?.baseSha }.filterNotNull()
      ).map { it ?: false }.withInitial(false),
      mergeRequest.details, gitRemote.repository.infoFlow()
    ) { isBaseInHistory, details, repoInfo ->
      if (repoInfo.state in REPO_MERGING_STATES) return@combine Either.left(MergeInProgress)

      val sourceProject = details.sourceProject ?: return@combine Either.left(SourceRepositoryNotFound)

      val sourceRemoteDescriptor = sourceProject.getRemoteDescriptor(gitRemote)
      val targetRemoteDescriptor = details.targetProject.getRemoteDescriptor(gitRemote)

      val currentRemoteBranch = repoInfo.findFirstRemoteBranchTrackedByCurrent()
      if (currentRemoteBranch != null && isBaseInHistory &&
          currentRemoteBranch.nameForRemoteOperations == details.sourceBranch &&
          currentRemoteBranch.remote == GitRemoteBranchesUtil.findRemote(gitRemote.repository, sourceRemoteDescriptor))
        return@combine Either.left(AlreadyResolvedLocally)

      Either.right(
        ResolveConflictsLocallyCoordinates(sourceRemoteDescriptor, details.sourceBranch, targetRemoteDescriptor, details.targetBranch)
      )
    },
  initialRequestOrErrorState = Either.left(DetailsNotLoaded)
)

sealed interface GitLabResolveConflictsLocallyError {
  data object AlreadyResolvedLocally : GitLabResolveConflictsLocallyError
  data object MergeInProgress : GitLabResolveConflictsLocallyError
  data object SourceRepositoryNotFound : GitLabResolveConflictsLocallyError
  data object DetailsNotLoaded : GitLabResolveConflictsLocallyError
}
