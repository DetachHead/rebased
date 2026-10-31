// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl

import com.intellij.openapi.util.text.StringUtil

/**
 * The longest fragment that a message quotes in full, the ellipsis included.
 */
private const val MAX_QUOTED_CHARS = 40

/**
 * How many characters of the tail a shortened fragment keeps.
 */
private const val QUOTED_TAIL_CHARS = 12

/**
 * What a shortened fragment puts in place of its middle.
 */
private const val ELLIPSIS = "..."

/**
 * The fragment as a quoted string that is safe to put in a message.
 *
 * An insert fragment can be a whole pasted file, and `toString` reaches exception messages
 * and the log. So a long fragment gives up its middle to an ellipsis and reports its own
 * length instead. A line break becomes an escape, which keeps the message on one line. Only the
 * head and the tail are copied, so a message about a paste does not cost the paste.
 */
internal fun CharSequence.quotedForMessage(): String {
  if (length <= MAX_QUOTED_CHARS) {
    return "\"${StringUtil.escapeStringCharacters(toString())}\""
  }
  val head = subSequence(0, MAX_QUOTED_CHARS - QUOTED_TAIL_CHARS - ELLIPSIS.length)
  val tail = subSequence(length - QUOTED_TAIL_CHARS, length)
  return "\"${StringUtil.escapeStringCharacters("$head$ELLIPSIS$tail")}\" ($length chars)"
}

/**
 * The longest list that a message prints in full.
 */
private const val MAX_LISTED_ITEMS = 10

/**
 * The lvs as a list that is safe to put in a message. See the list overload.
 */
internal fun IntArray.listedForMessage(): String {
  return asList().listedForMessage("lvs")
}

/**
 * The items as a list that is safe to put in a message. [noun] names the items in the length.
 *
 * A version gets one head per replica that a merge joins, and a check can list every head of a
 * graph. A long list therefore keeps its head and reports its own length.
 */
internal fun List<Any>.listedForMessage(noun: String): String {
  if (size <= MAX_LISTED_ITEMS) {
    return joinToString(prefix = "[", postfix = "]")
  }
  val postfix = ", ... ($size $noun)]"
  return take(MAX_LISTED_ITEMS).joinToString(prefix = "[", postfix = postfix)
}
