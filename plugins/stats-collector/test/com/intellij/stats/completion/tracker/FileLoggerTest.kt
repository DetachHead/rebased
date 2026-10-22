// Copyright 2000-2022 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.stats.completion.tracker

import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.CaretModel
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.stats.completion.storage.FilePathProvider
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.replaceService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.util.UUID
import java.util.concurrent.TimeUnit

@TestApplication
class FileLoggerTest {
  companion object {
    private val projectFixture = projectFixture()
  }

  @TestDisposable
  lateinit var testRootDisposable: Disposable

  @TempDir
  lateinit var dir: File

  private lateinit var logFile: File

  private lateinit var pathProvider: FilePathProvider

  @BeforeEach
  fun setUp() {
    logFile = File(dir, "unique_1")

    pathProvider = mock(FilePathProvider::class.java).apply {
      `when`(getStatsDataDirectory()).thenReturn(dir)
      `when`(getUniqueFile()).thenReturn(logFile)
    }

    projectFixture.get().messageBus.connect(testRootDisposable).subscribe(LookupManagerListener.TOPIC, CompletionLoggerInitializer())
  }

  @Test
  fun testLogging() {
    val fileLengthBefore = logFile.length()
    val uidProvider = mock(InstallationIdProvider::class.java).apply {
      `when`(installationId()).thenReturn(UUID.randomUUID().toString())
    }

    ApplicationManager.getApplication().replaceService(FilePathProvider::class.java, pathProvider, testRootDisposable)
    ApplicationManager.getApplication().replaceService(InstallationIdProvider::class.java, uidProvider, testRootDisposable)

    val loggerProvider = CompletionFileLoggerProvider()

    val logger = loggerProvider.newCompletionLogger(Language.ANY.displayName, shouldLogElementFeatures = true)

    val documentMock = mock(Document::class.java).apply {
      `when`(text).thenReturn("")
    }

    val editorMock = mock(Editor::class.java).apply {
      `when`(caretModel).thenReturn(mock(CaretModel::class.java))
      `when`(document).thenReturn(documentMock)
    }

    val lookup = mock(LookupImpl::class.java).apply {
      `when`(getRelevanceObjects(ArgumentMatchers.any(), ArgumentMatchers.anyBoolean())).thenReturn(emptyMap())
      `when`(items).thenReturn(emptyList())
      `when`(psiFile).thenReturn(null)
      `when`(editor).thenReturn(editorMock)
    }

    val watchService = FileSystems.getDefault().newWatchService()
    val key = dir.toPath().register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)

    logger.completionStarted(lookup, 0, true, 2, System.currentTimeMillis())

    logger.completionCancelled(true, emptyMap(), System.currentTimeMillis())
    loggerProvider.dispose()

    var attemps = 0
    while (!logFile.exists() && attemps < 5) {
      watchService.poll(15, TimeUnit.SECONDS)
      attemps += 1
    }

    key.cancel()
    watchService.close()
    assertThat(logFile.length()).isGreaterThan(fileLengthBefore)
  }
}