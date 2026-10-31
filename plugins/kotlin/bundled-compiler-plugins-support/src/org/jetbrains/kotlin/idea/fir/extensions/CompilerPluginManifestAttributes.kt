// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.fir.extensions

import java.util.jar.Attributes

/**
 * Attributes present in compiler plugin jar manifests.
 * A "compatible" third-party compiler plugin needs to have [SINCE_BUILD_ATTR] matching the IDE's version.
 * It can optionally have [UNTIL_BUILD_ATTR] to limit its compatibility.
 *
 * @see CompilerPluginRegistrarUtils
 */
object CompilerPluginManifestAttributes {
    val SINCE_BUILD_ATTR: Attributes.Name = Attributes.Name("Kotlin-Compiler-Plugin-Since-Build")
    val UNTIL_BUILD_ATTR: Attributes.Name = Attributes.Name("Kotlin-Compiler-Plugin-Until-Build")
}