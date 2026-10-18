// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.log

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.vcs.log.impl.MainVcsLogUiProperties.SHOW_REPOSITORY_PATH
import com.intellij.vcs.log.impl.VcsLogManager
import com.intellij.vcs.log.impl.VcsLogProjectTabsProperties
import com.intellij.vcs.log.impl.VcsLogTabLocation
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.vcs.log.ui.MainVcsLogUi
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
@State(name = "GitSubmoduleLogTabs", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class GitRepositoryLogTabs(private val project: Project, coroutineScope: CoroutineScope) :
  PersistentStateComponent<GitRepositoryLogTabsState> {
  private var state = GitRepositoryLogTabsState()
  private val requests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  private val observedUis = mutableSetOf<MainVcsLogUi>()

  val selectedBranch: String? get() = state.selectedBranch
  val showRepositoryPaths: Boolean get() = state.showRepositoryPaths
  val isAvailable: Boolean
    get() {
      val directory = project.guessProjectDir() ?: return false
      return GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(directory)?.submodules?.isNotEmpty() == true
    }

  init {
    val connection = project.messageBus.connect(coroutineScope)
    connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { requestUpdate() })
    connection.subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, VcsRepositoryMappingListener { requestUpdate() })
    connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
      override fun fileOpened(source: FileEditorManager, file: VirtualFile) = requestUpdate()
    })
    connection.subscribe(VcsProjectLog.VCS_PROJECT_LOG_CHANGED, object : VcsProjectLog.ProjectLogListener {
      override fun logCreated(manager: VcsLogManager) = requestUpdate()
      override fun logDisposed(manager: VcsLogManager) {}
    })
    coroutineScope.launch(Dispatchers.EDT) {
      requests.collectLatest {
        val branch = selectedBranch ?: return@collectLatest
        val roots = withContext(Dispatchers.Default) {
          project.serviceAsync<VcsRepositoryManager>().ensureUpToDate()
          readAction {
            val repositories = GitRepositoryManager.getInstance(project).repositories
            val matches = if (isAvailable) repositories.filter { it.hasBranch(branch) } else emptyList()
            matches.associate { it.root.url to it.root }
          }
        }
        val log = project.serviceAsync<VcsProjectLog>()
        if (roots.isNotEmpty() || state.tabIdsByRoot.isNotEmpty()) {
          log.init(true)
        }
        if (branch == selectedBranch) updateTabs(log, roots)
      }
    }
    requestUpdate()
  }

  override fun getState(): GitRepositoryLogTabsState = state

  override fun loadState(state: GitRepositoryLogTabsState) {
    this.state = state
    requestUpdate()
  }

  @RequiresEdt
  fun setFilter(branch: String?, showRepositoryPaths: Boolean) {
    val selectedBranch = branch?.trim()?.takeIf { it.isNotEmpty() }
    if (!selectedBranch.equals(state.selectedBranch, ignoreCase = true)) state.dismissedRoots.clear()
    state.selectedBranch = selectedBranch
    state.showRepositoryPaths = showRepositoryPaths
    if (selectedBranch == null) {
      state.dismissedRoots.clear()
      state.tabIdsByRoot.clear()
      project.service<VcsProjectLog>().resetLogTabs()
    }
    requestUpdate()
  }

  private fun requestUpdate() {
    requests.tryEmit(Unit)
  }

  @RequiresEdt
  private fun updateTabs(projectLog: VcsProjectLog, roots: Map<String, VirtualFile>) {
    val manager = projectLog.logManager ?: return
    val uiProperties = project.service<VcsLogProjectTabsProperties>()
    if (roots.isNotEmpty()) {
      projectLog.updateRepositoryLogTabs(state.tabIdsByRoot.values.toSet())
    }
    state.tabIdsByRoot.values.retainAll(uiProperties.tabs.keys)
    for (root in state.tabIdsByRoot.keys - roots.keys) {
      val id = state.tabIdsByRoot.remove(root) ?: continue
      projectLog.closeLogTab(id)
      if (id in uiProperties.tabs) state.tabIdsByRoot[root] = id
    }
    val location = if (uiProperties.shouldShowInEditor()) VcsLogTabLocation.EDITOR else VcsLogTabLocation.TOOL_WINDOW
    for ((root, file) in roots) {
      if (root in state.dismissedRoots || root in state.tabIdsByRoot) continue
      projectLog.openRepositoryLogTab(file, location)?.let { state.tabIdsByRoot[root] = it.id }
    }
    val matchingTabs = state.tabIdsByRoot.filterKeys { it in roots }.values.toSet()
    projectLog.updateRepositoryLogTabs(matchingTabs.takeIf { it.isNotEmpty() })
    val rootsByTabId = state.tabIdsByRoot.entries.associate { (root, id) -> id to root }
    for (ui in manager.getLogUis().filterIsInstance<MainVcsLogUi>()) {
      val root = rootsByTabId[ui.id] ?: continue
      if (ui.properties[SHOW_REPOSITORY_PATH] != state.showRepositoryPaths) {
        ui.properties[SHOW_REPOSITORY_PATH] = state.showRepositoryPaths
      }
      if (!observedUis.add(ui)) continue
      Disposer.register(ui) {
        observedUis.remove(ui)
        if (!project.isDisposed && !manager.isDisposed && state.tabIdsByRoot[root] == ui.id) {
          state.tabIdsByRoot.remove(root)
          state.dismissedRoots.add(root)
          requestUpdate()
        }
      }
    }
  }
}

internal class GitRepositoryLogTabsState {
  var selectedBranch: String? = null
  var showRepositoryPaths: Boolean = false
  var tabIdsByRoot: MutableMap<String, String> = LinkedHashMap()
  var dismissedRoots: MutableSet<String> = LinkedHashSet()
}

internal fun GitRepository.hasBranch(name: String): Boolean =
  branchNames().any { it.equals(name, ignoreCase = true) }

internal fun Collection<GitRepository>.branchNames(): List<String> =
  flatMap { it.branchNames() }.toSortedSet(String.CASE_INSENSITIVE_ORDER).toList()

private fun GitRepository.branchNames(): Sequence<String> =
  branches.localBranches.asSequence().map { it.name } + branches.remoteBranches.asSequence().map { it.nameForRemoteOperations }

internal class GitRepositoryLogTabsStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    project.serviceAsync<GitRepositoryLogTabs>()
  }
}
