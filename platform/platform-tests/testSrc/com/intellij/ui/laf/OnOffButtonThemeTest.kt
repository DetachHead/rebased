// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.laf

import com.intellij.ide.ui.UITheme
import com.intellij.ide.ui.laf.darcula.ui.installNewUiOnOffButtonUI
import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.IconManager
import com.intellij.ui.svg.AttributeMutator
import com.intellij.ui.svg.createJSvgDocument
import com.intellij.util.xml.dom.createXmlStreamReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import javax.swing.UIDefaults

private const val DELEGATE = "com.intellij.ide.ui.laf.darcula.ui.DarculaOnOffButtonUI"

@TestApplication
class OnOffButtonThemeTest {
  private var iconsActivated = false

  @BeforeEach
  fun activateIcons() {
    val iconManagerClass = IconManager.getInstance().javaClass
    IconManager.activate(null)
    IconLoader.activate()
    iconsActivated = IconManager.getInstance().javaClass != iconManagerClass
  }

  @AfterEach
  fun deactivateIcons() {
    // Keep the icons of a test that activated them before this one
    if (iconsActivated) {
      IconLoader.deactivate()
      IconManager.deactivate()
    }
  }

  @ParameterizedTest
  @ValueSource(strings = [
    // Classic
    "darcula", "intellijlaf", "Light", "HighContrast",
    // New UI
    "expUI/expUI_dark", "expUI/expUI_light", "expUI/expUI_light_with_light_header",
    // Islands
    "islands/ManyIslandsDark", "islands/ManyIslandsLight", "islands/ManyIslandsDarcula",
    // High contrast
    "islands/HighContrast",
  ])
  fun `bundled themes leave the toggle delegate to the UI mode`(themePath: String) {
    // Classic UI then keeps the default delegate of OnOffButton
    assertNull(loadDefaults(themePath)["OnOffButtonUI"], themePath)
  }

  @ParameterizedTest
  @ValueSource(strings = [
    // Darcula is shown in New UI too, light classic and custom themes derive from IntelliJ
    "darcula", "intellijlaf",
    // New UI
    "expUI/expUI_dark", "expUI/expUI_light", "expUI/expUI_light_with_light_header",
    // Islands
    "islands/ManyIslandsDark", "islands/ManyIslandsLight", "islands/ManyIslandsDarcula",
    // High contrast
    "islands/HighContrast",
  ])
  fun `new UI installs the toggle delegate and themes resolve its colors`(themePath: String) {
    val defaults = loadDefaults(themePath)
    installNewUiOnOffButtonUI(defaults)

    assertEquals(DELEGATE, defaults["OnOffButtonUI"], themePath)
    assertNotNull(defaults.getUIClass("OnOffButtonUI", javaClass.classLoader), themePath)

    for (state in listOf("Off", "On", "OffFocused", "OnFocused", "OffDisabled", "OnDisabled")) {
      val path = "/com/intellij/ide/ui/laf/icons/toggle$state.svg"
      javaClass.getResourceAsStream(path)!!.use { stream ->
        createJSvgDocument(createXmlStreamReader(stream), object : AttributeMutator {
          override fun invoke(attributes: MutableMap<String, String>) {
            for (attribute in listOf("color-fill-key", "color-stroke-key")) {
              val key = attributes[attribute] ?: continue
              assertNotNull(defaults.getColor("ColorPalette.$key"), "$themePath: $key")
            }
          }
        })
      }
    }
  }

  @Test
  fun `new UI keeps the toggle delegate of a theme`() {
    val delegate = "com.intellij.laf.win10.WinOnOffButtonUI"
    val defaults = UIDefaults().also { it["OnOffButtonUI"] = delegate }
    installNewUiOnOffButtonUI(defaults)

    assertEquals(delegate, defaults["OnOffButtonUI"])
  }

  @ParameterizedTest
  @CsvSource(
    "darcula, islands/ManyIslandsDarcula",
    "intellijlaf, islands/ManyIslandsLight",
    "expUI/expUI_dark, islands/ManyIslandsDark",
    "expUI/expUI_light, islands/ManyIslandsLight",
    "expUI/expUI_light_with_light_header, islands/ManyIslandsLight",
  )
  fun `toggle colors match the corresponding Islands theme`(themePath: String, islandsThemePath: String) {
    val expected = toggleColors(loadDefaults(islandsThemePath))
    assertEquals(13, expected.size, islandsThemePath)
    assertEquals(expected, toggleColors(loadDefaults(themePath)), themePath)
  }

  private fun toggleColors(defaults: UIDefaults): Map<String, Int> = defaults.keys
    .filterIsInstance<String>()
    .filter { it.startsWith("ColorPalette.toggle-") }
    .associateWith { defaults.getColor(it).rgb }

  private fun loadDefaults(themePath: String): UIDefaults {
    val data = javaClass.getResourceAsStream("/themes/$themePath.theme.json")!!.use { it.readBytes() }
    val theme = UITheme.loadFromJsonWithParent(data, themePath, javaClass.classLoader)
    return UIDefaults().also { theme.applyTheme(it) }
  }
}
