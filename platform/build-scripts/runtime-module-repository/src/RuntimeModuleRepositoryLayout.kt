// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.buildScripts.runtimeModuleRepository

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Files
import java.nio.file.Path

private const val LAYOUT_FORMAT_VERSION = 1

private val layoutJson = Json {
  encodeDefaults = true
  explicitNulls = false
  prettyPrint = true
  prettyPrintIndent = "  "
}

/**
 * The files of each plugin in a distribution. The `main` function of `RuntimeModuleRepositoryMain` reads it from the `--layout` file.
 */
@ApiStatus.Internal
@Serializable
class RuntimeModuleRepositoryLayout(
  val version: Int = LAYOUT_FORMAT_VERSION,
  /** The core plugin, the bundled plugins and the additional frontend-only plugins, in this order. */
  val plugins: List<RuntimeModuleRepositoryPluginLayout>,
)

@ApiStatus.Internal
@Serializable
class RuntimeModuleRepositoryPluginLayout(
  /** The JPS module whose resources contain the plugin descriptor. It names the plugin in the `--descriptor` option. */
  val descriptorModule: String,
  /** Whether the plugin is not bundled, and only the frontend process started from the IDE loads it. */
  val additionalFrontendOnlyPlugin: Boolean = false,
  /** The files of the plugin, in the order of the layout. */
  val entries: List<PluginDistributionEntry>,
)

@ApiStatus.Internal
fun readRuntimeModuleRepositoryLayout(file: Path): RuntimeModuleRepositoryLayout {
  val layout = layoutJson.decodeFromString<RuntimeModuleRepositoryLayout>(Files.readString(file))
  require(layout.version == LAYOUT_FORMAT_VERSION) {
    "$file has the layout format version ${layout.version}, but $LAYOUT_FORMAT_VERSION is expected"
  }
  return layout
}

@ApiStatus.Internal
fun writeRuntimeModuleRepositoryLayout(layout: RuntimeModuleRepositoryLayout, file: Path) {
  Files.writeString(file, layoutJson.encodeToString(layout) + "\n")
}
