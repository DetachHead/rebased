// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.ex

import com.intellij.openapi.editor.impl.DeleteOpImpl
import com.intellij.openapi.editor.impl.InsertOpImpl
import com.intellij.openapi.editor.impl.ModStampOpImpl
import com.intellij.openapi.editor.impl.UnmodifiedLinesOpImpl
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
sealed interface DocumentTextOp {
  /**
   * The position in the document that this op changes.
   */
  fun offset(): Int

  /**
   * The number of characters that this op inserts or deletes.
   */
  fun length(): Int
}

@ApiStatus.Internal
sealed interface DocumentOp {

  interface Insert : DocumentTextOp, DocumentOp {
    /**
     * The fragment of text that this op inserts.
     */
    fun fragment(): CharSequence
  }

  /**
   * A delete carries no content, so [offset] and [length] describe it in full.
   */
  interface Delete : DocumentTextOp, DocumentOp

  interface ModStamp : DocumentOp {
    fun modStamp(): Long
    fun incSequence(): Boolean
  }

  interface UnmodifiedLines : DocumentOp {
    fun startLine(): Int
    fun endLine(): Int
    fun exceptLines(): IntArray
  }

  companion object {
    /**
     * An insert of [fragment] at [offset]. The op detaches itself from [fragment], so a
     * caller may keep changing a mutable sequence afterwards. The copy is free when the
     * sequence is already immutable, which a `String` is.
     *
     * The op does not check the bounds. The document rejects an offset it cannot use, and an empty
     * fragment is a legal op that changes nothing.
     */
    @JvmStatic
    fun insertOp(offset: Int, fragment: CharSequence): Insert {
      return InsertOpImpl(offset, fragment)
    }

    /**
     * A delete of [length] characters at [offset]. A zero length is a legal op that changes nothing.
     */
    @JvmStatic
    fun deleteOp(offset: Int, length: Int): Delete {
      return DeleteOpImpl(offset, length)
    }

    @JvmStatic
    fun modStampOp(modStamp: Long, incSequence: Boolean): ModStamp {
      return ModStampOpImpl(modStamp, incSequence)
    }

    @JvmStatic
    fun unmodifiedLinesOp(startLine: Int, endLine: Int, exceptLines: IntArray): UnmodifiedLines {
      return UnmodifiedLinesOpImpl(startLine, endLine, exceptLines)
    }
  }
}
