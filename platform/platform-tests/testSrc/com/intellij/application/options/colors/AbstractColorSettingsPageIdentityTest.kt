// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.application.options.colors

import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.psi.codeStyle.DisplayPriority
import com.intellij.psi.codeStyle.DisplayPrioritySortable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Checks that a `colorSettings` declaration states the id, the name, the band and the weight of
 * its page, that it states a name, that it names a class that exists, and that its class keeps no second
 * declaration on the `colorSettingsPage` point.
 *
 * Every case reads [ColorSettingsPageEP.EP_NAME], so a subclass states the contract for the plugin set of
 * its own module, and it creates each declared page.
 */
abstract class AbstractColorSettingsPageIdentityTest {

  @Test
  fun `a declaration states the id of its page`() {
    forEachDeclaration { declaration, page ->
      assertThat(declaration.pageId)
        .describedAs("the id of %s", declaration.implementationClass)
        .isEqualTo(page.id)
    }
  }

  @Test
  fun `a declaration states the name of its page`() {
    forEachDeclaration { declaration, page ->
      assertThat(declaration.pageName)
        .describedAs("the name of %s", declaration.implementationClass)
        .isEqualTo(page.displayName)
    }
  }

  /**
   * [ColorSettingsPageEP.getPriority] and the page both answer the band of the running product, so a
   * declaration that states `keyLanguageIn` is checked for the product of the run.
   */
  @Test
  fun `a declaration states the band and the weight of its page`() {
    forEachDeclaration { declaration, page ->
      val sortable = page as? DisplayPrioritySortable
      assertThat(declaration.priority)
        .describedAs("the band of %s", declaration.implementationClass)
        .isEqualTo(sortable?.priority ?: DisplayPriority.LANGUAGE_SETTINGS)
      assertThat(declaration.groupWeight)
        .describedAs("the weight of %s", declaration.implementationClass)
        .isEqualTo(sortable?.weight ?: 0)
    }
  }

  /**
   * [ColorSettingsPageEP.getPageName] reports an error for a declaration with no name, and the settings tree
   * then loads the page class in its sort.
   */
  @Test
  fun `every declaration states a name`() {
    val nameless = declarations().filter { it.displayName == null && it.key == null }

    assertThat(nameless.map { it.implementationClass })
      .describedAs("a colorSettings declaration must state displayName, or key and bundle")
      .isEmpty()
  }

  /**
   * [ColorSettingsPageCatalog] lets a `colorSettings` declaration answer for a class that also
   * carries a `colorSettingsPage` declaration. That shape serves an external plugin that supports an older
   * IDE with one archive, and a module of this repository has no such reason.
   *
   * The case reads the class name of every extension of the old point, and it creates no page.
   */
  @Test
  fun `no class of this plugin set holds both declarations`() {
    val declaredAsBean = declarations().mapTo(HashSet()) { it.implementationClass }

    val both = ColorSettingsPage.EP_NAME.filterableLazySequence()
      .filter { it.implementationClassName in declaredAsBean }
      .map { it.implementationClassName }
      .toList()

    assertThat(both)
      .describedAs("remove the colorSettingsPage declaration, the colorSettings one answers")
      .isEmpty()
  }

  @Test
  fun `every declaration names a class that exists`() {
    val absent = declarations().filter { it.findPageClass() == null }

    assertThat(absent.map { it.implementationClass }).isEmpty()
  }

  private fun declarations(): List<ColorSettingsPageEP> = ColorSettingsPageEP.EP_NAME.extensionList

  private fun forEachDeclaration(check: (ColorSettingsPageEP, ColorSettingsPage) -> Unit) {
    val declarations = declarations()
    assertThat(declarations)
      .describedAs("this module registers no colorSettings declaration, so the test states nothing")
      .isNotEmpty()

    for (declaration in declarations) {
      check(declaration, declaration.instance)
    }
  }
}
