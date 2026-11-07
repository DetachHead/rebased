// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.k2.intentions.tests

import org.jetbrains.kotlin.idea.base.test.IgnoreTests
import org.jetbrains.kotlin.idea.fir.K2DirectiveBasedActionUtils
import org.jetbrains.kotlin.idea.intentions.AbstractIntentionTestBase
import org.jetbrains.kotlin.idea.test.KotlinLightProjectDescriptor
import org.jetbrains.kotlin.idea.test.KotlinWithJdkAndRuntimeLightProjectDescriptor
import org.jetbrains.kotlin.idea.test.runAll
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.util.invalidateCaches
import java.io.File

abstract class AbstractK2IntentionTest : AbstractIntentionTestBase() {

    override fun getDefaultProjectDescriptor(): KotlinLightProjectDescriptor = KotlinWithJdkAndRuntimeLightProjectDescriptor.getInstance()

    override fun doTest(unused: String) {
        IgnoreTests.runTestIfNotDisabledByFileDirective(dataFile().toPath(), IgnoreTests.DIRECTIVES.IGNORE_K2) {
            super.doTest(unused)
        }
    }

    override val skipErrorsBeforeCheckDirectives: List<String>
        get() = super.skipErrorsBeforeCheckDirectives + K2DirectiveBasedActionUtils.DISABLE_K2_ERRORS_DIRECTIVE

    override val skipErrorsAfterCheckDirectives: List<String>
        get() = super.skipErrorsAfterCheckDirectives + K2DirectiveBasedActionUtils.DISABLE_K2_ERRORS_DIRECTIVE

    override fun checkForErrorsBefore(mainFile: File, ktFile: KtFile, fileText: String) {
        K2DirectiveBasedActionUtils.checkForErrorsBefore(mainFile, ktFile, fileText)
    }

    override fun checkForErrorsAfter(mainFile: File, ktFile: KtFile, fileText: String) {
        K2DirectiveBasedActionUtils.checkForErrorsAfter(mainFile, ktFile, fileText)
    }

    override fun tearDown() {
        runAll(
            { project.invalidateCaches() },
            { super.tearDown() },
        )
    }
}