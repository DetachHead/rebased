// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.console

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.ProjectRule
import com.intellij.util.ThrowableRunnable
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.ClassRule
import org.junit.Test

class VcsConsoleTabServiceTest {
  companion object {
    @JvmField
    @ClassRule
    val projectRule = ProjectRule()
  }

  @Test
  fun consoleContentIsCreatedLazilyAndCanBeRecreated() {
    val project = projectRule.project
    val service = VcsConsoleTabServiceImpl(project)
    Disposer.register(project, service)

    EdtTestUtil.runInEdtAndWait(ThrowableRunnable<Throwable> {
      val contentManager = ChangesViewContentManager.getInstance(project)
      assertNull(contentManager.findContent(ChangesViewContentManager.CONSOLE))

      service.showConsoleTab(false, null)
      val firstContent = contentManager.findContent(ChangesViewContentManager.CONSOLE)
      assertNotNull(firstContent)

      contentManager.removeContent(firstContent!!)
      assertNull(contentManager.findContent(ChangesViewContentManager.CONSOLE))

      service.showConsoleTab(false, null)
      val secondContent = contentManager.findContent(ChangesViewContentManager.CONSOLE)
      assertNotNull(secondContent)
      assertNotSame(firstContent, secondContent)

      contentManager.removeContent(secondContent!!)
    })
  }
}
