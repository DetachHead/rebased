// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.application.options.colors

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.util.NlsContexts
import com.intellij.psi.codeStyle.DisplayPriority
import com.intellij.psi.codeStyle.DisplayPrioritySortable
import org.jetbrains.annotations.ApiStatus.Internal
import org.jetbrains.annotations.VisibleForTesting

/**
 * One colour settings page, as the "Colors and Fonts" node needs it: the id, the name, the order,
 * and a way to reach the page itself.
 *
 * An entry of a [ColorSettingsPageEP] declaration answers the first four values from the declaration,
 * and it creates the page only when [page] is read. An entry of a page object reads every value from it.
 */
@Internal
sealed interface ColorSettingsPageEntry {
  /** The name of the [ColorSettingsPage] class. This value is the key of the catalog. */
  val implementationClassName: String

  val id: String

  val displayName: @NlsContexts.ConfigurableName String

  val priority: DisplayPriority

  val weight: Int

  /**
   * Whether the page is beta. A declaration is the only source of this value, as it is for a configurable
   * declaration, so reading it creates no page and loads no class.
   */
  val isBeta: Boolean

  /** The class of the page. Reading this value creates no page. */
  val pageClass: Class<*>

  /** The page itself. Reading this value creates it when the entry comes from a declaration. */
  val page: ColorSettingsPage
}

/**
 * One extension of [ColorSettingsPage.EP_NAME], as [ColorSettingsPageCatalog] needs it.
 *
 * This interface is the seam that lets a test state a declaration that holds no page yet.
 * The production implementation wraps a [com.intellij.openapi.extensions.LazyExtension].
 */
@Internal
interface LegacyColorSettingsPage {
  /** The implementation attribute of the declaration. Reading this value creates no page. */
  val implementationClassName: String

  /** The page itself, or `null` when it cannot be created. */
  val page: ColorSettingsPage?
}

/**
 * Answers every colour settings page of the IDE, once per page.
 *
 * A page reaches the catalog from three sources: a [ColorSettingsPageEP] declaration, a
 * [ColorSettingsPage.EP_NAME] declaration, and an object that somebody registered at run time, for example
 * [com.intellij.openapi.options.colors.ColorSettingsPages.registerPage] or the remote development client.
 * A class may hold a declaration of both kinds, so the catalog states which one answers:
 *
 * 1. The catalog keys a page by the name of its implementation class.
 * 2. A [ColorSettingsPageEP] declaration beats every page of [ColorSettingsPage.EP_NAME] of the same class,
 *    and that class stays unloaded. So one plugin archive may carry both declarations and serve an older IDE
 *    and a new one.
 *
 * The key costs nothing: [com.intellij.openapi.extensions.LazyExtension.implementationClassName] reads the
 * string of the declaration until somebody loads the class.
 *
 * **Rule 2 covers an object of a declared class as well**, because the platform states no value that tells an
 * object registration from a declaration, and this migration adds none. Nothing reaches that shape: the
 * remote development client registers a `ProtocolColorSettingsPage`, which no declaration names, and no
 * caller of [com.intellij.openapi.options.colors.ColorSettingsPages.registerPage] exists in this repository.
 * A caller that must keep its own object therefore registers a class that carries no declaration.
 *
 * Every de-duplication of a colour page lives here. A caller that needs the pages themselves reads
 * [com.intellij.openapi.options.colors.ColorSettingsPages.getRegisteredPages], which maps this catalog to
 * instances. Every colour page creation of the IDE therefore passes this object.
 */
@Internal
object ColorSettingsPageCatalog {
  private val LOG = logger<ColorSettingsPageCatalog>()

  /**
   * Returns one entry per page, the pages of [ColorSettingsPage.EP_NAME] first.
   * The order follows the two extension points, so a caller that sorts keeps its own rule.
   */
  @JvmStatic
  fun getEntries(): List<ColorSettingsPageEntry> =
    buildEntries(ColorSettingsPageEP.EP_NAME.extensionList, legacyPages())

  /** The rule of the three sources, with the two extension points behind a parameter. */
  @VisibleForTesting
  fun buildEntries(
    declarations: List<ColorSettingsPageEP>,
    legacyPages: Sequence<LegacyColorSettingsPage>,
  ): List<ColorSettingsPageEntry> {
    // a LinkedHashMap, so the order of the declarations survives the de-duplication
    val declarationByClassName = LinkedHashMap<String, ColorSettingsPageEP>(declarations.size)
    for (declaration in declarations) {
      if (declarationByClassName.put(declaration.implementationClass, declaration) != null) {
        LOG.error("Two colorSettings declarations name ${declaration.implementationClass}. The last one answers.")
      }
    }

    val entries = ArrayList<ColorSettingsPageEntry>(declarations.size)
    for (legacyPage in legacyPages) {
      if (declarationByClassName.containsKey(legacyPage.implementationClassName)) {
        continue  // rule 2: the declaration answers, and this class stays unloaded
      }
      entries.add(ObjectPageEntry(legacyPage.page ?: continue))
    }
    for (declaration in declarationByClassName.values) {
      entries.add(DeclaredPageEntry(declaration))
    }
    return entries
  }

  private fun legacyPages(): Sequence<LegacyColorSettingsPage> =
    ColorSettingsPage.EP_NAME.filterableLazySequence().map { extension ->
      object : LegacyColorSettingsPage {
        override val implementationClassName: String get() = extension.implementationClassName
        override val page: ColorSettingsPage? get() = extension.instance
      }
    }
}

/** An entry of a [ColorSettingsPageEP] declaration. It creates the page only when [page] is read. */
private class DeclaredPageEntry(private val declaration: ColorSettingsPageEP) : ColorSettingsPageEntry {
  override val implementationClassName: String
    get() = declaration.implementationClass

  override val id: String
    get() = declaration.pageId

  override val displayName: @NlsContexts.ConfigurableName String
    get() = declaration.pageName

  override val priority: DisplayPriority
    get() = declaration.priority

  override val weight: Int
    get() = declaration.groupWeight

  override val isBeta: Boolean
    get() = declaration.beta

  override val pageClass: Class<*>
    get() = declaration.findPageClass() ?: declaration.page.javaClass

  override val page: ColorSettingsPage
    get() = declaration.page
}

/** An entry of a page object, which either a declaration of [ColorSettingsPage.EP_NAME] or a caller created. */
private class ObjectPageEntry(override val page: ColorSettingsPage) : ColorSettingsPageEntry {
  override val implementationClassName: String
    get() = page.javaClass.name

  override val id: String
    get() = page.id

  override val displayName: @NlsContexts.ConfigurableName String
    get() = page.displayName

  override val priority: DisplayPriority
    get() = (page as? DisplayPrioritySortable)?.priority ?: DisplayPriority.LANGUAGE_SETTINGS

  override val weight: Int
    get() = (page as? DisplayPrioritySortable)?.weight ?: 0

  /** A page of the old point declares no badge, so it states none. */
  override val isBeta: Boolean
    get() = false

  override val pageClass: Class<*>
    get() = page.javaClass
}
