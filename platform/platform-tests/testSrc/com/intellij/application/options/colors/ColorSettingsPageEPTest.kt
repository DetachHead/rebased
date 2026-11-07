// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.application.options.colors

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.options.colors.ColorSettingsPages
import com.intellij.psi.codeStyle.DisplayPriority
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.util.PlatformUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import javax.swing.Icon

/**
 * [ColorSettingsPageEP] states a colour page as a declaration, so the settings tree reads the id, the name and
 * the order and creates no page.
 *
 * This test covers the two halves of the point. The registry half proves that a declared page still reaches
 * `ColorSettingsPages.getRegisteredPages()`, which four callers outside Settings read. The declaration half
 * proves that the bean answers with no page instance, and that a declaration with no name reports an error.
 */
@TestApplication
internal class ColorSettingsPageEPTest {

  @TestDisposable
  private lateinit var disposable: Disposable

  @Test
  fun `a declared page joins the registered pages`() {
    val legacy = TestPage("legacy", "A legacy page")
    maskLegacyPages(legacy)
    maskDeclaredPages(declaration(displayName = "A declared page"))

    val pages = ColorSettingsPages.getInstance().registeredPages

    assertThat(pages).hasSize(2)
    assertThat(pages[0]).isSameAs(legacy)
    assertThat(pages[1]).isInstanceOf(CountedTestPage::class.java)
  }

  @Test
  fun `the legacy point alone answers as before`() {
    val legacy = TestPage("legacy", "A legacy page")
    maskLegacyPages(legacy)
    maskDeclaredPages()

    assertThat(ColorSettingsPages.getInstance().registeredPages).containsExactly(legacy)
  }

  @Test
  fun `the same page answers every call`() {
    maskLegacyPages()
    maskDeclaredPages(declaration(displayName = "A declared page"))

    val first = ColorSettingsPages.getInstance().registeredPages.single()
    val second = ColorSettingsPages.getInstance().registeredPages.single()

    assertThat(first).isSameAs(second)
  }

  /** The tree reads these four values per node, so none of them may create the page. */
  @Test
  fun `the declaration answers the id the name and the order with no page`() {
    val created = CountedTestPage.created
    val ep = declaration(id = "declared-id", displayName = "A declared page",
                         priority = DisplayPriority.COMMON_SETTINGS, groupWeight = 7)

    assertThat(ep.pageId).isEqualTo("declared-id")
    assertThat(ep.pageName).isEqualTo("A declared page")
    assertThat(ep.priority).isEqualTo(DisplayPriority.COMMON_SETTINGS)
    assertThat(ep.groupWeight).isEqualTo(7)
    assertThat(CountedTestPage.created).isEqualTo(created)

    assertThat(ep.instance).isInstanceOf(CountedTestPage::class.java)
    assertThat(CountedTestPage.created).isEqualTo(created + 1)
  }

  /** A declaration that states no id answers the implementation class name, which is what the page returns. */
  @Test
  fun `a declaration without an id answers the class name`() {
    val ep = declaration(displayName = "A declared page")

    assertThat(ep.pageId).isEqualTo(CountedTestPage::class.java.name)
    assertThat(ep.pageId).isEqualTo(ep.instance.id)
  }

  /**
   * A page whose band depends on the product states `keyLanguageIn`, and the declaration answers the band
   * of the running product with no page. This is the shape of
   * `PlatformUtils.isWebStorm() ? KEY_LANGUAGE_SETTINGS : LANGUAGE_SETTINGS`.
   */
  @Test
  fun `a named product raises the band of the declaration`() {
    val created = CountedTestPage.created
    val ep = declaration(displayName = "A declared page", keyLanguageIn = "WebStorm,PhpStorm")

    withPlatformPrefix(PlatformUtils.WEB_PREFIX) {
      assertThat(ep.priority).isEqualTo(DisplayPriority.KEY_LANGUAGE_SETTINGS)
    }
    withPlatformPrefix(PlatformUtils.PHP_PREFIX) {
      assertThat(ep.priority).isEqualTo(DisplayPriority.KEY_LANGUAGE_SETTINGS)
    }
    withPlatformPrefix(PlatformUtils.IDEA_PREFIX) {
      assertThat(ep.priority).isEqualTo(DisplayPriority.LANGUAGE_SETTINGS)
    }
    assertThat(CountedTestPage.created).isEqualTo(created)
  }

  @Test
  fun `a declaration without a name reports an error`() {
    val ep = declaration()

    val error = LoggedErrorProcessor.executeAndReturnLoggedError { ep.pageName }

    assertThat(error.message).contains(CountedTestPage::class.java.name)
  }

  private fun maskLegacyPages(vararg pages: ColorSettingsPage) {
    ExtensionTestUtil.maskExtensions(ColorSettingsPage.EP_NAME, pages.toList(), disposable)
  }

  private fun maskDeclaredPages(vararg declarations: ColorSettingsPageEP) {
    ExtensionTestUtil.maskExtensions(ColorSettingsPageEP.EP_NAME, declarations.toList(), disposable)
  }

  private fun withPlatformPrefix(prefix: String, check: () -> Unit) {
    val before = System.getProperty(PlatformUtils.PLATFORM_PREFIX_KEY)
    System.setProperty(PlatformUtils.PLATFORM_PREFIX_KEY, prefix)
    try {
      check()
    }
    finally {
      if (before == null) System.clearProperty(PlatformUtils.PLATFORM_PREFIX_KEY)
      else System.setProperty(PlatformUtils.PLATFORM_PREFIX_KEY, before)
    }
  }

  private fun declaration(
    id: String? = null,
    displayName: String? = null,
    priority: DisplayPriority = DisplayPriority.LANGUAGE_SETTINGS,
    groupWeight: Int = 0,
    keyLanguageIn: String? = null,
  ): ColorSettingsPageEP {
    val ep = ColorSettingsPageEP()
    ep.implementationClass = CountedTestPage::class.java.name
    ep.declaredPageId = id
    ep.displayName = displayName
    ep.declaredPriority = priority
    ep.groupWeight = groupWeight
    ep.keyLanguageIn = keyLanguageIn
    ep.pluginDescriptor = DefaultPluginDescriptor(PluginId.getId("com.intellij.colorSettings.test"),
                                                  javaClass.classLoader)
    return ep
  }

  internal open class TestPage(private val pageId: String, private val name: String) : ColorSettingsPage {
    override fun getIcon(): Icon? = null

    override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

    override fun getDemoText(): String = ""

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = emptyArray()

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = name

    override fun getId(): String = pageId
  }

  /** Counts its own construction, so a test can prove that reading a declaration creates no page. */
  internal class CountedTestPage : TestPage(CountedTestPage::class.java.name, "A counted page") {
    init {
      created++
    }

    companion object {
      @JvmStatic
      var created: Int = 0
    }
  }
}
