// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.stats.completion.tracker

import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.pom.java.LanguageLevel
import com.intellij.stats.completion.network.service.RequestService
import com.intellij.stats.completion.network.service.ResponseData
import com.intellij.stats.completion.sender.StatisticSenderImpl
import com.intellij.stats.completion.storage.FilePathProvider
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.PerformanceUnitTest
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.javaCodeInsightFixture
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.setUpJdk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@PerformanceUnitTest
@TestApplication
class PerformanceTests {
  companion object {
    private val projectFixture = projectFixture(openAfterCreation = true)
  }

  private val tempDirFixture = tempPathFixture()
  private val moduleFixture = projectFixture.moduleFixture(tempDirFixture, addPathToSourceRoot = true)
  private val myFixture by javaCodeInsightFixture(projectFixture, tempDirFixture)

  @TestDisposable
  lateinit var testRootDisposable: Disposable

  private lateinit var pathProvider: FilePathProvider

  private val runnable = "interface Runnable { void run();  void notify(); void wait(); void notifyAll(); }"
  private val text = """
class Test {
    public void run() {
        Runnable r = new Runnable() {
            public void run() {}
        };
        r<caret>
    }
}
"""

  @BeforeEach
  fun setUp(): Unit = onEdt {
    val project = projectFixture.get()
    val module = moduleFixture.get()
    setUpJdk(LanguageLevel.JDK_1_6, project, module, testRootDisposable)
    IdeaTestUtil.setModuleLanguageLevel(module, LanguageLevel.JDK_1_6, testRootDisposable)

    pathProvider = ApplicationManager.getApplication().getService(FilePathProvider::class.java)
    project.messageBus.connect(testRootDisposable).subscribe(LookupManagerListener.TOPIC, CompletionLoggerInitializer())
  }

  @AfterEach
  fun tearDown() {
    CompletionLoggerProvider.getInstance().dispose()
    val statsDir = pathProvider.getStatsDataDirectory()
    statsDir.deleteRecursively()
  }

  @Test
  fun `test do not block EDT on data send`() {
    onEdt {
      myFixture.configureByText("Test.java", text)
      myFixture.addClass(runnable)
    }

    val requestService = slowRequestService()

    val file = pathProvider.getUniqueFile()
    file.writeText("Some existing data to send")

    val app = ApplicationManager.getApplication()
    app.replaceService(FilePathProvider::class.java, pathProvider, testRootDisposable)
    app.replaceService(RequestService::class.java, requestService, testRootDisposable)

    val sender = StatisticSenderImpl()

    val isSendFinished = AtomicBoolean(false)

    val lock = Object()
    app.executeOnPooledThread {
      synchronized(lock, { lock.notify() })
      sender.sendStatsData("")
      isSendFinished.set(true)
    }
    synchronized(lock, { lock.wait() })

    onEdt {
      myFixture.type('.')
      myFixture.completeBasic()
      myFixture.type("xx")
    }

    assertFalse(isSendFinished.get())
  }

  private fun onEdt(block: () -> Unit): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      block()
    }
  }

  private fun slowRequestService(): RequestService {
    return mock(RequestService::class.java).apply {
      `when`(postZipped(anyString(), any() ?: File("."))).then {
        Thread.sleep(10000)
        ResponseData(200)
      }
    }
  }

}
