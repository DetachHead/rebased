// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.impl

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorComposite
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorAutoClosingHandler
import com.intellij.openapi.fileEditor.impl.EditorComposite
import com.intellij.openapi.fileEditor.impl.EditorTabbedContainer
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.replacedServiceFixture
import com.intellij.testFramework.junit5.fixture.testFixture
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.TabDescriptor
import com.intellij.ui.content.impl.TabbedContentImpl
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBTabsImpl
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.vcs.log.ui.MainVcsLogUi
import com.intellij.vcs.log.ui.VcsLogUiHolder
import com.intellij.vcs.log.ui.editor.DefaultVcsLogFile
import com.intellij.vcs.log.ui.editor.VcsLogVirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import javax.swing.JPanel
import javax.swing.JTabbedPane

@TestApplication
@Timeout(30)
internal class RepositoryLogTabWorkspaceTest {
  private val projectFixture = projectFixture()
  private val lifetimeFixture = testFixture {
    val disposable = Disposer.newDisposable()
    initialized(disposable) { withContext(Dispatchers.EDT) { Disposer.dispose(disposable) } }
  }
  private val editorManagerFixture = projectFixture.replacedServiceFixture(FileEditorManager::class.java) {
    mock<FileEditorManagerEx>()
  }
  private val toolWindowManagerFixture = projectFixture.replacedServiceFixture(ToolWindowManager::class.java) {
    mock<ToolWindowManager>()
  }

  @AfterEach
  fun clearMocks(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      reset(editorManagerFixture.get())
    }
  }

  @Test
  fun `repository tabs hide and restore the previous editor layout`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val view = EditorView()
      val log = view.addLog("", "Log")
      val readme = view.addFile(LightVirtualFile("README"), "README").apply {
        component.putClientProperty(JBTabsImpl.PINNED, true)
      }
      val manual = view.addLog("manual", "Log: feature")
      view.tabs.select(manual, false)
      val original = listOf(log, readme, manual)
      val workspace = RepositoryLogTabWorkspace(projectFixture.get(), lifetimeFixture.get())

      workspace.update(emptySet())
      assertThat(view.visibleTabs()).isEmpty()
      assertThat(view.tabs.tabs).containsExactlyInAnyOrderElementsOf(original)
      assertThat(original.map { it.component.parent }).containsOnlyNulls()
      assertThat(readme.isPinned).isTrue()
      val composite = mock<EditorComposite>()
      whenever(composite.file).thenReturn(readme.`object` as VirtualFile)
      assertThat(EditorAutoClosingHandler.isClosingAllowed(composite)).isFalse()

      val zulu = view.addLog("zulu", "Zulu", repository = true)
      view.addLog("alpha", "alpha", repository = true)
      val beta = view.addLog("beta", "Beta", repository = true)
      whenever(composite.file).thenReturn(zulu.`object` as VirtualFile)
      assertThat(EditorAutoClosingHandler.isClosingAllowed(composite)).isFalse()
      zulu.component.putClientProperty(JBTabsImpl.PINNED, true)
      workspace.update(setOf("zulu", "alpha", "beta"))
      assertThat(view.visibleTabs().map { it.text }).containsExactly("alpha", "Beta", "Zulu")
      for (selected in listOf(zulu, beta)) {
        view.tabs.select(selected, false)
        assertThat(view.tabs.tabs.filter { it.component.parent === view.tabs.component }).containsExactly(selected)
      }

      view.closeLogTabs("zulu", "alpha", "beta")
      assertThat(view.visibleTabs()).containsExactly(zulu)
      workspace.update(null)
      assertThat(view.visibleTabs()).containsExactlyElementsOf(original + zulu)
      assertThat(view.tabs.selectedInfo).isSameAs(manual)
      assertThat(view.tabs.tabs.filter { it.component.parent === view.tabs.component }).containsExactly(manual)
      assertThat(zulu.isPinned).isTrue()
      assertThat(readme.isPinned).isTrue()
      whenever(composite.file).thenReturn(readme.`object` as VirtualFile)
      assertThat(EditorAutoClosingHandler.isClosingAllowed(composite)).isTrue()
    }
  }

  @Test
  fun `pinning keeps repository tabs in name order`(): Unit = checkPinnedOrder(sortAlphabetically = false)

  @Test
  fun `repository name order overrides alphabetical path order`(): Unit = checkPinnedOrder(sortAlphabetically = true)

  private fun checkPinnedOrder(sortAlphabetically: Boolean): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val keepPinnedOnLeft = AdvancedSettings.getBoolean("editor.keep.pinned.tabs.on.left")
      try {
        AdvancedSettings.setBoolean("editor.keep.pinned.tabs.on.left", true)
        val view = EditorView()
        view.tabs.setAlphabeticalMode(sortAlphabetically)
        val log = view.addLog("", "Log")
        val workspace = RepositoryLogTabWorkspace(projectFixture.get(), lifetimeFixture.get())
        workspace.update(emptySet())
        val zulu = view.addLog("zulu", "Zulu", repository = true).setText("C:/a/Zulu")
        val beta = view.addLog("beta", "Beta", repository = true).setText("C:/y/Beta")
        workspace.update(setOf("zulu", "beta"))
        view.tabs.select(beta, false)
        val alpha = view.addLog("alpha", "alpha", repository = true).setText("C:/z/alpha")
        workspace.update(setOf("zulu", "alpha", "beta"))
        assertThat(view.tabs.getVisibleInfos()).containsExactly(alpha, beta, zulu)
        assertThat(view.tabs.selectedInfo).isSameAs(beta)

        view.tabs.reallocate(zulu, alpha)
        view.tabs.resetTabsCache()
        view.tabs.fireTabsMoved()
        assertThat(view.tabs.getVisibleInfos()).containsExactly(alpha, beta, zulu)

        zulu.component.putClientProperty(JBTabsImpl.PINNED, true)

        assertThat(view.tabs.getVisibleInfos()).containsExactly(alpha, beta, zulu)
        assertThat(view.tabs.selectedInfo).isSameAs(beta)
        assertThat(zulu.isPinned).isTrue()
        workspace.update(null)
        assertThat(view.tabs.getVisibleInfos().first()).isSameAs(zulu)
        assertThat(view.tabs.selectedInfo).isSameAs(log)
      }
      finally {
        AdvancedSettings.setBoolean("editor.keep.pinned.tabs.on.left", keepPinnedOnLeft)
      }
    }
  }

  @Test
  fun `reopening while following restores the saved layout and both selections`(): Unit = checkEditorRestore(matching = true)

  @Test
  fun `reopening without matches restores the saved editor layout`(): Unit = checkEditorRestore(matching = false)

  @Test
  fun `editor selections survive a restart when the saved tab is unavailable`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val view = EditorView()
      view.addLog("", "Log")
      val properties = projectFixture.get().service<VcsLogProjectTabsProperties>()
      properties.state.repositoryWorkspace = VcsLogProjectTabsProperties.RepositoryWorkspaceState().apply {
        preservedLogIds.add("")
        editorWindows.add(VcsLogProjectTabsProperties.RepositoryEditorWindowState().apply {
          selectedRepositoryPath = "removed"
        })
      }
      val workspace = RepositoryLogTabWorkspace(projectFixture.get(), lifetimeFixture.get())
      val followedIds = setOf("alpha", "beta")
      workspace.update(followedIds)
      val beta = view.addLog("beta", "Beta", repository = true)
      val alpha = view.addLog("alpha", "alpha", repository = true)
      workspace.update(followedIds)

      assertThat(view.tabs.selectedInfo).isSameAs(alpha)
      assertThat(properties.state.repositoryWorkspace!!.editorWindows.single().selectedRepositoryPath)
        .isEqualTo((alpha.`object` as VirtualFile).path)
      view.tabs.select(beta, false)
      val saved = XmlSerializer.serialize(properties.state)
      Disposer.dispose(workspace)

      properties.loadState(XmlSerializer.deserialize(saved, VcsLogProjectTabsProperties.State::class.java))
      val reopened = EditorView()
      reopened.addLog("alpha", "alpha", repository = true)
      val reopenedBeta = reopened.addLog("beta", "Beta", repository = true)
      val reopenedWorkspace = RepositoryLogTabWorkspace(projectFixture.get(), lifetimeFixture.get())
      reopenedWorkspace.update(followedIds)

      assertThat(reopened.tabs.selectedInfo).isSameAs(reopenedBeta)
    }
  }

  private fun checkEditorRestore(matching: Boolean): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val view = EditorView()
      view.addLog("", "Log")
      val docs = view.addLog("docs", "Log: docs")
      view.addLog("task", "Log: TASK")
      val properties = projectFixture.get().service<VcsLogProjectTabsProperties>()
      properties.createProperties("docs").saveFilterValues("text", listOf("docs"))
      properties.createProperties("task").saveFilterValues("text", listOf("TASK"))
      view.tabs.select(docs, false)
      val workspace = RepositoryLogTabWorkspace(projectFixture.get(), lifetimeFixture.get())
      workspace.update(emptySet())
      view.addLog("alpha", "alpha", repository = true)
      val beta = view.addLog("beta", "Beta", repository = true)
      val followedIds = setOf("alpha", "beta")
      workspace.update(followedIds)
      view.tabs.select(beta, false)

      val saved = XmlSerializer.serialize(properties.state)
      Disposer.dispose(workspace)
      properties.loadState(XmlSerializer.deserialize(saved, VcsLogProjectTabsProperties.State::class.java))
      val reopened = EditorView()
      reopened.addLog("alpha", "alpha", repository = true)
      val reopenedBeta = reopened.addLog("beta", "Beta", repository = true)
      reopened.addLog("task", "Log: TASK")
      reopened.addLog("", "Log")
      val reopenedDocs = reopened.addLog("docs", "Log: docs")
      val reopenedWorkspace = RepositoryLogTabWorkspace(projectFixture.get(), lifetimeFixture.get())
      if (matching) {
        reopenedWorkspace.update(followedIds)
        assertThat(reopened.visibleTabs().map { it.text }).containsExactly("alpha", "Beta")
        assertThat(reopened.tabs.selectedInfo).isSameAs(reopenedBeta)
      }
      reopened.closeLogTabs("alpha", "beta")
      reopenedWorkspace.update(null)
      assertThat(reopened.visibleTabs().map { it.text }).containsExactly("Log", "Log: docs", "Log: TASK")
      assertThat(reopened.tabs.selectedInfo).isSameAs(reopenedDocs)
      assertThat(properties.createProperties("docs").getFilterValues("text")).containsExactly("docs")
      assertThat(properties.createProperties("task").getFilterValues("text")).containsExactly("TASK")
    }
  }

  @Test
  fun `repository and preserved tabs without filters survive a restart`(): Unit = checkSavedTabs(alreadyOpen = false)

  @Test
  fun `editor restoration reuses the saved repository tabs`(): Unit = checkSavedTabs(alreadyOpen = true)

  private fun checkSavedTabs(alreadyOpen: Boolean): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val view = EditorView()
      val docs = view.addLog("docs", "Log: docs")
      view.addLog("task", "Log: TASK")
      view.tabs.select(docs, false)
      val project = projectFixture.get()
      val properties = project.service<VcsLogProjectTabsProperties>()
      properties.createProperties("docs")[MainVcsLogUiProperties.ROOT_SCOPE] = "file://C:/repos/docs"
      properties.state.repositoryWorkspace = VcsLogProjectTabsProperties.RepositoryWorkspaceState().apply {
        preservedLogIds.add("task")
      }
      val manager = editorManagerFixture.get() as FileEditorManagerEx
      if (!alreadyOpen) whenever(manager.openFiles).thenReturn(emptyArray())
      val reopened = mutableListOf<String>()
      doAnswer {
        val file = it.getArgument<DefaultVcsLogFile>(0)
        reopened.add(file.tabId)
        assertThat(it.getArgument<FileEditorOpenOptions>(2).selectAsCurrent).isFalse()
        FileEditorComposite.EMPTY
      }.whenever(manager).openFile(any(), isNull(), any<FileEditorOpenOptions>())
      val logManager = mock<VcsLogManager>()
      val tabsManager = VcsLogTabsManager(project, properties, logManager)
      val settings = service<VcsLogApplicationSettings>()
      val showInEditor = settings[CommonUiProperties.SHOW_IN_EDITOR]
      try {
        settings[CommonUiProperties.SHOW_IN_EDITOR] = true
        tabsManager.createTabs()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertThat(reopened).containsExactlyElementsOf(if (alreadyOpen) emptyList() else listOf("docs", "task"))
        assertThat(view.tabs.selectedInfo).isSameAs(docs)
      }
      finally {
        Disposer.dispose(tabsManager)
        settings[CommonUiProperties.SHOW_IN_EDITOR] = showInEditor
        reset(logManager)
      }
    }
  }

  @Test
  fun `clear closes the saved and generated editor logs`(): Unit = checkEditorReset(following = true)

  @Test
  fun `clear closes additional editor logs when the filter is already clear`(): Unit = checkEditorReset(following = false)

  private fun checkEditorReset(following: Boolean): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val view = EditorView()
      val log = view.addLog("", "Log")
      val readme = view.addFile(LightVirtualFile("README"), "README").apply {
        component.putClientProperty(JBTabsImpl.PINNED, true)
      }
      view.addLog("manual", "Log: TASK").component.putClientProperty(JBTabsImpl.PINNED, true)
      val project = projectFixture.get()
      val properties = project.service<VcsLogProjectTabsProperties>()
      properties.createProperties("manual").saveFilterValues("text", listOf("TASK"))
      val workspace = RepositoryLogTabWorkspace(project, lifetimeFixture.get())
      if (following) workspace.update(emptySet())
      view.addLog("repository", "Repository", repository = true).component.putClientProperty(JBTabsImpl.PINNED, true)
      if (following) workspace.update(setOf("repository"))
      val manager = VcsLogTabsManager(project, properties, mock<VcsLogManager>())
      try {
        workspace.update(null)
        manager.resetTabs()

        assertThat(view.visibleTabs()).containsExactly(log, readme)
        assertThat(readme.isPinned).isTrue()
        assertThat(properties.tabs).isEmpty()
        assertThat(properties.state.repositoryWorkspace).isNull()
        assertThat(properties.checkTabHasFilters("manual")).isFalse()
      }
      finally {
        Disposer.dispose(manager)
      }
    }
  }

  @Test
  fun `reset closes pinned and grouped tool window logs and keeps the console`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val view = ToolWindowView()
      val log = view.addLog(VcsLogManager.MAIN_LOG_ID, "Log")
      val console = view.addConsole()
      val grouped = TabbedContentImpl(VcsLogContentUtil.DEFAULT_TAB_GROUP_ID, TabDescriptor(view.panel("docs"), "docs"), true)
      grouped.addContent(TabDescriptor(view.panel("task"), "TASK"), true)
      view.manager.addContent(grouped)
      val workspace = RepositoryLogTabWorkspace(projectFixture.get(), lifetimeFixture.get())
      workspace.update(emptySet())
      val repository = view.addLog("repository", "Repository", repository = true).apply { isPinned = true }
      workspace.update(setOf("repository"))
      val tabsManager = VcsLogTabsManager(projectFixture.get(), view.properties, mock<VcsLogManager>())
      try {
        workspace.update(null)
        tabsManager.resetTabs()

        assertThat(view.manager.contents).containsExactly(log, console)
        assertThat(view.properties.tabs).isEmpty()
        assertThat(view.properties.state.repositoryWorkspace).isNull()
        assertThat(Disposer.isDisposed(repository)).isTrue()
        assertThat(Disposer.isDisposed(grouped)).isTrue()
      }
      finally {
        Disposer.dispose(tabsManager)
      }
    }
  }

  @Test
  fun `tool window selections survive a restart`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val view = ToolWindowView()
      view.addLog(VcsLogManager.MAIN_LOG_ID, "Log")
      val manual = view.addLog("manual", "Manual")
      view.manager.setSelectedContent(manual, false)
      val workspace = RepositoryLogTabWorkspace(projectFixture.get(), lifetimeFixture.get())
      workspace.update(emptySet())
      view.addLog("alpha", "alpha", repository = true)
      val beta = view.addLog("beta", "Beta", repository = true)
      val followedIds = setOf("alpha", "beta")
      workspace.update(followedIds)
      view.manager.setSelectedContent(beta, false)
      val serialized = XmlSerializer.serialize(view.properties.state)
      Disposer.dispose(workspace)

      view.properties.loadState(XmlSerializer.deserialize(serialized, VcsLogProjectTabsProperties.State::class.java))
      val reopened = ToolWindowView()
      val reopenedLog = reopened.addLog(VcsLogManager.MAIN_LOG_ID, "Log")
      val reopenedManual = reopened.addLog("manual", "Manual")
      reopened.addLog("alpha", "alpha", repository = true)
      val reopenedBeta = reopened.addLog("beta", "Beta", repository = true)
      reopened.manager.setSelectedContent(reopenedLog, false)
      val restored = RepositoryLogTabWorkspace(projectFixture.get(), lifetimeFixture.get())
      restored.update(followedIds)

      assertThat(reopened.manager.selectedContent).isSameAs(reopenedBeta)
      restored.update(null)
      assertThat(reopened.manager.contents.take(2)).containsExactly(reopenedLog, reopenedManual)
      assertThat(reopened.manager.selectedContent).isSameAs(reopenedManual)
    }
  }

  @Test
  fun `the tool window selects a repository when following starts`(): Unit = checkToolWindowSelection(restored = false)

  @Test
  fun `the tool window selects a repository when the saved selection is unavailable`(): Unit = checkToolWindowSelection(restored = true)

  private fun checkToolWindowSelection(restored: Boolean): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val view = ToolWindowView()
      val manager = view.manager
      val log = view.addLog(VcsLogManager.MAIN_LOG_ID, "Log")
      val console = view.addConsole()
      manager.setSelectedContent(log, false)
      val workspace = RepositoryLogTabWorkspace(projectFixture.get(), lifetimeFixture.get())
      workspace.update(emptySet())
      if (restored) view.properties.state.repositoryWorkspace!!.selectedRepositoryToolWindowId = "removed"
      val beta = view.addLog("beta", "Beta", repository = true)
      val alpha = view.addLog("alpha", "alpha", repository = true)
      view.addLog("gamma", "Gamma", repository = true)
      val followedIds = setOf("alpha", "beta", "gamma")

      workspace.update(followedIds)

      assertThat(manager.selectedContent).isSameAs(alpha)
      manager.removeContent(alpha, true, false, false)
      workspace.update(followedIds)
      assertThat(manager.selectedContent).isSameAs(beta)
      manager.setSelectedContent(console, false)
      manager.removeContent(beta, true, false, false)
      workspace.update(followedIds)
      assertThat(manager.selectedContent).isSameAs(console)
      workspace.update(null)
      assertThat(manager.selectedContent).isSameAs(log)
    }
  }

  @Test
  fun `tool window tabs retain their components pin state and selection`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val view = ToolWindowView()
      val manager = view.manager
      val log = view.addLog(VcsLogManager.MAIN_LOG_ID, "Log")
      val manual = view.addLog("manual", "Log: feature").apply { isPinned = true }
      val original = listOf(log, manual)
      manager.setSelectedContent(manual, false)
      val workspace = RepositoryLogTabWorkspace(projectFixture.get(), lifetimeFixture.get())
      workspace.update(emptySet())
      assertThat(manager.contents).isEmpty()
      assertThat(Disposer.isDisposed(manual)).isFalse()

      for (name in listOf("Zulu", "alpha", "Beta")) {
        view.addLog(name, name, repository = true)
      }
      workspace.update(setOf("Zulu", "alpha", "Beta"))
      assertThat(manager.contents.map(Content::getDisplayName)).containsExactly("alpha", "Beta", "Zulu")
      val pane = UIUtil.findComponentOfType(manager.component, JTabbedPane::class.java)!!
      assertThat((0 until pane.tabCount).map(pane::getTitleAt)).containsExactly("alpha", "Beta", "Zulu")
      val zulu = manager.contents.last()
      manager.setSelectedContent(zulu, false)
      zulu.putUserData(Content.TEMPORARY_REMOVED_KEY, true)
      manager.removeContent(zulu, false, false, false)
      manager.addContent(zulu, 0)
      zulu.putUserData(Content.TEMPORARY_REMOVED_KEY, null)
      manager.setSelectedContent(zulu, false)
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      assertThat((0 until pane.tabCount).map(pane::getTitleAt)).containsExactly("alpha", "Beta", "Zulu")
      assertThat(manager.selectedContent).isSameAs(zulu)

      zulu.isPinned = true
      val ordinary = manager.contents.filter { it !== zulu }
      val tabsManager = VcsLogTabsManager(projectFixture.get(), view.properties, mock<VcsLogManager>())
      Disposer.register(lifetimeFixture.get(), tabsManager)
      listOf("alpha", "Beta", "Zulu").forEach(tabsManager::closeTab)
      workspace.update(null)
      assertThat(manager.contents).containsExactlyElementsOf(original + zulu)
      assertThat(manager.selectedContent).isSameAs(manual)
      assertThat(zulu.isPinned).isTrue()
      assertThat(Disposer.isDisposed(zulu)).isFalse()
      assertThat(ordinary).allSatisfy { assertThat(Disposer.isDisposed(it)).isTrue() }
      assertThat(manual.isPinned).isTrue()
      assertThat(Disposer.isDisposed(manual)).isFalse()
      assertThat(manual.getUserData(Content.TEMPORARY_REMOVED_KEY)).isNull()
    }
  }

  private inner class ToolWindowView {
    private val factory = ContentFactory.getInstance()
    val manager = factory.createContentManager(true, projectFixture.get())
    val properties = projectFixture.get().service<VcsLogProjectTabsProperties>()

    init {
      Disposer.register(lifetimeFixture.get(), manager)
      val toolWindow = mock<ToolWindow>()
      whenever(toolWindow.contentManagerIfCreated).thenReturn(manager)
      whenever(toolWindowManagerFixture.get().getToolWindow(ChangesViewContentManager.TOOLWINDOW_ID)).thenReturn(toolWindow)
      val editorManager = editorManagerFixture.get() as FileEditorManagerEx
      whenever(editorManager.windows).thenReturn(emptyArray())
      whenever(editorManager.openFiles).thenReturn(emptyArray())
    }

    fun panel(id: String): JPanel {
      val ui = mock<MainVcsLogUi>()
      Disposer.register(lifetimeFixture.get(), ui)
      whenever(ui.id).thenReturn(id)
      whenever(ui.properties).thenReturn(properties.createProperties(id))
      if (id != VcsLogManager.MAIN_LOG_ID) properties.addTab(id, VcsLogTabLocation.TOOL_WINDOW)
      return object : JPanel(), VcsLogUiHolder {
        override val vcsLogUi = ui
      }
    }

    fun addLog(id: String, name: String, repository: Boolean = false): Content {
      if (repository) properties.createProperties(id)[MainVcsLogUiProperties.ROOT_SCOPE] = "file://C:/repos/$name"
      return factory.createContent(panel(id), name, true).also(manager::addContent)
    }

    fun addConsole(): Content = factory.createContent(JPanel(), "Console", false).also(manager::addContent)
  }

  private inner class EditorView {
    val tabs = JBTabsImpl(projectFixture.get(), lifetimeFixture.get())
    private val properties = projectFixture.get().service<VcsLogProjectTabsProperties>()
    private val tabsManager = VcsLogTabsManager(projectFixture.get(), properties, mock<VcsLogManager>())

    init {
      Disposer.register(lifetimeFixture.get(), tabsManager)
      val window = mock<EditorWindow>()
      val container = mock<EditorTabbedContainer>()
      val manager = editorManagerFixture.get() as FileEditorManagerEx
      whenever(window.tabbedPane).thenReturn(container)
      whenever(container.tabs).thenReturn(tabs)
      whenever(manager.windows).thenReturn(arrayOf(window))
      whenever(manager.currentWindow).thenReturn(window)
      whenever(window.isFileOpen(any())).thenAnswer {
        val file = it.getArgument<VirtualFile>(0)
        tabs.tabs.any { tab -> tab.`object` == file }
      }
      whenever(window.isFilePinned(any())).thenAnswer {
        val file = it.getArgument<VirtualFile>(0)
        tabs.tabs.any { tab -> tab.`object` == file && tab.isPinned }
      }
      whenever(manager.openFiles).thenAnswer { tabs.tabs.map { it.`object` as VirtualFile }.toTypedArray() }
      doAnswer {
        val file = it.getArgument<VirtualFile>(0)
        tabs.tabs.firstOrNull { tab -> tab.`object` === file }?.let(tabs::removeTab)
        if (file is DefaultVcsLogFile) properties.removeTab(file.tabId)
        null
      }.whenever(manager).closeFile(any())
    }

    fun addFile(file: VirtualFile, name: String): TabInfo = tabs.addTab(TabInfo(JPanel()).setObject(file).setText(name))

    fun addLog(id: String, name: String, repository: Boolean = false): TabInfo {
      val file = DefaultVcsLogFile(VcsLogVirtualFileSystem.VcsLogComplexPath("session", "project", id))
      if (repository) {
        properties.createProperties(id)[MainVcsLogUiProperties.ROOT_SCOPE] = "file://C:/repos/$name"
        file.isRepositoryScoped = true
      }
      if (id.isNotEmpty()) properties.addTab(id, VcsLogTabLocation.EDITOR)
      return addFile(file, name)
    }

    fun visibleTabs(): List<TabInfo> = tabs.tabs.filterNot { it.isHidden }

    fun closeLogTabs(vararg ids: String) = ids.forEach(tabsManager::closeTab)
  }
}
