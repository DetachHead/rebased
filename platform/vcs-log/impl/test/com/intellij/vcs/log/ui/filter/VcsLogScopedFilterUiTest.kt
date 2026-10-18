// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.ui.filter

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.vcs.log.VcsLogFilterCollection
import com.intellij.vcs.log.VcsLogProvider
import com.intellij.vcs.log.data.VcsLogData
import com.intellij.vcs.log.impl.MainVcsLogUiProperties
import com.intellij.vcs.log.impl.VcsLogProjectTabsProperties
import com.intellij.vcs.log.ui.VcsLogColorManager
import com.intellij.vcs.log.visible.filters.VcsLogFilterObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@TestApplication
@Timeout(30)
internal class VcsLogScopedFilterUiTest {
  @Test
  fun `clearing filters preserves only the repository scope`(
    @TestDisposable disposable: Disposable,
  ): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      for (scoped in listOf(false, true)) {
        val root = LightVirtualFile("root")
        val other = LightVirtualFile("other")
        val data = mock<VcsLogData>()
        whenever(data.logProviders).thenReturn(mapOf(root to mock<VcsLogProvider>(), other to mock<VcsLogProvider>()))
        whenever(data.storage).thenReturn(mock())
        val properties = VcsLogProjectTabsProperties().createProperties("test")
        if (scoped) properties[MainVcsLogUiProperties.ROOT_SCOPE] = root.url
        val ui = VcsLogClassicFilterUi(data, {}, properties, mock<VcsLogColorManager>(),
                                     VcsLogFilterObject.EMPTY_COLLECTION, disposable)
        assertThat(ui.filters.get(VcsLogFilterCollection.BRANCH_FILTER)).isNull()
        ui.filters = VcsLogFilterObject.collection(VcsLogFilterObject.fromBranch("preview"), VcsLogFilterObject.fromRoots(listOf(other)))
        assertThat(ui.filters.get(VcsLogFilterCollection.ROOT_FILTER)?.roots).containsExactly(if (scoped) root else other)
        assertThat(ui.filters.get(VcsLogFilterCollection.BRANCH_FILTER)?.textPresentation).containsExactly("preview")
        ui.clearFilters()

        if (scoped) assertThat(ui.filters.get(VcsLogFilterCollection.ROOT_FILTER)?.roots).containsExactly(root)
        else assertThat(ui.filters.isEmpty).isTrue()
        assertThat(ui.filters.get(VcsLogFilterCollection.BRANCH_FILTER)).isNull()
      }
    }
  }
}
