// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.branch

import com.intellij.dvcs.repo.repositoryId
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.vcs.Executor.cd
import com.intellij.ui.tree.TreeTestUtil
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.vcs.git.branch.popup.GitBranchesPopupStepBase
import com.intellij.vcs.git.branch.popup.GitDefaultBranchesPopupStep
import com.intellij.vcs.git.branch.popup.GitDefaultBranchesTreeRenderer
import com.intellij.vcs.git.branch.tree.GitBranchesTreeRenderer
import com.intellij.vcs.git.repo.GitRepositoriesHolder
import git4idea.repo.GitRepository
import git4idea.test.GitPlatformTest
import git4idea.test.branch
import git4idea.test.git
import kotlinx.coroutines.runBlocking
import javax.swing.Icon

/**
 * End-to-end check that an orphaned local branch (configured upstream deleted on the remote, i.e. git's `gone` state)
 * is rendered with the warning icon by the real branches-popup tree, exercising the whole pipeline:
 * git config -> [git4idea.repo.GitConfig.parseUpstreamGoneBranches] -> repo info -> DTO -> shared state -> renderer.
 */
class GitOrphanedBranchWidgetTest : GitPlatformTest() {
  private lateinit var repo: GitRepository
  private lateinit var popupStep: GitBranchesPopupStepBase

  override fun setUp() {
    super.setUp()
    repo = setupRepositories(projectPath, "parent", "bro-repo").projectRepo
    cd(projectPath)
    refresh()
    repositoryManager.updateAllRepositories()
    runBlocking { GitRepositoriesHolder.getInstance(project).awaitInitialization() }
  }

  fun testOrphanedBranchUsesWarningIcon() {
    // A healthy branch that still tracks an existing remote branch.
    repo.branch("healthy")
    repo.git("push -u origin healthy")

    // An orphaned branch: push & set upstream, then delete it on the remote and prune locally.
    // The branch.<name>.merge/remote config survives, but refs/remotes/origin/gone-feature is removed.
    repo.branch("gone-feature")
    repo.git("push -u origin gone-feature")
    repo.git("push origin --delete gone-feature")
    repo.git("fetch --prune origin")

    repo.update()
    repositoryManager.updateAllRepositories()
    runBlocking { GitRepositoriesHolder.getInstance(project).awaitInitialization() }

    val icons = iconsByText(buildTestTree())

    assertEquals("Orphaned branch must use the warning icon", AllIcons.General.Warning, icons["gone-feature"])
    assertEquals("Healthy tracking branch must keep the regular branch icon", AllIcons.Vcs.BranchNode, icons["healthy"])
  }

  private fun iconsByText(tree: Tree): Map<String, Icon?> = invokeAndWaitIfNeeded {
    val renderer = tree.cellRenderer as GitBranchesTreeRenderer
    val result = LinkedHashMap<String, Icon?>()
    for (row in 0 until tree.rowCount) {
      val userObject = TreeUtil.getUserObject(tree.getPathForRow(row).lastPathComponent)
      val text = popupStep.getNodeText(userObject) ?: continue
      result[text] = renderer.getIcon(userObject, false)
    }
    result
  }

  private fun buildTestTree(filter: String? = null): Tree {
    repositoryManager.updateAllRepositories()
    return invokeAndWaitIfNeeded {
      val holder = GitRepositoriesHolder.getInstance(project)
      val repositories = holder.getAll()
      val tree = Tree()
      val preferredSelection = checkNotNull(holder.get(repo.repositoryId()))
      popupStep = GitDefaultBranchesPopupStep.create(project, preferredSelection, repositories)
      tree.cellRenderer = GitDefaultBranchesTreeRenderer(popupStep)
      tree.model = popupStep.treeModel
      popupStep.updateTreeModelIfNeeded(tree, filter)
      popupStep.setSearchPattern(filter)
      tree.also { TreeTestUtil(it).expandAll() }
    }
  }
}
