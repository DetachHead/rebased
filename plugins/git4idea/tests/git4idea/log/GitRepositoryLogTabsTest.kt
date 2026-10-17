// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.log

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.platform.util.coroutines.childScope
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.junit5.fixture.testFixture
import com.intellij.testFramework.replaceService
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.vcs.log.impl.CommonUiProperties
import com.intellij.vcs.log.impl.MainVcsLogUiProperties
import com.intellij.vcs.log.impl.VcsLogApplicationSettings
import com.intellij.vcs.log.impl.VcsLogManager
import com.intellij.vcs.log.impl.VcsLogProjectTabsProperties
import com.intellij.vcs.log.impl.VcsLogTabLocation
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.vcs.log.ui.MainVcsLogUi
import com.intellij.vcs.test.vcsTestProjectPathFixture
import git4idea.repo.GitRepository
import git4idea.test.createRepository
import git4idea.test.git
import git4idea.test.gitSingleRepoContextFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@TestApplication
@Timeout(30)
internal class GitRepositoryLogTabsTest {
  private val logSettingsFixture = testFixture {
    val settings = service<VcsLogApplicationSettings>()
    val showInEditor = settings[CommonUiProperties.SHOW_IN_EDITOR]
    settings[CommonUiProperties.SHOW_IN_EDITOR] = false
    initialized(Unit) { settings[CommonUiProperties.SHOW_IN_EDITOR] = showInEditor }
  }
  private val contextFixture = gitSingleRepoContextFixture(projectPathFixture = testFixture {
    logSettingsFixture.init()
    initialized(vcsTestProjectPathFixture().init()) {}
  })
  private val context get() = contextFixture.get()

  @Test
  fun `matches full local and remote names without checking them out`(): Unit = with(context) {
    git("branch local/preview")
    git("remote add upstream .")
    git("update-ref refs/remotes/upstream/feature/preview HEAD")
    repo.update()

    assertThat(repo.hasBranch("local/preview")).isTrue()
    assertThat(repo.hasBranch("LOCAL/PREVIEW")).isTrue()
    assertThat(repo.hasBranch("feature/preview")).isTrue()
    assertThat(repo.hasBranch("FEATURE/PREVIEW")).isTrue()
    assertThat(repo.hasBranch("preview")).isFalse()
    assertThat(repo.hasBranch("upstream/feature/preview")).isFalse()
    assertThat(repo.currentBranchName).isEqualTo("master")
    assertThat(repo.hasBranch("missing")).isFalse()
  }

  @Test
  fun `only submodules in the root repository enable the feature`(): Unit = with(context) {
    val tabs = project.service<GitRepositoryLogTabs>()
    assertThat(tabs.isAvailable).isFalse()
    val other = createRepository(project, projectNioRoot.resolve("Other"), true)
    declareSubmodule(other, "Child")
    assertThat(tabs.isAvailable).isFalse()
    declareSubmodule(repo, "Child")
    assertThat(tabs.isAvailable).isTrue()

    git("config -f .gitmodules --remove-section submodule.Child")
    repo.update()
    assertThat(tabs.isAvailable).isFalse()
  }

  @Test
  fun `clearing the filter resets the log even when all managed tabs are closed`(@TestDisposable disposable: Disposable): Unit =
    timeoutRunBlocking {
      withContext(Dispatchers.EDT) {
        val log = mock(VcsProjectLog::class.java)
        context.project.replaceService(VcsProjectLog::class.java, log, disposable)
        val tabs = context.project.service<GitRepositoryLogTabs>()
        tabs.loadState(GitRepositoryLogTabsState().apply {
          selectedBranch = "preview"
          dismissedRoots.add("child")
        })
        tabs.setFilter("  Preview  ", true)
        assertThat(tabs.selectedBranch).isEqualTo("Preview")
        tabs.setFilter(" ", true)
        assertThat(tabs.selectedBranch).isNull()
        assertThat(tabs.getState().dismissedRoots).isEmpty()
        assertThat(tabs.showRepositoryPaths).isTrue()
        verify(log).resetLogTabs()
      }
    }

  @Test
  fun `completion combines local and remote names and removes duplicates without case sensitivity`(): Unit = with(context) {
    git("branch Feature/Preview")
    git("branch zulu")
    git("remote add upstream .")
    git("update-ref refs/remotes/upstream/feature/preview HEAD")
    git("update-ref refs/remotes/upstream/alpha HEAD")
    git("tag excluded")
    repo.update()

    assertThat(listOf(repo).branchNames()).containsExactly("alpha", "Feature/Preview", "master", "zulu")
  }

  @Test
  fun `repository changes and branch selection update the tabs`(@TestDisposable disposable: Disposable): Unit = timeoutRunBlocking {
    var scope = childScope("Repository log tabs", supervisor = false)
    with(context) {
      declareSubmodule(repo, "Child")
      val child = createRepository(project, projectNioRoot.resolve("Child"), true)
      val other = createRepository(project, projectNioRoot.resolve("Other"), true)
      git("branch preview")
      child.git("branch Preview")
      repo.update()
      child.update()
      val properties = project.service<VcsLogProjectTabsProperties>()
      val log = mock(VcsProjectLog::class.java)
      val manager = mock(VcsLogManager::class.java)
      val uis = linkedMapOf<String, MainVcsLogUi>()
      val pinnedIds = mutableSetOf<String>()
      val closeAttempts = mutableSetOf<String>()
      var shownTabs: Set<String>? = null
      val updates = Channel<Unit>(Channel.CONFLATED)
      var nextId = 0
      whenever(log.logManager).thenReturn(manager)
      whenever(log.init(true)).thenReturn(manager)
      for (repository in listOf(repo, child, other)) {
        doAnswer {
          val id = "repository-${nextId++}"
          val ui = mock(MainVcsLogUi::class.java)
          Disposer.register(disposable, ui)
          whenever(ui.id).thenReturn(id)
          whenever(ui.properties).thenReturn(properties.createProperties(id))
          ui.properties[MainVcsLogUiProperties.ROOT_SCOPE] = repository.root.url
          properties.addTab(id, VcsLogTabLocation.TOOL_WINDOW)
          uis[id] = ui
          ui
        }.`when`(log).openRepositoryLogTab(repository.root, VcsLogTabLocation.TOOL_WINDOW)
      }
      doAnswer {
        val id = it.getArgument<String>(0)
        closeAttempts.add(id)
        if (id !in pinnedIds) {
          properties.removeTab(id)
          uis.remove(id)?.let(Disposer::dispose)
        }
        Unit
      }.`when`(log).closeLogTab(anyString())
      doAnswer {
        shownTabs = it.getArgument(0)
        Unit
      }.`when`(log).updateRepositoryLogTabs(any())
      doAnswer {
        updates.trySend(Unit)
        uis.values.toList()
      }.`when`(manager).getLogUis()
      project.replaceService(VcsProjectLog::class.java, log, disposable)
      var tabs = GitRepositoryLogTabs(project, scope)
      suspend fun awaitUpdate(condition: () -> Boolean) {
        do {
          updates.receive()
        }
        while (!withContext(Dispatchers.EDT) { condition() })
      }
      try {
        withContext(Dispatchers.EDT) { tabs.setFilter("preview", true) }
        awaitUpdate { tabs.getState().tabIdsByRoot.size == 2 }
        val initial = withContext(Dispatchers.EDT) {
          assertThat(uis.values).allSatisfy {
            assertThat(it.properties[MainVcsLogUiProperties.SHOW_REPOSITORY_PATH]).isTrue()
            assertThat(properties.checkTabHasFilters(it.id)).isFalse()
          }
          tabs.getState().tabIdsByRoot.toMap().also { log.closeLogTab(it.getValue(child.root.url)) }
        }
        awaitUpdate { child.root.url in tabs.getState().dismissedRoots }
        withContext(Dispatchers.EDT) { tabs.setFilter("PREVIEW", false) }
        awaitUpdate { uis.values.all { !it.properties[MainVcsLogUiProperties.SHOW_REPOSITORY_PATH] } }
        val saved = withContext(Dispatchers.EDT) {
          assertThat(tabs.getState().tabIdsByRoot).containsExactlyEntriesOf(mapOf(repo.root.url to initial.getValue(repo.root.url)))
          XmlSerializer.serialize(tabs.getState())
        }
        scope.coroutineContext.job.cancelAndJoin()
        updates.tryReceive()
        scope = childScope("Restored repository log tabs", supervisor = false)
        withContext(Dispatchers.EDT) {
          tabs = GitRepositoryLogTabs(project, scope)
          tabs.loadState(XmlSerializer.deserialize(saved, GitRepositoryLogTabsState::class.java))
        }
        awaitUpdate { tabs.getState().tabIdsByRoot == mapOf(repo.root.url to initial.getValue(repo.root.url)) }
        withContext(Dispatchers.EDT) {
          assertThat(tabs.selectedBranch).isEqualTo("PREVIEW")
          assertThat(tabs.showRepositoryPaths).isFalse()
          assertThat(tabs.getState().dismissedRoots).containsExactly(child.root.url)
          assertThat(uis.keys).containsExactly(initial.getValue(repo.root.url))
          tabs.setFilter("master", false)
        }
        awaitUpdate { tabs.getState().tabIdsByRoot.size == 3 }
        withContext(Dispatchers.EDT) { tabs.setFilter("preview", false) }
        awaitUpdate { tabs.selectedBranch == "preview" && tabs.getState().tabIdsByRoot.size == 2 }
        val pinnedId = withContext(Dispatchers.EDT) {
          tabs.getState().tabIdsByRoot.getValue(repo.root.url).also { pinnedIds.add(it) }
        }
        git("branch -D preview")
        repo.update()
        awaitUpdate { pinnedId in closeAttempts && shownTabs?.size == 1 }
        child.git("branch -D Preview")
        child.update()
        awaitUpdate { tabs.getState().tabIdsByRoot.values.toSet() == setOf(pinnedId) && shownTabs == null }
        withContext(Dispatchers.EDT) {
          assertThat(tabs.selectedBranch).isEqualTo("preview")
          verify(log, atLeastOnce()).updateRepositoryLogTabs(null)
        }
        git("branch preview")
        repo.update()
        awaitUpdate { shownTabs == setOf(pinnedId) }
        withContext(Dispatchers.EDT) {
          assertThat(uis.keys).containsExactly(pinnedId)
          pinnedIds.clear()
        }
        git("branch -D preview")
        repo.update()
        awaitUpdate { tabs.getState().tabIdsByRoot.isEmpty() }
        assertThat(listOf(repo, child, other)).allSatisfy { assertThat(it.currentBranchName).isEqualTo("master") }
      }
      finally {
        scope.coroutineContext.job.cancelAndJoin()
        withContext(Dispatchers.EDT) { reset(log, manager) }
      }
    }
  }

  private fun declareSubmodule(repository: GitRepository, name: String) {
    repository.git("config -f .gitmodules submodule.$name.path $name")
    repository.git("config -f .gitmodules submodule.$name.url ../$name")
    repository.update()
  }
}
