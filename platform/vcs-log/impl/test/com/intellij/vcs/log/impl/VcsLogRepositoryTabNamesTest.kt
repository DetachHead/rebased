// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.impl

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.vcs.log.impl.VcsLogTabsManager.Companion.onDisplayNameChange
import com.intellij.vcs.log.ui.MainVcsLogUi
import com.intellij.vcs.log.ui.VcsLogUiEx
import com.intellij.vcs.log.ui.filter.VcsLogFilterUiEx
import com.intellij.vcs.log.visible.filters.VcsLogFilterObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@TestApplication
internal class VcsLogRepositoryTabNamesTest {
  @Test
  fun `repository tabs keep their names when filters change`() {
    val properties = VcsLogProjectTabsProperties().createProperties("repository")
    properties[MainVcsLogUiProperties.ROOT_SCOPE] = "file://C:/repos/Child"
    val filters = mock<VcsLogFilterUiEx>()
    val ui = mock<VcsLogUiEx>()
    whenever(ui.properties).thenReturn(properties)
    whenever(ui.filterUi).thenReturn(filters)

    for (branch in listOf("preview", "master")) {
      whenever(filters.filters).thenReturn(VcsLogFilterObject.collection(VcsLogFilterObject.fromBranch(branch)))
      assertThat(VcsLogTabsUtil.generateDisplayName(ui)).isEqualTo("Child")
      assertThat(VcsLogTabsUtil.generateShortDisplayName(ui)).isEqualTo("Child")
    }
  }

  @Test
  @Timeout(30)
  fun `the path option updates titles and survives a restart`(@TestDisposable disposable: Disposable): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = VcsLogProjectTabsProperties()
      val properties = tabs.createProperties("repository")
      properties[MainVcsLogUiProperties.ROOT_SCOPE] = "file://C:/repos/My Repository"
      val ui = mock<MainVcsLogUi>()
      Disposer.register(disposable, ui)
      whenever(ui.properties).thenReturn(properties)
      whenever(ui.filterUi).thenReturn(mock())
      val titles = mutableListOf<String>()
      ui.onDisplayNameChange { titles.add(VcsLogTabsUtil.generateDisplayName(ui)) }

      properties[MainVcsLogUiProperties.SHOW_REPOSITORY_PATH] = true
      assertThat(VcsLogTabsUtil.getRepositoryName(properties)).isEqualTo("My Repository")
      val restored = VcsLogProjectTabsProperties()
      restored.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(tabs.state), VcsLogProjectTabsProperties.State::class.java))
      assertThat(VcsLogTabsUtil.getRepositoryDisplayName(restored.createProperties("repository")))
        .isEqualTo("C:/repos/My Repository")
      properties[MainVcsLogUiProperties.SHOW_REPOSITORY_PATH] = false

      assertThat(titles).containsExactly("C:/repos/My Repository", "My Repository")
    }
  }
}
