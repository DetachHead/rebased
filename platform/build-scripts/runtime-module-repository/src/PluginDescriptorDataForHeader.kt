// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.buildScripts.runtimeModuleRepository

import com.intellij.platform.pluginSystem.parser.impl.elements.ContentModuleElement
import com.intellij.platform.pluginSystem.parser.impl.elements.ModuleLoadingRuleValue
import com.intellij.platform.pluginSystem.parser.impl.elements.ModuleVisibilityValue
import com.intellij.platform.pluginSystem.parser.impl.parseContentAndXIncludes
import com.intellij.platform.runtime.repository.RuntimeModuleId
import com.intellij.platform.runtime.repository.RuntimeModuleLoadingRule
import com.intellij.platform.runtime.repository.RuntimeModuleVisibility
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.reader

/**
 * Represents the data from `plugin.xml` descriptor that is required to generate [com.intellij.platform.runtime.repository.RuntimePluginHeader]
 */
@ApiStatus.Internal
class PluginDescriptorDataForHeader(
  val pluginId: String,
  val pluginDescriptorJpsModuleName: String,
  val additionalFrontendOnlyPlugin: Boolean,
  val contentModules: Map<String, ContentModuleRegistrationDataForHeader>,
  /** dependencies of this plugin descriptor on other plugin descriptor modules (via `<dependencies><plugin>` tag in `plugin.xml`) */
  val pluginDescriptorDependenciesOnPluginDescriptorModules: List<RuntimeModuleId>,
) {
  override fun toString(): String {
    return "PluginDescriptorDataForHeader{pluginId=$pluginId, pluginDescriptorJpsModuleName=$pluginDescriptorJpsModuleName, additionalFrontendOnlyPlugin=$additionalFrontendOnlyPlugin}"
  }
}

@ApiStatus.Internal
data class ContentModuleRegistrationDataForHeader(
  val name: String,
  val namespace: String,
  val loadingRule: RuntimeModuleLoadingRule,
  val requiredIfAvailable: RuntimeModuleId?,
  val visibility: RuntimeModuleVisibility,
  /** dependencies of this content module on other plugin descriptor modules (via `<dependencies><plugin>` tag) */
  val dependenciesOnPluginDescriptorModules: List<RuntimeModuleId>,
)

/**
 * Finds the descriptor of a content module that a plugin descriptor registers.
 */
@ApiStatus.Internal
fun interface ContentModuleDescriptorProvider {
  /**
   * Returns the descriptor of [contentModule] with `xi:include` references inlined, or `null` if there is none.
   * [pluginId] is the ID of the plugin that registers the module.
   */
  fun findDescriptor(pluginId: String, contentModule: ContentModuleElement): ByteArray?
}

/**
 * Reads the data for the plugin header from [pluginDescriptorContent], the plugin descriptor with `xi:include` references inlined.
 */
@ApiStatus.Internal
fun readPluginDescriptorDataForHeader(
  pluginDescriptorContent: ByteArray,
  pluginDescriptorJpsModuleName: String,
  additionalFrontendOnlyPlugin: Boolean,
  contentModuleDescriptorProvider: ContentModuleDescriptorProvider,
): PluginDescriptorDataForHeader {
  val parsedContent = parseContentAndXIncludes(input = pluginDescriptorContent, locationSource = pluginDescriptorJpsModuleName)
  val pluginId = parsedContent.pluginId ?: error("<id> tag is not set in plugin.xml in $pluginDescriptorJpsModuleName")
  val contentModules = parsedContent.contentModules.mapNotNull { contentModuleElement ->
    val namespace = contentModuleElement.namespace ?: $$"$${pluginId}_$implicit"
    if (contentModuleElement.name.contains("/")) return@mapNotNull null //todo remove this check after all content modules are extracted to separate JPS modules (IJPL-165543)

    val descriptorName = "${contentModuleElement.name}.xml"
    val moduleXmlData = contentModuleDescriptorProvider.findDescriptor(pluginId, contentModuleElement)
    require(moduleXmlData != null) { "Cannot find $descriptorName descriptor for plugin.xml in $pluginDescriptorJpsModuleName" }
    val loadingRule = contentModuleElement.loadingRule.toRuntimeModuleLoadingRule()
    val requiredIfAvailable = contentModuleElement.requiredIfAvailable?.let { RuntimeModuleId.contentModule(it, RuntimeModuleId.DEFAULT_NAMESPACE) }
    val rawModuleDescriptorData = parseContentAndXIncludes(moduleXmlData, locationSource = "cached data for $descriptorName")
    val visibility = rawModuleDescriptorData.moduleVisibility.toRuntimeModuleVisibility()
    val dependenciesOnPluginDescriptorModules = rawModuleDescriptorData.pluginDependencies.map {
      RuntimeModuleId.pluginDescriptorModule(it)
    }
    ContentModuleRegistrationDataForHeader(contentModuleElement.name, namespace, loadingRule, requiredIfAvailable, visibility, dependenciesOnPluginDescriptorModules)
  }
  val pluginDescriptorDependenciesOnPluginDescriptorModules = parsedContent.pluginDependencies.map { RuntimeModuleId.pluginDescriptorModule(it) }
  return PluginDescriptorDataForHeader(pluginId, pluginDescriptorJpsModuleName, additionalFrontendOnlyPlugin, contentModules.associateBy { it.name }, pluginDescriptorDependenciesOnPluginDescriptorModules)
}

/**
 * If some plugins are suppressed in the product by default, they should not be included in the runtime module repository to avoid ambiguity if they contain modules duplicating
 * modules from other plugins.
 * [idePropertiesFiles] are the additional `idea.properties` files of the product, which select the suppressed plugins.
 */
@ApiStatus.Internal
fun removeDataForSuppressedPlugins(
  originalPluginDescriptorsData: List<PluginDescriptorDataForHeader>,
  idePropertiesFiles: List<Path>,
): List<PluginDescriptorDataForHeader> {
  val properties = Properties()
  idePropertiesFiles.forEach { propertiesFile ->
    propertiesFile.reader().buffered().use { reader ->
      properties.load(reader)
    }
  }
  val selector = properties.getProperty("idea.suppressed.plugins.set.selector") ?: return originalPluginDescriptorsData
  val suppressedPluginsString = properties.getProperty("idea.suppressed.plugins.set.${selector}") ?: return originalPluginDescriptorsData
  val suppressedPlugins = suppressedPluginsString.split(",").mapTo(HashSet()) { it.trim() }
  return originalPluginDescriptorsData.filterNot { it.pluginId in suppressedPlugins }
}

private fun ModuleVisibilityValue.toRuntimeModuleVisibility(): RuntimeModuleVisibility {
  return when (this) {
    ModuleVisibilityValue.PRIVATE -> RuntimeModuleVisibility.PRIVATE
    ModuleVisibilityValue.INTERNAL -> RuntimeModuleVisibility.INTERNAL
    ModuleVisibilityValue.PUBLIC -> RuntimeModuleVisibility.PUBLIC
  }
}

private fun ModuleLoadingRuleValue.toRuntimeModuleLoadingRule(): RuntimeModuleLoadingRule {
  return when (this) {
    ModuleLoadingRuleValue.REQUIRED -> RuntimeModuleLoadingRule.REQUIRED
    ModuleLoadingRuleValue.OPTIONAL -> RuntimeModuleLoadingRule.OPTIONAL
    ModuleLoadingRuleValue.EMBEDDED -> RuntimeModuleLoadingRule.EMBEDDED
    ModuleLoadingRuleValue.ON_DEMAND -> RuntimeModuleLoadingRule.ON_DEMAND
  }
}
