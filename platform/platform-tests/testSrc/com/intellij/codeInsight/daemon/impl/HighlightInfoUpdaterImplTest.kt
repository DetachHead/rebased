// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.impl.CoreProgressManager
import com.intellij.openapi.progress.impl.ProgressManagerImpl
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.FakePsiElement
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertSame

@TestApplication
internal class HighlightInfoUpdaterImplTest {
  @Test
  fun `diagnostics precede other elements and sort by maximum severity`() {
    val elements = List(7) { element() }
    val customSeverity = HighlightSeverity("CUSTOM", HighlightSeverity.ERROR.myVal + 1)
    val highlights = mapOf(
      elements[1] to listOf(info(HighlightSeverity.WARNING)),
      elements[2] to listOf(info(HighlightSeverity.WARNING), info(HighlightSeverity.ERROR)),
      elements[4] to listOf(info(HighlightSeverity.WARNING)),
      elements[5] to listOf(info(customSeverity)),
      elements[6] to emptyList(),
    )

    val sorted = HighlightInfoUpdaterImpl.sortByPsiElementFertility(elements, highlights)

    assertEquals(listOf(elements[5], elements[2], elements[1], elements[4], elements[6], elements[0], elements[3]), sorted)
    assertThrows<UnsupportedOperationException> { (sorted as MutableList<PsiElement>).clear() }
    assertEquals(7, elements.size)
  }

  @Test
  fun `elements without diagnostics retain the original list`() {
    val elements = List(100) { element() }

    assertSame(elements, HighlightInfoUpdaterImpl.sortByPsiElementFertility(elements, emptyMap()))
    assertSame(elements, HighlightInfoUpdaterImpl.sortByPsiElementFertility(
      elements, mapOf(element() to listOf(info(HighlightSeverity.ERROR))),
    ))
  }

  @Test
  fun `diagnostics are looked up once per input element and duplicates are preserved`() {
    val first = element()
    val second = element()
    val elements = listOf(second, first, second, first)
    var lookups = 0
    val highlights = object : HashMap<PsiElement, List<HighlightInfo>>() {
      override fun get(key: PsiElement): List<HighlightInfo>? {
        lookups++
        return super.get(key)
      }
    }
    highlights[first] = listOf(info(HighlightSeverity.ERROR))
    highlights[second] = listOf(info(HighlightSeverity.WARNING))

    val sorted = HighlightInfoUpdaterImpl.sortByPsiElementFertility(elements, highlights)

    assertEquals(listOf(first, first, second, second), sorted)
    assertEquals(elements.size, lookups)
    assertEquals(listOf(second, first, second, first), elements)
  }

  @Test
  fun `cancellation stops scanning elements`() {
    val indicator = ProgressIndicatorBase()
    val elements = List(100) { element() }
    var lookups = 0
    val highlights = object : HashMap<PsiElement, List<HighlightInfo>>() {
      override fun get(key: PsiElement): List<HighlightInfo>? {
        if (++lookups == 10) indicator.cancel()
        return super.get(key)
      }
    }
    highlights[elements[0]] = listOf(info(HighlightSeverity.ERROR))

    assertCanceled(indicator) {
      HighlightInfoUpdaterImpl.sortByPsiElementFertility(elements, highlights)
    }
    assertEquals(10, lookups)
  }

  @Test
  fun `cancellation stops scanning diagnostics`() {
    val indicator = ProgressIndicatorBase()
    val element = element()
    val info = info(HighlightSeverity.ERROR)
    var reads = 0
    val infos = object : AbstractList<HighlightInfo>() {
      override val size: Int = 100

      override fun get(index: Int): HighlightInfo {
        if (++reads == 10) indicator.cancel()
        return info
      }
    }

    assertCanceled(indicator) {
      HighlightInfoUpdaterImpl.sortByPsiElementFertility(listOf(element), mapOf(element to infos))
    }
    assertEquals(10, reads)
  }

  @Test
  fun `cancellation stops sorting elements with diagnostics`() {
    val stackWalker = StackWalker.getInstance()
    val testThread = Thread.currentThread()
    val indicator = ProgressIndicatorBase()
    val hook = CoreProgressManager.CheckCanceledHook {
      if (Thread.currentThread() === testThread && stackWalker.walk { frames -> frames.anyMatch { it.methodName == "sort" } }) {
        indicator.cancel()
        true
      }
      else {
        false
      }
    }
    val elements = List(100) { element() }
    val info = info(HighlightSeverity.ERROR)
    val highlights = elements.associateWith { listOf(info) }

    (ProgressManager.getInstance() as ProgressManagerImpl).runWithHook(hook) {
      assertCanceled(indicator) {
        HighlightInfoUpdaterImpl.sortByPsiElementFertility(elements, highlights)
      }
    }
  }

  private fun element(): PsiElement = object : FakePsiElement() {
    override fun getParent(): PsiElement? = null
    override fun getName(): String = "element"
  }

  private fun info(severity: HighlightSeverity): HighlightInfo =
    HighlightInfo.newHighlightInfo(HighlightInfoType.INFORMATION).range(0, 1).severity(severity).createUnconditionally()

  private fun assertCanceled(indicator: ProgressIndicator, action: () -> Unit) {
    assertThrows<ProcessCanceledException> {
      ProgressManager.getInstance().runProcess({ action() }, indicator)
    }
  }
}
