// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package com.intellij.openapi.fileEditor.impl

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import javax.swing.JPanel

@TestApplication
@RunInEdt(writeIntent = true)
internal class EditorHostAdapterTest {
  @Test
  fun mainSplittersHasOneStableParent() {
    val mainSplitters = JPanel()
    val adapter = EditorHostAdapter(mainSplitters)

    assertThat(mainSplitters.parent).isSameAs(adapter)
    assertThat(adapter.componentCount).isEqualTo(1)
    assertThat(adapter.getComponent(0)).isSameAs(mainSplitters)
  }

  @Test
  fun movingOuterHostDoesNotReparentMainSplitters() {
    val mainSplitters = JPanel()
    val adapter = EditorHostAdapter(mainSplitters)
    val firstHost = JPanel(BorderLayout())
    val secondHost = JPanel(BorderLayout())

    firstHost.add(adapter, BorderLayout.CENTER)
    assertThat(mainSplitters.parent).isSameAs(adapter)

    secondHost.add(adapter, BorderLayout.CENTER)
    assertThat(mainSplitters.parent).isSameAs(adapter)
    assertThat(adapter.parent).isSameAs(secondHost)
  }
}
