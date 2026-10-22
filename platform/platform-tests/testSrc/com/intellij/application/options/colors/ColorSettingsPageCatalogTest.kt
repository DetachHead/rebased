// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.application.options.colors

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.options.newEditor.BetaConfigurable
import com.intellij.psi.codeStyle.DisplayPriority
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import javax.swing.Icon

/**
 * [ColorSettingsPageCatalog] answers every colour page once, from three sources: a [ColorSettingsPageEP]
 * declaration, a [ColorSettingsPage] declaration, and an object that a caller registered at run time.
 *
 * This test states the rule of the three sources, and it states the win: the settings tree builds the node
 * of a declaration and loads no class.
 */
@TestApplication
internal class ColorSettingsPageCatalogTest {

  @TestDisposable
  private lateinit var disposable: Disposable

  /** A page of the old point comes first, and an entry of the old point reads the class of its page. */
  @Test
  fun `a declaration of each point answers once`() {
    val legacy = LegacyPageStub("com.example.LegacyPage", TestPage("legacy", "A legacy page"))
    val declared = declaration("com.example.DeclaredPage", displayName = "A declared page")

    val entries = ColorSettingsPageCatalog.buildEntries(listOf(declared), sequenceOf(legacy))

    assertThat(entries.map { it.displayName }).containsExactly("A legacy page", "A declared page")
    assertThat(entries.map { it.implementationClassName })
      .containsExactly(TestPage::class.java.name, "com.example.DeclaredPage")
  }

  /** Rule 2. One plugin archive may carry both declarations, so the new one answers and the class stays unloaded. */
  @Test
  fun `a declaration of the new point beats a declaration of the old point`() {
    val legacy = LegacyPageStub("com.example.BothPage", TestPage("legacy-id", "A legacy name"))
    val declared = declaration("com.example.BothPage", id = "declared-id", displayName = "A declared name")

    val entries = ColorSettingsPageCatalog.buildEntries(listOf(declared), sequenceOf(legacy))

    assertThat(entries).hasSize(1)
    assertThat(entries.single().id).isEqualTo("declared-id")
    assertThat(entries.single().displayName).isEqualTo("A declared name")
    assertThat(legacy.pageRead).isFalse()
  }

  /**
   * A page that a caller registered as an object, for example a page of the remote development client or a page
   * of [com.intellij.openapi.options.colors.ColorSettingsPages.registerPage], answers like every other page of
   * the old point.
   *
   * The catalog cannot tell such an object from a declaration of the old point, because the platform states no
   * value for it, and this migration adds none. So rule 2 covers an object of a declared class too, and the
   * second half of this case states that limit. Nothing reaches it: the client registers a
   * `ProtocolColorSettingsPage`, which no declaration names.
   */
  @Test
  fun `a page of the old point answers, and a declaration of its class beats it`() {
    val page = TestPage("object-id", "An object name")
    val runTimePage = LegacyPageStub("com.example.BothPage", page)

    val alone = ColorSettingsPageCatalog.buildEntries(emptyList(), sequenceOf(runTimePage))

    assertThat(alone).hasSize(1)
    assertThat(alone.single().page).isSameAs(page)

    val declared = declaration("com.example.BothPage", id = "declared-id", displayName = "A declared name")

    val entries = ColorSettingsPageCatalog.buildEntries(listOf(declared), sequenceOf(runTimePage))

    assertThat(entries).hasSize(1)
    assertThat(entries.single().id).isEqualTo("declared-id")
  }

  /** Two declarations of one class are a mistake of a declaration, so the catalog reports it and answers once. */
  @Test
  fun `two declarations of one class answer once`() {
    val first = declaration("com.example.TwicePage", id = "first", displayName = "First")
    val second = declaration("com.example.TwicePage", id = "second", displayName = "Second")

    val error = LoggedErrorProcessor.executeAndReturnLoggedError {
      assertThat(ColorSettingsPageCatalog.buildEntries(listOf(first, second), emptySequence())).hasSize(1)
    }

    assertThat(error.message).contains("com.example.TwicePage")
  }

  /**
   * The win of the whole step, stated as a test.
   *
   * The declaration names a class that does not exist, so any attempt to create the page fails loudly.
   * The tree still holds the node, with the declared id and the declared name.
   */
  @Test
  fun `the settings tree builds the node of a declaration and loads no class`() {
    val absentClass = "com.intellij.application.options.colors.AbsentColourPage"
    ExtensionTestUtil.maskExtensions(ColorSettingsPage.EP_NAME, emptyList(), disposable)
    ExtensionTestUtil.maskExtensions(ColorAndFontPanelFactory.EP_NAME, emptyList(), disposable)
    ExtensionTestUtil.maskExtensions(
      ColorSettingsPageEP.EP_NAME,
      listOf(declaration(absentClass, displayName = "An absent page")),
      disposable,
    )

    val options = ColorAndFontOptions()
    try {
      val children = options.configurables.map { it as SearchableConfigurable }

      assertThat(children.map { it.id }).contains(ColorAndFontOptions.ID + "." + absentClass)
      assertThat(children.map { it.displayName }).contains("An absent page")
    }
    finally {
      options.disposeUIResources()
    }
  }

  /**
   * The id of a child node is [ColorAndFontOptions.getPageConfigurableId] of the entry id, for an entry of either
   * source.
   *
   * Two readers in another process join on that string, so the rule may not drift.
   * `BackendConfigurablesStorage.filterConfigurables` removes the id from the list that a remote development host
   * sends to its client, and `FrontendHighlighterRegistrationHost.shouldRegister` keeps the page of the host when
   * no entry of the client states the id of it. A change of the rule here leaves a colour node twice in a split
   * session, and nothing reports it.
   */
  @Test
  fun `the node id of a child is the id rule of its entry id`() {
    ExtensionTestUtil.maskExtensions(ColorAndFontPanelFactory.EP_NAME, emptyList(), disposable)
    ExtensionTestUtil.maskExtensions(ColorSettingsPage.EP_NAME, listOf(TestPage("legacy-id", "A legacy page")), disposable)
    ExtensionTestUtil.maskExtensions(
      ColorSettingsPageEP.EP_NAME,
      listOf(declaration("com.intellij.application.options.colors.AbsentColourPage", id = "declared-id",
                         displayName = "A declared page")),
      disposable,
    )

    val options = ColorAndFontOptions()
    try {
      val childIds = options.configurables.map { (it as SearchableConfigurable).id }
      val entryIds = ColorSettingsPageCatalog.getEntries().map { it.id }

      assertThat(entryIds).containsExactlyInAnyOrder("legacy-id", "declared-id")
      assertThat(childIds).containsAll(entryIds.map { ColorAndFontOptions.getPageConfigurableId(it) })
      // the rest of the children are the two font pages, which the parent adds by hand and no colour page backs
      assertThat(childIds - entryIds.map { ColorAndFontOptions.getPageConfigurableId(it) }.toSet())
        .containsExactlyInAnyOrder(ColorAndFontOptions.getPageConfigurableId("ColorSchemeFont"),
                                   ColorAndFontOptions.getPageConfigurableId("ConsoleFont"))
    }
    finally {
      options.disposeUIResources()
    }
  }

  /**
   * The settings tree paints the beta badge of a colour child from the declaration, through
   * [com.intellij.openapi.options.newEditor.BetaConfigurable], so it creates no page and loads no class.
   * A page of the old point states no badge, because that declaration carries no attribute for it.
   */
  @Test
  fun `a child states the beta badge of its declaration`() {
    val absentClass = "com.intellij.application.options.colors.AbsentBetaColourPage"
    ExtensionTestUtil.maskExtensions(ColorAndFontPanelFactory.EP_NAME, emptyList(), disposable)
    ExtensionTestUtil.maskExtensions(ColorSettingsPage.EP_NAME, listOf(TestPage("legacy-id", "A legacy page")), disposable)
    ExtensionTestUtil.maskExtensions(
      ColorSettingsPageEP.EP_NAME,
      listOf(declaration(absentClass, id = "beta-id", displayName = "A beta page", beta = true)),
      disposable,
    )

    val options = ColorAndFontOptions()
    try {
      val beta = options.configurables.associate { (it as SearchableConfigurable).id to (it as BetaConfigurable).isBeta }

      assertThat(beta[ColorAndFontOptions.getPageConfigurableId("beta-id")]).isTrue()
      assertThat(beta[ColorAndFontOptions.getPageConfigurableId("legacy-id")]).isFalse()
      assertThat(beta[ColorAndFontOptions.getPageConfigurableId("ConsoleFont")]).isFalse()
    }
    finally {
      options.disposeUIResources()
    }
  }

  private fun declaration(
    implementationClass: String,
    id: String? = null,
    displayName: String? = null,
    priority: DisplayPriority = DisplayPriority.LANGUAGE_SETTINGS,
    groupWeight: Int = 0,
    beta: Boolean = false,
  ): ColorSettingsPageEP {
    val ep = ColorSettingsPageEP()
    ep.implementationClass = implementationClass
    ep.declaredPageId = id
    ep.displayName = displayName
    ep.declaredPriority = priority
    ep.groupWeight = groupWeight
    ep.beta = beta
    ep.pluginDescriptor = DefaultPluginDescriptor(PluginId.getId("com.intellij.colorSettings.test"),
                                                  javaClass.classLoader)
    return ep
  }

  /** Stands for one extension of [ColorSettingsPage.EP_NAME], and records whether the catalog asked for the page. */
  private class LegacyPageStub(
    override val implementationClassName: String,
    private val instance: ColorSettingsPage,
  ) : LegacyColorSettingsPage {
    var pageRead: Boolean = false
      private set

    override val page: ColorSettingsPage
      get() {
        pageRead = true
        return instance
      }
  }

  private class TestPage(private val pageId: String, private val name: String) : ColorSettingsPage {
    override fun getIcon(): Icon? = null

    override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

    override fun getDemoText(): String = ""

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = emptyArray()

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = name

    override fun getId(): String = pageId
  }
}
