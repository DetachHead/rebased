// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.buildScripts.runtimeModuleRepository

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.annotations.ApiStatus

/**
 * One file of a plugin distribution that the runtime module repository describes.
 */
@ApiStatus.Internal
@Serializable
class PluginDistributionEntry(
  val kind: Kind,
  /** The module name for [Kind.MODULE_OUTPUT] and [Kind.MODULE_LIBRARY], and the library name for [Kind.PROJECT_LIBRARY]. */
  val name: String,
  /** The path of the file relative to the distribution root, with `/` separators. It is `null` if the file is not in the distribution. */
  val path: String? = null,
  /** The path of the file relative to the `lib` directory of the plugin. */
  val relativeOutputFile: String? = null,
) {
  @Serializable
  enum class Kind {
    /** The production output of a JPS module. */
    @SerialName("module")
    MODULE_OUTPUT,

    /** A file of a project-level library. */
    @SerialName("projectLibrary")
    PROJECT_LIBRARY,

    /** A file of a module-level library. [name] is the module that declares the library. */
    @SerialName("moduleLibrary")
    MODULE_LIBRARY,
  }

  override fun toString(): String = "PluginDistributionEntry(kind=$kind, name=$name, path=$path, relativeOutputFile=$relativeOutputFile)"
}
