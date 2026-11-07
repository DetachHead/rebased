// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.terminal.tests.reworked.hyperlinks

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.terminal.backend.hyperlinks.filter.TerminalFileKind
import com.intellij.terminal.backend.hyperlinks.filter.TerminalVfsFileLookup
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

internal class TerminalVfsFileLookupTest {

  @Rule
  @JvmField
  val tempDir: TemporaryFolder = TemporaryFolder()

  private val fileSystem: LocalFileSystem = mock()
  private val lookup = TerminalVfsFileLookup(fileSystem)

  /** Puts a file named [name] into the fake VFS cache and returns its path. */
  private fun cachedFile(name: String, isDirectory: Boolean = false, isSpecial: Boolean = false): EelPath {
    val file = File(tempDir.root, name)
    val virtualFile: VirtualFile = mock()
    whenever(virtualFile.isValid).thenReturn(true)
    whenever(virtualFile.isDirectory).thenReturn(isDirectory)
    whenever(virtualFile.`is`(VFileProperty.SPECIAL)).thenReturn(isSpecial)
    whenever(fileSystem.findFileByPathIfCached(file.absolutePath)).thenReturn(virtualFile)
    return EelPath.parse(file.absolutePath, LocalEelDescriptor)
  }

  @Test
  fun `regular file`() {
    assertThat(lookup.lookup(cachedFile("file.txt"))).isEqualTo(TerminalFileKind.FILE)
  }

  @Test
  fun `existing directory`() {
    assertThat(lookup.lookup(cachedFile("dir", isDirectory = true))).isEqualTo(TerminalFileKind.DIRECTORY)
  }

  @Test
  fun `special file`() {
    assertThat(lookup.lookup(cachedFile("fifo", isSpecial = true))).isEqualTo(TerminalFileKind.OTHER)
  }

  @Test
  fun `file not in the cache`() {
    val missing = EelPath.parse(File(tempDir.root, "missing.txt").absolutePath, LocalEelDescriptor)
    assertThat(lookup.lookup(missing)).isNull()
  }
}
