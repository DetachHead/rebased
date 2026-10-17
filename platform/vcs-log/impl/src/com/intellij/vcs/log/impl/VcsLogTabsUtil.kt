// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.impl

import com.intellij.openapi.util.NlsContexts.TabTitle
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.ContentUtilEx
import com.intellij.vcs.log.VcsLogBundle
import com.intellij.vcs.log.ui.VcsLogUiEx
import com.intellij.vcs.log.util.GraphOptionsUtil.presentationForTabTitle
import com.intellij.vcs.log.visible.filters.getPresentation
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
object VcsLogTabsUtil {
  fun getFullName(shortName: @TabTitle String): @TabTitle String {
    return ContentUtilEx.getFullName(VcsLogBundle.message("vcs.log.tab.name"), shortName)
  }

  @JvmStatic
  fun generateDisplayName(ui: VcsLogUiEx): @TabTitle String {
    return getRepositoryDisplayName(ui.properties) ?: getFullName(generateShortDisplayName(ui))
  }

  fun generateShortDisplayName(ui: VcsLogUiEx): @TabTitle String {
    getRepositoryDisplayName(ui.properties)?.let { return it }
    val options = ui.properties.getOrNull(MainVcsLogUiProperties.GRAPH_OPTIONS)
    val optionsPresentation = options?.presentationForTabTitle ?: ""
    val filters = ui.filterUi.filters
    val filtersPresentation = if (filters.isEmpty) "" else filters.getPresentation(withPrefix = optionsPresentation.isNotEmpty())
    @NlsSafe
    val presentation = listOf(optionsPresentation, filtersPresentation).filter { it.isNotEmpty() }.joinToString(separator = " ")
    return StringUtil.shortenTextWithEllipsis(presentation, 150, 20)
  }

  internal fun getRepositoryName(properties: VcsLogUiProperties): @NlsSafe String? =
    properties.getOrNull(MainVcsLogUiProperties.ROOT_SCOPE)?.takeIf { it.isNotEmpty() }?.trimEnd('/')?.substringAfterLast('/')

  internal fun getRepositoryPath(properties: VcsLogUiProperties): @NlsSafe String? =
    properties.getOrNull(MainVcsLogUiProperties.ROOT_SCOPE)?.takeIf { it.isNotEmpty() }?.let(VfsUtilCore::urlToPath)

  internal fun getRepositoryDisplayName(properties: VcsLogUiProperties): @NlsSafe String? =
    if (properties.getOrNull(MainVcsLogUiProperties.SHOW_REPOSITORY_PATH) == true) getRepositoryPath(properties)
    else getRepositoryName(properties)
}
