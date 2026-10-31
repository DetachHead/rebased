// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.j2k.k2

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.j2k.J2KNullabilityInferenceExtension
import org.jetbrains.kotlin.j2k.J2KNullityInferrer
import org.jetbrains.kotlin.j2k.Nullability

class J2KNullabilityInferenceExtensionTest : KotlinLightCodeInsightFixtureTestCase() {
    fun testNotNullVerdictYieldsToReturnedNull() = doTest(Nullability.NotNull, """
        public class C {
            public String m() { return null; }
        }
    """) { inferrer, c ->
        assertTrue(inferrer.nullableTypes.contains(c.methods[0].returnType))
    }

    fun testNotNullVerdictYieldsToAssignedNull() = doTest(Nullability.NotNull, """
        public class C {
            public String f;
            public void reset() { f = null; }
        }
    """) { inferrer, c ->
        assertTrue(inferrer.nullableTypes.contains(c.fields[0].type))
    }

    fun testNotNullVerdictWithoutNullEvidence() = doTest(Nullability.NotNull, """
        public class C {
            public String f;
            public String m() { return f; }
        }
    """) { inferrer, c ->
        assertTrue(inferrer.notNullTypes.contains(c.fields[0].type))
        assertTrue(inferrer.notNullTypes.contains(c.methods[0].returnType))
    }

    // The framework calls the method, so a null check in the body does not override the verdict
    fun testNotNullVerdictOnParameterStands() = doTest(Nullability.NotNull, """
        public class C {
            public void m(String p) { if (p == null) return; }
        }
    """) { inferrer, c ->
        assertFalse(inferrer.nullableTypes.contains(c.methods[0].parameterList.parameters[0].type))
    }

    fun testDefaultVerdictKeepsInference() = doTest(Nullability.Default, """
        public class C {
            public String f;
            public void reset() { f = null; }
        }
    """) { inferrer, c ->
        assertTrue(inferrer.nullableTypes.contains(c.fields[0].type))
    }

    private fun doTest(verdict: Nullability, javaCode: String, validator: (J2KNullityInferrer, PsiClass) -> Unit) {
        J2KNullabilityInferenceExtension.EP_NAME.point.registerExtension(FixedVerdict(verdict), testRootDisposable)
        val psiClass = myFixture.addClass(javaCode.trimIndent())
        val inferrer = J2KNullityInferrer()
        inferrer.collect(psiClass.containingFile as PsiJavaFile)
        validator(inferrer, psiClass)
    }

    private class FixedVerdict(private val verdict: Nullability) : J2KNullabilityInferenceExtension {
        override fun calculateNullability(element: PsiParameter): Nullability = verdict
        override fun calculateNullability(element: PsiMethod): Nullability = verdict
        override fun calculateNullability(element: PsiField): Nullability = verdict
        override fun calculateTypeArgumentNullability(element: PsiMethod): Nullability? = null
    }
}
