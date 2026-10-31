// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.navigation.impl.SourceNavigationRequest
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.light.LightElement
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

@TestApplication
internal class NavigationGutterIconRendererTest {
  companion object {
    private val project = projectFixture()
    private val file = project.moduleFixture().sourceRootFixture().psiFileFixture("target.txt", "hello world")
  }

  @Test
  fun `popup fallback resolves a non-navigatable target offset in background`(): Unit = timeoutRunBlocking {
    val navigatable = readAction {
      val psiFile = file.get()
      val leaf = requireNotNull(psiFile.findElementAt(6))
      val target = Proxy.newProxyInstance(PsiElement::class.java.classLoader, arrayOf(PsiElement::class.java)) { proxy, method, args ->
        when (method.name) {
          "getNavigationElement" -> proxy
          "getTextOffset" -> {
            assertFalse(ApplicationManager.getApplication().isDispatchThread)
            assertTrue(ApplicationManager.getApplication().isReadAccessAllowed)
            6
          }
          else -> method.invoke(leaf, *(args ?: emptyArray()))
        }
      } as PsiElement
      assertFalse(target is Navigatable)
      val origin = object : LightElement(psiFile.manager, psiFile.language) {
        override fun getContainingFile(): PsiFile = psiFile
        override fun getTextRange(): TextRange = TextRange(6, 11)
        override fun toString(): String = "Gutter target"
      }
      origin.setNavigationElement(target)
      NavigationGutterIconRenderer.createPopupNavigatable(origin)
    }

    readAction {
      val request = navigatable.navigationRequest() as? SourceNavigationRequest
      assertNotNull(request)
      assertEquals(file.get().virtualFile, request!!.file)
      assertEquals(6, request.offsetMarker?.startOffset)
    }
  }
}
