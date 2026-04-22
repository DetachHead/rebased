// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.project.impl

import com.intellij.openapi.util.registry.RegistryManager
import com.intellij.openapi.util.registry.RegistryValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IdeProjectFrameAllocatorTest {
  @Test
  fun `startup does not open project view when registry key is disabled`() {
    assertThat(shouldOpenProjectViewOnStartup(TestRegistryManager(enabled = false))).isFalse()
  }

  @Test
  fun `startup does not open project view when tool window is configured with doNotActivateOnStart`() {
    assertThat(shouldOpenProjectViewOnStartup(TestRegistryManager(enabled = true))).isFalse()
  }

  private class TestRegistryManager(private val enabled: Boolean) : RegistryManager {
    override fun `is`(key: String): Boolean = enabled

    override fun intValue(key: String): Int = throw UnsupportedOperationException()

    override fun stringValue(key: String): String? = throw UnsupportedOperationException()

    override fun intValue(key: String, defaultValue: Int): Int = throw UnsupportedOperationException()

    override fun get(key: String): RegistryValue = throw UnsupportedOperationException()

    override fun resetValueChangeListener() {}

    override suspend fun awaitRegistryLoad() {}
  }
}
