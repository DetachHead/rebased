// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.repo

import com.intellij.openapi.util.NlsSafe
import com.intellij.vcs.git.ref.GitCurrentRef
import git4idea.GitReference
import git4idea.GitStandardLocalBranch
import git4idea.GitStandardRemoteBranch
import git4idea.GitTag
import git4idea.GitWorkingTree
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls

@ApiStatus.Internal
interface GitRepositoryState {
  val currentRef: GitCurrentRef?
  val revision: @NlsSafe GitHash?
  val localBranches: Set<GitStandardLocalBranch>
  val remoteBranches: Set<GitStandardRemoteBranch>
  val tags: Set<GitTag>
  val workingTrees: Collection<GitWorkingTree>
  val recentBranches: List<GitStandardLocalBranch>
  val operationState: GitOperationState

  /** Local branches whose configured upstream no longer exists (git's `gone` state). See [isUpstreamGone]. */
  val upstreamGoneBranches: Set<GitStandardLocalBranch>
    get() = emptySet()

  val currentBranch: GitStandardLocalBranch? get() = (currentRef as? GitCurrentRef.LocalBranch)?.branch

  /**
   * For a fresh repository a list of local branches is empty.
   * However, it still makes sense to show the current branch in the UI.
   */
  val localBranchesOrCurrent: Set<GitStandardLocalBranch>
    get() = localBranches.ifEmpty { setOfNotNull(currentBranch) }

  fun isCurrentRef(ref: GitReference): Boolean = currentRef?.matches(ref) ?: false

  fun getDisplayableBranchText(): @Nls String

  fun getTrackingInfo(branch: GitStandardLocalBranch): GitStandardRemoteBranch?
}

/**
 * A local branch is considered orphaned ("upstream gone") when it has a configured upstream
 * (tracking info), but that upstream branch is no longer present among the known remote branches.
 * This matches git's `gone` status (e.g. `git branch -vv` showing `[origin/x: gone]`), which becomes
 * true after a fetch with `--prune` removes the stale remote-tracking ref.
 */
@ApiStatus.Internal
fun GitRepositoryState.isUpstreamGone(branch: GitStandardLocalBranch): Boolean = branch in upstreamGoneBranches
