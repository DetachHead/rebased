// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileEditor

import com.intellij.openapi.application.UiWithModelAccess
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.impl.text.AsyncEditorLoader
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.platform.util.coroutines.childScope
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@TestApplication
@Timeout(30)
class AsyncEditorReadinessTest {
  private val projectFixture = projectFixture()

  @Test
  fun `canceling a wait preserves loading and loader disposal cancels other waits`(): Unit =
    timeoutRunBlocking(context = Dispatchers.UiWithModelAccess) {
      val project = projectFixture.get()
      val manager = FileEditorManager.getInstance(project)
      val factory = EditorFactory.getInstance()
      val editor = factory.createEditor(factory.createDocument("text"))
      val loaderScope = childScope("Test editor loading")
      val loaderJob = loaderScope.coroutineContext.job
      try {
        val loader = AsyncEditorLoader(project, TextEditorProvider.getInstance(), loaderScope)
        editor.putUserData(AsyncEditorLoader.ASYNC_LOADER, loader)
        val first = async(start = CoroutineStart.UNDISPATCHED) { manager.awaitLoaded(editor) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { manager.awaitLoaded(editor) }
        assertThat(first.isCompleted).isFalse()
        assertThat(second.isCompleted).isFalse()

        first.cancelAndJoin()
        assertThat(loaderJob.isActive).isTrue()
        assertThat(second.isCompleted).isFalse()

        loader.dispose()
        loaderJob.join()
        second.join()
        assertThat(second.isCancelled).isTrue()
      }
      finally {
        loaderJob.cancel()
        factory.releaseEditor(editor)
      }
    }

  @Test
  fun `loaded editors are ready and disposed editors cancel the wait`(): Unit =
    timeoutRunBlocking(context = Dispatchers.UiWithModelAccess) {
      val manager = FileEditorManager.getInstance(projectFixture.get())
      val factory = EditorFactory.getInstance()
      val editor = factory.createEditor(factory.createDocument("text"))
      try {
        manager.awaitLoaded(editor)
      }
      finally {
        factory.releaseEditor(editor)
      }
      val wait = async { manager.awaitLoaded(editor) }
      wait.join()
      assertThat(wait.isCancelled).isTrue()
    }
}
