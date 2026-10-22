// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.plugins.markdown.inspections

import com.intellij.markdown.backend.inspections.MarkdownLazyBlockQuoteContinuationInspection
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import org.intellij.plugins.markdown.MarkdownBundle
import org.junit.Test
import java.util.UUID

class MarkdownLazyBlockQuoteContinuationInspectionTest : LightPlatformCodeInsightFixture4TestCase() {
  private val description = MarkdownBundle.message("markdown.lazy.block.quote.continuation.inspection.description")

  override fun setUp() {
    super.setUp()
    myFixture.enableInspections(MarkdownLazyBlockQuoteContinuationInspection())
  }

  @Test
  fun `test all lazy lines`() = doFixTest("""
    > line 1
    <weak_warning descr="$description">line 2
    line 3
    line 4</weak_warning>
  """, """
    > line 1
    > line 2
    > line 3
    > line 4
  """)

  @Test
  fun `test nested quote`() = doFixTest("""
    > > a
    <weak_warning descr="$description">b</weak_warning>
  """, """
    > > a
    > > b
  """)

  @Test
  fun `test quote in list`() = doFixTest("""
    - > a
      <weak_warning descr="$description">b</weak_warning>
  """, """
    - > a
      > b
  """)

  @Test
  fun `test list in quote`() = doFixTest("""
    > - a
    <weak_warning descr="$description">b</weak_warning>
  """, """
    > - a
    >   b
  """)

  @Test
  fun `test alert`() = doFixTest("""
    > [!NOTE]
    > a
    <weak_warning descr="$description">b</weak_warning>
  """, """
    > [!NOTE]
    > a
    > b
  """)

  @Test
  fun `test marker without space`() = doFixTest("""
    >a
    <weak_warning descr="$description">b</weak_warning>
  """, """
    >a
    >b
  """)

  @Test
  fun `test indented lazy line`() = doFixTest("""
    > a
    <weak_warning descr="$description">   b</weak_warning>
  """, """
    > a
    > b
  """)

  @Test
  fun `test quote after lazy line`() = doFixTest("""
    > a
    <weak_warning descr="$description">b</weak_warning>
    > c
  """, """
    > a
    > b
    > c
  """)

  @Test
  fun `test lazy line with inline markup`() = doFixTest("""
    > a
    <weak_warning descr="$description">**b** [link](https://example.com) `c`</weak_warning>
  """, """
    > a
    > **b** [link](https://example.com) `c`
  """)

  @Test
  fun `test blank line after quote`() = checkHighlighting("""
    > a

    b
  """)

  @Test
  fun `test empty quote line`() = checkHighlighting("""
    > a
    >
    b
  """)

  @Test
  fun `test quote ends with heading`() = checkHighlighting("""
    > # a
    b
  """)

  @Test
  fun `test quote ends with code fence`() = checkHighlighting("""
    > ```
    > a
    > ```
    b
  """)

  @Test
  fun `test block after quote`() = checkHighlighting("""
    > a
    - b

    > a
    # b

    > a
    ---

    > a
    > > b
  """)

  @Test
  fun `test new quote after quote in list`() = checkHighlighting("""
    - > a
    > b
  """)

  @Test
  fun `test lazy list line`() = checkHighlighting("""
    - a
    b

    > - a
    > b
  """)

  @Test
  fun `test air prompt`() {
    myFixture.configureByText("AirPrompt-${UUID.randomUUID()}.md", "> a\nb")
    myFixture.checkHighlighting()
  }

  private fun checkHighlighting(text: String) {
    myFixture.configureByText("some.md", text.trimIndent())
    myFixture.checkHighlighting()
  }

  private fun doFixTest(before: String, after: String) {
    checkHighlighting(before)
    val fixName = MarkdownBundle.message("markdown.lazy.block.quote.continuation.quick.fix.name")
    myFixture.launchAction(myFixture.getAllQuickFixes().single { it.text == fixName })
    myFixture.checkResult(after.trimIndent())
  }
}
