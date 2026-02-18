// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.impl

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsBundle
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider
import com.intellij.ui.content.Content
import com.intellij.vcs.console.VcsConsoleTabService
import java.util.function.Supplier

internal class VcsConsoleContentProvider(private val project: Project) : ChangesViewContentProvider {
  override fun initTabContent(content: Content) {
    val consoleTabService = VcsConsoleTabService.getInstance(project)
    consoleTabService.setupConsoleContentTab(content)
    consoleTabService.showConsoleTabAndScrollToTheEnd()
  }

  internal class DisplayNameSupplier : Supplier<String> {
    override fun get(): String = VcsBundle.message("vcs.console.toolwindow.display.name")
  }
}