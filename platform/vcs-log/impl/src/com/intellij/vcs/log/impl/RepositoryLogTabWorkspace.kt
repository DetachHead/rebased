// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.impl

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.tabs.JBTabsEx
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.vcs.log.impl.VcsLogProjectTabsProperties.RepositoryEditorTabState
import com.intellij.vcs.log.impl.VcsLogProjectTabsProperties.RepositoryEditorWindowState
import com.intellij.vcs.log.impl.VcsLogProjectTabsProperties.RepositoryWorkspaceState
import com.intellij.vcs.log.ui.editor.DefaultVcsLogFile

internal val PRESERVED_LOG_WORKSPACE_FILE: Key<Boolean> = Key.create("vcs.log.preserved.workspace.file")

internal class RepositoryLogTabWorkspace(private val project: Project, parent: Disposable) : Disposable {
  private val editorTabs = mutableMapOf<JBTabsEx, PreservedLogEditorTabs>()
  private var toolWindowTabs: PreservedLogToolWindowTabs? = null

  init {
    Disposer.register(parent, this)
  }

  @RequiresEdt
  fun update(repositoryTabIds: Set<String>?) {
    val editorManager = FileEditorManagerEx.getInstanceEx(project)
    val properties = project.service<VcsLogProjectTabsProperties>()
    if (repositoryTabIds == null) {
      val saved = properties.state.repositoryWorkspace ?: return
      if (editorTabs.isEmpty() && toolWindowTabs == null) update(emptySet())
      editorTabs.values.forEach { it.restore() }
      toolWindowTabs?.restore()
      editorManager.windows.getOrNull(saved.selectedWindow)?.let { editorManager.currentWindow = it }
      editorTabs.values.forEach(Disposer::dispose)
      toolWindowTabs?.let(Disposer::dispose)
      editorTabs.clear()
      toolWindowTabs = null
      properties.state.repositoryWorkspace = null
      return
    }

    val resumed = properties.state.repositoryWorkspace != null && editorTabs.isEmpty()
    val saved = properties.state.repositoryWorkspace ?: RepositoryWorkspaceState().also { state ->
      state.preservedLogIds.addAll((properties.tabs.keys - repositoryTabIds) + setOf("", VcsLogManager.MAIN_LOG_ID))
      editorManager.openFiles.filterNot { it is DefaultVcsLogFile && it.tabId in repositoryTabIds }
        .mapTo(state.preservedFilePaths) { it.path }
      state.selectedWindow = editorManager.windows.indexOf(editorManager.currentWindow)
      properties.state.repositoryWorkspace = state
    }
    val preserved = saved.preservedLogIds
    val windows = editorManager.windows.mapNotNull { it.tabbedPane.tabs as? JBTabsEx }
    for (tabs in editorTabs.keys - windows.toSet()) {
      editorTabs.remove(tabs)?.let(Disposer::dispose)
    }
    for ((index, tabs) in windows.withIndex()) {
      while (saved.editorWindows.size <= index) saved.editorWindows.add(RepositoryEditorWindowState())
      editorTabs.getOrPut(tabs) { PreservedLogEditorTabs(tabs, this, saved.editorWindows[index], resumed) }.update(
        preserve = { file -> file.path in saved.preservedFilePaths || file is DefaultVcsLogFile && file.tabId in preserved },
        repositoryName = { file ->
          (file as? DefaultVcsLogFile)?.let { VcsLogTabsUtil.getRepositoryName(properties.createProperties(it.tabId)) }
        }
      )
    }
    VcsLogContentUtil.getToolWindow(project)?.contentManagerIfCreated?.let { manager ->
      val preservedContents = manager.contentComponentSequence()
        .filter { (content, component) ->
          content.tabName == VcsLogContentUtil.MAIN_LOG_TAB_NAME || VcsLogContentUtil.getLogUi(component)?.id in preserved
        }
        .map { it.first }.toSet()
      val tabs = toolWindowTabs ?: PreservedLogToolWindowTabs(manager, this, saved).also { toolWindowTabs = it }
      tabs.update(
        preserve = { it in preservedContents },
        repositoryName = { content ->
          VcsLogContentUtil.getLogUi(content.component)?.let { VcsLogTabsUtil.getRepositoryName(it.properties) }
        }
      )
    }
  }

  override fun dispose() {
    editorTabs.clear()
    toolWindowTabs = null
  }
}

private class PreservedLogEditorTabs(
  private val tabs: JBTabsEx,
  parent: Disposable,
  private val state: RepositoryEditorWindowState,
  private var restoreRepositorySelection: Boolean,
) : Disposable {
  private val savedTabs get() = state.tabs
  private val preservedFiles = mutableSetOf<VirtualFile>()
  private val wasTabOrderPreserved = tabs.isTabOrderPreserved
  private var repositoryName: (VirtualFile) -> String? = { null }

  init {
    Disposer.register(parent, this)
    tabs.isTabOrderPreserved = true
    tabs.addListener(object : TabsListener {
      override fun tabsMoved() = sort()

      override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
        if (!restoreRepositorySelection) saveRepositorySelection()
      }
    }, this)
  }

  @RequiresEdt
  fun update(preserve: (VirtualFile) -> Boolean, repositoryName: (VirtualFile) -> String?) {
    this.repositoryName = repositoryName
    if (!state.selectionSaved) {
      state.selectedPath = (tabs.selectedInfo?.`object` as? VirtualFile)?.takeIf(preserve)?.path
      state.selectionSaved = true
    }
    val container = tabs.component
    var contentHidden = false
    for ((index, tab) in tabs.tabs.withIndex()) {
      val file = tab.`object` as? VirtualFile ?: continue
      if (!preserve(file)) continue
      savedTabs.putIfAbsent(file.path, RepositoryEditorTabState().also {
        it.hidden = tab.isHidden
        it.index = index
      })
      preservedFiles.add(file)
      file.putUserData(PRESERVED_LOG_WORKSPACE_FILE, true)
      tab.isHidden = true
      if (tab.component.parent === container) {
        container.remove(tab.component)
        contentHidden = true
      }
    }
    if (contentHidden) {
      container.revalidate()
      container.repaint()
    }
    sort()
    if (restoreRepositorySelection) {
      val visibleTabs = tabs.tabs.filterNot { it.isHidden }
      val selection = visibleTabs.firstOrNull { (it.`object` as? VirtualFile)?.path == state.selectedRepositoryPath }
                      ?: visibleTabs.firstOrNull { (it.`object` as? VirtualFile)?.let(repositoryName) != null }
      selection?.let {
        tabs.select(it, false)
        restoreRepositorySelection = false
      }
    }
    if (!restoreRepositorySelection) saveRepositorySelection()
  }

  private fun saveRepositorySelection() {
    val file = tabs.selectedInfo?.`object` as? VirtualFile ?: return
    if (repositoryName(file) != null && file.path !in savedTabs) state.selectedRepositoryPath = file.path
  }

  @RequiresEdt
  fun restore() {
    for (tab in tabs.tabs) {
      val file = tab.`object` as? VirtualFile ?: continue
      savedTabs[file.path]?.let { tab.isHidden = it.hidden }
    }
    tabs.sortTabs(compareBy { savedTabs[(it.`object` as? VirtualFile)?.path]?.index ?: Int.MAX_VALUE })
    tabs.tabs.firstOrNull { (it.`object` as? VirtualFile)?.path == state.selectedPath && !it.isHidden }?.let {
      tabs.select(it, false)
    }
    tabs.isTabOrderPreserved = wasTabOrderPreserved
  }

  private fun sort() {
    val current = tabs.tabs.filterNot { it.isHidden }
    val sorted = repositoryTabOrder(current) { tab -> (tab.`object` as? VirtualFile)?.let(repositoryName) }
    if (current == sorted) return
    val positions = sorted.withIndex().associate { it.value to it.index }
    tabs.sortTabs(compareBy { positions[it] ?: Int.MAX_VALUE })
  }

  override fun dispose() {
    preservedFiles.forEach { it.putUserData(PRESERVED_LOG_WORKSPACE_FILE, null) }
    preservedFiles.clear()
    repositoryName = { null }
  }
}

private class PreservedLogToolWindowTabs(
  private val manager: ContentManager,
  parent: Disposable,
  private val state: RepositoryWorkspaceState,
) : Disposable {
  private val savedTabs = linkedMapOf<Content, Int>()
  private var repositoryName: (Content) -> String? = { null }
  private var sorting = false
  private var sortScheduled = false
  private var restoreRepositorySelection = true
  private val listener = object : ContentManagerListener {
    override fun contentAdded(event: ContentManagerEvent) {
      if (sorting || sortScheduled) return
      sortScheduled = true
      ApplicationManager.getApplication().invokeLater {
        sortScheduled = false
        if (!Disposer.isDisposed(this@PreservedLogToolWindowTabs)) sort()
      }
    }

    override fun selectionChanged(event: ContentManagerEvent) {
      if (!sorting && !restoreRepositorySelection) saveRepositorySelection()
    }

    override fun contentRemoved(event: ContentManagerEvent) {
      if (!sorting && manager.selectedContent == null && repositoryName(event.content) != null) {
        restoreRepositorySelection = true
      }
    }
  }

  init {
    Disposer.register(parent, this)
    manager.addContentManagerListener(listener)
  }

  @RequiresEdt
  fun update(preserve: (Content) -> Boolean, repositoryName: (Content) -> String?) {
    this.repositoryName = repositoryName
    if (!state.toolWindowSelectionSaved) {
      state.selectedToolWindowId = manager.selectedContent?.takeIf(preserve)?.let(::contentId)
      state.toolWindowSelectionSaved = true
    }
    sorting = true
    try {
      for ((index, content) in manager.contents.withIndex()) {
        if (!preserve(content)) continue
        val id = contentId(content)
        val savedIndex = state.toolWindowIndices.getOrPut(id) { index }
        savedTabs.putIfAbsent(content, savedIndex)
        content.putUserData(Content.TEMPORARY_REMOVED_KEY, true)
        manager.removeContent(content, false, false, false)
        Disposer.register(this, content)
      }
    }
    finally {
      sorting = false
    }
    sort()
    if (restoreRepositorySelection) {
      val selection = manager.contents.firstOrNull { contentId(it) == state.selectedRepositoryToolWindowId }
                      ?: manager.contents.firstOrNull { repositoryName(it) != null }
      selection?.let {
        manager.setSelectedContent(it, false)
        restoreRepositorySelection = false
      }
    }
    if (!restoreRepositorySelection) saveRepositorySelection()
  }

  private fun saveRepositorySelection() {
    val content = manager.selectedContent ?: return
    val id = contentId(content)
    if (repositoryName(content) != null && id !in state.toolWindowIndices) state.selectedRepositoryToolWindowId = id
  }

  private fun sort() {
    val current = manager.contents.toList()
    val sorted = repositoryTabOrder(current, repositoryName)
    if (current == sorted) return
    val selectedContent = manager.selectedContent
    sorting = true
    try {
      sorted.forEachIndexed { index, content ->
        if (manager.getIndexOfContent(content) == index) return@forEachIndexed
        content.putUserData(Content.TEMPORARY_REMOVED_KEY, true)
        manager.removeContent(content, false, false, false)
        manager.addContent(content, index)
        content.putUserData(Content.TEMPORARY_REMOVED_KEY, null)
      }
      selectedContent?.let { manager.setSelectedContent(it, false) }
    }
    finally {
      sorting = false
    }
  }

  @RequiresEdt
  fun restore() {
    for ((content, index) in savedTabs.entries.sortedBy { it.value }) {
      if (Disposer.isDisposed(content)) continue
      manager.addContent(content, index.coerceAtMost(manager.contentCount))
      content.putUserData(Content.TEMPORARY_REMOVED_KEY, null)
      Disposer.register(manager, content)
    }
    manager.contents.firstOrNull { contentId(it) == state.selectedToolWindowId }?.let { manager.setSelectedContent(it, false) }
    savedTabs.clear()
  }

  override fun dispose() {
    manager.removeContentManagerListener(listener)
    savedTabs.clear()
    repositoryName = { null }
  }

  private fun contentId(content: Content): String = VcsLogContentUtil.getId(content) ?: content.tabName
}

private fun <T> repositoryTabOrder(tabs: List<T>, repositoryName: (T) -> String?): List<T> {
  val repositories = tabs.filter { repositoryName(it) != null }
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { tab: T -> repositoryName(tab)!! }.thenBy(repositoryName)).iterator()
  return tabs.map { if (repositoryName(it) == null) it else repositories.next() }
}
