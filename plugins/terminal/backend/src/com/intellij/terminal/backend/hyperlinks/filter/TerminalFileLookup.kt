// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.terminal.backend.hyperlinks.filter

import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.provider.asNioPath
import org.jetbrains.annotations.ApiStatus
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Looks up files in the environment of the terminal for the path finders.
 *
 * [TerminalVfsFileLookup] consults the VFS cache and is cheap enough for every output
 * line. [TerminalNioFileLookup] reads the file system and suits the hovered line only.
 */
@ApiStatus.Internal
fun interface TerminalFileLookup {
  /**
   * Returns the kind of the file at [path], or `null` if there is no such file.
   * Symbolic links are followed. The finders link only a [TerminalFileKind.FILE] or a
   * [TerminalFileKind.DIRECTORY], see [lookupLinkTarget].
   */
  fun lookup(path: EelPath): TerminalFileKind?
}

@ApiStatus.Internal
enum class TerminalFileKind {
  FILE,
  DIRECTORY,
  /** A named pipe, a device, a socket, or another file that is not regular. */
  OTHER,
}

/**
 * Returns the kind of the file at [path] if a link to it can be followed, or `null`.
 * A file of kind [TerminalFileKind.OTHER] is never a link target.
 */
internal fun TerminalFileLookup.lookupLinkTarget(path: EelPath): TerminalFileKind? =
  lookup(path)?.takeUnless { it == TerminalFileKind.OTHER }

/**
 * Looks up files already loaded into the VFS, without reading the disk.
 */
@ApiStatus.Internal
class TerminalVfsFileLookup(private val localFileSystem: LocalFileSystem) : TerminalFileLookup {
  override fun lookup(path: EelPath): TerminalFileKind? {
    val nioPath = path.asNioPathOrNull() ?: return null
    val file = localFileSystem.findFileByPathIfCached(nioPath.toString())?.takeIf { it.isValid } ?: return null
    return when {
      file.`is`(VFileProperty.SPECIAL) -> TerminalFileKind.OTHER
      file.isDirectory -> TerminalFileKind.DIRECTORY
      else -> TerminalFileKind.FILE
    }
  }
}

/**
 * Looks up files in the environment of the terminal through its NIO file system.
 *
 * Blocks the calling thread while the environment answers. A path is reported as absent
 * if its environment has no NIO file system, since a link to it could not be followed,
 * or if the environment cannot be reached.
 */
@ApiStatus.Internal
class TerminalNioFileLookup : TerminalFileLookup {
  override fun lookup(path: EelPath): TerminalFileKind? {
    val nioPath = path.asNioPathOrNull() ?: return null
    val attributes = try {
      Files.readAttributes(nioPath, BasicFileAttributes::class.java)
    }
    catch (_: FileSystemException) {
      return null // no such file, or no access to it
    }
    catch (e: Exception) {
      rethrowControlFlowException(e)
      LOG.warn("Failed to look up $path", e)
      return null
    }
    return when {
      attributes.isRegularFile -> TerminalFileKind.FILE
      attributes.isDirectory -> TerminalFileKind.DIRECTORY
      else -> TerminalFileKind.OTHER
    }
  }
}

/** Returns the NIO path, or `null` if the environment has no NIO file system. */
private fun EelPath.asNioPathOrNull(): Path? {
  return try {
    asNioPath()
  }
  catch (_: IllegalArgumentException) {
    null
  }
}

private val LOG = logger<TerminalNioFileLookup>()
