// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.Properties
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class RebasedMacUpdateStoreTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `round trips direct prepared update`() {
    val store = store()
    val versionDirectory = Files.createDirectories(store.versionDirectory("1.2.3"))
    val normalizedStagedApp = Files.createDirectories(versionDirectory.resolve("Rebased.app"))
    val normalizedVerifiedDmg = Files.writeString(versionDirectory.resolve("rebased.dmg"), "verified DMG")
    val update = PreparedRebasedMacUpdate(
      version = "1.2.3",
      strategy = RebasedMacUpdateStrategy.DIRECT,
      stagedApp = normalizedStagedApp.resolveSibling("nested/../Rebased.app"),
      verifiedDmg = normalizedVerifiedDmg.resolveSibling("nested/../rebased.dmg"),
      verifiedDmgSha256 = VERIFIED_DMG_SHA256.uppercase(),
      brewExecutable = null,
      releasePageUrl = RELEASE_URL,
    )

    store.save(update)

    assertEquals(
      update.copy(
        stagedApp = normalizedStagedApp.toRealPath(),
        verifiedDmg = normalizedVerifiedDmg.toRealPath(),
        verifiedDmgSha256 = VERIFIED_DMG_SHA256,
      ),
      RebasedMacUpdateStore(storeRoot()).load(),
    )
  }

  @Test
  fun `round trips Homebrew prepared update`() {
    val store = store()
    val normalizedBrewExecutable = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val update = PreparedRebasedMacUpdate(
      version = "1.2.3",
      strategy = RebasedMacUpdateStrategy.HOMEBREW,
      stagedApp = null,
      verifiedDmg = null,
      verifiedDmgSha256 = null,
      brewExecutable = normalizedBrewExecutable.resolveSibling("../bin/brew"),
      releasePageUrl = RELEASE_URL,
    )

    store.save(update)

    assertEquals(update.copy(brewExecutable = normalizedBrewExecutable), RebasedMacUpdateStore(storeRoot()).load())
  }

  @Test
  fun `falls back when atomic state replacement is unsupported`() {
    val moveOptions = mutableListOf<Set<CopyOption>>()
    val store = RebasedMacUpdateStore(
      root = storeRoot(),
      move = { source, target, options ->
        moveOptions.add(options.toSet())
        if (ATOMIC_MOVE in options) {
          throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "not supported")
        }
        Files.move(source, target, *options)
      },
    )
    val update = PreparedRebasedMacUpdate(
      version = "1.2.3",
      strategy = RebasedMacUpdateStrategy.HOMEBREW,
      stagedApp = null,
      verifiedDmg = null,
      verifiedDmgSha256 = null,
      brewExecutable = executable(tempDir.resolve("opt/homebrew/bin/brew")),
      releasePageUrl = RELEASE_URL,
    )

    store.save(update)

    assertEquals(update, store.load())
    assertEquals(
      listOf(
        setOf(ATOMIC_MOVE, REPLACE_EXISTING),
        setOf(REPLACE_EXISTING),
      ),
      moveOptions,
    )
  }

  @Test
  fun `failed state replacement preserves previous state and removes temporary file`() {
    val brewExecutable = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val previousUpdate = PreparedRebasedMacUpdate(
      version = "1.2.3",
      strategy = RebasedMacUpdateStrategy.HOMEBREW,
      stagedApp = null,
      verifiedDmg = null,
      verifiedDmgSha256 = null,
      brewExecutable = brewExecutable,
      releasePageUrl = RELEASE_URL,
    )
    store().save(previousUpdate)
    val replacementUpdate = previousUpdate.copy(version = "1.2.4")
    val moveFailure = IOException("move failed")
    var moveAttempts = 0
    val failingStore = RebasedMacUpdateStore(
      root = storeRoot(),
      move = { _, _, _ ->
        moveAttempts++
        throw moveFailure
      },
    )

    val thrown = assertThrows(IOException::class.java) {
      failingStore.save(replacementUpdate)
    }

    assertSame(moveFailure, thrown)
    assertEquals(1, moveAttempts)
    assertEquals(previousUpdate, store().load())
    Files.newDirectoryStream(storeRoot(), "prepared.*.tmp").use {
      assertFalse(it.iterator().hasNext())
    }
  }

  @Test
  fun `rejects direct staged app outside store root`() {
    val stagedApp = Files.createDirectories(tempDir.resolve("outside/Rebased.app"))
    writeState(directState("1.2.3", stagedApp))

    assertNull(store().load())
  }

  @Test
  fun `rejects relative direct staged app`() {
    writeState(directState("1.2.3", Path.of("staged/Rebased.app")))

    assertNull(store().load())
  }

  @Test
  fun `rejects direct staged app that is a regular file`() {
    val store = store()
    val versionDirectory = Files.createDirectories(store.versionDirectory("1.2.3"))
    val stagedApp = Files.createFile(versionDirectory.resolve("Rebased.app"))
    writeState(directState("1.2.3", stagedApp))

    assertNull(store.load())
  }

  @Test
  fun `rejects direct staged app under wrong version directory`() {
    val store = store()
    val stagedApp = Files.createDirectories(store.versionDirectory("2.0.0").resolve("Rebased.app"))
    writeState(directState("1.2.3", stagedApp))

    assertNull(store.load())
  }

  @Test
  fun `rejects symlink from stored version directory to another version`() {
    val store = store()
    val wrongVersionDirectory = Files.createDirectories(store.versionDirectory("2.0.0"))
    Files.createDirectories(wrongVersionDirectory.resolve("Rebased.app"))
    Files.createDirectories(storeRoot())
    val versionDirectory = Files.createSymbolicLink(store.versionDirectory("1.2.3"), wrongVersionDirectory)
    writeState(directState("1.2.3", versionDirectory.resolve("Rebased.app")))

    assertNull(store.load())
  }

  @Test
  fun `rejects direct staged app symlink`() {
    val store = store()
    val target = Files.createDirectories(tempDir.resolve("outside/Rebased.app"))
    val versionDirectory = Files.createDirectories(store.versionDirectory("1.2.3"))
    val stagedApp = Files.createSymbolicLink(versionDirectory.resolve("Rebased.app"), target)
    writeState(directState("1.2.3", stagedApp))

    assertNull(store.load())
  }

  @Test
  fun `rejects an intermediate symlink in the direct staged app path`() {
    val store = store()
    val versionDirectory = Files.createDirectories(store.versionDirectory("1.2.3"))
    val target = Files.createDirectories(versionDirectory.resolve("actual/Rebased.app"))
    val intermediateSymlink = Files.createSymbolicLink(versionDirectory.resolve("staged"), target.parent)
    writeState(directState("1.2.3", intermediateSymlink.resolve("Rebased.app")))

    assertNull(store.load())
  }

  @Test
  fun `rejects missing direct staged app`() {
    val store = store()
    val stagedApp = store.versionDirectory("1.2.3").resolve("Rebased.app")
    writeState(directState("1.2.3", stagedApp))

    assertNull(store.load())
  }

  @Test
  fun `rejects missing or malformed direct verified DMG attestation`() {
    val store = store()
    val stagedApp = Files.createDirectories(store.versionDirectory("1.2.3").resolve("Rebased.app"))
    val state = directState("1.2.3", stagedApp).toMutableMap()

    state.remove(VERIFIED_DMG_PROPERTY)
    writeState(state)
    assertNull(store.load())

    state.putAll(directState("1.2.3", stagedApp))
    state.remove(VERIFIED_DMG_SHA256_PROPERTY)
    writeState(state)
    assertNull(store.load())

    state.putAll(
      directState(
        "1.2.3",
        stagedApp,
        verifiedDmg = store.versionDirectory("1.2.3").resolve("missing.dmg"),
      ),
    )
    writeState(state)
    assertNull(store.load())

    for (digest in listOf("", "abc", "g".repeat(64), "0".repeat(63), "0".repeat(65))) {
      state.putAll(directState("1.2.3", stagedApp))
      state[VERIFIED_DMG_SHA256_PROPERTY] = digest
      writeState(state)
      assertNull(store.load(), digest)
    }
  }

  @Test
  fun `rejects direct verified DMG outside expected version directory`() {
    val store = store()
    val stagedApp = Files.createDirectories(store.versionDirectory("1.2.3").resolve("Rebased.app"))
    val outsideDmg = Files.writeString(tempDir.resolve("outside.dmg"), "verified DMG")
    writeState(directState("1.2.3", stagedApp, verifiedDmg = outsideDmg))

    assertNull(store.load())
  }

  @Test
  fun `rejects symlinked direct verified DMG`() {
    val store = store()
    val versionDirectory = Files.createDirectories(store.versionDirectory("1.2.3"))
    val stagedApp = Files.createDirectories(versionDirectory.resolve("Rebased.app"))
    val target = Files.writeString(tempDir.resolve("outside.dmg"), "verified DMG")
    val verifiedDmg = Files.createSymbolicLink(versionDirectory.resolve("rebased.dmg"), target)
    writeState(directState("1.2.3", stagedApp, verifiedDmg = verifiedDmg))

    assertNull(store.load())
  }

  @Test
  fun `rejects Homebrew state with missing relative or nonexistent executable`() {
    val state = homebrewState().toMutableMap()
    writeState(state)
    assertNull(store().load())

    state[BREW_EXECUTABLE_PROPERTY] = "bin/brew"
    writeState(state)
    assertNull(store().load())

    state[BREW_EXECUTABLE_PROPERTY] = tempDir.resolve("missing/bin/brew").toString()
    writeState(state)
    assertNull(store().load())
  }

  @Test
  fun `rejects Homebrew state with a nonexecutable regular file`() {
    val brewExecutable = regularFile(tempDir.resolve("opt/homebrew/bin/brew"))
    writeState(homebrewState(brewExecutable))

    assertNull(store().load())
  }

  @Test
  fun `accepts a Homebrew symlink and preserves its normalized lexical path`() {
    val target = executable(tempDir.resolve("Cellar/homebrew/bin/brew"))
    val stablePath = tempDir.resolve("opt/homebrew/bin/brew")
    Files.createDirectories(stablePath.parent)
    Files.createSymbolicLink(stablePath, target)
    val update = PreparedRebasedMacUpdate(
      version = "1.2.3",
      strategy = RebasedMacUpdateStrategy.HOMEBREW,
      stagedApp = null,
      verifiedDmg = null,
      verifiedDmgSha256 = null,
      brewExecutable = stablePath.resolveSibling("../bin/brew"),
      releasePageUrl = RELEASE_URL,
    )

    store().save(update)

    assertEquals(update.copy(brewExecutable = stablePath), store().load())
  }

  @Test
  fun `revalidates the Homebrew executable on later load`() {
    val brewExecutable = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val update = PreparedRebasedMacUpdate(
      version = "1.2.3",
      strategy = RebasedMacUpdateStrategy.HOMEBREW,
      stagedApp = null,
      verifiedDmg = null,
      verifiedDmgSha256 = null,
      brewExecutable = brewExecutable,
      releasePageUrl = RELEASE_URL,
    )
    store().save(update)
    assertTrue(
      brewExecutable.toFile().setExecutable(false, false),
      "Failed to make $brewExecutable nonexecutable",
    )

    assertNull(store().load())
  }

  @Test
  fun `rejects Homebrew state with staged app`() {
    val state = homebrewState(executable(tempDir.resolve("opt/homebrew/bin/brew"))).toMutableMap()
    state[STAGED_APP_PROPERTY] = tempDir.resolve("staged/Rebased.app").toString()
    writeState(state)

    assertNull(store().load())
  }

  @Test
  fun `rejects Homebrew state with direct DMG attestation`() {
    val state = homebrewState(executable(tempDir.resolve("opt/homebrew/bin/brew"))).toMutableMap()
    state[VERIFIED_DMG_PROPERTY] = tempDir.resolve("rebased.dmg").toString()
    state[VERIFIED_DMG_SHA256_PROPERTY] = VERIFIED_DMG_SHA256
    writeState(state)

    assertNull(store().load())
  }

  @Test
  fun `rejects invalid strategy and malformed or non-HTTPS release URL`() {
    val brewExecutable = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val state = homebrewState(brewExecutable).toMutableMap()
    state[STRATEGY_PROPERTY] = "UNKNOWN"
    writeState(state)
    assertNull(store().load())

    for (releaseUrl in listOf("not a URL", "http://example.com/releases/1.2.3", "https:///releases/1.2.3")) {
      state[STRATEGY_PROPERTY] = RebasedMacUpdateStrategy.HOMEBREW.name
      state[RELEASE_PAGE_URL_PROPERTY] = releaseUrl
      writeState(state)
      assertNull(store().load(), releaseUrl)
    }
  }

  @Test
  fun `returns null for missing or malformed properties`() {
    assertNull(store().load())

    Files.createDirectories(storeRoot())
    Files.writeString(storeRoot().resolve(STATE_FILE), "version=1.2.3\nstrategy=\\uZZZZ\n")
    assertNull(store().load())
  }

  @Test
  fun `load rejects a store root symlink`() {
    val target = Files.createDirectories(tempDir.resolve("unrelated-root"))
    val brewExecutable = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    Files.createSymbolicLink(storeRoot(), target)
    writeState(homebrewState(brewExecutable))

    assertNull(store().load())
  }

  @Test
  fun `save rejects a store root symlink without modifying its target`() {
    val target = Files.createDirectories(tempDir.resolve("unrelated-root"))
    val targetMarker = Files.createFile(target.resolve("keep.txt"))
    Files.createSymbolicLink(storeRoot(), target)
    val update = PreparedRebasedMacUpdate(
      version = "1.2.3",
      strategy = RebasedMacUpdateStrategy.HOMEBREW,
      stagedApp = null,
      verifiedDmg = null,
      verifiedDmgSha256 = null,
      brewExecutable = executable(tempDir.resolve("opt/homebrew/bin/brew")),
      releasePageUrl = RELEASE_URL,
    )

    assertThrows(IOException::class.java) {
      store().save(update)
    }

    assertTrue(Files.exists(targetMarker))
    assertFalse(Files.exists(target.resolve(STATE_FILE)))
  }

  @Test
  fun `clear removes prepared state`() {
    val store = store()
    store.save(
      PreparedRebasedMacUpdate(
        version = "1.2.3",
        strategy = RebasedMacUpdateStrategy.HOMEBREW,
        stagedApp = null,
        verifiedDmg = null,
        verifiedDmgSha256 = null,
        brewExecutable = executable(tempDir.resolve("opt/homebrew/bin/brew")),
        releasePageUrl = RELEASE_URL,
      ),
    )

    store.clear()

    assertFalse(Files.exists(storeRoot().resolve(STATE_FILE)))
    assertNull(store.load())
  }

  @Test
  fun `clear rejects a store root symlink without modifying its target`() {
    val target = Files.createDirectories(tempDir.resolve("unrelated-root"))
    val targetState = Files.createFile(target.resolve(STATE_FILE))
    Files.createSymbolicLink(storeRoot(), target)

    assertThrows(IOException::class.java) {
      store().clear()
    }

    assertTrue(Files.exists(targetState))
  }

  @Test
  fun `clear stale data keeps current version and state files without following symlinks`() {
    val store = store()
    val currentDirectory = Files.createDirectories(store.versionDirectory("1.2 beta"))
    val currentMarker = Files.createFile(currentDirectory.resolve("Rebased.app"))
    val staleDirectory = Files.createDirectories(store.versionDirectory("1.1.0").resolve("nested"))
    Files.createFile(staleDirectory.resolve("stale.data"))
    val partialDownload = Files.createFile(storeRoot().resolve("update.dmg.part"))
    val preparedState = Files.createFile(storeRoot().resolve(STATE_FILE))
    val installResult = Files.createFile(storeRoot().resolve(INSTALL_RESULT_FILE))
    val activeInstallResultClaim = Files.createFile(storeRoot().resolve("$INSTALL_RESULT_FILE.consuming-active"))
    val staleInstallResultClaim = Files.createFile(storeRoot().resolve("$INSTALL_RESULT_FILE.consuming-stale"))
    Files.setLastModifiedTime(staleInstallResultClaim, FileTime.fromMillis(0))
    val staleInstallResultLock = Files.createFile(storeRoot().resolve("install-result.lock"))
    val symlinkTarget = Files.createDirectories(tempDir.resolve("outside-target"))
    val targetMarker = Files.createFile(symlinkTarget.resolve("keep.txt"))
    val staleSymlink = Files.createSymbolicLink(storeRoot().resolve("stale-link"), symlinkTarget)

    store.clearStaleData("1.2 beta")

    assertTrue(Files.exists(currentMarker))
    assertTrue(Files.exists(preparedState))
    assertTrue(Files.exists(installResult))
    assertTrue(Files.exists(activeInstallResultClaim))
    assertFalse(Files.exists(staleInstallResultClaim))
    assertFalse(Files.exists(staleInstallResultLock))
    assertFalse(Files.exists(staleDirectory))
    assertFalse(Files.exists(partialDownload))
    assertFalse(Files.exists(staleSymlink))
    assertTrue(Files.exists(targetMarker))
  }

  @Test
  fun `clear stale data is a no-op for missing root`() {
    store().clearStaleData("1.2.3")

    assertFalse(Files.exists(storeRoot()))
  }

  @Test
  fun `clear stale data rejects a store root symlink without modifying its target`() {
    val target = Files.createDirectories(tempDir.resolve("unrelated-root/nested"))
    val targetMarker = Files.createFile(target.resolve("keep.txt"))
    Files.createSymbolicLink(storeRoot(), target.parent)

    assertThrows(IOException::class.java) {
      store().clearStaleData("1.2.3")
    }

    assertTrue(Files.exists(targetMarker))
  }

  @Test
  fun `clear stale data rejects a noncanonical store root without modifying its target`() {
    val actualParent = Files.createDirectories(tempDir.resolve("actual-parent")).toRealPath()
    val actualRoot = Files.createDirectories(actualParent.resolve("store"))
    val targetMarker = Files.createFile(actualRoot.resolve("keep.txt"))
    val linkedParent = Files.createSymbolicLink(tempDir.resolve("linked-parent"), actualParent)

    assertThrows(IOException::class.java) {
      RebasedMacUpdateStore(linkedParent.resolve("store")).clearStaleData("1.2.3")
    }

    assertTrue(Files.exists(targetMarker))
  }

  @Test
  fun `clear stale data rejects a regular file root`() {
    Files.createFile(storeRoot())

    assertThrows(NotDirectoryException::class.java) {
      store().clearStaleData("1.2.3")
    }
  }

  @Test
  fun `clear stale data propagates an unlistable root error`() {
    Files.createSymbolicLink(storeRoot(), tempDir.resolve("missing-root-target"))

    assertThrows(IOException::class.java) {
      store().clearStaleData("1.2.3")
    }
  }

  @Test
  fun `consumes successful direct result and removes only validated update data`() {
    val store = store()
    val prepared = saveDirectUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    val backup = Files.createDirectories(currentApp.resolveSibling("Rebased.app.rebased-update-backup"))
    Files.createFile(backup.resolve("old-app"))
    writeInstallResult("success", "installed", backup.toString(), prepared.version, "direct")

    val result = store.consumeInstallResult(prepared.version, currentApp)

    assertEquals(RebasedMacInstallResult.Success, result)
    assertFalse(Files.exists(backup))
    assertFalse(Files.exists(store.versionDirectory(prepared.version)))
    assertNull(store.load())
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE)))
  }

  @Test
  fun `recovers stale install result abandoned after atomic claim`() {
    val store = store()
    val prepared = saveDirectUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    val backup = Files.createDirectories(currentApp.resolveSibling("Rebased.app.rebased-update-backup"))
    val marker = storeRoot().resolve("install-result.properties.consuming-abandoned")
    writeInstallResult(marker, "success", "installed", backup.toString(), prepared.version, "direct")
    Files.setLastModifiedTime(marker, FileTime.fromMillis(0))

    val result = store.consumeInstallResult(prepared.version, currentApp)

    assertEquals(RebasedMacInstallResult.Success, result)
    assertFalse(Files.exists(marker))
    assertNull(store.load())
  }

  @Test
  fun `consumes successful Homebrew result and clears prepared state without a backup`() {
    val store = store()
    val prepared = saveHomebrewUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    writeInstallResult("success", "installed", "", prepared.version, "homebrew")

    val result = store.consumeInstallResult(prepared.version, currentApp)

    assertEquals(RebasedMacInstallResult.Success, result)
    assertNull(store.load())
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE)))
  }

  @Test
  fun `failed result is consumed while prepared state remains retryable`() {
    val store = store()
    val prepared = saveDirectUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    writeInstallResult("failed", "replacement failed", "", prepared.version, "direct")

    assertEquals(
      RebasedMacInstallResult.Failed("replacement failed"),
      store.consumeInstallResult("1.2.2", currentApp),
    )
    assertEquals(prepared, store.load())
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE)))

    writeInstallResult("failed", " ", "", prepared.version, "direct")
    assertEquals(
      RebasedMacInstallResult.Failed("The Rebased update installer reported a failure."),
      store.consumeInstallResult("1.2.2", currentApp),
    )
  }

  @Test
  fun `failed result with another version is consumed without binding current prepared state`() {
    val store = store()
    val prepared = saveDirectUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    writeInstallResult("failed", "old failure", "", "1.2.2", "direct")

    assertEquals(RebasedMacInstallResult.None, store.consumeInstallResult("1.2.2", currentApp))
    assertEquals(prepared, store.load())
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE), NOFOLLOW_LINKS))
  }

  @Test
  fun `failed result with another strategy is consumed without binding current prepared state`() {
    val store = store()
    val prepared = saveDirectUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    writeInstallResult("failed", "old failure", "", prepared.version, "homebrew")

    assertEquals(RebasedMacInstallResult.None, store.consumeInstallResult("1.2.2", currentApp))
    assertEquals(prepared, store.load())
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE), NOFOLLOW_LINKS))
  }

  @Test
  fun `failed Homebrew result with backup is consumed without binding current prepared state`() {
    val store = store()
    val prepared = saveHomebrewUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    writeInstallResult("failed", "old failure", tempDir.resolve("unrelated-backup").toString(), prepared.version, "homebrew")

    assertEquals(RebasedMacInstallResult.None, store.consumeInstallResult("1.2.2", currentApp))
    assertEquals(prepared, store.load())
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE), NOFOLLOW_LINKS))
  }

  @Test
  fun `failed direct result with unrelated backup is consumed without binding current prepared state`() {
    val store = store()
    val prepared = saveDirectUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    writeInstallResult("failed", "old failure", tempDir.resolve("unrelated-backup").toString(), prepared.version, "direct")

    assertEquals(RebasedMacInstallResult.None, store.consumeInstallResult("1.2.2", currentApp))
    assertEquals(prepared, store.load())
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE), NOFOLLOW_LINKS))
  }

  @Test
  fun `successful result rejects version and strategy mismatches without clearing prepared state`() {
    val store = store()
    val prepared = saveDirectUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    val backup = Files.createDirectories(currentApp.resolveSibling("Rebased.app.rebased-update-backup"))
    val cases = listOf(
      Triple("1.2.4", "direct", "marker version"),
      Triple(prepared.version, "homebrew", "marker strategy"),
    )

    for ((version, strategy, description) in cases) {
      writeInstallResult(
        "success",
        "installed",
        if (strategy == "direct") backup.toString() else "",
        version,
        strategy,
      )

      val result = store.consumeInstallResult(prepared.version, currentApp)

      assertTrue(result is RebasedMacInstallResult.Failed, description)
      assertEquals(prepared, store.load(), description)
      assertTrue(Files.exists(backup), description)
      assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE)), description)
    }
  }

  @Test
  fun `malformed oversized unknown and symbolic result markers fail safely and are consumed`() {
    val store = store()
    saveHomebrewUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))

    Files.writeString(storeRoot().resolve(INSTALL_RESULT_FILE), "status=success\n")
    assertTrue(store.consumeInstallResult("1.2.3", currentApp) is RebasedMacInstallResult.Failed)
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE)))

    Files.writeString(storeRoot().resolve(INSTALL_RESULT_FILE), "x".repeat(70_000))
    assertTrue(store.consumeInstallResult("1.2.3", currentApp) is RebasedMacInstallResult.Failed)
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE)))

    writeInstallResult("unknown", "unexpected", "", "1.2.3", "homebrew")
    assertTrue(store.consumeInstallResult("1.2.3", currentApp) is RebasedMacInstallResult.Failed)
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE)))

    val externalMarker = Files.writeString(tempDir.resolve("external-result"), "status=success\n")
    Files.createSymbolicLink(storeRoot().resolve(INSTALL_RESULT_FILE), externalMarker)
    assertTrue(store.consumeInstallResult("1.2.3", currentApp) is RebasedMacInstallResult.Failed)
    assertEquals("status=success\n", Files.readString(externalMarker))
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE), NOFOLLOW_LINKS))
  }

  @Test
  fun `atomic move fallback removes a nonempty directory result marker after one failure`() {
    val marker = Files.createDirectories(storeRoot().resolve(INSTALL_RESULT_FILE).resolve("nested"))
    Files.writeString(marker.resolve("payload"), "unexpected")
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    val store = RebasedMacUpdateStore(
      root = storeRoot(),
      move = { source, target, _ ->
        throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "not supported")
      },
    )

    assertTrue(store.consumeInstallResult("1.2.3", currentApp) is RebasedMacInstallResult.Failed)
    assertEquals(RebasedMacInstallResult.None, store.consumeInstallResult("1.2.3", currentApp))
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE), NOFOLLOW_LINKS))
    Files.newDirectoryStream(storeRoot(), "$INSTALL_RESULT_FILE.consuming-*").use {
      assertFalse(it.iterator().hasNext())
    }
  }

  @Test
  fun `directory result fallback removes a symbolic consume lock without following it`() {
    val marker = Files.createDirectories(storeRoot().resolve(INSTALL_RESULT_FILE))
    val externalLock = Files.writeString(tempDir.resolve("external-lock"), "keep")
    Files.createSymbolicLink(marker.resolve(".consume.lock"), externalLock)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))

    assertTrue(atomicMoveFallbackStore().consumeInstallResult("1.2.3", currentApp) is RebasedMacInstallResult.Failed)
    assertEquals("keep", Files.readString(externalLock))
    assertFalse(Files.exists(marker, NOFOLLOW_LINKS))
  }

  @Test
  fun `directory result fallback removes a directory consume lock`() {
    val marker = Files.createDirectories(storeRoot().resolve(INSTALL_RESULT_FILE))
    Files.writeString(Files.createDirectories(marker.resolve(".consume.lock")).resolve("payload"), "unexpected")
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))

    assertTrue(atomicMoveFallbackStore().consumeInstallResult("1.2.3", currentApp) is RebasedMacInstallResult.Failed)
    assertFalse(Files.exists(marker, NOFOLLOW_LINKS))
  }

  @Test
  fun `directory result fallback removes a read-only consume lock`() {
    val marker = Files.createDirectories(storeRoot().resolve(INSTALL_RESULT_FILE))
    val lock = Files.writeString(marker.resolve(".consume.lock"), "unexpected")
    Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("r--r--r--"))
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))

    assertTrue(atomicMoveFallbackStore().consumeInstallResult("1.2.3", currentApp) is RebasedMacInstallResult.Failed)
    assertFalse(Files.exists(marker, NOFOLLOW_LINKS))
  }

  @Test
  fun `concurrent directory result fallback claims marker root exactly once`() {
    val executor = Executors.newFixedThreadPool(2)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    try {
      repeat(20) {
        val marker = Files.createDirectories(storeRoot().resolve(INSTALL_RESULT_FILE))
        Files.writeString(Files.createDirectories(marker.resolve(".consume.lock")).resolve("payload"), "unexpected")
        val moveBarrier = CyclicBarrier(2)
        val store = RebasedMacUpdateStore(
          root = storeRoot(),
          move = { source, target, _ ->
            moveBarrier.await(10, TimeUnit.SECONDS)
            throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "not supported")
          },
        )

        val results = List(2) {
          executor.submit<RebasedMacInstallResult> {
            store.consumeInstallResult("1.2.3", currentApp)
          }
        }.map { it.get(10, TimeUnit.SECONDS) }

        assertEquals(1, results.count { it is RebasedMacInstallResult.Failed })
        assertEquals(1, results.count { it == RebasedMacInstallResult.None })
        assertFalse(Files.exists(marker, NOFOLLOW_LINKS))
      }
    }
    finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `install result consumption rejects a noncanonical store root without modifying its target`() {
    val actualParent = Files.createDirectories(tempDir.resolve("actual-parent")).toRealPath()
    val actualRoot = Files.createDirectories(actualParent.resolve("store"))
    val marker = Files.writeString(actualRoot.resolve(INSTALL_RESULT_FILE), "status=success\n")
    val linkedParent = Files.createSymbolicLink(tempDir.resolve("linked-parent"), actualParent)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))

    val result = RebasedMacUpdateStore(linkedParent.resolve("store"))
      .consumeInstallResult("1.2.3", currentApp)

    assertEquals(RebasedMacInstallResult.None, result)
    assertEquals("status=success\n", Files.readString(marker))
  }

  @Test
  fun `atomic move failure leaves install result unclaimed`() {
    val marker = Files.createDirectories(storeRoot()).resolve(INSTALL_RESULT_FILE)
    Files.writeString(marker, "status=success\n")
    val store = RebasedMacUpdateStore(
      root = storeRoot(),
      move = { _, _, _ -> throw IOException("claim failed") },
    )
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))

    assertEquals(RebasedMacInstallResult.None, store.consumeInstallResult("1.2.3", currentApp))
    assertEquals("status=success\n", Files.readString(marker))
  }

  @Test
  fun `post-claim read failure returns failed and consumes marker`() {
    val store = RebasedMacUpdateStore(
      root = storeRoot(),
      readClaimedResult = { throw IOException("read failed") },
    )
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    writeInstallResult("failed", "failed once", "", "1.2.3", "homebrew")

    assertTrue(store.consumeInstallResult("1.2.2", currentApp) is RebasedMacInstallResult.Failed)
    assertEquals(RebasedMacInstallResult.None, store.consumeInstallResult("1.2.2", currentApp))
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE), NOFOLLOW_LINKS))
  }

  @Test
  fun `post-claim cleanup failure returns failed after marker is consumed`() {
    val store = RebasedMacUpdateStore(
      root = storeRoot(),
      readClaimedResult = Files::readAllBytes,
      deleteClaimedResult = { throw IOException("cleanup failed") },
    )
    saveHomebrewUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    writeInstallResult("failed", "failed once", "", "1.2.3", "homebrew")

    assertTrue(store.consumeInstallResult("1.2.2", currentApp) is RebasedMacInstallResult.Failed)
    assertEquals(RebasedMacInstallResult.None, store.consumeInstallResult("1.2.2", currentApp))
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE), NOFOLLOW_LINKS))
    Files.newDirectoryStream(storeRoot(), "$INSTALL_RESULT_FILE.consuming-*").use {
      assertFalse(it.iterator().hasNext())
    }
  }

  @Test
  fun `post-claim cleanup failure cannot replay after abandoned claim timeout`() {
    val store = RebasedMacUpdateStore(
      root = storeRoot(),
      readClaimedResult = Files::readAllBytes,
      deleteClaimedResult = { throw IOException("cleanup failed") },
    )
    val prepared = saveHomebrewUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    writeInstallResult("failed", "failed once", "", prepared.version, "homebrew")

    assertTrue(store.consumeInstallResult("1.2.2", currentApp) is RebasedMacInstallResult.Failed)
    Files.newDirectoryStream(storeRoot(), "$INSTALL_RESULT_FILE.consumed-*").use { markers ->
      val marker = markers.single()
      Files.setLastModifiedTime(marker, FileTime.fromMillis(0))
    }

    assertEquals(RebasedMacInstallResult.None, store.consumeInstallResult("1.2.2", currentApp))
    assertEquals(prepared, store.load())
  }

  @Test
  fun `successful result preserves prepared data when claimed marker deletion fails`() {
    val store = RebasedMacUpdateStore(
      root = storeRoot(),
      readClaimedResult = Files::readAllBytes,
      deleteClaimedResult = { throw IOException("cleanup failed") },
    )
    val prepared = saveDirectUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    val backup = Files.createDirectories(currentApp.resolveSibling("Rebased.app.rebased-update-backup"))
    val backupMarker = Files.writeString(backup.resolve("old-app"), "old")
    writeInstallResult("success", "installed", backup.toString(), prepared.version, "direct")

    val result = store.consumeInstallResult(prepared.version, currentApp)

    assertTrue(result is RebasedMacInstallResult.Failed)
    assertEquals(prepared, store.load())
    assertTrue(Files.exists(prepared.stagedApp!!))
    assertTrue(Files.exists(prepared.verifiedDmg!!))
    assertTrue(Files.exists(backupMarker))
    Files.newDirectoryStream(storeRoot(), "$INSTALL_RESULT_FILE.consuming-*").use {
	      assertFalse(it.iterator().hasNext())
	    }
	    Files.newDirectoryStream(storeRoot(), "$INSTALL_RESULT_FILE.consumed-*").use {
	      assertEquals(1, it.toList().size)
    }
  }

  @Test
  fun `successful direct result never treats marker backup as deletion authority`() {
    val store = store()
    val prepared = saveDirectUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    val expectedBackup = Files.createDirectories(currentApp.resolveSibling("Rebased.app.rebased-update-backup"))
    val unrelated = Files.createDirectories(tempDir.resolve("unrelated-backup"))
    val unrelatedMarker = Files.createFile(unrelated.resolve("keep"))
    writeInstallResult("success", "installed", unrelated.toString(), prepared.version, "direct")

    val result = store.consumeInstallResult(prepared.version, currentApp)

    assertTrue(result is RebasedMacInstallResult.Failed)
    assertTrue(Files.exists(unrelatedMarker))
    assertTrue(Files.exists(expectedBackup))
    assertEquals(prepared, store.load())
  }

  @Test
  fun `successful direct result refuses a symbolic expected backup without following it`() {
    val store = store()
    val prepared = saveDirectUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    val outsideBackup = Files.createDirectories(tempDir.resolve("outside-backup"))
    val outsideMarker = Files.createFile(outsideBackup.resolve("keep"))
    val backup = Files.createSymbolicLink(
      currentApp.resolveSibling("Rebased.app.rebased-update-backup"),
      outsideBackup,
    )
    writeInstallResult("success", "installed", backup.toString(), prepared.version, "direct")

    val result = store.consumeInstallResult(prepared.version, currentApp)

    assertTrue(result is RebasedMacInstallResult.Failed)
    assertTrue(Files.isSymbolicLink(backup))
    assertTrue(Files.exists(outsideMarker))
    assertEquals(prepared, store.load())
  }

  @Test
  fun `late cleanup failure after successful install returns success and leaves prepared state for housekeeping`() {
    val failure = IOException("delete failed")
    val store = RebasedMacUpdateStore(
      root = storeRoot(),
      deleteRecursively = { path ->
        if (path.fileName.toString() == "Rebased.app.rebased-update-backup") {
          Files.delete(path)
        }
        else {
          throw failure
        }
      },
    )
    val prepared = saveDirectUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    val backup = Files.createDirectories(currentApp.resolveSibling("Rebased.app.rebased-update-backup"))
    writeInstallResult("success", "installed", backup.toString(), prepared.version, "direct")

    val result = store.consumeInstallResult(prepared.version, currentApp)

    assertEquals(RebasedMacInstallResult.Success, result)
    assertEquals(prepared, store.load())
    assertFalse(Files.exists(backup))
    assertTrue(Files.exists(prepared.stagedApp!!))
    assertTrue(Files.exists(prepared.verifiedDmg!!))
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE)))
  }

  @Test
  fun `atomic move fallback consumes install result only once`() {
    saveHomebrewUpdate(store())
    val store = RebasedMacUpdateStore(
      root = storeRoot(),
      move = { source, target, _ ->
        throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "not supported")
      },
    )
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    writeInstallResult("failed", "failed once", "", "1.2.3", "homebrew")

    assertEquals(
      RebasedMacInstallResult.Failed("failed once"),
      store.consumeInstallResult("1.2.2", currentApp),
    )
    assertEquals(RebasedMacInstallResult.None, store.consumeInstallResult("1.2.2", currentApp))
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE), NOFOLLOW_LINKS))
  }

  @Test
  fun `atomic move fallback leaves a read-only install result unclaimed`() {
    val store = atomicMoveFallbackStore()
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    writeInstallResult("failed", "retry later", "", "1.2.3", "homebrew")
    val marker = storeRoot().resolve(INSTALL_RESULT_FILE)
    Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("r--r--r--"))

    try {
      assertEquals(RebasedMacInstallResult.None, store.consumeInstallResult("1.2.2", currentApp))
      assertTrue(Files.exists(marker, NOFOLLOW_LINKS))
    }
    finally {
      Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-------"))
    }
  }

  @Test
  fun `atomic move fallback leaves an install result unclaimed when deletion fails`() {
    val store = atomicMoveFallbackStore()
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    writeInstallResult("failed", "retry later", "", "1.2.3", "homebrew")
    val marker = storeRoot().resolve(INSTALL_RESULT_FILE)
    Files.setPosixFilePermissions(storeRoot(), PosixFilePermissions.fromString("r-x------"))

    try {
      assertEquals(RebasedMacInstallResult.None, store.consumeInstallResult("1.2.2", currentApp))
      assertTrue(Files.exists(marker, NOFOLLOW_LINKS))
    }
    finally {
      Files.setPosixFilePermissions(storeRoot(), PosixFilePermissions.fromString("rwx------"))
    }
  }

  @Test
  fun `atomic move fallback leaves a nonregular install result unclaimed when deletion fails`() {
    val store = atomicMoveFallbackStore()
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    val marker = Files.createDirectories(storeRoot().resolve(INSTALL_RESULT_FILE))
    Files.writeString(marker.resolve("payload"), "unexpected")
    Files.setPosixFilePermissions(storeRoot(), PosixFilePermissions.fromString("r-x------"))

    try {
      assertEquals(RebasedMacInstallResult.None, store.consumeInstallResult("1.2.3", currentApp))
      assertTrue(Files.exists(marker, NOFOLLOW_LINKS))
    }
    finally {
      Files.setPosixFilePermissions(storeRoot(), PosixFilePermissions.fromString("rwx------"))
    }
  }

  @Test
  fun `atomic move fallback does not follow or retain a symbolic legacy lock`() {
    Files.createDirectories(storeRoot())
    val externalLock = Files.writeString(tempDir.resolve("external-lock"), "keep")
    Files.createSymbolicLink(storeRoot().resolve("install-result.lock"), externalLock)
    saveHomebrewUpdate(store())
    val store = RebasedMacUpdateStore(
      root = storeRoot(),
      move = { source, target, _ ->
        throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "not supported")
      },
    )
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    writeInstallResult("failed", "failed once", "", "1.2.3", "homebrew")

    assertEquals(
      RebasedMacInstallResult.Failed("failed once"),
      store.consumeInstallResult("1.2.2", currentApp),
    )
    assertEquals(RebasedMacInstallResult.None, store.consumeInstallResult("1.2.2", currentApp))
    assertEquals("keep", Files.readString(externalLock))
    assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE), NOFOLLOW_LINKS))
  }

  @Test
  fun `install result is consumed only once`() {
    val store = store()
    val prepared = saveHomebrewUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    writeInstallResult("failed", "failed once", "", prepared.version, "homebrew")

    assertEquals(
      RebasedMacInstallResult.Failed("failed once"),
      store.consumeInstallResult("1.2.2", currentApp),
    )
    assertEquals(RebasedMacInstallResult.None, store.consumeInstallResult("1.2.2", currentApp))
  }

  @Test
  fun `concurrent atomic move fallback consumers claim marker exactly once`() {
    val executor = Executors.newFixedThreadPool(2)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    saveHomebrewUpdate(store())
    try {
      repeat(20) {
        writeInstallResult("failed", "failed once", "", "1.2.3", "homebrew")
        val moveBarrier = CyclicBarrier(2)
        val store = RebasedMacUpdateStore(
          root = storeRoot(),
          move = { source, target, _ ->
            moveBarrier.await(10, TimeUnit.SECONDS)
            throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "not supported")
          },
        )

        val results = List(2) {
          executor.submit<RebasedMacInstallResult> {
            store.consumeInstallResult("1.2.2", currentApp)
          }
        }.map { it.get(10, TimeUnit.SECONDS) }

        assertEquals(1, results.count { it == RebasedMacInstallResult.Failed("failed once") })
        assertEquals(1, results.count { it == RebasedMacInstallResult.None })
        assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE), NOFOLLOW_LINKS))
      }
    }
    finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `concurrent install result consumers claim marker exactly once`() {
    val executor = Executors.newFixedThreadPool(2)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    saveHomebrewUpdate(store())
    try {
      repeat(20) {
        writeInstallResult("failed", "failed once", "", "1.2.3", "homebrew")
        val moveBarrier = CyclicBarrier(2)
        val store = RebasedMacUpdateStore(
          root = storeRoot(),
          move = { source, target, options ->
            moveBarrier.await(10, TimeUnit.SECONDS)
            Files.move(source, target, *options)
          },
        )

        val results = List(2) {
          executor.submit<RebasedMacInstallResult> {
            store.consumeInstallResult("1.2.2", currentApp)
          }
        }.map { it.get(10, TimeUnit.SECONDS) }

        assertEquals(1, results.count { it == RebasedMacInstallResult.Failed("failed once") })
        assertEquals(1, results.count { it == RebasedMacInstallResult.None })
        assertFalse(Files.exists(storeRoot().resolve(INSTALL_RESULT_FILE), NOFOLLOW_LINKS))
        Files.newDirectoryStream(storeRoot(), "$INSTALL_RESULT_FILE.consuming-*").use {
          assertFalse(it.iterator().hasNext())
        }
      }
    }
    finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `discard prepared state removes only canonical store data and exact nonsymbolic backup`() {
    val store = store()
    val prepared = saveDirectUpdate(store)
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    val backup = Files.createDirectories(currentApp.resolveSibling("Rebased.app.rebased-update-backup"))
    val unrelated = Files.createDirectories(tempDir.resolve("unrelated"))
    val unrelatedMarker = Files.createFile(unrelated.resolve("keep"))

    store.discardPreparedState(prepared, currentApp)

    assertFalse(Files.exists(backup))
    assertFalse(Files.exists(store.versionDirectory(prepared.version)))
    assertNull(store.load())
    assertTrue(Files.exists(unrelatedMarker))
  }

  @Test
  fun `sanitizes version directory name`() {
    val directory = store().versionDirectory("1.2 beta/rc+1")

    assertEquals(
      storeRoot().resolve("1.2_beta_rc_1-c5257023a272f3c7a9c1c9b7cafd541f0d5fa889dccbdc56d980f42793b615a3"),
      directory,
    )
    assertEquals(storeRoot(), directory.parent)
  }

  @Test
  fun `maps colliding and case-only versions to distinct deterministic directories`() {
    val versions = listOf("1.2 beta", "1.2/beta", "1.2_beta", "1.2 BETA")
    val store = store()
    val directoryNames = versions.map { store.versionDirectory(it).fileName.toString() }

    assertEquals(directoryNames, versions.map { store.versionDirectory(it).fileName.toString() })
    assertEquals(versions.size, directoryNames.toSet().size)
    assertEquals(versions.size, directoryNames.map { it.lowercase(Locale.ROOT) }.toSet().size)
    assertTrue(directoryNames.all { it.substringAfterLast('-').matches(Regex("[0-9a-f]{64}")) })
  }

  @Test
  fun `bounds long version directory names while preserving deterministic hash identity`() {
    val version = "release." + "1.2.3.alpha.".repeat(30)
    val otherVersion = "$version-next"
    val store = store()

    val directory = store.versionDirectory(version)
    val repeatedDirectory = store.versionDirectory(version)
    val otherDirectory = store.versionDirectory(otherVersion)
    val directoryName = directory.fileName.toString()
    val otherDirectoryName = otherDirectory.fileName.toString()

    assertTrue(version.length > 300)
    assertTrue(directoryName.toByteArray(Charsets.UTF_8).size <= 255)
    assertEquals(version.take(190), directoryName.substringBeforeLast('-'))
    assertEquals(directory, repeatedDirectory)
    assertEquals(directoryName.substringBeforeLast('-'), otherDirectoryName.substringBeforeLast('-'))
    assertNotEquals(directoryName.substringAfterLast('-'), otherDirectoryName.substringAfterLast('-'))
    assertTrue(directoryName.substringAfterLast('-').matches(Regex("[0-9a-f]{64}")))
    assertTrue(Files.isDirectory(Files.createDirectories(directory)))
  }

  private fun store(): RebasedMacUpdateStore = RebasedMacUpdateStore(storeRoot())

  private fun storeRoot(): Path = tempDir.toRealPath().resolve("store")

  private fun saveDirectUpdate(
    store: RebasedMacUpdateStore,
    version: String = "1.2.3",
  ): PreparedRebasedMacUpdate {
    val versionDirectory = Files.createDirectories(store.versionDirectory(version))
    val update = PreparedRebasedMacUpdate(
      version = version,
      strategy = RebasedMacUpdateStrategy.DIRECT,
      stagedApp = Files.createDirectories(versionDirectory.resolve("Rebased.app")),
      verifiedDmg = Files.writeString(versionDirectory.resolve("rebased.dmg"), "verified DMG"),
      verifiedDmgSha256 = VERIFIED_DMG_SHA256,
      brewExecutable = null,
      releasePageUrl = RELEASE_URL,
    )
    store.save(update)
    return store.load()!!
  }

  private fun saveHomebrewUpdate(
    store: RebasedMacUpdateStore,
    version: String = "1.2.3",
  ): PreparedRebasedMacUpdate {
    val update = PreparedRebasedMacUpdate(
      version = version,
      strategy = RebasedMacUpdateStrategy.HOMEBREW,
      stagedApp = null,
      verifiedDmg = null,
      verifiedDmgSha256 = null,
      brewExecutable = executable(tempDir.resolve("opt/homebrew/bin/brew")),
      releasePageUrl = RELEASE_URL,
    )
    store.save(update)
    return store.load()!!
  }

  private fun writeInstallResult(
    status: String,
    message: String,
    backup: String,
    version: String,
    strategy: String,
  ) = writeInstallResult(storeRoot().resolve(INSTALL_RESULT_FILE), status, message, backup, version, strategy)

  private fun writeInstallResult(
    path: Path,
    status: String,
    message: String,
    backup: String,
    version: String,
    strategy: String,
  ) {
    val properties = Properties().apply {
      setProperty("status", status)
      setProperty("message", message)
      setProperty("backup", backup)
      setProperty("version", version)
      setProperty("strategy", strategy)
    }
    Files.createDirectories(path.parent)
    Files.newOutputStream(path).use {
      properties.store(it, null)
    }
  }

  private fun atomicMoveFallbackStore(): RebasedMacUpdateStore {
    return RebasedMacUpdateStore(
      root = storeRoot(),
      move = { source, target, _ ->
        throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "not supported")
      },
    )
  }

  private fun directState(
    version: String,
    stagedApp: Path,
    verifiedDmg: Path = validVerifiedDmg(version),
    verifiedDmgSha256: String = VERIFIED_DMG_SHA256,
  ): Map<String, String> = mapOf(
    VERSION_PROPERTY to version,
    STRATEGY_PROPERTY to RebasedMacUpdateStrategy.DIRECT.name,
    STAGED_APP_PROPERTY to stagedApp.toString(),
    VERIFIED_DMG_PROPERTY to verifiedDmg.toString(),
    VERIFIED_DMG_SHA256_PROPERTY to verifiedDmgSha256,
    RELEASE_PAGE_URL_PROPERTY to RELEASE_URL,
  )

  private fun validVerifiedDmg(version: String): Path {
    val verifiedDmg = store().versionDirectory(version).resolve("rebased.dmg")
    Files.createDirectories(verifiedDmg.parent)
    if (!Files.exists(verifiedDmg)) {
      Files.writeString(verifiedDmg, "verified DMG")
    }
    return verifiedDmg
  }

  private fun homebrewState(brewExecutable: Path? = null): Map<String, String> = buildMap {
    put(VERSION_PROPERTY, "1.2.3")
    put(STRATEGY_PROPERTY, RebasedMacUpdateStrategy.HOMEBREW.name)
    if (brewExecutable != null) {
      put(BREW_EXECUTABLE_PROPERTY, brewExecutable.toString())
    }
    put(RELEASE_PAGE_URL_PROPERTY, RELEASE_URL)
  }

  private fun executable(path: Path): Path {
    regularFile(path)
    assertTrue(path.toFile().setExecutable(true), "Failed to make $path executable")
    return path
  }

  private fun regularFile(path: Path): Path {
    Files.createDirectories(path.parent)
    Files.createFile(path)
    assertFalse(Files.isExecutable(path), "Expected $path to be nonexecutable")
    return path
  }

  private fun writeState(values: Map<String, String>) {
    Files.createDirectories(storeRoot())
    val properties = Properties()
    for ((key, value) in values) {
      properties.setProperty(key, value)
    }
    Files.newOutputStream(storeRoot().resolve(STATE_FILE)).use {
      properties.store(it, null)
    }
  }

  companion object {
    private const val STATE_FILE = "prepared.properties"
    private const val INSTALL_RESULT_FILE = "install-result.properties"
    private const val VERSION_PROPERTY = "version"
    private const val STRATEGY_PROPERTY = "strategy"
    private const val STAGED_APP_PROPERTY = "stagedApp"
    private const val VERIFIED_DMG_PROPERTY = "verifiedDmg"
    private const val VERIFIED_DMG_SHA256_PROPERTY = "verifiedDmgSha256"
    private const val BREW_EXECUTABLE_PROPERTY = "brewExecutable"
    private const val RELEASE_PAGE_URL_PROPERTY = "releasePageUrl"
    private const val RELEASE_URL = "https://github.com/luozejian/rebased/releases/tag/v1.2.3"
    private const val VERIFIED_DMG_SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  }
}
