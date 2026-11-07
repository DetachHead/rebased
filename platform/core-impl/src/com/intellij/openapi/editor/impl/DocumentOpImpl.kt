// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl

import com.intellij.openapi.editor.ex.DocumentOp
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.text.ImmutableCharSequence

internal class InsertOpImpl(
  private val offset: Int,
  fragment: CharSequence,
) : DocumentOp.Insert {
  /**
   * A copy detaches the op from a mutable CharSequence the caller may keep.
   * It is free for a sequence that is already immutable, and a String is the usual case.
   */
  private val fragment: CharSequence = ImmutableCharSequence.asImmutable(fragment)

  override fun offset(): Int = offset
  override fun length(): Int = fragment.length
  override fun fragment(): CharSequence = fragment

  override fun equals(other: Any?): Boolean {
    return other is InsertOpImpl &&
           offset == other.offset &&
           fragment.contentEquals(other.fragment)
  }

  override fun hashCode(): Int {
    return 31 * offset + StringUtil.stringHashCode(fragment)
  }

  override fun toString(): String {
    return "ins($offset, ${fragment.quotedForMessage()})"
  }
}

internal class DeleteOpImpl(
  private val offset: Int,
  private val length: Int,
) : DocumentOp.Delete {
  override fun offset(): Int = offset
  override fun length(): Int = length

  override fun equals(other: Any?): Boolean {
    return other is DeleteOpImpl &&
           offset == other.offset &&
           length == other.length
  }

  override fun hashCode(): Int {
    return 31 * offset + length
  }

  override fun toString(): String {
    return "del($offset, len=$length)"
  }
}

internal class ModStampOpImpl(
  private val stamp: Long,
  private val incSequence: Boolean,
) : DocumentOp.ModStamp {
  override fun modStamp(): Long = stamp
  override fun incSequence(): Boolean = incSequence

  override fun toString(): String {
    return "modStamp($stamp, incSequence=$incSequence)"
  }
}

internal class UnmodifiedLinesOpImpl(
  private val startLine: Int,
  private val endLine: Int,
  private val exceptLines: IntArray,
) : DocumentOp.UnmodifiedLines {
  init {
    require(startLine >= 0)
    require(endLine >= 0)
  }

  override fun startLine(): Int = startLine
  override fun endLine(): Int = endLine
  override fun exceptLines(): IntArray = exceptLines

  override fun toString(): String {
    return "unmodifiedLines($startLine, $endLine, exceptLines=${exceptLines.contentToString()})"
  }
}
