// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.zuban.common

import org.jetbrains.annotations.ApiStatus

/**
 * The `typeCheckingMode` that the IDE sends to zuban, by the [value] that zuban reads.
 *
 * A `mode` in the `[tool.zuban]` section of the project still wins. [DEFAULT] and [MYPY] replace only the
 * choice that zuban makes by itself: Mypy mode for a project with a `[tool.mypy]` section or a
 * `mypy.ini`, and default mode for every other project. Zuban also knows `off`, which the inspections
 * toggle of the tool already covers, so it is not here.
 */
@ApiStatus.Internal
enum class ZubanTypeCheckingMode(val value: String) {
  AUTO("auto"),
  DEFAULT("default"),
  MYPY("mypy");

  companion object {
    /** The mode with [value], or `null` for a value zuban does not know. */
    fun of(value: String?): ZubanTypeCheckingMode? = entries.firstOrNull { it.value == value }
  }
}
