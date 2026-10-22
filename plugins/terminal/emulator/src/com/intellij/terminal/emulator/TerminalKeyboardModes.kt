// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.terminal.emulator

import org.jetbrains.annotations.ApiStatus

/**
 * A flag of the Kitty keyboard protocol (https://sw.kovidgoyal.net/kitty/keyboard-protocol/), one bit
 * of the value a program pushes with `CSI > flags u`. The declaration order is the bit order: a flag
 * is bit `ordinal` of that value.
 */
@ApiStatus.Internal
enum class KittyKeyboardFlag {
  DISAMBIGUATE_ESCAPE_CODES,
  REPORT_EVENT_TYPES,
  REPORT_ALTERNATE_KEYS,
  REPORT_ALL_KEYS_AS_ESCAPE_CODES,
  REPORT_ASSOCIATED_TEXT,
}
