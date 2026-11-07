// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.impl.moduleRepository

import com.intellij.platform.buildScripts.runtimeModuleRepository.PluginDescriptorDataForHeader
import com.intellij.platform.buildScripts.runtimeModuleRepository.readPluginDescriptorDataForHeader
import org.jetbrains.intellij.build.PLUGIN_XML_RELATIVE_PATH
import org.jetbrains.intellij.build.classPath.PluginBuildResult
import org.jetbrains.intellij.build.impl.PRODUCT_DESCRIPTOR_META_PATH
import org.jetbrains.intellij.build.impl.PlatformLayout
import org.jetbrains.intellij.build.impl.ScopedCachedDescriptorContainer
import org.jetbrains.intellij.build.impl.projectStructureMapping.ModuleOutputEntry

/**
 * Fetches plugin descriptor data from descriptors of the core and bundled plugins, including additional plugins for the embedded frontend.
 * For performance reasons, it takes contents of descriptor files from [org.jetbrains.intellij.build.impl.DescriptorCacheContainer] where xi:include references are already inlined.
 */
internal fun fetchPluginDescriptorsData(
  platformLayout: PlatformLayout,
  corePluginDescriptorModuleName: String,
  embeddedFrontendDescriptorModuleName: String?,
  bundledPlugins: List<PluginBuildResult>,
  additionalFrontendOnlyPlugins: List<PluginBuildResult>
): List<PluginDescriptorDataForHeader> {
  val platformContainer = platformLayout.descriptorCacheContainer.forPlatform(platformLayout)
  val corePluginContent = platformContainer.getCachedFileData(PRODUCT_DESCRIPTOR_META_PATH) ?: error("Cannot find core plugin descriptor")

  val corePluginDescriptorData = fetchPluginDescriptorDataForHeader(
    corePluginContent,
    pluginDescriptorJpsModuleName = corePluginDescriptorModuleName,
    platformContainer,
    additionalContainersForEmbeddedFrontend = emptyList(),
    additionalFrontendOnlyPlugin = false,
  )

  val additionalContainersForEmbeddedFrontend =
    if (embeddedFrontendDescriptorModuleName != null) {
      //descriptors for embedded frontend may be stored inside containers for the platform, and for the corresponding plugin, see deprecatedResolveDescriptorForEmbeddedProduct
      val pluginWithEmbeddedFrontendCandidates = bundledPlugins.filter { plugin ->
        plugin.distribution.any { it is ModuleOutputEntry && it.owner.moduleName == embeddedFrontendDescriptorModuleName } && plugin.distribution.size > 1
      }
      require(pluginWithEmbeddedFrontendCandidates.isNotEmpty()) { "Cannot find plugin with embedded frontend $embeddedFrontendDescriptorModuleName" }
      require(pluginWithEmbeddedFrontendCandidates.size == 1) { "Found multiple plugins with embedded frontend $embeddedFrontendDescriptorModuleName: $pluginWithEmbeddedFrontendCandidates" }
      listOf(platformContainer, platformLayout.descriptorCacheContainer.forPlugin(pluginWithEmbeddedFrontendCandidates.single().dir))
    }
    else emptyList()

  fun fetchPluginDescriptorDataForHeader(plugin: PluginBuildResult, additionalFrontendOnlyPlugin: Boolean): PluginDescriptorDataForHeader {
    val descriptorContainer = platformLayout.descriptorCacheContainer.forPlugin(plugin.dir)
    val fileContent = descriptorContainer.getCachedFileData(PLUGIN_XML_RELATIVE_PATH) ?: error(
      "Cannot find plugin.xml for ${plugin.dir} in the cache"
    )
    return fetchPluginDescriptorDataForHeader(
      fileContent,
      pluginDescriptorJpsModuleName = plugin.mainModule,
      descriptorContainer,
      additionalContainersForEmbeddedFrontend,
      additionalFrontendOnlyPlugin,
    )
  }

  val bundledPluginDescriptorsData = bundledPlugins.map { plugin -> fetchPluginDescriptorDataForHeader(plugin, additionalFrontendOnlyPlugin = false) }
  val additionalFrontendPluginDescriptorsData = additionalFrontendOnlyPlugins.map { plugin -> fetchPluginDescriptorDataForHeader(plugin, additionalFrontendOnlyPlugin = true) }
  return listOf(corePluginDescriptorData) + bundledPluginDescriptorsData + additionalFrontendPluginDescriptorsData
}

private fun fetchPluginDescriptorDataForHeader(
  pluginDescriptorContent: ByteArray,
  pluginDescriptorJpsModuleName: String,
  descriptorContainer: ScopedCachedDescriptorContainer,
  additionalContainersForEmbeddedFrontend: List<ScopedCachedDescriptorContainer>,
  additionalFrontendOnlyPlugin: Boolean,
): PluginDescriptorDataForHeader {
  return readPluginDescriptorDataForHeader(
    pluginDescriptorContent = pluginDescriptorContent,
    pluginDescriptorJpsModuleName = pluginDescriptorJpsModuleName,
    additionalFrontendOnlyPlugin = additionalFrontendOnlyPlugin,
    contentModuleDescriptorProvider = { pluginId, contentModule ->
      val descriptorName = "${contentModule.name}.xml"
      var moduleXmlData = descriptorContainer.getCachedFileData(descriptorName)
      if (moduleXmlData == null && pluginId == "com.intellij") {
        moduleXmlData = additionalContainersForEmbeddedFrontend.firstNotNullOfOrNull { it.getCachedFileData(descriptorName) }
      }
      moduleXmlData
    },
  )
}
