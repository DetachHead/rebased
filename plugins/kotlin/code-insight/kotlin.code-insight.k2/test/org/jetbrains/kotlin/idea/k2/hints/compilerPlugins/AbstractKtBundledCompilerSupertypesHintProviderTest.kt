// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.hints.compilerPlugins

import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.testFramework.common.runAll
import com.intellij.testFramework.rules.TempDirectory
import org.jetbrains.kotlin.idea.codeInsight.hints.AbstractKotlinInlayHintsProviderTest
import org.jetbrains.kotlin.idea.fir.extensions.KotlinK2BundledCompilerPlugins
import org.jetbrains.kotlin.idea.k2.codeinsight.hints.compilerPlugins.KtCompilerSupertypesHintProvider

abstract class AbstractKtCompilerSupertypesHintProviderTestBase(val treatAsThirdParty: Boolean) : AbstractKotlinInlayHintsProviderTest() {
    val tempDir = TempDirectory()

    override fun setUp() {
        super.setUp()
        tempDir.before(name)
    }

    override fun tearDown() {
        runAll({ tempDir.after() }, { super.tearDown() })
    }

    override fun inlayHintsProvider(): InlayHintsProvider =
        KtCompilerSupertypesHintProvider()

    override fun doTest(testPath: String) {
        val plugin = KotlinK2BundledCompilerPlugins.KOTLINX_SERIALIZATION_COMPILER_PLUGIN

        module.withCompilerPlugin(
            plugin,
            pluginJar = if (treatAsThirdParty) createThirdPartyCompilerPluginJar(plugin, tempDir) else plugin.bundledJarLocation,
        ) {
            super.doTest(testPath)
        }
    }
}

abstract class AbstractKtBundledCompilerSupertypesHintProviderTest :
    AbstractKtCompilerSupertypesHintProviderTestBase(treatAsThirdParty = false)

abstract class AbstractKtThirdPartyCompilerSupertypesHintProviderTest :
    AbstractKtCompilerSupertypesHintProviderTestBase(treatAsThirdParty = true)