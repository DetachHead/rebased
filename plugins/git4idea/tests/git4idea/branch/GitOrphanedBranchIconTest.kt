// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.branch

import com.intellij.icons.AllIcons
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.vcs.git.ref.GitCurrentRef
import com.intellij.vcs.git.repo.GitHash
import com.intellij.vcs.git.repo.GitOperationState
import com.intellij.vcs.git.repo.GitRepositoryState
import com.intellij.vcs.git.repo.isUpstreamGone
import com.intellij.vcs.git.ui.GitBranchesTreeIconProvider
import git4idea.GitStandardLocalBranch
import git4idea.GitStandardRemoteBranch
import git4idea.GitTag
import git4idea.GitWorkingTree
import icons.DvcsImplIcons

class GitOrphanedBranchIconTest : LightPlatformTestCase() {

  fun `test orphaned local branch uses the warning icon`() {
    val branch = GitStandardLocalBranch("feature")
    assertSame(AllIcons.General.Warning,
               GitBranchesTreeIconProvider.forRef(branch, current = false, favorite = false, selected = false, upstreamGone = true))
  }

  fun `test current and favorite take priority over the warning`() {
    val branch = GitStandardLocalBranch("feature")
    assertSame(DvcsImplIcons.CurrentBranchLabel,
               GitBranchesTreeIconProvider.forRef(branch, current = true, favorite = false, selected = false, upstreamGone = true))
    assertSame(AllIcons.Nodes.Favorite,
               GitBranchesTreeIconProvider.forRef(branch, current = false, favorite = true, selected = false, upstreamGone = true))
  }

  fun `test tag is never shown as orphaned`() {
    val tag = GitTag("v1.0")
    assertSame(DvcsImplIcons.BranchLabel,
               GitBranchesTreeIconProvider.forRef(tag, current = false, favorite = false, selected = false, upstreamGone = true))
  }

  fun `test healthy local branch keeps the regular branch icon`() {
    val branch = GitStandardLocalBranch("feature")
    assertSame(AllIcons.Vcs.BranchNode,
               GitBranchesTreeIconProvider.forRef(branch, current = false, favorite = false, selected = false, upstreamGone = false))
  }

  fun `test isUpstreamGone checks membership in the gone-upstream set`() {
    val branch = GitStandardLocalBranch("feature")
    val other = GitStandardLocalBranch("other")

    assertTrue(state(upstreamGone = setOf(branch)).isUpstreamGone(branch))
    assertFalse(state(upstreamGone = setOf(other)).isUpstreamGone(branch))
    assertFalse(state(upstreamGone = emptySet()).isUpstreamGone(branch))
  }

  private fun state(upstreamGone: Set<GitStandardLocalBranch>): GitRepositoryState =
    object : GitRepositoryState {
      override val currentRef: GitCurrentRef? = null
      override val revision: GitHash? = null
      override val localBranches: Set<GitStandardLocalBranch> = emptySet()
      override val remoteBranches: Set<GitStandardRemoteBranch> = emptySet()
      override val tags: Set<GitTag> = emptySet()
      override val workingTrees: Collection<GitWorkingTree> = emptyList()
      override val recentBranches: List<GitStandardLocalBranch> = emptyList()
      override val operationState: GitOperationState = GitOperationState.NORMAL
      override val upstreamGoneBranches: Set<GitStandardLocalBranch> = upstreamGone
      override fun getDisplayableBranchText(): String = ""
      override fun getTrackingInfo(branch: GitStandardLocalBranch): GitStandardRemoteBranch? = null
    }
}
