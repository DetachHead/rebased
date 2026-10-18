// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.impl

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.ContentUtilEx
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.vcs.log.VcsLogFilterCollection
import com.intellij.vcs.log.impl.VcsLogContentUtil.getToolWindow
import com.intellij.vcs.log.impl.VcsLogContentUtil.openLogTab
import com.intellij.vcs.log.impl.VcsLogContentUtil.updateLogUiName
import com.intellij.vcs.log.impl.VcsLogEditorUtil.findVcsLogUi
import com.intellij.vcs.log.ui.MainVcsLogUi
import com.intellij.vcs.log.ui.editor.DefaultVcsLogFile
import com.intellij.vcs.log.ui.editor.VcsLogVirtualFileSystem
import java.util.concurrent.CompletableFuture

internal class VcsLogTabsManager(
  private val project: Project,
  private val uiProperties: VcsLogProjectTabsProperties,
  private val logManager: VcsLogManager,
) : Disposable {
  private val futureToolWindow: CompletableFuture<ToolWindow> = CompletableFuture()
  private var filesListenerInstalled = false
  private var toolWindowListenerInstalled = false
  private var isDisposed: Boolean = false

  @RequiresEdt
  fun createTabs() {
    val savedTabs = uiProperties.tabs
    if (savedTabs.isEmpty()) return

    val editorTabs = mutableListOf<String>()
    val toolWindowTabs = mutableListOf<String>()
    for ((id, location) in savedTabs) {
      when (location) {
        VcsLogTabLocation.EDITOR -> editorTabs.add(id)
        VcsLogTabLocation.TOOL_WINDOW -> toolWindowTabs.add(id)
        else -> LOG.warn("Reopening standalone tabs is not supported")
      }
    }
    closeTabsWithoutFilters(editorTabs)
    closeTabsWithoutFilters(toolWindowTabs)

    if (editorTabs.isNotEmpty()) {
      invokeLater(ModalityState.nonModal()) {
        if (logManager.isDisposed) return@invokeLater
        LOG.debug("Reopening editor tabs with ids: $editorTabs")
        val editorManager = FileEditorManager.getInstance(project)
        editorTabs.filter { it in uiProperties.tabs }.forEach { id ->
          if (editorManager.openFiles.none { it is DefaultVcsLogFile && it.tabId == id }) {
            openEditorLogTab(id, false, null)
          }
        }
      }
    }

    if (!uiProperties.shouldShowInEditor()) {
      if (toolWindowTabs.isNotEmpty()) {
        futureToolWindow.thenAccept { toolWindow ->
          if (!LOG.assertTrue(!logManager.isDisposed, "Attempting to open tabs on disposed VcsLogManager")) return@thenAccept
          LOG.debug("Reopening toolwindow tabs with ids: $toolWindowTabs")
          toolWindowTabs.filter { it in uiProperties.tabs }.forEach { openToolWindowLogTab(toolWindow, it, false, null) }
        }
      }

      ToolWindowManager.getInstance(project).invokeLater {
        if (logManager.isDisposed) return@invokeLater

        val toolWindow = getToolWindow(project) ?: run {
          LOG.error("Could not find tool window by id ${ChangesViewContentManager.TOOLWINDOW_ID}")
          return@invokeLater
        }

        if (toolWindow.isVisible) {
          futureToolWindow.complete(toolWindow)
        }
      }
    }
  }

  private fun closeTabsWithoutFilters(tabs: MutableList<String>) {
    val shouldRestoreWithoutFilters = Registry.`is`("vcs.log.tabs.restore.without.filters", false)
    if (shouldRestoreWithoutFilters) return

    val iter = tabs.iterator()
    // restore one tab without filters for better UX (don't make the user create a new empty tab themselves)
    var oneTabPreserved = false
    while (iter.hasNext()) {
      val tabId = iter.next()
      if (uiProperties.checkTabHasFilters(tabId) ||
          tabId in uiProperties.state.repositoryWorkspace?.preservedLogIds.orEmpty() ||
          uiProperties.createProperties(tabId)[MainVcsLogUiProperties.ROOT_SCOPE].isNotEmpty()) {
        continue
      }
      else if (!oneTabPreserved) {
        oneTabPreserved = true
      }
      else {
        iter.remove()
        uiProperties.removeTab(tabId)
      }
    }
  }

  override fun dispose() {
    isDisposed = true
  }

  @RequiresEdt
  fun toolWindowShown(toolWindow: ToolWindow) {
    futureToolWindow.complete(toolWindow)
  }

  @RequiresEdt
  fun openAnotherLogTab(
    filters: VcsLogFilterCollection,
    location: VcsLogTabLocation,
    root: VirtualFile? = null,
  ): MainVcsLogUi {
    require(!isDisposed) { "Already disposed" }
    val tabId = logManager.generateNewLogId()
    uiProperties.resetState(tabId)
    if (root != null) {
      uiProperties.createProperties(tabId)[MainVcsLogUiProperties.ROOT_SCOPE] = root.url
    }
    val focus = root == null
    if (location === VcsLogTabLocation.EDITOR) {
      val editors = openEditorLogTab(tabId, focus, filters)
      return findVcsLogUi(editors, MainVcsLogUi::class.java)!!
    }
    else if (location === VcsLogTabLocation.TOOL_WINDOW) {
      val toolWindow = VcsLogContentUtil.getToolWindowOrThrow(project)
      futureToolWindow.complete(toolWindow)
      return openToolWindowLogTab(toolWindow, tabId, focus, filters)
    }
    throw UnsupportedOperationException("Only log in editor or tool window is supported")
  }

  private fun openEditorLogTab(tabId: String, focus: Boolean, filters: VcsLogFilterCollection?): Array<FileEditor> {
    val file = VcsLogVirtualFileSystem.Holder.getInstance().createVcsLogFile(project, tabId, filters)
    installFilesListener()
    uiProperties.addTab(tabId, VcsLogTabLocation.EDITOR)
    return FileEditorManagerEx.getInstanceEx(project)
      .openFile(file, null, FileEditorOpenOptions(selectAsCurrent = focus, requestFocus = focus, reuseOpen = true))
      .allEditors.toTypedArray()
  }

  private fun installFilesListener() {
    if(filesListenerInstalled) return
    project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
      override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        if (file !is DefaultVcsLogFile) return
        // restore the tab after project/log is recreated
        if (this@VcsLogTabsManager.project.isDisposed) return
        uiProperties.removeTab(file.tabId)
      }
    })
    filesListenerInstalled = true
  }

  private fun openToolWindowLogTab(toolWindow: ToolWindow, tabId: String, focus: Boolean,
                                   filters: VcsLogFilterCollection?): MainVcsLogUi {
    val ui = logManager.createLogUi(tabId, filters)
    val repositoryScoped = uiProperties.createProperties(tabId)[MainVcsLogUiProperties.ROOT_SCOPE].isNotEmpty()
    if (repositoryScoped) {
      Disposer.register(ui) {
        if (!logManager.isDisposed && !project.isDisposed) uiProperties.removeTab(tabId)
      }
      VcsLogContentUtil.openRepositoryLogTab(logManager, toolWindow, ui)
    }
    else {
      openLogTab(logManager, toolWindow, VcsLogContentUtil.DEFAULT_TAB_GROUP_ID, ui,
                 { it: MainVcsLogUi -> VcsLogTabsUtil.generateShortDisplayName(it) }, focus)
    }
    ui.onDisplayNameChange { updateLogUiName(project, ui) }

    installContentListener(toolWindow)
    uiProperties.addTab(tabId, VcsLogTabLocation.TOOL_WINDOW)

    return ui
  }

  private fun installContentListener(toolWindow: ToolWindow) {
    if (toolWindowListenerInstalled) return
    val listener = object : ContentManagerListener {
      override fun contentRemoved(event: ContentManagerEvent) {
        if (event.content.getUserData(Content.TEMPORARY_REMOVED_KEY) == true) return
        // restore the tab after project/log is recreated
        if (this@VcsLogTabsManager.project.isDisposed) return
        val id = VcsLogContentUtil.getId(event.content)
        if (id != null) {
          uiProperties.removeTab(id)
        }
      }
    }
    toolWindow.addContentManagerListener(listener)
    Disposer.register(this) {
      if (!toolWindow.isDisposed) {
        toolWindow.contentManagerIfCreated?.removeContentManagerListener(listener)
      }
    }
    toolWindowListenerInstalled = true
  }

  fun getTabs(): Set<String> = uiProperties.tabs.keys

  @RequiresEdt
  fun resetTabs() {
    val editorManager = FileEditorManager.getInstance(project)
    editorManager.openFiles.filterIsInstance<DefaultVcsLogFile>().filter { it.tabId.isNotEmpty() }
      .forEach { editorManager.closeFile(it) }
    getToolWindow(project)?.contentManagerIfCreated?.let { manager ->
      val components = manager.contentComponentSequence().map { it.second }.filter {
        val ui = VcsLogContentUtil.getLogUi(it)
        ui != null && ui.id != VcsLogManager.MAIN_LOG_ID
      }.toList()
      components.forEach { component -> ContentUtilEx.closeContentTab(manager) { it === component } }
    }
    uiProperties.tabs.keys.forEach(uiProperties::removeTab)
  }

  @RequiresEdt
  fun closeTab(tabId: String) {
    val editorManager = FileEditorManagerEx.getInstanceEx(project)
    val file = editorManager.openFiles.filterIsInstance<DefaultVcsLogFile>().firstOrNull { it.tabId == tabId }
    if (file != null) {
      if (editorManager.windows.any { it.isFileOpen(file) && it.isFilePinned(file) }) return
      editorManager.closeFile(file)
    }
    else {
      val manager = getToolWindow(project)?.contentManagerIfCreated
      val tab = manager?.contentComponentSequence()?.firstOrNull { (_, component) ->
        VcsLogContentUtil.getLogUi(component)?.id == tabId
      }
      if (manager != null && tab != null) {
        val (content, component) = tab
        if (content.isPinned || !content.isCloseable) return
        if (!ContentUtilEx.closeContentTab(manager) { it === component }) return
      }
    }
    uiProperties.removeTab(tabId)
  }

  fun getTabs(location: VcsLogTabLocation): Set<String> =
    uiProperties.tabs.filterValues { it === location }.keys

  companion object {
    private val LOG = Logger.getInstance(VcsLogTabsManager::class.java)

    fun MainVcsLogUi.onDisplayNameChange(block: () -> Unit) {
      filterUi.addFilterListener { block() }
      properties.onPropertyChange(this) {
        if (it == MainVcsLogUiProperties.GRAPH_OPTIONS || it == MainVcsLogUiProperties.SHOW_REPOSITORY_PATH) block()
      }
    }
  }
}
