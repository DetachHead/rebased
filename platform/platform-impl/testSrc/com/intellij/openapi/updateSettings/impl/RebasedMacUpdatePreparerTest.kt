// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.EmptyProgressIndicatorBase
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.system.CpuArch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import java.util.concurrent.CancellationException

internal class RebasedMacUpdatePreparerTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `prepares verifies and saves a direct DMG update`() {
    val store = store()
    val staleDirectory = Files.createDirectories(store.versionDirectory("1.2.2"))
    val staleMarker = Files.createFile(staleDirectory.resolve("stale"))
    val targetDirectory = Files.createDirectories(store.versionDirectory(TARGET_VERSION))
    val targetMarker = Files.createFile(targetDirectory.resolve("old"))
    val mountRoot = Files.createDirectories(tempDir.resolve("mounts"))
    val unrelatedMount = Files.createDirectories(mountRoot.resolve("unrelated"))
    val unrelatedMountMarker = Files.createFile(unrelatedMount.resolve("keep"))
    val operations = FakeOperations()
    val indicator = ProgressIndicatorBase()
    operations.useIndicator(indicator)
    val (build, channel) = updateData(blogPostUrl = null)

    val prepared = preparer(store, operations, mountRoot = mountRoot).prepare(build, channel, indicator)

    val versionDirectory = store.versionDirectory(TARGET_VERSION)
    val partDmg = versionDirectory.resolve("rebased.dmg.part")
    val verifiedDmg = versionDirectory.resolve("rebased.dmg")
    val mountDirectory = checkNotNull(operations.mountDirectory)
    val mountedApp = mountDirectory.resolve("Rebased.app")
    val stagedApp = versionDirectory.resolve("Rebased.app")
    val infoPlist = stagedApp.resolve("Contents/Info.plist")
    val executable = stagedApp.resolve("Contents/MacOS/rebased")
    val expected = PreparedRebasedMacUpdate(
      version = TARGET_VERSION,
      strategy = RebasedMacUpdateStrategy.DIRECT,
      stagedApp = stagedApp.toRealPath(),
      verifiedDmg = verifiedDmg.toRealPath(),
      verifiedDmgSha256 = EXPECTED_SHA256,
      brewExecutable = null,
      releasePageUrl = CHANNEL_URL,
    )
    assertEquals(expected, prepared)
    assertEquals(expected, store.load())
    assertEquals(listOf(DOWNLOAD_URL to partDmg), operations.downloads)
    assertEquals(listOf(partDmg to verifiedDmg), operations.moves)
    assertEquals(
      listOf(
        listOf(
          "/usr/bin/hdiutil", "attach", "-readonly", "-noautoopen", "-noautofsck", "-nobrowse",
          "-plist", "-mountpoint", mountDirectory.toString(), verifiedDmg.toString(),
        ),
        listOf("/usr/bin/ditto", mountedApp.toString(), stagedApp.toString()),
        plistCommand("CFBundleIdentifier", infoPlist),
        plistCommand("CFBundleShortVersionString", infoPlist),
        plistCommand("CFBundleExecutable", infoPlist),
        listOf("/usr/bin/lipo", "-archs", executable.toString()),
	        listOf("/usr/bin/codesign", "-dv", stagedApp.toString()),
        listOf("/usr/bin/hdiutil", "detach", ATTACH_DEVICE),
        plistCommand("CFBundleIdentifier", infoPlist),
        plistCommand("CFBundleShortVersionString", infoPlist),
        plistCommand("CFBundleExecutable", infoPlist),
        listOf("/usr/bin/lipo", "-archs", executable.toString()),
	        listOf("/usr/bin/codesign", "-dv", stagedApp.toString()),
      ),
      operations.commands,
    )
    assertEquals(
      listOf(
        SHORT_COMMAND_TIMEOUT_MILLIS,
        DITTO_TIMEOUT_MILLIS,
        SHORT_COMMAND_TIMEOUT_MILLIS,
        SHORT_COMMAND_TIMEOUT_MILLIS,
        SHORT_COMMAND_TIMEOUT_MILLIS,
        SHORT_COMMAND_TIMEOUT_MILLIS,
	        SHORT_COMMAND_TIMEOUT_MILLIS,
        SHORT_COMMAND_TIMEOUT_MILLIS,
        SHORT_COMMAND_TIMEOUT_MILLIS,
        SHORT_COMMAND_TIMEOUT_MILLIS,
        SHORT_COMMAND_TIMEOUT_MILLIS,
        SHORT_COMMAND_TIMEOUT_MILLIS,
	        SHORT_COMMAND_TIMEOUT_MILLIS,
      ),
      operations.commandTimeouts,
    )
    assertEquals("Downloading Rebased update" to false, operations.downloadProgress)
    assertTrue(operations.indeterminateStages.containsAll(listOf(
      "Verifying Rebased update",
      "Mounting Rebased update",
      "Copying Rebased update",
      "Validating Rebased update",
    )))
    assertFalse(Files.exists(staleMarker))
    assertFalse(Files.exists(targetMarker))
    assertFalse(Files.exists(partDmg))
    assertTrue(Files.isRegularFile(verifiedDmg))
    assertTrue(Files.isExecutable(executable))
    assertFalse(Files.exists(mountDirectory))
    assertTrue(mountDirectory.startsWith(mountRoot.toRealPath()))
    assertFalse(mountDirectory.startsWith(versionDirectory))
    assertTrue(Files.exists(unrelatedMountMarker))
  }

  @Test
  fun `unreadable code signature is rejected and not saved`() {
    val store = store()
    val operations = FakeOperations().apply {
      codeSignatureExitCode = 1
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertNull(store.load())
    assertTargetClean(store, operations)
  }

  @Test
  fun `digest mismatch never mounts and cleans target data`() {
    val store = store()
    val operations = FakeOperations().apply {
      digest = "f".repeat(64)
    }
    val indicator = ProgressIndicatorBase()
    operations.useIndicator(indicator)
    val (build, channel) = updateData()

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      preparer(store, operations).prepare(build, channel, indicator)
    }

    assertTrue(operations.commands.isEmpty())
    assertEquals(List(2) { store.versionDirectory(TARGET_VERSION) }, operations.deleted)
    assertFalse(Files.exists(store.versionDirectory(TARGET_VERSION)))
    assertFalse(Files.exists(tempDir.resolve("mounts")))
    assertNull(store.load())
  }

  @Test
  fun `valid retained DMG revalidates`() {
    val store = store()
    val prepared = persistedDirectUpdate(store)
    val indicator = ProgressIndicatorBase()

    val verifiedDmg = preparer(store, DefaultRebasedMacUpdateOperations())
      .revalidateVerifiedDmg(prepared, ABC_SHA256.uppercase(), indicator)

    assertEquals(prepared.verifiedDmg, verifiedDmg)
    assertEquals(1.0, indicator.fraction)
  }

  @Test
  fun `replaced retained DMG and forged persisted digest fail against caller trusted digest`() {
    val store = store()
    val originallyPrepared = persistedDirectUpdate(store)
    val verifiedDmg = checkNotNull(originallyPrepared.verifiedDmg)
    Files.writeString(verifiedDmg, "malicious")
    val maliciousDigest = DefaultRebasedMacUpdateOperations().sha256(verifiedDmg, ProgressIndicatorBase())
    editPersistedState {
      setProperty("verifiedDmgSha256", maliciousDigest)
    }
    val attackerPrepared = checkNotNull(store.load())

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      preparer(store, DefaultRebasedMacUpdateOperations())
        .revalidateVerifiedDmg(attackerPrepared, ABC_SHA256, ProgressIndicatorBase())
    }
  }

  @Test
  fun `stale prepared path fails when canonical store state changes`() {
    val store = store()
    val prepared = persistedDirectUpdate(store)
    val alternateDmg = Files.writeString(store.versionDirectory(TARGET_VERSION).resolve("alternate.dmg"), "abc")
    editPersistedState {
      setProperty("verifiedDmg", alternateDmg.toString())
    }
    assertEquals(alternateDmg.toRealPath(), checkNotNull(store.load()).verifiedDmg)

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      preparer(store, DefaultRebasedMacUpdateOperations())
        .revalidateVerifiedDmg(prepared, ABC_SHA256, ProgressIndicatorBase())
    }
  }

  @Test
  fun `noncanonical persisted DMG path fails revalidation`() {
    val store = store()
    persistedDirectUpdate(store)
    val alternateDmg = Files.writeString(store.versionDirectory(TARGET_VERSION).resolve("alternate.dmg"), "abc")
    editPersistedState {
      setProperty("verifiedDmg", alternateDmg.toString())
    }
    val attackerPrepared = checkNotNull(store.load())

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      preparer(store, DefaultRebasedMacUpdateOperations())
        .revalidateVerifiedDmg(attackerPrepared, ABC_SHA256, ProgressIndicatorBase())
    }
  }

  @Test
  fun `caller trusted digest must be exactly 64 hexadecimal characters`() {
    val store = store()
    val prepared = persistedDirectUpdate(store)
    val invalidDigests = listOf(
      "",
      "a".repeat(63),
      "a".repeat(65),
      "g".repeat(64),
    )

    for (trustedDigest in invalidDigests) {
      assertThrows(RebasedMacUpdateException.Verification::class.java) {
        preparer(store, DefaultRebasedMacUpdateOperations())
          .revalidateVerifiedDmg(prepared, trustedDigest, ProgressIndicatorBase())
      }
    }
  }

  @Test
  fun `process cancellation propagates and cleans partial download`() {
    val cancellation = ProcessCanceledException()
    assertDownloadCancellationPropagates(cancellation)
  }

  @Test
  fun `Java cancellation propagates and cleans partial download`() {
    val cancellation = CancellationException("cancelled")
    assertDownloadCancellationPropagates(cancellation)
  }

  @Test
  fun `nonzero partial attach plist detaches the device`() {
    val store = store()
    val operations = FakeOperations().apply {
      attachExitCode = 1
    }

    assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(store, operations)
    }

    assertEquals(listOf("/usr/bin/hdiutil", "attach"), operations.commands.first().take(2))
    assertEquals(listOf("/usr/bin/hdiutil", "detach", ATTACH_DEVICE), operations.commands.last())
    assertTargetClean(store, operations)
  }

  @Test
  fun `attach failure without mount evidence cleans empty mount directory`() {
    val store = store()
    val operations = FakeOperations().apply {
      attachExitCode = 1
      attachStdout = ""
      createMountedAppOnAttach = false
      detachExitCode = 1
    }

    assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(store, operations)
    }

    assertFalse(Files.exists(checkNotNull(operations.mountDirectory)))
    assertTargetClean(store, operations)
  }

  @Test
  fun `timeout partial attach plist detaches the device`() {
    val store = store()
    val operations = FakeOperations().apply {
      attachTimedOut = true
    }

    val thrown = assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(store, operations)
    }

    assertTrue(thrown.message!!.contains("timed out", ignoreCase = true))
    assertEquals(listOf("/usr/bin/hdiutil", "attach"), operations.commands.first().take(2))
    assertEquals(listOf("/usr/bin/hdiutil", "detach", ATTACH_DEVICE), operations.commands.last())
    assertTargetClean(store, operations)
  }

  @Test
  fun `successful attach plist detaches the first valid device`() {
    val store = store()
    val operations = FakeOperations().apply {
      attachStdout = attachPlist("disk-relative", "/tmp/not-a-device", ATTACH_DEVICE, "/dev/disk99")
    }

    prepare(store, operations)

    assertEquals(
      listOf("/usr/bin/hdiutil", "detach", ATTACH_DEVICE),
      operations.commands.single { it.take(2) == listOf("/usr/bin/hdiutil", "detach") },
    )
  }

  @Test
  fun `malformed attach plist falls back to the mount point`() {
    val store = store()
    val operations = FakeOperations().apply {
      attachStdout = "<plist><dict>"
    }

    prepare(store, operations)

    assertEquals(
      listOf("/usr/bin/hdiutil", "detach", checkNotNull(operations.mountDirectory).toString()),
      operations.commands.single { it.take(2) == listOf("/usr/bin/hdiutil", "detach") },
    )
  }

  @Test
  fun `ordinary device detach failure retries with force`() {
    val store = store()
    val operations = FakeOperations().apply {
      detachExitCode = 1
      forcedDetachExitCode = 0
    }

    prepare(store, operations)

    assertEquals(
      listOf(
        listOf("/usr/bin/hdiutil", "detach", ATTACH_DEVICE),
        listOf("/usr/bin/hdiutil", "detach", "-force", ATTACH_DEVICE),
      ),
      operations.commands.filter { it.take(2) == listOf("/usr/bin/hdiutil", "detach") },
    )
    assertFalse(Files.exists(checkNotNull(operations.mountDirectory)))
  }

  @Test
  fun `attach cancellation propagates after detach attempt`() {
    val store = store()
    val cancellation = ProcessCanceledException()
    val operations = FakeOperations().apply {
      attachFailure = cancellation
    }

    val thrown = assertThrows(ProcessCanceledException::class.java) {
      prepare(store, operations)
    }

    assertSame(cancellation, thrown)
    assertEquals(
      listOf("/usr/bin/hdiutil", "detach", checkNotNull(operations.mountDirectory).toString()),
      operations.commands.last(),
    )
    assertTargetClean(store, operations)
  }

  @Test
  fun `cancellation before attach does not leak a mount directory`() {
    val store = store()
    val operations = FakeOperations().apply {
      cancelAfterMove = true
    }

    assertThrows(ProcessCanceledException::class.java) {
      prepare(store, operations)
    }

    assertTrue(operations.commands.isEmpty())
    assertFalse(Files.exists(tempDir.resolve("mounts")))
    assertTargetClean(store, operations)
  }

  @Test
  fun `copy failure still detaches`() {
    val store = store()
    val operations = FakeOperations().apply {
      copyExitCode = 1
    }

    val thrown = assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(store, operations)
    }

    assertTrue(thrown.message!!.contains("copy", ignoreCase = true))
    assertEquals(
      listOf(
        listOf("/usr/bin/hdiutil", "attach"),
        listOf("/usr/bin/ditto", operations.commands.getOrNull(1)?.getOrNull(1)),
        listOf("/usr/bin/hdiutil", "detach"),
      ),
      commandPrefixes(operations),
    )
    assertTargetClean(store, operations)
  }

  @Test
  fun `copy cancellation propagates after detach`() {
    val store = store()
    val cancellation = ProcessCanceledException()
    val operations = FakeOperations().apply {
      copyFailure = cancellation
    }

    val thrown = assertThrows(ProcessCanceledException::class.java) {
      prepare(store, operations)
    }

    assertSame(cancellation, thrown)
    assertEquals(
      listOf(
        listOf("/usr/bin/hdiutil", "attach"),
        listOf("/usr/bin/ditto", operations.commands.getOrNull(1)?.getOrNull(1)),
        listOf("/usr/bin/hdiutil", "detach"),
      ),
      commandPrefixes(operations),
    )
    assertTargetClean(store, operations)
  }

  @Test
  fun `detach-only failure is preparation error`() {
    val store = store()
    val operations = FakeOperations().apply {
      detachExitCode = 1
    }

    val thrown = assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(store, operations)
    }

    assertTrue(thrown.message!!.contains("detach", ignoreCase = true))
    assertEquals(
      listOf(
        listOf("/usr/bin/hdiutil", "detach", ATTACH_DEVICE),
        listOf("/usr/bin/hdiutil", "detach", "-force", ATTACH_DEVICE),
        listOf("/usr/bin/hdiutil", "detach", checkNotNull(operations.mountDirectory).toString()),
        listOf("/usr/bin/hdiutil", "detach", "-force", checkNotNull(operations.mountDirectory).toString()),
      ),
      operations.commands.filter { it.take(2) == listOf("/usr/bin/hdiutil", "detach") },
    )
    assertEquals(11, operations.commands.size)
    assertTrue(Files.isDirectory(checkNotNull(operations.mountDirectory)))
    assertFalse(checkNotNull(operations.mountDirectory) in operations.deleted)
    assertTargetClean(store, operations)
  }

  @Test
  fun `detach failure is suppressed on copy failure`() {
    val store = store()
    val operations = FakeOperations().apply {
      copyExitCode = 1
      detachExitCode = 1
    }

    val thrown = assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(store, operations)
    }

    assertTrue(thrown.message!!.contains("copy", ignoreCase = true))
    assertEquals(1, thrown.suppressed.size)
    assertTrue(thrown.suppressed.single() is RebasedMacUpdateException.Preparation)
    assertTrue(thrown.suppressed.single().message!!.contains("detach", ignoreCase = true))
    assertTrue(Files.isDirectory(checkNotNull(operations.mountDirectory)))
    assertTargetClean(store, operations)
  }

  @Test
  fun `copy cancellation remains primary when detach fails`() {
    val store = store()
    val cancellation = ProcessCanceledException()
    val operations = FakeOperations().apply {
      copyFailure = cancellation
      detachExitCode = 1
    }

    val thrown = assertThrows(ProcessCanceledException::class.java) {
      prepare(store, operations)
    }

    assertSame(cancellation, thrown)
    assertEquals(1, thrown.suppressed.size)
    assertTrue(thrown.suppressed.single() is RebasedMacUpdateException.Preparation)
    assertTrue(Files.isDirectory(checkNotNull(operations.mountDirectory)))
    assertTargetClean(store, operations)
  }

  @Test
  fun `invalid bundle identifier is rejected and detached`() {
    val store = store()
    val operations = FakeOperations().apply {
      bundleIdentifier = "com.example.imposter"
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertEquals(listOf("/usr/bin/hdiutil", "detach"), commandPrefixes(operations).last())
    assertTargetClean(store, operations)
  }

  @Test
  fun `wrong bundle version is rejected and detached`() {
    val store = store()
    val operations = FakeOperations().apply {
      bundleVersion = "9.9.9"
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertEquals(listOf("/usr/bin/hdiutil", "detach"), commandPrefixes(operations).last())
    assertTargetClean(store, operations)
  }

  @Test
  fun `bundle identifier comparison is exact`() {
    val store = store()
    val operations = FakeOperations().apply {
      bundleIdentifier = " $BUNDLE_ID "
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertTargetClean(store, operations)
  }

  @Test
  fun `bundle version comparison is exact`() {
    val store = store()
    val operations = FakeOperations().apply {
      bundleVersion = " $TARGET_VERSION "
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertTargetClean(store, operations)
  }

  @Test
  fun `mismatched CFBundleExecutable is rejected and detached`() {
    val store = store()
    val operations = FakeOperations().apply {
      bundleExecutable = "imposter"
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertEquals(listOf("/usr/bin/hdiutil", "detach"), commandPrefixes(operations).last())
    assertTargetClean(store, operations)
  }

  @Test
  fun `wrong Apple Silicon architecture is rejected and detached`() {
    val store = store()
    val operations = FakeOperations().apply {
      architectures = "x86_64"
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations, arch = CpuArch.ARM64)
    }

    assertEquals(listOf("/usr/bin/hdiutil", "detach"), commandPrefixes(operations).last())
    assertTargetClean(store, operations)
  }

  @Test
  fun `wrong Intel architecture is rejected and detached`() {
    val store = store()
    val operations = FakeOperations().apply {
      architectures = "arm64"
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations, arch = CpuArch.X86_64)
    }

    assertEquals(listOf("/usr/bin/hdiutil", "detach"), commandPrefixes(operations).last())
    assertTargetClean(store, operations)
  }

  @Test
  fun `missing staged app is rejected and detached`() {
    val store = store()
    val operations = FakeOperations().apply {
      stagedBundleMode = StagedBundleMode.MISSING_APP
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertEquals(listOf("/usr/bin/hdiutil", "detach"), commandPrefixes(operations).last())
    assertTargetClean(store, operations)
  }

  @Test
  fun `symlinked staged app is rejected without deleting its target`() {
    val store = store()
    val operations = FakeOperations().apply {
      stagedBundleMode = StagedBundleMode.SYMLINK_APP
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertTrue(Files.isDirectory(operations.outsideArtifact!!))
    assertEquals(listOf("/usr/bin/hdiutil", "detach"), commandPrefixes(operations).last())
    assertTargetClean(store, operations)
  }

  @Test
  fun `symlinked Contents directory is rejected and detached`() {
    val store = store()
    val operations = FakeOperations().apply {
      stagedBundleMode = StagedBundleMode.SYMLINK_CONTENTS
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertEquals(listOf("/usr/bin/hdiutil", "detach"), commandPrefixes(operations).last())
    assertTargetClean(store, operations)
  }

  @Test
  fun `symlinked MacOS directory is rejected and detached`() {
    val store = store()
    val operations = FakeOperations().apply {
      stagedBundleMode = StagedBundleMode.SYMLINK_MACOS
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertEquals(listOf("/usr/bin/hdiutil", "detach"), commandPrefixes(operations).last())
    assertTargetClean(store, operations)
  }

  @Test
  fun `missing staged binary is rejected and detached`() {
    val store = store()
    val operations = FakeOperations().apply {
      stagedBundleMode = StagedBundleMode.MISSING_BINARY
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertEquals(listOf("/usr/bin/hdiutil", "detach"), commandPrefixes(operations).last())
    assertTargetClean(store, operations)
  }

  @Test
  fun `non-executable staged binary is rejected and detached`() {
    val store = store()
    val operations = FakeOperations().apply {
      stagedBundleMode = StagedBundleMode.NON_EXECUTABLE_BINARY
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertEquals(listOf("/usr/bin/hdiutil", "detach"), commandPrefixes(operations).last())
    assertTargetClean(store, operations)
  }

  @Test
  fun `symlinked binary is rejected without cleaning outside version directory`() {
    val store = store()
    val operations = FakeOperations().apply {
      stagedBundleMode = StagedBundleMode.SYMLINK_BINARY
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertTrue(Files.isRegularFile(operations.outsideArtifact!!))
    assertTrue(
      operations.deleted.all {
        it == store.versionDirectory(TARGET_VERSION) || it.startsWith(tempDir.resolve("mounts").toRealPath())
      },
    )
    assertEquals(listOf("/usr/bin/hdiutil", "detach"), commandPrefixes(operations).last())
    assertTargetClean(store, operations)
  }

  @Test
  fun `cleanup refuses to follow a symlinked store root`() {
    val store = store()
    val operations = FakeOperations()
    val outsideRoot = Files.createDirectories(tempDir.resolve("outside-root"))
    val outsideVersionDirectory = Files.createDirectories(
      outsideRoot.resolve(store.versionDirectory(TARGET_VERSION).fileName),
    )
    val outsideMarker = Files.createFile(outsideVersionDirectory.resolve("keep"))
    Files.createSymbolicLink(tempDir.resolve("updates"), outsideRoot)

    assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(store, operations)
    }

    assertTrue(Files.exists(outsideMarker))
    assertTrue(operations.deleted.isEmpty())
  }

  @Test
  fun `final identity failure is not saved`() {
    val store = store()
    val operations = FakeOperations().apply {
      finalBundleIdentifier = "com.example.changed"
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertEquals(listOf("/usr/bin/hdiutil", "detach"), commandPrefixes(operations)[7])
    assertEquals(listOf("/usr/bin/plutil", "-extract"), commandPrefixes(operations).last())
    assertNull(store.load())
    assertTargetClean(store, operations)
  }

  @Test
  fun `final CFBundleExecutable mutation is not saved`() {
    val store = store()
    val operations = FakeOperations().apply {
      finalBundleExecutable = "imposter"
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertNull(store.load())
    assertTargetClean(store, operations)
  }

  @Test
  fun `final version mutation is not saved`() {
    val store = store()
    val operations = FakeOperations().apply {
      finalBundleVersion = "9.9.9"
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertTargetClean(store, operations)
  }

  @Test
  fun `final architecture mutation is not saved`() {
    val store = store()
    val operations = FakeOperations().apply {
      finalArchitectures = "x86_64"
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations, arch = CpuArch.ARM64)
    }

    assertTargetClean(store, operations)
  }

  @Test
  fun `final binary symlink mutation is not saved`() {
    val store = store()
    val operations = FakeOperations().apply {
      symlinkBinaryAfterInitialValidation = true
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertTrue(Files.isRegularFile(operations.outsideArtifact!!))
    assertTargetClean(store, operations)
  }

  @Test
  fun `final executable permission mutation is not saved`() {
    val store = store()
    val operations = FakeOperations().apply {
      removeExecutablePermissionAfterInitialValidation = true
    }

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      prepare(store, operations)
    }

    assertNull(store.load())
    assertTargetClean(store, operations)
  }

  @Test
  fun `cancellation during final validation is not saved`() {
    val store = store()
    val cancellation = ProcessCanceledException()
    val operations = FakeOperations().apply {
      finalValidationFailure = cancellation
    }

    val thrown = assertThrows(ProcessCanceledException::class.java) {
      prepare(store, operations)
    }

    assertSame(cancellation, thrown)
    assertNull(store.load())
    assertTargetClean(store, operations)
  }

  @Test
  fun `cancellation immediately before READY does not save`() {
    val store = store()
    val operations = FakeOperations().apply {
      cancelAfterFinalValidation = true
    }

    assertThrows(ProcessCanceledException::class.java) {
      prepare(store, operations)
    }

    assertNull(store.load())
    assertTargetClean(store, operations)
  }

  @Test
  fun `network failure is typed download error and cleans partial data`() {
    val store = store()
    val failure = IOException("connection reset")
    val operations = FakeOperations().apply {
      downloadFailure = failure
    }

    val thrown = assertThrows(RebasedMacUpdateException.Download::class.java) {
      prepare(store, operations)
    }

    assertSame(failure, thrown.cause)
    assertTargetClean(store, operations)
  }

  @Test
  fun `source detection failure discards an existing direct prepared update`() {
    val store = store()
    val prepared = persistedDirectUpdate(store, "1.2.2")
    val oldVersionDirectory = store.versionDirectory(prepared.version)
    val failure = IOException("source detection failed")
    val (build, channel) = updateData()

    val thrown = assertThrows(IOException::class.java) {
      RebasedMacUpdatePreparer(
        store = store,
        operations = FakeOperations(),
        sourceDetector = { throw failure },
        arch = CpuArch.ARM64,
        mountRoot = tempDir.resolve("mounts"),
      ).prepare(build, channel, ProgressIndicatorBase())
    }

    assertSame(failure, thrown)
    assertFalse(Files.exists(oldVersionDirectory))
    assertNull(store.load())
  }

  @Test
  fun `direct preflight failure discards an existing direct prepared update`() {
    val store = store()
    val prepared = persistedDirectUpdate(store, "1.2.2")
    val oldVersionDirectory = store.versionDirectory(prepared.version)
    val operations = FakeOperations()

    assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(store, operations, data = updateData(downloadUrl = "http://example.com/rebased.dmg"))
    }

    assertTrue(operations.downloads.isEmpty())
    assertFalse(Files.exists(oldVersionDirectory))
    assertNull(store.load())
  }

  @Test
  fun `malformed prepared state is cleared without trusting it as deletion authority`() {
    val store = store()
    val untrustedDirectory = Files.createDirectories(tempDir.resolve("untrusted-version"))
    val untrustedMarker = Files.writeString(untrustedDirectory.resolve("keep"), "keep")
    Files.createDirectories(tempDir.resolve("updates"))
    Files.writeString(
      tempDir.resolve("updates/prepared.properties"),
      """
        version=1.2.2
        strategy=DIRECT
        stagedApp=${untrustedDirectory.resolve("Rebased.app")}
        verifiedDmg=${untrustedDirectory.resolve("rebased.dmg")}
        verifiedDmgSha256=$EXPECTED_SHA256
        releasePageUrl=$RELEASE_URL
      """.trimIndent(),
    )
    val failure = IOException("source detection failed")
    val (build, channel) = updateData()

    assertThrows(IOException::class.java) {
      RebasedMacUpdatePreparer(
        store = store,
        operations = FakeOperations(),
        sourceDetector = { throw failure },
        arch = CpuArch.ARM64,
        mountRoot = tempDir.resolve("mounts"),
      ).prepare(build, channel, ProgressIndicatorBase())
    }

    assertFalse(Files.exists(tempDir.resolve("updates/prepared.properties")))
    assertTrue(Files.exists(untrustedMarker))
    assertNull(store.load())
  }

  @Test
  fun `blank target version clears stale prepared state before direct preflight`() {
    val store = store()
    val operations = FakeOperations()
    seedPreparedHomebrewUpdate(store, homebrewExecutable())

    assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(store, operations, data = updateData(version = " ", buildNumber = TARGET_VERSION))
    }

    assertTrue(operations.downloads.isEmpty())
    assertTrue(operations.deleted.isEmpty())
    assertNull(store.load())
  }

  @Test
  fun `invalid download URL is rejected before creating target data`() {
    val store = store()
    val operations = FakeOperations()
    seedPreparedHomebrewUpdate(store, homebrewExecutable())

    assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(store, operations, data = updateData(downloadUrl = "http://example.com/rebased.dmg"))
    }

    assertTrue(operations.downloads.isEmpty())
    assertTrue(operations.deleted.isEmpty())
    assertNull(store.load())
  }

  @Test
  fun `missing digest is rejected before creating target data`() {
    val store = store()
    val operations = FakeOperations()
    seedPreparedHomebrewUpdate(store, homebrewExecutable())

    assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(store, operations, data = updateData(digest = null))
    }

    assertTrue(operations.downloads.isEmpty())
    assertTrue(operations.deleted.isEmpty())
    assertNull(store.load())
  }

  @Test
  fun `malformed release page clears stale prepared state before direct preflight`() {
    val store = store()
    val operations = FakeOperations()
    seedPreparedHomebrewUpdate(store, homebrewExecutable())

    assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(
        store,
        operations,
        data = updateData(blogPostUrl = "://malformed", channelUrl = "not a URI"),
      )
    }

    assertTrue(operations.downloads.isEmpty())
    assertTrue(operations.deleted.isEmpty())
    assertNull(store.load())
  }

  @Test
  fun `invalid release page is rejected before creating target data`() {
    val store = store()
    val operations = FakeOperations()
    seedPreparedHomebrewUpdate(store, homebrewExecutable())

    assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(
        store,
        operations,
        data = updateData(blogPostUrl = null, channelUrl = "http://example.com/releases/latest"),
      )
    }

    assertTrue(operations.downloads.isEmpty())
    assertTrue(operations.deleted.isEmpty())
    assertNull(store.load())
  }

  @Test
  fun `Homebrew unavailable never falls back to direct preparation`() {
    val store = store()
    val operations = FakeOperations()
    seedPreparedHomebrewUpdate(store, homebrewExecutable())

    assertThrows(RebasedMacUpdateException.HomebrewUnavailable::class.java) {
      prepare(
        store,
        operations,
        source = RebasedMacInstallationSource.HomebrewUnavailable,
        data = updateData(downloadUrl = "http://example.com/rebased.zip", digest = null),
      )
    }

    assertTrue(operations.downloads.isEmpty())
    assertTrue(operations.moves.isEmpty())
    assertTrue(operations.commands.isEmpty())
    assertTrue(operations.deleted.isEmpty())
    assertNull(store.load())
  }

  @Test
  fun `constructing a Homebrew preparer preserves the prepared update`() {
    val store = store()
    val brewExecutable = homebrewExecutable()
    val prepared = seedPreparedHomebrewUpdate(store, brewExecutable)

    preparer(
      store,
      FakeOperations(),
      source = RebasedMacInstallationSource.Homebrew(brewExecutable),
    )

    assertEquals(prepared, store.load())
  }

  @Test
  fun `blank target version clears stale prepared state before Homebrew preflight`() {
    val store = store()
    val operations = FakeOperations()
    val brewExecutable = homebrewExecutable()
    seedPreparedHomebrewUpdate(store, brewExecutable)

    assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(
        store,
        operations,
        source = RebasedMacInstallationSource.Homebrew(brewExecutable),
        data = updateData(version = " ", buildNumber = TARGET_VERSION),
      )
    }

    assertTrue(operations.commands.isEmpty())
    assertHomebrewDidNotSave(store, operations)
  }

  @Test
  fun `malformed release page clears stale prepared state before Homebrew preflight`() {
    val store = store()
    val operations = FakeOperations()
    val brewExecutable = homebrewExecutable()
    seedPreparedHomebrewUpdate(store, brewExecutable)

    assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(
        store,
        operations,
        source = RebasedMacInstallationSource.Homebrew(brewExecutable),
        data = updateData(blogPostUrl = "://malformed", channelUrl = "not a URI"),
      )
    }

    assertTrue(operations.commands.isEmpty())
    assertHomebrewDidNotSave(store, operations)
  }

  @Test
  fun `non HTTPS release page clears stale prepared state before Homebrew preflight`() {
    val store = store()
    val operations = FakeOperations()
    val brewExecutable = homebrewExecutable()
    seedPreparedHomebrewUpdate(store, brewExecutable)

    assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepare(
        store,
        operations,
        source = RebasedMacInstallationSource.Homebrew(brewExecutable),
        data = updateData(blogPostUrl = null, channelUrl = "http://example.com/releases/latest"),
      )
    }

    assertTrue(operations.commands.isEmpty())
    assertHomebrewDidNotSave(store, operations)
  }

  @Test
  fun `prepares and saves a Homebrew update with exact command arguments`() {
    val store = store()
    val operations = FakeOperations()
    val brewExecutable = homebrewExecutable(symlink = true)
    val indicator = TestProgressIndicator()
    operations.useIndicator(indicator)
    val (build, channel) = updateData(
      downloadUrl = "http://example.com/not-a-direct-update.zip",
      digest = null,
    )

    val prepared = preparer(
      store,
      operations,
      source = RebasedMacInstallationSource.Homebrew(brewExecutable.resolveSibling("../bin/brew")),
    ).prepare(build, channel, indicator)

    val expected = PreparedRebasedMacUpdate(
      version = TARGET_VERSION,
      strategy = RebasedMacUpdateStrategy.HOMEBREW,
      stagedApp = null,
      verifiedDmg = null,
      verifiedDmgSha256 = null,
      brewExecutable = brewExecutable,
      releasePageUrl = RELEASE_URL,
    )
    assertTrue(Files.isSymbolicLink(brewExecutable))
    assertEquals(expected, prepared)
    assertEquals(expected, store.load())
    assertEquals(
      listOf(
        listOf(brewExecutable.toString(), "update"),
        listOf(brewExecutable.toString(), "info", "--json=v2", "--cask", "rebased"),
        listOf(brewExecutable.toString(), "fetch", "--cask", "rebased"),
      ),
      operations.commands,
    )
    assertEquals(
      listOf(
        HOMEBREW_LONG_TIMEOUT_MILLIS,
        SHORT_COMMAND_TIMEOUT_MILLIS,
        HOMEBREW_LONG_TIMEOUT_MILLIS,
      ),
      operations.commandTimeouts,
    )
    assertEquals(
      listOf(
        "Refreshing Homebrew",
        "Checking the Homebrew Rebased version",
        "Fetching the Rebased update with Homebrew",
      ),
      operations.indeterminateStages,
    )
    assertEquals(0.0, indicator.fraction)
    assertTrue(operations.downloads.isEmpty())
    assertTrue(operations.moves.isEmpty())
    assertTrue(operations.deleted.isEmpty())
    assertFalse(Files.exists(store.versionDirectory(TARGET_VERSION)))
  }

  @Test
  fun `Homebrew update failure retains bounded diagnostic heads and actionable tails`() {
    val store = store()
    val operations = FakeOperations().apply {
      brewUpdateResult = RebasedCommandResult(
        exitCode = 1,
        stdout = "stdout-start\n" + "x".repeat(4_000) + "\nstdout-final-context",
        stderr = "stderr-start\n" + "y".repeat(4_000) + "\nError: Homebrew update failed because the tap is unavailable",
      )
    }
    val brewExecutable = homebrewExecutable()
    seedPreparedHomebrewUpdate(store, brewExecutable)

    val thrown = assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepareHomebrew(store, operations, brewExecutable)
    }

    val message = checkNotNull(thrown.message)
    assertTrue(message.contains("stdout-start"))
    assertTrue(message.contains("stdout-final-context"))
    assertTrue(message.contains("stderr-start"))
    assertTrue(message.contains("Error: Homebrew update failed because the tap is unavailable"))
    assertTrue(message.contains("output truncated"))
    assertTrue(message.indexOf("stderr:") < message.indexOf("stdout:"))
    assertTrue(message.length < 2_500)
    assertEquals(listOf(listOf(brewExecutable.toString(), "update")), operations.commands)
    assertHomebrewDidNotSave(store, operations)
  }

  @Test
  fun `Homebrew update timeout includes command diagnostics`() {
    val store = store()
    val operations = FakeOperations().apply {
      brewUpdateResult = RebasedCommandResult(
        exitCode = -1,
        stdout = "update stdout",
        stderr = "update stderr",
        timedOut = true,
      )
    }
    val brewExecutable = homebrewExecutable()

    val thrown = assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepareHomebrew(store, operations, brewExecutable)
    }

    assertTrue(thrown.message!!.contains("timed out", ignoreCase = true))
    assertTrue(thrown.message!!.contains("update stdout"))
    assertTrue(thrown.message!!.contains("update stderr"))
    assertEquals(listOf(listOf(brewExecutable.toString(), "update")), operations.commands)
    assertHomebrewDidNotSave(store, operations)
  }

  @Test
  fun `malformed Homebrew info JSON is rejected before fetch`() {
    assertInvalidHomebrewInfo("not JSON")
  }

  @Test
  fun `Homebrew info without casks is rejected before fetch`() {
    assertInvalidHomebrewInfo("""{"formulae":[]}""")
  }

  @Test
  fun `Homebrew info with multiple casks is rejected before fetch`() {
    assertInvalidHomebrewInfo(
      """{"casks":[{"version":"$TARGET_VERSION"},{"version":"$TARGET_VERSION"}]}""",
    )
  }

  @Test
  fun `Homebrew info without a cask version is rejected before fetch`() {
    assertInvalidHomebrewInfo("""{"casks":[{}]}""")
  }

  @Test
  fun `Homebrew info with a blank cask version is rejected before fetch`() {
    assertInvalidHomebrewInfo("""{"casks":[{"version":"  "}]}""")
  }

  @Test
  fun `Homebrew info with a nonstring cask version is rejected before fetch`() {
    assertInvalidHomebrewInfo("""{"casks":[{"version":123}]}""")
  }

  @Test
  fun `lagging Homebrew cask version is recoverable and does not fetch direct`() {
    assertHomebrewVersionMismatch("1.2.2")
  }

  @Test
  fun `different Homebrew cask version is recoverable and does not fetch direct`() {
    assertHomebrewVersionMismatch("1.2.3-beta")
  }

  @Test
  fun `Homebrew fetch failure clears stale prepared state`() {
    val store = store()
    val operations = FakeOperations().apply {
      brewFetchResult = RebasedCommandResult(1, "fetch stdout", "fetch stderr")
    }
    val brewExecutable = homebrewExecutable()
    seedPreparedHomebrewUpdate(store, brewExecutable)

    val thrown = assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepareHomebrew(store, operations, brewExecutable)
    }

    assertTrue(thrown.message!!.contains("fetch stdout"))
    assertTrue(thrown.message!!.contains("fetch stderr"))
    assertEquals(listOf("update", "info", "fetch"), operations.commands.map { it[1] })
    assertHomebrewDidNotSave(store, operations)
  }

  @Test
  fun `Homebrew fetch timeout does not save`() {
    val store = store()
    val operations = FakeOperations().apply {
      brewFetchResult = RebasedCommandResult(-1, "", "fetch timed out", timedOut = true)
    }
    val brewExecutable = homebrewExecutable()

    val thrown = assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepareHomebrew(store, operations, brewExecutable)
    }

    assertTrue(thrown.message!!.contains("timed out", ignoreCase = true))
    assertEquals(listOf("update", "info", "fetch"), operations.commands.map { it[1] })
    assertHomebrewDidNotSave(store, operations)
  }

  @Test
  fun `cancellation before prepare preserves stale state without detecting source`() {
    val store = store()
    val operations = FakeOperations()
    val brewExecutable = homebrewExecutable()
    val prepared = seedPreparedHomebrewUpdate(store, brewExecutable)
    val indicator = TestProgressIndicator().apply { cancel() }

    assertThrows(ProcessCanceledException::class.java) {
      val (build, channel) = updateData()
      RebasedMacUpdatePreparer(
        store = store,
        operations = operations,
        sourceDetector = { error("Source detection must not run for an already canceled prepare") },
        arch = CpuArch.ARM64,
        mountRoot = tempDir.resolve("mounts"),
      ).prepare(build, channel, indicator)
    }

    assertTrue(operations.commands.isEmpty())
    assertEquals(prepared, store.load())
  }

  @Test
  fun `Homebrew final save failure clears stale prepared state`() {
    val root = tempDir.toRealPath().resolve("updates")
    val initialStore = RebasedMacUpdateStore(root)
    val operations = FakeOperations()
    val brewExecutable = homebrewExecutable()
    seedPreparedHomebrewUpdate(initialStore, brewExecutable)
    val failingStore = RebasedMacUpdateStore(root) { _, _, _ ->
      throw IOException("disk full")
    }

    val thrown = assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepareHomebrew(failingStore, operations, brewExecutable)
    }

    assertTrue(thrown.message!!.contains("save", ignoreCase = true))
    assertEquals(listOf("update", "info", "fetch"), operations.commands.map { it[1] })
    assertHomebrewDidNotSave(failingStore, operations)
  }

  @Test
  fun `Homebrew cancellation during update propagates without save`() {
    assertHomebrewCommandCancellation("update", expectedCommands = listOf("update"))
  }

  @Test
  fun `Homebrew cancellation during version check propagates without save`() {
    assertHomebrewCommandCancellation("info", expectedCommands = listOf("update", "info"))
  }

  @Test
  fun `Homebrew cancellation during fetch propagates without save`() {
    assertHomebrewCommandCancellation("fetch", expectedCommands = listOf("update", "info", "fetch"))
  }

  @Test
  fun `Homebrew cancellation after fetch propagates before save`() {
    val store = store()
    val operations = FakeOperations().apply {
      cancelAfterBrewCommand = "fetch"
    }
    val brewExecutable = homebrewExecutable()

    assertThrows(ProcessCanceledException::class.java) {
      prepareHomebrew(store, operations, brewExecutable)
    }

    assertEquals(listOf("update", "info", "fetch"), operations.commands.map { it[1] })
    assertHomebrewDidNotSave(store, operations)
  }

  @Test
  fun `relative Homebrew executable is rejected before commands`() {
    val store = store()
    val operations = FakeOperations()
    seedPreparedHomebrewUpdate(store, homebrewExecutable())

    assertThrows(RebasedMacUpdateException.HomebrewUnavailable::class.java) {
      prepareHomebrew(store, operations, Path.of("bin/brew"))
    }

    assertTrue(operations.commands.isEmpty())
    assertHomebrewDidNotSave(store, operations)
  }

  @Test
  fun `missing Homebrew executable clears stale prepared state before commands`() {
    val store = store()
    val operations = FakeOperations()
    seedPreparedHomebrewUpdate(store, homebrewExecutable())
    val brewExecutable = tempDir.resolve("missing-homebrew/bin/brew").toAbsolutePath()

    assertThrows(RebasedMacUpdateException.HomebrewUnavailable::class.java) {
      prepareHomebrew(store, operations, brewExecutable)
    }

    assertTrue(operations.commands.isEmpty())
    assertHomebrewDidNotSave(store, operations)
  }

  @Test
  fun `nonexecutable Homebrew executable clears stale prepared state before commands`() {
    val store = store()
    val operations = FakeOperations()
    seedPreparedHomebrewUpdate(store, homebrewExecutable())
    val brewExecutable = tempDir.resolve("nonexecutable-homebrew/bin/brew").toAbsolutePath()
    Files.createDirectories(brewExecutable.parent)
    Files.writeString(brewExecutable, "#!/bin/sh\n")
    assertFalse(Files.isExecutable(brewExecutable))

    assertThrows(RebasedMacUpdateException.HomebrewUnavailable::class.java) {
      prepareHomebrew(store, operations, brewExecutable)
    }

    assertTrue(operations.commands.isEmpty())
    assertHomebrewDidNotSave(store, operations)
  }

  @Test
  fun `Homebrew executable removed after fetch is not saved`() {
    val store = store()
    val operations = FakeOperations()
    val brewExecutable = homebrewExecutable()
    operations.afterBrewFetch = { Files.delete(brewExecutable) }

    assertThrows(RebasedMacUpdateException.HomebrewUnavailable::class.java) {
      prepareHomebrew(store, operations, brewExecutable)
    }

    assertEquals(listOf("update", "info", "fetch"), operations.commands.map { it[1] })
    assertHomebrewDidNotSave(store, operations)
  }

  @Test
  fun `Homebrew executable made nonexecutable after fetch is not saved`() {
    val store = store()
    val operations = FakeOperations()
    val brewExecutable = homebrewExecutable()
    operations.afterBrewFetch = { makeNonExecutable(brewExecutable) }

    assertThrows(RebasedMacUpdateException.HomebrewUnavailable::class.java) {
      prepareHomebrew(store, operations, brewExecutable)
    }

    assertEquals(listOf("update", "info", "fetch"), operations.commands.map { it[1] })
    assertHomebrewDidNotSave(store, operations)
  }

  @Test
  fun `default operations hash with progress and recursively delete files`() {
    val operations = DefaultRebasedMacUpdateOperations()
    val directory = Files.createDirectories(tempDir.resolve("operations/subdirectory"))
    val file = Files.writeString(directory.resolve("payload"), "abc")
    val indicator = ProgressIndicatorBase()

    assertEquals(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      operations.sha256(file, indicator),
    )
    assertEquals(1.0, indicator.fraction)

    operations.deleteRecursively(directory.parent)

    assertFalse(Files.exists(directory.parent))
  }

  @Test
  fun `default operations hashing is cancellable between chunks`() {
    val operations = DefaultRebasedMacUpdateOperations()
    val file = Files.write(tempDir.resolve("large.dmg"), ByteArray(2 * 1024 * 1024) { it.toByte() })
    val indicator = TestProgressIndicator(cancelOnProgress = true)

    assertThrows(ProcessCanceledException::class.java) {
      operations.sha256(file, indicator)
    }

    assertTrue(indicator.fraction > 0.0)
    assertTrue(indicator.fraction < 1.0)
  }

  @Test
  fun `default operations report subprocess timeout`() {
    val result = DefaultRebasedMacUpdateOperations().run(
      listOf("/bin/sleep", "1"),
      ProgressIndicatorBase(),
      10,
    )

    assertTrue(result.timedOut)
  }

  @Test
  fun `default operations fall back when atomic move is unavailable`() {
    val attempts = mutableListOf<Set<CopyOption>>()
    val operations = DefaultRebasedMacUpdateOperations(
      move = { source, target, options ->
        attempts.add(options.toSet())
        if (ATOMIC_MOVE in options) {
          throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "unsupported")
        }
        Files.move(source, target, *options)
      },
    )
    val source = Files.writeString(tempDir.resolve("source.dmg.part"), "verified")
    val target = tempDir.resolve("target.dmg")

    operations.moveAtomically(source, target)

    assertEquals(
      listOf(
        setOf(ATOMIC_MOVE, REPLACE_EXISTING),
        setOf(REPLACE_EXISTING),
      ),
      attempts,
    )
    assertEquals("verified", Files.readString(target))
    assertFalse(Files.exists(source))
  }

  private fun assertDownloadCancellationPropagates(cancellation: RuntimeException) {
    val store = store()
    val operations = FakeOperations().apply {
      downloadFailure = cancellation
    }
    val indicator = ProgressIndicatorBase()
    operations.useIndicator(indicator)
    val (build, channel) = updateData()

    val thrown = assertThrows(cancellation.javaClass) {
      preparer(store, operations).prepare(build, channel, indicator)
    }

    assertSame(cancellation, thrown)
    assertEquals(List(2) { store.versionDirectory(TARGET_VERSION) }, operations.deleted)
    assertFalse(Files.exists(store.versionDirectory(TARGET_VERSION)))
    assertNull(store.load())
  }

  private fun assertInvalidHomebrewInfo(stdout: String) {
    val store = store()
    val operations = FakeOperations().apply {
      brewInfoResult = RebasedCommandResult(0, stdout, "")
    }
    val brewExecutable = homebrewExecutable()
    seedPreparedHomebrewUpdate(store, brewExecutable)

    val thrown = assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepareHomebrew(store, operations, brewExecutable)
    }

    assertTrue(thrown.message!!.contains("metadata", ignoreCase = true))
    assertEquals(listOf("update", "info"), operations.commands.map { it[1] })
    assertHomebrewDidNotSave(store, operations)
  }

  private fun assertHomebrewVersionMismatch(availableVersion: String) {
    val store = store()
    val operations = FakeOperations().apply {
      brewInfoResult = RebasedCommandResult(
        0,
        """{"casks":[{"version":"$availableVersion"}]}""",
        "",
      )
    }
    val brewExecutable = homebrewExecutable()
    seedPreparedHomebrewUpdate(store, brewExecutable)

    val thrown = assertThrows(RebasedMacUpdateException.Preparation::class.java) {
      prepareHomebrew(store, operations, brewExecutable)
    }

    assertTrue(thrown.message!!.contains(TARGET_VERSION))
    assertTrue(thrown.message!!.contains(availableVersion))
    assertEquals(listOf("update", "info"), operations.commands.map { it[1] })
    assertHomebrewDidNotSave(store, operations)
  }

  private fun assertHomebrewCommandCancellation(command: String, expectedCommands: List<String>) {
    val store = store()
    val cancellation = ProcessCanceledException()
    val operations = FakeOperations().apply {
      brewFailureCommand = command
      brewCommandFailure = cancellation
    }
    val brewExecutable = homebrewExecutable()

    val thrown = assertThrows(ProcessCanceledException::class.java) {
      prepareHomebrew(store, operations, brewExecutable)
    }

    assertSame(cancellation, thrown)
    assertEquals(expectedCommands, operations.commands.map { it[1] })
    assertHomebrewDidNotSave(store, operations)
  }

  private fun prepareHomebrew(
    store: RebasedMacUpdateStore,
    operations: FakeOperations,
    brewExecutable: Path,
  ): PreparedRebasedMacUpdate =
    prepare(
      store,
      operations,
      source = RebasedMacInstallationSource.Homebrew(brewExecutable),
    )

  private fun assertHomebrewDidNotSave(store: RebasedMacUpdateStore, operations: FakeOperations) {
    assertTrue(operations.downloads.isEmpty())
    assertTrue(operations.moves.isEmpty())
    assertTrue(operations.deleted.isEmpty())
    assertNull(store.load())
  }

  private fun homebrewExecutable(symlink: Boolean = false): Path {
    val executable = tempDir.resolve(if (symlink) "Cellar/homebrew/bin/brew" else "opt/homebrew/bin/brew")
    Files.createDirectories(executable.parent)
    Files.writeString(executable, "#!/bin/sh\n")
    assertTrue(executable.toFile().setExecutable(true), "Failed to make $executable executable")
    if (!symlink) return executable.toAbsolutePath().normalize()

    val stablePath = tempDir.resolve("opt/homebrew/bin/brew")
    Files.createDirectories(stablePath.parent)
    Files.createSymbolicLink(stablePath, executable)
    return stablePath.toAbsolutePath().normalize()
  }

  private fun seedPreparedHomebrewUpdate(
    store: RebasedMacUpdateStore,
    brewExecutable: Path,
  ): PreparedRebasedMacUpdate {
    store.save(
      PreparedRebasedMacUpdate(
        version = "1.2.2",
        strategy = RebasedMacUpdateStrategy.HOMEBREW,
        stagedApp = null,
        verifiedDmg = null,
        verifiedDmgSha256 = null,
        brewExecutable = brewExecutable,
        releasePageUrl = "https://example.com/releases/1.2.2",
      ),
    )
    return checkNotNull(store.load())
  }

  private fun makeNonExecutable(executable: Path) {
    assertTrue(executable.toFile().setExecutable(false, false), "Failed to make $executable non-executable")
    assertFalse(Files.isExecutable(executable), "Expected $executable to be non-executable")
  }

  private fun prepare(
    store: RebasedMacUpdateStore,
    operations: FakeOperations,
    source: RebasedMacInstallationSource = RebasedMacInstallationSource.Direct,
    arch: CpuArch = CpuArch.ARM64,
    data: Pair<BuildInfo, UpdateChannel> = updateData(),
  ): PreparedRebasedMacUpdate {
    val indicator = TestProgressIndicator()
    operations.useIndicator(indicator)
    return preparer(store, operations, source, arch).prepare(data.first, data.second, indicator)
  }

  private fun commandPrefixes(operations: FakeOperations): List<List<String?>> =
    operations.commands.map { it.take(2) }

  private fun assertTargetClean(store: RebasedMacUpdateStore, operations: FakeOperations) {
    val versionDirectory = store.versionDirectory(TARGET_VERSION)
    assertTrue(versionDirectory in operations.deleted)
    assertFalse(Files.exists(versionDirectory))
    assertNull(store.load())
  }

  private fun preparer(
    store: RebasedMacUpdateStore,
    operations: RebasedMacUpdateOperations,
    source: RebasedMacInstallationSource = RebasedMacInstallationSource.Direct,
    arch: CpuArch = CpuArch.ARM64,
    mountRoot: Path = tempDir.resolve("mounts"),
  ): RebasedMacUpdatePreparer =
    RebasedMacUpdatePreparer(store, operations, { source }, arch, mountRoot)

  private fun store(): RebasedMacUpdateStore =
    RebasedMacUpdateStore(tempDir.toRealPath().resolve("updates"))

  private fun updateData(
    version: String = TARGET_VERSION,
    buildNumber: String = version,
    downloadUrl: String = DOWNLOAD_URL,
    digest: String? = EXPECTED_SHA256.uppercase(),
    blogPostUrl: String? = RELEASE_URL,
    channelUrl: String = CHANNEL_URL,
  ): Pair<BuildInfo, UpdateChannel> {
    val digestAttribute = digest?.let { """ digest="$it"""" }.orEmpty()
    val blogPost = blogPostUrl?.let { """<blogPost url="$it"/>""" }.orEmpty()
    val product = parseUpdateData(
      """
        <products>
          <product name="Rebased">
            <code>RB</code>
            <channel id="release" status="release" url="$channelUrl">
              <build number="$buildNumber" fullNumber="$buildNumber" version="$version">
                $blogPost
                <button name="Download" url="$downloadUrl" download="true"$digestAttribute/>
              </build>
            </channel>
          </product>
        </products>
      """.trimIndent(),
      "RB",
    )!!
    val channel = product.channels.single()
    return channel.builds.single() to channel
  }

  private fun plistCommand(key: String, infoPlist: Path): List<String> =
    listOf("/usr/bin/plutil", "-extract", key, "raw", "-o", "-", infoPlist.toString())

  private fun attachPlist(vararg devices: String): String =
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <plist version="1.0">
        <dict>
          <key>system-entities</key>
          <array>
            ${devices.joinToString("\n            ") { device ->
              "<dict><key>dev-entry</key><string>$device</string></dict>"
            }}
          </array>
        </dict>
      </plist>
    """.trimIndent()

  private fun persistedDirectUpdate(
    store: RebasedMacUpdateStore,
    version: String = TARGET_VERSION,
  ): PreparedRebasedMacUpdate {
    val versionDirectory = Files.createDirectories(store.versionDirectory(version))
    val stagedApp = Files.createDirectories(versionDirectory.resolve("Rebased.app"))
    val verifiedDmg = Files.writeString(versionDirectory.resolve("rebased.dmg"), "abc")
    store.save(
      PreparedRebasedMacUpdate(
        version = version,
        strategy = RebasedMacUpdateStrategy.DIRECT,
        stagedApp = stagedApp,
        verifiedDmg = verifiedDmg,
        verifiedDmgSha256 = ABC_SHA256,
        brewExecutable = null,
        releasePageUrl = RELEASE_URL,
      ),
    )
    return checkNotNull(store.load())
  }

  private fun editPersistedState(edit: Properties.() -> Unit) {
    val stateFile = tempDir.resolve("updates/prepared.properties")
    val properties = Properties()
    Files.newInputStream(stateFile).use(properties::load)
    properties.edit()
    Files.newOutputStream(stateFile).use {
      properties.store(it, null)
    }
  }

  private inner class FakeOperations : RebasedMacUpdateOperations {
    val downloads = mutableListOf<Pair<String, Path>>()
    val moves = mutableListOf<Pair<Path, Path>>()
    val commands = mutableListOf<List<String>>()
    val commandTimeouts = mutableListOf<Int>()
    val deleted = mutableListOf<Path>()
    val indeterminateStages = mutableListOf<String?>()
    var downloadProgress: Pair<String?, Boolean>? = null
    var mountDirectory: Path? = null
    var digest = EXPECTED_SHA256.uppercase()
    var downloadFailure: Throwable? = null
    var attachExitCode = 0
    var attachTimedOut = false
    var attachStdout = attachPlist(ATTACH_DEVICE)
    var attachFailure: Throwable? = null
    var createMountedAppOnAttach = true
    var cancelAfterMove = false
    var copyExitCode = 0
    var detachExitCode = 0
    var forcedDetachExitCode: Int? = null
    var copyFailure: Throwable? = null
    var bundleIdentifier = BUNDLE_ID
    var finalBundleIdentifier: String? = null
    var bundleVersion = TARGET_VERSION
    var finalBundleVersion: String? = null
    var bundleExecutable = BUNDLE_EXECUTABLE
    var finalBundleExecutable: String? = null
    var architectures = "arm64 x86_64"
    var finalArchitectures: String? = null
	    var codeSignatureExitCode = 0
    var symlinkBinaryAfterInitialValidation = false
    var removeExecutablePermissionAfterInitialValidation = false
    var finalValidationFailure: Throwable? = null
    var cancelAfterFinalValidation = false
    var stagedBundleMode = StagedBundleMode.VALID
    var outsideArtifact: Path? = null
    var brewUpdateResult = RebasedCommandResult(0, "", "")
    var brewInfoResult = RebasedCommandResult(0, """{"casks":[{"version":"$TARGET_VERSION"}]}""", "")
    var brewFetchResult = RebasedCommandResult(0, "", "")
    var brewFailureCommand: String? = null
    var brewCommandFailure: Throwable? = null
    var cancelAfterBrewCommand: String? = null
    var afterBrewFetch: (() -> Unit)? = null
    private var stagedApp: Path? = null
    private var bundleIdentifierReads = 0
    private var architectureReads = 0

    override fun download(url: String, target: Path, indicator: ProgressIndicator) {
      downloads.add(url to target)
      downloadProgress = indicator.text to indicator.isIndeterminate
      Files.writeString(target, "verified DMG")
      downloadFailure?.let { throw it }
    }

    override fun sha256(path: Path, indicator: ProgressIndicator): String {
      recordIndeterminateStage(indicator)
      assertTrue(Files.isRegularFile(path))
      return digest
    }

    override fun run(
      arguments: List<String>,
      indicator: ProgressIndicator,
      timeoutMillis: Int,
    ): RebasedCommandResult {
      commands.add(arguments.toList())
      commandTimeouts.add(timeoutMillis)
      recordIndeterminateStage(indicator)
      val brewCommand = arguments.getOrNull(1)
      if (brewCommand in setOf("update", "info", "fetch")) {
        if (brewCommand == brewFailureCommand) {
          throw checkNotNull(brewCommandFailure)
        }
        val result = when (brewCommand) {
          "update" -> brewUpdateResult
          "info" -> brewInfoResult
          "fetch" -> brewFetchResult
          else -> error("Unexpected Homebrew command: $arguments")
        }
        if (brewCommand == "fetch") {
          afterBrewFetch?.invoke()
        }
        if (brewCommand == cancelAfterBrewCommand) {
          indicator.cancel()
        }
        return result
      }
      return when {
        arguments.take(2) == listOf("/usr/bin/hdiutil", "attach") -> {
          val mountDirectory = Path.of(arguments[arguments.indexOf("-mountpoint") + 1])
          this.mountDirectory = mountDirectory
          if (createMountedAppOnAttach) {
            Files.createDirectories(mountDirectory.resolve("Rebased.app"))
          }
          attachFailure?.let { throw it }
          RebasedCommandResult(attachExitCode, attachStdout, "attach failed", timedOut = attachTimedOut)
        }
        arguments.first() == "/usr/bin/ditto" -> {
          copyFailure?.let { throw it }
          if (copyExitCode == 0) {
            createStagedBundle(Path.of(arguments[2]))
          }
          RebasedCommandResult(copyExitCode, "", "copy failed")
        }
        arguments.take(2) == listOf("/usr/bin/plutil", "-extract") -> {
          val value = when (arguments[2]) {
            "CFBundleIdentifier" -> {
              bundleIdentifierReads++
              if (bundleIdentifierReads > 1) finalValidationFailure?.let { throw it }
              bundleIdentifier
            }
            "CFBundleShortVersionString" -> bundleVersion
            "CFBundleExecutable" -> bundleExecutable
            else -> error("Unexpected plist key: ${arguments[2]}")
          }
          RebasedCommandResult(0, "$value\n", "")
        }
        arguments.take(2) == listOf("/usr/bin/lipo", "-archs") -> {
          architectureReads++
          if (architectureReads > 1 && cancelAfterFinalValidation) {
            indicator.cancel()
          }
          RebasedCommandResult(0, "$architectures\n", "")
        }
	        arguments.take(2) == listOf("/usr/bin/codesign", "-dv") -> {
	          RebasedCommandResult(codeSignatureExitCode, "", "code object is not signed at all")
	        }
        arguments.take(2) == listOf("/usr/bin/hdiutil", "detach") -> {
          val exitCode = if ("-force" in arguments) forcedDetachExitCode ?: detachExitCode else detachExitCode
          if (exitCode == 0) {
            NioFiles.deleteRecursively(checkNotNull(mountDirectory))
            mutateStagedBundle()
          }
          RebasedCommandResult(exitCode, "", "detach failed")
        }
        else -> error("Unexpected command: $arguments")
      }
    }

    override fun deleteRecursively(path: Path) {
      deleted.add(path)
      NioFiles.deleteRecursively(path)
    }

    override fun moveAtomically(source: Path, target: Path) {
      moves.add(source to target)
      Files.move(source, target)
      if (cancelAfterMove) {
        currentIndicator.cancel()
      }
    }

    private fun createStagedBundle(stagedApp: Path) {
      this.stagedApp = stagedApp
      when (stagedBundleMode) {
        StagedBundleMode.VALID -> createBundleFiles(stagedApp, includeBinary = true)
        StagedBundleMode.MISSING_APP -> Unit
        StagedBundleMode.SYMLINK_APP -> {
          val target = Files.createTempDirectory(tempDir, "outside-app").resolve("Rebased.app")
          createBundleFiles(target, includeBinary = true)
          Files.createSymbolicLink(stagedApp, target)
          outsideArtifact = target
        }
        StagedBundleMode.SYMLINK_CONTENTS -> {
          val target = stagedApp.resolve("RealContents")
          createBundleContents(target, includeBinary = true)
          Files.createSymbolicLink(stagedApp.resolve("Contents"), target.fileName)
        }
        StagedBundleMode.SYMLINK_MACOS -> {
          val contents = Files.createDirectories(stagedApp.resolve("Contents"))
          Files.createFile(contents.resolve("Info.plist"))
          val target = Files.createDirectories(contents.resolve("RealMacOS"))
          Files.createFile(target.resolve("rebased"))
          Files.createSymbolicLink(contents.resolve("MacOS"), target.fileName)
        }
        StagedBundleMode.MISSING_BINARY -> createBundleFiles(stagedApp, includeBinary = false)
        StagedBundleMode.NON_EXECUTABLE_BINARY -> {
          createBundleFiles(stagedApp, includeBinary = true)
          makeNonExecutable(stagedApp.resolve("Contents/MacOS/$BUNDLE_EXECUTABLE"))
        }
        StagedBundleMode.SYMLINK_BINARY -> {
          createBundleFiles(stagedApp, includeBinary = false)
          val target = Files.createTempFile(tempDir, "outside-binary", ".bin")
          Files.createSymbolicLink(stagedApp.resolve("Contents/MacOS/$BUNDLE_EXECUTABLE"), target)
          outsideArtifact = target
        }
      }
    }

    private fun mutateStagedBundle() {
      val stagedApp = stagedApp ?: return
      if (stagedBundleMode != StagedBundleMode.VALID) return
      bundleIdentifier = finalBundleIdentifier ?: bundleIdentifier
      bundleVersion = finalBundleVersion ?: bundleVersion
      bundleExecutable = finalBundleExecutable ?: bundleExecutable
      architectures = finalArchitectures ?: architectures
      writeInfoPlist(stagedApp.resolve("Contents/Info.plist"))
      if (symlinkBinaryAfterInitialValidation) {
        val executable = stagedApp.resolve("Contents/MacOS/$BUNDLE_EXECUTABLE")
        Files.delete(executable)
        val target = Files.createTempFile(tempDir, "outside-final-binary", ".bin")
        Files.createSymbolicLink(executable, target)
        outsideArtifact = target
      }
      if (removeExecutablePermissionAfterInitialValidation) {
        makeNonExecutable(stagedApp.resolve("Contents/MacOS/$BUNDLE_EXECUTABLE"))
      }
    }

    private fun createBundleFiles(stagedApp: Path, includeBinary: Boolean) {
      createBundleContents(stagedApp.resolve("Contents"), includeBinary)
    }

    private fun createBundleContents(contentsDirectory: Path, includeBinary: Boolean) {
      val contents = Files.createDirectories(contentsDirectory.resolve("MacOS"))
      writeInfoPlist(contents.resolveSibling("Info.plist"))
      if (includeBinary) {
        val executable = Files.createFile(contents.resolve(BUNDLE_EXECUTABLE))
        assertTrue(executable.toFile().setExecutable(true), "Failed to make $executable executable")
      }
    }

    private fun writeInfoPlist(infoPlist: Path) {
      Files.writeString(
        infoPlist,
        """
          <?xml version="1.0" encoding="UTF-8"?>
          <plist version="1.0">
            <dict>
              <key>CFBundleIdentifier</key>
              <string>$bundleIdentifier</string>
              <key>CFBundleShortVersionString</key>
              <string>$bundleVersion</string>
              <key>CFBundleExecutable</key>
              <string>$bundleExecutable</string>
            </dict>
          </plist>
        """.trimIndent(),
      )
    }

    private fun makeNonExecutable(executable: Path) {
      assertTrue(executable.toFile().setExecutable(false, false), "Failed to make $executable non-executable")
      assertFalse(Files.isExecutable(executable), "Expected $executable to be non-executable")
    }

    private fun recordIndeterminateStage(indicator: ProgressIndicator) {
      assertTrue(indicator.isIndeterminate)
      indeterminateStages.add(indicator.text)
    }

    private lateinit var currentIndicator: ProgressIndicator

    fun useIndicator(indicator: ProgressIndicator) {
      currentIndicator = indicator
    }
  }

  companion object {
    private const val TARGET_VERSION = "1.2.3"
    private const val ATTACH_DEVICE = "/dev/disk42"
    private const val BUNDLE_ID = "io.github.detachhead.rebased"
    private const val BUNDLE_EXECUTABLE = "rebased"
    private const val DOWNLOAD_URL = "https://example.com/releases/1.2.3/rebased-aarch64.dmg"
    private const val RELEASE_URL = "https://example.com/releases/1.2.3"
    private const val CHANNEL_URL = "https://example.com/releases/latest"
    private const val EXPECTED_SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private const val ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private const val SHORT_COMMAND_TIMEOUT_MILLIS = 60_000
    private const val DITTO_TIMEOUT_MILLIS = 600_000
    private const val HOMEBREW_LONG_TIMEOUT_MILLIS = 600_000
  }

  private enum class StagedBundleMode {
    VALID,
    MISSING_APP,
    SYMLINK_APP,
    SYMLINK_CONTENTS,
    SYMLINK_MACOS,
    MISSING_BINARY,
    NON_EXECUTABLE_BINARY,
    SYMLINK_BINARY,
  }

  private class TestProgressIndicator(
    private val cancelOnProgress: Boolean = false,
  ) : EmptyProgressIndicatorBase(ModalityState.nonModal()) {
    private var canceled = false
    private var fractionValue = 0.0
    private var indeterminate = true
    private var textValue: String? = null

    override fun cancel() {
      canceled = true
    }

    override fun isCanceled(): Boolean = canceled

    override fun setFraction(fraction: Double) {
      fractionValue = fraction
      if (cancelOnProgress && fraction > 0.0) {
        cancel()
      }
    }

    override fun getFraction(): Double = fractionValue

    override fun setIndeterminate(indeterminate: Boolean) {
      this.indeterminate = indeterminate
    }

    override fun isIndeterminate(): Boolean = indeterminate

    override fun setText(text: String?) {
      textValue = text
    }

    override fun getText(): String? = textValue
  }
}
