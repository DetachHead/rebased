// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package com.intellij.toolWindow

import com.intellij.openapi.application.EDT
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class ToolWindowManagerTest {
  val project by projectFixture()

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `default layout`(isNewUi: Boolean) = runBlocking(Dispatchers.EDT) {
    testDefaultLayout(isNewUi = isNewUi, project = project)
  }

  @ParameterizedTest
  @ValueSource(strings = ["left", "bottom"])
  fun `button layout`(anchor: String) = runBlocking(Dispatchers.EDT) {
    testButtonLayout(isNewUi = true, anchor = ToolWindowAnchor.fromText(anchor))
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `remove button on setting an available property to false`(isNewUi: Boolean) = runBlocking(Dispatchers.EDT) {
    ToolWindowManagerTestHelper.available(isNewUi = isNewUi, project = project)
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `show tool window if it was visible last session but became available only after initial registration`(isNewUi: Boolean) {
    runBlocking(Dispatchers.EDT) {
      ToolWindowManagerTestHelper.showOnAvailable(isNewUi = isNewUi, project = project)
    }
  }

  @Test
  fun `migrates project tool window to split section in new ui layouts`() {
    val manager = ToolWindowDefaultLayoutManager(isNewUi = true)
    manager.loadState(
      ToolWindowLayoutStorageManagerState(
        activeLayoutName = ToolWindowDefaultLayoutManager.INITIAL_LAYOUT_NAME,
        layouts = mapOf(
          ToolWindowDefaultLayoutManager.INITIAL_LAYOUT_NAME to ToolWindowLayoutDescriptor(
            v2 = listOf(
              ToolWindowDescriptor(
                id = "Project",
                anchor = ToolWindowDescriptor.ToolWindowAnchor.LEFT,
                isSplit = false,
              )
            )
          )
        )
      )
    )

    val projectInfo = manager.getLayoutCopy().getInfo("Project")
    assertThat(projectInfo).isNotNull()
    assertThat(projectInfo!!.isSplit).isTrue()
  }
}
