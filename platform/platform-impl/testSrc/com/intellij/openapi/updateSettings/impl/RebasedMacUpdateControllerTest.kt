// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.BuildNumber
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.system.OS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

@TestApplication
internal class RebasedMacUpdateControllerTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `direct preparation requires a valid DMG URL and digest`() {
    assertTrue(canPrepare(platformUpdate()))
    assertFalse(canPrepare(platformUpdate(downloadUrl = null)))
    assertFalse(canPrepare(platformUpdate(downloadUrl = "http://example.test/rebased-aarch64.dmg")))
    assertFalse(canPrepare(platformUpdate(downloadUrl = "https://example.test/rebased-aarch64.zip")))
    assertFalse(canPrepare(platformUpdate(downloadUrl = "https://")))
    assertFalse(canPrepare(platformUpdate(digest = null)))
    assertFalse(canPrepare(platformUpdate(digest = "not-a-digest")))
  }

  @Test
  fun `Homebrew preparation does not require direct release assets`() {
    assertTrue(
      canPrepare(
        platformUpdate(downloadUrl = null, digest = null),
        manager = ExternalUpdateManager.BREW,
      )
    )
  }

  @Test
  fun `preparation requires restart-capable no-patch macOS`() {
    val update = platformUpdate()

    assertFalse(canPrepare(update, os = OS.macOS, restartCapable = false))
    assertFalse(canPrepare(update, os = OS.Windows, restartCapable = true))
    assertFalse(
      canPrepare(
        update.copy(patches = UpdateChain(listOf(BuildNumber.fromString("1.0")!!), null)),
        os = OS.macOS,
        restartCapable = true,
      )
    )
  }

  @Test
  fun `Homebrew bypasses the external manager gate only on restart-capable macOS`() {
    assertFalse(isExternalManagerBlocking(null, OS.Windows, restartCapable = false))
    assertFalse(isExternalManagerBlocking(ExternalUpdateManager.BREW, OS.macOS, restartCapable = true))
    assertTrue(isExternalManagerBlocking(ExternalUpdateManager.BREW, OS.macOS, restartCapable = false))
    assertTrue(isExternalManagerBlocking(ExternalUpdateManager.BREW, OS.Windows, restartCapable = true))
  }

  @Test
  fun `non-Homebrew external managers always block updates`() {
    assertTrue(isExternalManagerBlocking(ExternalUpdateManager.TOOLBOX))
    assertTrue(isExternalManagerBlocking(ExternalUpdateManager.SNAP))
    assertTrue(isExternalManagerBlocking(ExternalUpdateManager.FLATPAK))
    assertTrue(isExternalManagerBlocking(ExternalUpdateManager.UNKNOWN))

    val update = platformUpdate()
    assertFalse(canPrepare(update, manager = ExternalUpdateManager.TOOLBOX))
    assertFalse(canPrepare(update, manager = ExternalUpdateManager.SNAP))
    assertFalse(canPrepare(update, manager = ExternalUpdateManager.FLATPAK))
    assertFalse(canPrepare(update, manager = ExternalUpdateManager.UNKNOWN))
  }

  @Test
  fun `restored newer prepared update emits one ready notification and installs from its action`() {
    val harness = Harness()
    val prepared = preparedDirect()

    harness.workflow.restoreNotifications(null, RebasedMacInstallResult.None, prepared)
    harness.workflow.restoreNotifications(null, RebasedMacInstallResult.None, prepared)

    assertEquals(1, harness.notifications.size)
    assertEquals(0, harness.readyOffers)
    harness.notifications.single().invoke()
    harness.runNextBackground()
    harness.runNextUi()
    assertEquals(0, harness.prepareCalls)
    assertEquals(1, harness.freshCalls.size)
    assertEquals(listOf(listOf("install")), harness.restarts)
  }

  @Test
  fun `restored failure with newer prepared update emits only failure and retry installs`() {
    val harness = Harness()
    val prepared = preparedDirect()
    val result = RebasedMacInstallResult.Failed("installer failed")

    harness.workflow.restoreNotifications(null, result, prepared)
    harness.workflow.restoreNotifications(null, result, prepared)

    assertTrue(harness.notifications.isEmpty())
    val failure = harness.startupFailures.single()
    assertEquals("installer failed", failure.error.message)
    assertTrue(failure.retry != null)
    assertTrue(failure.openRelease != null)
    failure.openRelease!!.invoke()
    assertEquals(listOf(RELEASE_PAGE), harness.openedPages)

    failure.retry!!.invoke()
    harness.runNextBackground()
    harness.runNextUi()
    assertEquals(0, harness.prepareCalls)
    assertEquals(1, harness.freshCalls.size)
    assertEquals(listOf(listOf("install")), harness.restarts)
  }

  @Test
  fun `restored failure without valid prepared update has no retry or release action`() {
    val harness = Harness()

    harness.workflow.restoreNotifications(
      null,
      RebasedMacInstallResult.Failed("invalid retained state"),
      null,
    )

    val failure = harness.startupFailures.single()
    assertEquals("invalid retained state", failure.error.message)
    assertNull(failure.retry)
    assertNull(failure.openRelease)
    assertTrue(harness.notifications.isEmpty())
    assertTrue(harness.backgroundTasks.isEmpty())
  }

  @Test
  fun `restored success emits one startup information notification`() {
    val harness = Harness()

    harness.workflow.restoreNotifications(null, RebasedMacInstallResult.Success, null)
    harness.workflow.restoreNotifications(null, RebasedMacInstallResult.Success, null)

    assertEquals(1, harness.startupSuccesses)
    assertTrue(harness.notifications.isEmpty())
    assertTrue(harness.startupFailures.isEmpty())
  }

  @Test
  fun `cancellation after result consumption still delivers notification exactly once`() = runBlocking {
    val consumed = CompletableDeferred<Unit>()
    val releaseRead = CompletableDeferred<Unit>()
    val restoration = RebasedMacUpdateRestoration(RebasedMacInstallResult.Success, null)
    val delivered = mutableListOf<RebasedMacUpdateRestoration>()
    val failures = mutableListOf<Throwable>()
    val job = launch {
      restoreRebasedMacUpdateNotifications(
        read = {
          consumed.complete(Unit)
          releaseRead.await()
          restoration
        },
        notify = { delivered += it },
        reportFailure = { failures += it },
      )
    }

    consumed.await()
    job.cancel()
    releaseRead.complete(Unit)
    job.join()

    assertEquals(listOf(restoration), delivered)
    assertTrue(failures.isEmpty())
  }

  @Test
  fun `failed direct install at running version retains prepared state and backup for retry`() {
    val root = tempDir.toRealPath().resolve("failed-installed-store")
    val store = RebasedMacUpdateStore(root)
    val versionDirectory = Files.createDirectories(store.versionDirectory(VERSION))
    val stagedApp = Files.createDirectories(versionDirectory.resolve("Rebased.app"))
    val verifiedDmg = Files.writeString(versionDirectory.resolve("rebased.dmg"), "verified DMG")
    store.save(
      PreparedRebasedMacUpdate(
        version = VERSION,
        strategy = RebasedMacUpdateStrategy.DIRECT,
        stagedApp = stagedApp,
        verifiedDmg = verifiedDmg,
        verifiedDmgSha256 = DIGEST,
        brewExecutable = null,
        releasePageUrl = RELEASE_PAGE,
      )
    )
    val prepared = store.load()!!
    val currentApp = Files.createDirectories(tempDir.resolve("failed-installed-app/Rebased.app"))
    val backup = Files.createDirectories(currentApp.resolveSibling("Rebased.app.rebased-update-backup"))
    val backupMarker = Files.writeString(backup.resolve("old-app"), "old")
    val installResult = root.resolve("install-result.properties")
    Properties().apply {
      setProperty("status", "failed")
      setProperty("message", "cleanup failed")
      setProperty("backup", backup.toString())
      setProperty("version", VERSION)
      setProperty("strategy", "direct")
    }.also { properties ->
      Files.newOutputStream(installResult).use {
        properties.store(it, null)
      }
    }

    val restoration = readRebasedMacUpdateRestoration(store, VERSION, currentApp)

    assertEquals(RebasedMacInstallResult.Failed("cleanup failed"), restoration.result)
    assertEquals(prepared, restoration.prepared)
    val harness = Harness()
    harness.workflow.restoreNotifications(null, restoration.result, restoration.prepared)
    val failure = harness.startupFailures.single()
    assertTrue(failure.retry != null)
    assertTrue(failure.openRelease != null)
    assertEquals(prepared, store.load())
    assertTrue(Files.exists(stagedApp))
    assertTrue(Files.exists(verifiedDmg))
    assertTrue(Files.exists(backupMarker))
    assertFalse(Files.exists(installResult))
  }

  @Test
  fun `restore state discards prepared update at or below running version`() {
    val root = tempDir.toRealPath().resolve("store")
    val store = RebasedMacUpdateStore(root)
    val brew = Files.createDirectories(tempDir.resolve("bin")).resolve("brew")
    Files.createFile(brew)
    assertTrue(brew.toFile().setExecutable(true))
    store.save(
      PreparedRebasedMacUpdate(
        version = VERSION,
        strategy = RebasedMacUpdateStrategy.HOMEBREW,
        stagedApp = null,
        verifiedDmg = null,
        verifiedDmgSha256 = null,
        brewExecutable = brew,
        releasePageUrl = RELEASE_PAGE,
      )
    )
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))

    val restoration = readRebasedMacUpdateRestoration(store, VERSION, currentApp)

    assertEquals(RebasedMacInstallResult.None, restoration.result)
    assertNull(restoration.prepared)
    assertNull(store.load())
  }

  @Test
  fun `restore state clears malformed prepared state without throwing`() {
    val root = Files.createDirectories(tempDir.toRealPath().resolve("store"))
    Files.writeString(root.resolve("prepared.properties"), "version=bad\nstrategy=UNKNOWN\n")
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    val failures = mutableListOf<Throwable>()

    val restoration = readRebasedMacUpdateRestoration(
      RebasedMacUpdateStore(root),
      VERSION,
      currentApp,
      failures::add,
    )

    assertEquals(RebasedMacInstallResult.None, restoration.result)
    assertNull(restoration.prepared)
    assertFalse(Files.exists(root.resolve("prepared.properties")))
    assertEquals(1, failures.size)
  }

  @Test
  fun `restore state clears nonregular malformed prepared state without following it`() {
    val root = Files.createDirectories(tempDir.toRealPath().resolve("nonregular-state-store"))
    val invalidState = Files.createDirectories(root.resolve("prepared.properties"))
    Files.writeString(invalidState.resolve("junk"), "state")
    val abandonedVersionDirectory = Files.createDirectories(RebasedMacUpdateStore(root).versionDirectory(NEW_VERSION).resolve("mount"))
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))
    val outside = Files.createDirectories(tempDir.resolve("outside"))
    Files.createSymbolicLink(root.resolve("stale-link"), outside)
    val failures = mutableListOf<Throwable>()

    val restoration = readRebasedMacUpdateRestoration(
      RebasedMacUpdateStore(root),
      VERSION,
      currentApp,
      failures::add,
    )

    assertEquals(RebasedMacInstallResult.None, restoration.result)
    assertNull(restoration.prepared)
    assertFalse(Files.exists(invalidState))
    assertFalse(Files.exists(abandonedVersionDirectory))
    assertFalse(Files.exists(root.resolve("stale-link")))
    assertTrue(Files.exists(outside))
    assertEquals(1, failures.size)
  }

  @Test
  fun `restore state clears crash leftovers when no prepared update exists`() {
    val root = Files.createDirectories(tempDir.toRealPath().resolve("crash-store"))
    val store = RebasedMacUpdateStore(root)
    val abandonedVersionDirectory = Files.createDirectories(store.versionDirectory(NEW_VERSION).resolve("mount"))
    val abandonedPartial = Files.writeString(root.resolve("rebased.dmg.part"), "partial")
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app"))

    val restoration = readRebasedMacUpdateRestoration(store, VERSION, currentApp)

    assertEquals(RebasedMacInstallResult.None, restoration.result)
    assertNull(restoration.prepared)
    assertFalse(Files.exists(abandonedVersionDirectory))
    assertFalse(Files.exists(abandonedPartial))
  }

  @Test
  fun `restore state keeps valid prepared update while clearing unrelated crash leftovers`() {
    val root = tempDir.toRealPath().resolve("prepared-crash-store")
    val store = RebasedMacUpdateStore(root)
    val preparedVersionDirectory = Files.createDirectories(store.versionDirectory(NEW_VERSION))
    val stagedApp = Files.createDirectories(preparedVersionDirectory.resolve("Rebased.app"))
    val verifiedDmg = Files.writeString(preparedVersionDirectory.resolve("rebased.dmg"), "verified DMG")
    store.save(
      PreparedRebasedMacUpdate(
        version = NEW_VERSION,
        strategy = RebasedMacUpdateStrategy.DIRECT,
        stagedApp = stagedApp,
        verifiedDmg = verifiedDmg,
        verifiedDmgSha256 = DIGEST,
        brewExecutable = null,
        releasePageUrl = RELEASE_PAGE,
      )
    )
    val prepared = store.load()!!
    val abandonedVersionDirectory = Files.createDirectories(store.versionDirectory("1.2.2").resolve("mount"))
    val abandonedPartial = Files.writeString(root.resolve("rebased.dmg.part"), "partial")

    val restoration = readRebasedMacUpdateRestoration(
      store,
      VERSION,
      Files.createDirectories(tempDir.resolve("prepared-crash-app/Rebased.app")),
    )

    assertEquals(RebasedMacInstallResult.None, restoration.result)
    assertEquals(prepared, restoration.prepared)
    assertEquals(prepared, store.load())
    assertTrue(Files.exists(stagedApp))
    assertTrue(Files.exists(verifiedDmg))
    assertFalse(Files.exists(abandonedVersionDirectory))
    assertFalse(Files.exists(abandonedPartial))
  }

  @Test
  fun `restore state discards a persisted update with malformed version syntax`() {
    val root = tempDir.toRealPath().resolve("store")
    val store = RebasedMacUpdateStore(root)
    val brew = Files.createDirectories(tempDir.resolve("malformed-version-bin")).resolve("brew")
    Files.createFile(brew)
    assertTrue(brew.toFile().setExecutable(true))
    store.save(
      PreparedRebasedMacUpdate(
        version = "999.bad",
        strategy = RebasedMacUpdateStrategy.HOMEBREW,
        stagedApp = null,
        verifiedDmg = null,
        verifiedDmgSha256 = null,
        brewExecutable = brew,
        releasePageUrl = RELEASE_PAGE,
      )
    )
    val failures = mutableListOf<Throwable>()

    val restoration = readRebasedMacUpdateRestoration(
      store,
      VERSION,
      Files.createDirectories(tempDir.resolve("malformed-version-app/Rebased.app")),
      failures::add,
    )

    assertEquals(RebasedMacInstallResult.None, restoration.result)
    assertNull(restoration.prepared)
    assertNull(store.load())
    assertEquals(1, failures.size)
  }

  @Test
  fun `download queues preparation and offers ready only after it completes`() {
    val harness = Harness()

    harness.workflow.prepareAndOfferRestart(null, platformUpdate())

    assertEquals(1, harness.backgroundTasks.size)
    assertEquals(0, harness.readyOffers)

    harness.runNextBackground()
    assertEquals(0, harness.readyOffers)

    harness.runNextUi()
    assertEquals(1, harness.readyOffers)
  }

  @Test
  fun `duplicate download clicks queue only one preparation`() {
    val harness = Harness()

    harness.workflow.prepareAndOfferRestart(null, platformUpdate())
    harness.workflow.prepareAndOfferRestart(null, platformUpdate())

    assertEquals(1, harness.backgroundTasks.size)
  }

  @Test
  fun `new version waits for active preparation and suppresses its ready offer`() {
    val harness = Harness()

    harness.workflow.prepareAndOfferRestart(null, platformUpdate())
    harness.workflow.prepareAndOfferRestart(null, platformUpdate(NEW_VERSION))

    assertEquals(1, harness.backgroundTasks.size)
    harness.runNextBackground()
    assertTrue(harness.uiTasks.isEmpty())
    assertEquals(1, harness.backgroundTasks.size)

    harness.preparedResult = preparedDirect(NEW_VERSION)
    harness.runNextBackground()
    harness.runNextUi()

    assertEquals(listOf(VERSION, NEW_VERSION), harness.prepareVersions)
    assertEquals(1, harness.readyOffers)
  }

  @Test
  fun `background preparation and install are application scoped while UI keeps request project`() {
    val project = ProjectManager.getInstance().defaultProject
    val harness = Harness().apply {
      readyChoices += RebasedMacReadyChoice.RESTART_AND_INSTALL
    }

    harness.workflow.prepareAndOfferRestart(project, platformUpdate())

    assertEquals(listOf(null), harness.backgroundProjects)
    harness.runNextBackground()
    harness.runNextUi()

    assertEquals(listOf(project), harness.readyProjects)
    assertEquals(listOf(null, null), harness.backgroundProjects)
    harness.runNextBackground()
    harness.runNextUi()

    assertEquals(listOf(listOf("install")), harness.restarts)
  }

  @Test
  fun `multiple pending preparations keep only the latest different version`() {
    val harness = Harness()

    harness.workflow.prepareAndOfferRestart(null, platformUpdate())
    harness.workflow.prepareAndOfferRestart(null, platformUpdate(NEW_VERSION))
    harness.workflow.prepareAndOfferRestart(null, platformUpdate(NEW_VERSION))
    harness.workflow.prepareAndOfferRestart(null, platformUpdate(LATEST_VERSION))
    harness.workflow.prepareAndOfferRestart(null, platformUpdate(LATEST_VERSION))

    harness.runNextBackground()
    assertEquals(1, harness.backgroundTasks.size)
    harness.preparedResult = preparedDirect(LATEST_VERSION)
    harness.runNextBackground()
    harness.runNextUi()

    assertEquals(listOf(VERSION, LATEST_VERSION), harness.prepareVersions)
    assertEquals(1, harness.readyOffers)
  }

  @Test
  fun `older pending request cannot replace a newer queued version`() {
    val harness = Harness()

    harness.workflow.prepareAndOfferRestart(null, platformUpdate())
    harness.workflow.prepareAndOfferRestart(null, platformUpdate(LATEST_VERSION))
    harness.workflow.prepareAndOfferRestart(null, platformUpdate(NEW_VERSION))

    harness.runNextBackground()
    assertEquals(1, harness.backgroundTasks.size)

    harness.preparedResult = preparedDirect(LATEST_VERSION)
    harness.runNextBackground()
    harness.runNextUi()

    assertEquals(listOf(VERSION, LATEST_VERSION), harness.prepareVersions)
    assertEquals(1, harness.readyOffers)
  }

  @Test
  fun `malformed pending request cannot replace a valid queued version`() {
    val harness = Harness()

    harness.workflow.prepareAndOfferRestart(null, platformUpdate())
    harness.workflow.prepareAndOfferRestart(null, platformUpdate(LATEST_VERSION))
    harness.workflow.prepareAndOfferRestart(null, platformUpdate(version = "bad", buildNumber = LATEST_VERSION))

    harness.runNextBackground()
    assertEquals(1, harness.backgroundTasks.size)

    harness.preparedResult = preparedDirect(LATEST_VERSION)
    harness.runNextBackground()
    harness.runNextUi()

    assertEquals(listOf(VERSION, LATEST_VERSION), harness.prepareVersions)
    assertEquals(1, harness.readyOffers)
  }

  @Test
  fun `pending preparation starts after active failure without offering stale failure`() {
    val harness = Harness().apply {
      prepareFailures += IllegalStateException("old preparation failed")
    }

    harness.workflow.prepareAndOfferRestart(null, platformUpdate())
    harness.workflow.prepareAndOfferRestart(null, platformUpdate(NEW_VERSION))
    harness.runNextBackground()

    assertTrue(harness.uiTasks.isEmpty())
    assertEquals(0, harness.failureOffers)
    assertEquals(1, harness.backgroundTasks.size)

    harness.preparedResult = preparedDirect(NEW_VERSION)
    harness.runNextBackground()
    harness.runNextUi()

    assertEquals(listOf(VERSION, NEW_VERSION), harness.prepareVersions)
    assertEquals(1, harness.readyOffers)
  }

  @Test
  fun `same prepared version reoffers ready without preparing again`() {
    val harness = Harness().apply {
      readyChoices += RebasedMacReadyChoice.LATER
      readyChoices += RebasedMacReadyChoice.LATER
    }

    harness.prepareToReady()
    harness.runNextUi()
    harness.workflow.prepareAndOfferRestart(null, platformUpdate())

    assertTrue(harness.backgroundTasks.isEmpty())
    assertEquals(1, harness.uiTasks.size)
    harness.runNextUi()
    assertEquals(1, harness.prepareCalls)
    assertEquals(2, harness.readyOffers)
  }

  @Test
  fun `older request reoffers newer prepared update instead of replacing it`() {
    val harness = Harness(preparedDirect(LATEST_VERSION)).apply {
      readyChoices += RebasedMacReadyChoice.LATER
      readyChoices += RebasedMacReadyChoice.LATER
    }

    harness.workflow.prepareAndOfferRestart(null, platformUpdate(LATEST_VERSION))
    harness.runNextBackground()
    harness.runNextUi()
    harness.workflow.prepareAndOfferRestart(null, platformUpdate(NEW_VERSION))

    assertTrue(harness.backgroundTasks.isEmpty())
    assertEquals(1, harness.uiTasks.size)
    harness.runNextUi()

    assertEquals(listOf(LATEST_VERSION), harness.prepareVersions)
    assertEquals(2, harness.readyOffers)
  }

  @Test
  fun `new prepared version invalidates old notification action`() {
    val harness = Harness().apply {
      readyChoices += RebasedMacReadyChoice.LATER
    }

    harness.prepareToReady()
    harness.runNextUi()
    val oldNotificationAction = harness.notifications.single()
    harness.preparedResult = preparedDirect(NEW_VERSION)

    harness.workflow.prepareAndOfferRestart(null, platformUpdate(NEW_VERSION))
    oldNotificationAction()

    assertEquals(1, harness.backgroundTasks.size)
    harness.runNextBackground()
    assertEquals(2, harness.prepareCalls)
  }

  @Test
  fun `download click during install does not queue concurrent work`() {
    val harness = Harness().apply {
      readyChoices += RebasedMacReadyChoice.RESTART_AND_INSTALL
    }

    harness.prepareToReady()
    harness.runNextUi()
    harness.workflow.prepareAndOfferRestart(null, platformUpdate())

    assertEquals(1, harness.backgroundTasks.size)
  }

  @Test
  fun `restart choice queues command construction and restarts only after it completes`() {
    val harness = Harness().apply {
      readyChoices += RebasedMacReadyChoice.RESTART_AND_INSTALL
    }

    harness.prepareToReady()
    harness.runNextUi()

    assertEquals(1, harness.backgroundTasks.size)
    assertTrue(harness.restarts.isEmpty())

    harness.runNextBackground()
    assertTrue(harness.restarts.isEmpty())

    harness.runNextUi()
    assertEquals(listOf(listOf("install")), harness.restarts)
  }

  @Test
  fun `later keeps prepared state and notification action queues command construction`() {
    val harness = Harness().apply {
      readyChoices += RebasedMacReadyChoice.LATER
    }

    harness.prepareToReady()
    harness.runNextUi()

    assertEquals(1, harness.notifications.size)
    assertEquals(1, harness.prepareCalls)
    assertTrue(harness.backgroundTasks.isEmpty())
    assertTrue(harness.restarts.isEmpty())

    harness.notifications.single().invoke()

    assertEquals(1, harness.backgroundTasks.size)
    assertEquals(1, harness.prepareCalls)
  }

  @Test
  fun `direct install loads a fresh digest and passes it with the same indicator`() {
    val harness = Harness().apply {
      readyChoices += RebasedMacReadyChoice.RESTART_AND_INSTALL
    }
    val installIndicator = EmptyProgressIndicator()

    harness.prepareToReady()
    harness.runNextUi()
    harness.runNextBackground(installIndicator)

    val freshCall = harness.freshCalls.single()
    val commandCall = harness.commandCalls.single()
    assertEquals(VERSION, freshCall.version)
    assertSame(installIndicator, freshCall.indicator)
    assertEquals(DIGEST, commandCall.trustedDigest)
    assertSame(installIndicator, commandCall.indicator)
  }

  @Test
  fun `missing fresh direct digest fails without constructing a command`() {
    val harness = Harness().apply {
      readyChoices += RebasedMacReadyChoice.RESTART_AND_INSTALL
      freshDigest = null
      failureChoices += RebasedMacFailureChoice.CANCEL
    }

    harness.prepareToReady()
    harness.runNextUi()
    harness.runNextBackground()

    assertTrue(harness.commandCalls.isEmpty())
    assertTrue(harness.restarts.isEmpty())
    harness.runNextUi()
    assertEquals(1, harness.failureOffers)
  }

  @Test
  fun `Homebrew install skips fresh metadata and never elevates`() {
    val harness = Harness(prepared = preparedHomebrew()).apply {
      readyChoices += RebasedMacReadyChoice.RESTART_AND_INSTALL
      targetParentWritable = false
    }

    harness.prepareToReady()
    harness.runNextUi()
    harness.runNextBackground()

    assertTrue(harness.freshCalls.isEmpty())
    val commandCall = harness.commandCalls.single()
    assertNull(commandCall.trustedDigest)
    assertFalse(commandCall.elevate)
  }

  @Test
  fun `direct install targets the running app and elevates only for a non-writable parent`() {
    val writable = Harness().apply {
      readyChoices += RebasedMacReadyChoice.RESTART_AND_INSTALL
      targetParentWritable = true
    }
    writable.prepareToReady()
    writable.runNextUi()
    writable.runNextBackground()

    assertEquals(TARGET_APP, writable.commandCalls.single().targetApp)
    assertFalse(writable.commandCalls.single().elevate)

    val protected = Harness().apply {
      readyChoices += RebasedMacReadyChoice.RESTART_AND_INSTALL
      targetParentWritable = false
    }
    protected.prepareToReady()
    protected.runNextUi()
    protected.runNextBackground()

    assertEquals(TARGET_APP, protected.commandCalls.single().targetApp)
    assertTrue(protected.commandCalls.single().elevate)
  }

  @Test
  fun `prepare failure retry reruns preparation and open release uses the channel page`() {
    val retry = Harness().apply {
      prepareFailures += IllegalStateException("prepare failed")
      failureChoices += RebasedMacFailureChoice.RETRY
    }

    retry.workflow.prepareAndOfferRestart(null, platformUpdate())
    retry.runNextBackground()
    retry.runNextUi()

    assertEquals(1, retry.prepareCalls)
    assertEquals(1, retry.backgroundTasks.size)
    retry.runNextBackground()
    assertEquals(2, retry.prepareCalls)

    val openRelease = Harness().apply {
      prepareFailures += IllegalStateException("prepare failed")
      failureChoices += RebasedMacFailureChoice.OPEN_RELEASE_PAGE
    }
    openRelease.workflow.prepareAndOfferRestart(null, platformUpdate())
    openRelease.runNextBackground()
    openRelease.runNextUi()

    assertEquals(listOf(RELEASE_PAGE), openRelease.openedPages)
  }

  @Test
  fun `stale prepare failure does not interrupt newer preparation`() {
    val harness = Harness().apply {
      prepareFailures += IllegalStateException("prepare failed")
    }

    harness.workflow.prepareAndOfferRestart(null, platformUpdate())
    harness.runNextBackground()
    assertEquals(1, harness.uiTasks.size)

    harness.preparedResult = preparedDirect(NEW_VERSION)
    harness.workflow.prepareAndOfferRestart(null, platformUpdate(NEW_VERSION))
    harness.runNextUi()

    assertEquals(0, harness.failureOffers)
    assertEquals(1, harness.backgroundTasks.size)
    harness.runNextBackground()
    harness.runNextUi()
    assertEquals(1, harness.readyOffers)
  }

  @Test
  fun `install failure retry repeats only fresh metadata and command construction`() {
    val harness = Harness().apply {
      readyChoices += RebasedMacReadyChoice.RESTART_AND_INSTALL
      commandFailures += IllegalStateException("command failed")
      failureChoices += RebasedMacFailureChoice.RETRY
    }

    harness.prepareToReady()
    harness.runNextUi()
    harness.runNextBackground()
    harness.runNextUi()

    assertEquals(1, harness.prepareCalls)
    assertEquals(1, harness.backgroundTasks.size)

    harness.runNextBackground()
    harness.runNextUi()

    assertEquals(1, harness.prepareCalls)
    assertEquals(2, harness.freshCalls.size)
    assertEquals(2, harness.commandCalls.size)
    assertEquals(listOf(listOf("install")), harness.restarts)
  }

  @Test
  fun `restart capability loss before preparation reports failure and allows retry`() {
    val harness = Harness().apply {
      canRestart = false
      enableRestartOnFailure = true
      failureChoices += RebasedMacFailureChoice.RETRY
    }

    harness.workflow.prepareAndOfferRestart(null, platformUpdate())

    assertTrue(harness.backgroundTasks.isEmpty())
    harness.runNextUi()
    assertEquals(1, harness.failureOffers)
    assertEquals(1, harness.backgroundTasks.size)
  }

  @Test
  fun `restart capability loss before install keeps prepared update retryable`() {
    val harness = Harness().apply {
      readyChoices += RebasedMacReadyChoice.RESTART_AND_INSTALL
      failureChoices += RebasedMacFailureChoice.RETRY
      enableRestartOnFailure = true
    }

    harness.prepareToReady()
    harness.canRestart = false
    harness.runNextUi()

    assertEquals(1, harness.failureOffers)
    assertEquals(1, harness.prepareCalls)
    assertEquals(1, harness.backgroundTasks.size)
  }

  @Test
  fun `restart capability loss on EDT does not exit and retries prepared install`() {
    val harness = Harness().apply {
      readyChoices += RebasedMacReadyChoice.RESTART_AND_INSTALL
      failureChoices += RebasedMacFailureChoice.RETRY
      enableRestartOnFailure = true
    }

    harness.prepareToReady()
    harness.runNextUi()
    harness.runNextBackground()
    harness.canRestart = false
    harness.runNextUi()

    assertTrue(harness.restarts.isEmpty())
    assertEquals(1, harness.failureOffers)
    assertEquals(1, harness.prepareCalls)
    assertEquals(1, harness.backgroundTasks.size)
  }

  @Test
  fun `cancellation observed after command construction restores prepared state`() {
    val harness = Harness().apply {
      readyChoices += RebasedMacReadyChoice.RESTART_AND_INSTALL
      cancelIndicatorAfterCommand = true
    }

    harness.prepareToReady()
    harness.runNextUi()
    val failure = harness.runNextBackgroundCatching()

    assertTrue(failure is ProcessCanceledException)
    assertTrue(harness.uiTasks.isEmpty())
    assertTrue(harness.restarts.isEmpty())
    harness.workflow.prepareAndOfferRestart(null, platformUpdate())
    assertEquals(1, harness.uiTasks.size)
    assertEquals(1, harness.prepareCalls)
  }

  @Test
  fun `cancellation after command return but before EDT prevents restart and remains retryable`() {
    val harness = Harness().apply {
      readyChoices += RebasedMacReadyChoice.RESTART_AND_INSTALL
      readyChoices += RebasedMacReadyChoice.RESTART_AND_INSTALL
    }
    val indicator = EmptyProgressIndicator()

    harness.prepareToReady()
    harness.runNextUi()
    harness.runNextBackground(indicator)
    indicator.cancel()
    harness.runNextUi()

    assertTrue(harness.restarts.isEmpty())
    harness.workflow.prepareAndOfferRestart(null, platformUpdate())
    assertEquals(1, harness.uiTasks.size)
    harness.runNextUi()
    assertEquals(1, harness.backgroundTasks.size)
    assertEquals(1, harness.prepareCalls)
  }

  @Test
  fun `cancellation does not offer failure`() {
    val harness = Harness().apply {
      prepareFailures += ProcessCanceledException()
    }

    harness.workflow.prepareAndOfferRestart(null, platformUpdate())
    val failure = harness.runNextBackgroundCatching()

    assertTrue(failure is ProcessCanceledException)
    assertTrue(harness.uiTasks.isEmpty())
    assertEquals(0, harness.failureOffers)
  }

  @Test
  fun `cancellation after prepare returns resets phase without ready or failure`() {
    val harness = Harness().apply {
      cancelIndicatorAfterPrepare = true
    }

    harness.workflow.prepareAndOfferRestart(null, platformUpdate())
    val failure = harness.runNextBackgroundCatching()

    assertTrue(failure is ProcessCanceledException)
    assertTrue(harness.uiTasks.isEmpty())
    assertEquals(0, harness.readyOffers)
    assertEquals(0, harness.failureOffers)

    harness.cancelIndicatorAfterPrepare = false
    harness.workflow.prepareAndOfferRestart(null, platformUpdate())
    assertEquals(1, harness.backgroundTasks.size)
  }

  @Test
  fun `cancellation after prepare returns starts pending version without stale UI`() {
    val harness = Harness().apply {
      cancelIndicatorAfterPrepare = true
    }

    harness.workflow.prepareAndOfferRestart(null, platformUpdate())
    harness.workflow.prepareAndOfferRestart(null, platformUpdate(NEW_VERSION))
    val failure = harness.runNextBackgroundCatching()

    assertTrue(failure is ProcessCanceledException)
    assertTrue(harness.uiTasks.isEmpty())
    assertEquals(0, harness.readyOffers)
    assertEquals(0, harness.failureOffers)
    assertEquals(1, harness.backgroundTasks.size)

    harness.cancelIndicatorAfterPrepare = false
    harness.preparedResult = preparedDirect(NEW_VERSION)
    harness.runNextBackground()
    harness.runNextUi()

    assertEquals(listOf(VERSION, NEW_VERSION), harness.prepareVersions)
    assertEquals(1, harness.readyOffers)
  }

  private class Harness(
    prepared: PreparedRebasedMacUpdate = preparedDirect(),
  ) {
    val backgroundTasks = ArrayDeque<(ProgressIndicator) -> Unit>()
    val uiTasks = ArrayDeque<() -> Unit>()
    val readyChoices = ArrayDeque<RebasedMacReadyChoice>()
    val failureChoices = ArrayDeque<RebasedMacFailureChoice>()
    val prepareFailures = ArrayDeque<Throwable>()
    val commandFailures = ArrayDeque<Throwable>()
    val notifications = mutableListOf<() -> Unit>()
    val openedPages = mutableListOf<String>()
    val backgroundProjects = mutableListOf<Project?>()
    val readyProjects = mutableListOf<Project?>()
    val prepareVersions = mutableListOf<String>()
    val freshCalls = mutableListOf<FreshCall>()
    val commandCalls = mutableListOf<CommandCall>()
    val restarts = mutableListOf<List<String>>()
    val startupFailures = mutableListOf<StartupFailure>()
    var freshDigest: String? = DIGEST
    var preparedResult = prepared
    var targetParentWritable = true
    var canRestart = true
    var enableRestartOnFailure = false
    var cancelIndicatorAfterPrepare = false
    var cancelIndicatorAfterCommand = false
    var prepareCalls = 0
    var readyOffers = 0
    var failureOffers = 0
    var startupSuccesses = 0

    val workflow = RebasedMacUpdateWorkflow(
      RebasedMacUpdateWorkflowDependencies(
        runBackground = { project, task ->
          backgroundProjects += project
          backgroundTasks += task
        },
        invokeLater = { uiTasks += it },
        prepare = { build, _, indicator ->
          prepareCalls++
          prepareVersions += build.version
          if (prepareFailures.isNotEmpty()) throw prepareFailures.removeFirst()
          if (cancelIndicatorAfterPrepare) indicator.cancel()
          preparedResult
        },
        loadFreshDigest = { version, indicator ->
          freshCalls += FreshCall(version, indicator)
          freshDigest
        },
        buildCommand = { update, targetApp, trustedDigest, indicator, elevate ->
          commandCalls += CommandCall(update, targetApp, trustedDigest, indicator, elevate)
          if (commandFailures.isNotEmpty()) throw commandFailures.removeFirst()
          if (cancelIndicatorAfterCommand) indicator.cancel()
          listOf("install")
        },
        targetApp = { TARGET_APP },
        isWritable = { targetParentWritable },
        canRestart = { canRestart },
        showReady = { project, _ ->
          readyProjects += project
          readyOffers++
          readyChoices.removeFirstOrNull() ?: RebasedMacReadyChoice.LATER
        },
        showReadyNotification = { _, _, action -> notifications += action },
        showStartupSuccess = { _ -> startupSuccesses++ },
        showStartupFailure = { _, error, retry, openRelease ->
          startupFailures += StartupFailure(error, retry, openRelease)
        },
        showFailure = { _, _ ->
          failureOffers++
          if (enableRestartOnFailure) canRestart = true
          failureChoices.removeFirstOrNull() ?: RebasedMacFailureChoice.CANCEL
        },
        openReleasePage = { openedPages += it },
        restart = { restarts += it },
      )
    )

    fun prepareToReady() {
      workflow.prepareAndOfferRestart(null, platformUpdate())
      runNextBackground()
    }

    fun runNextBackground(indicator: ProgressIndicator = EmptyProgressIndicator()) {
      backgroundTasks.removeFirst().invoke(indicator)
    }

    fun runNextBackgroundCatching(indicator: ProgressIndicator = EmptyProgressIndicator()): Throwable? =
      runCatching { runNextBackground(indicator) }.exceptionOrNull()

    fun runNextUi() {
      uiTasks.removeFirst().invoke()
    }
  }

  private data class FreshCall(
    val version: String,
    val indicator: ProgressIndicator,
  )

  private data class CommandCall(
    val prepared: PreparedRebasedMacUpdate,
    val targetApp: Path,
    val trustedDigest: String?,
    val indicator: ProgressIndicator,
    val elevate: Boolean,
  )

  private data class StartupFailure(
    val error: Throwable,
    val retry: (() -> Unit)?,
    val openRelease: (() -> Unit)?,
  )

  companion object {
    private const val VERSION = "1.2.3"
    private const val NEW_VERSION = "1.2.4"
    private const val LATEST_VERSION = "1.2.5"
    private const val RELEASE_PAGE = "https://example.test/releases/1.2.3"
    private const val BLOG_PAGE = "https://example.test/blog/1.2.3"
    private val DIGEST = "a".repeat(64)
    private val TARGET_APP = Path.of("/Applications/Rebased.app")

    private fun canPrepare(
      update: PlatformUpdates.Loaded,
      manager: ExternalUpdateManager? = null,
      os: OS = OS.macOS,
      restartCapable: Boolean = true,
    ): Boolean = RebasedMacUpdateController.canPrepare(update, manager, os, restartCapable)

    private fun isExternalManagerBlocking(
      manager: ExternalUpdateManager?,
      os: OS = OS.macOS,
      restartCapable: Boolean = true,
    ): Boolean = RebasedMacUpdateController.isExternalManagerBlocking(manager, os, restartCapable)

    private fun platformUpdate(
      version: String = VERSION,
      buildNumber: String = version,
      downloadUrl: String? = "https://example.test/rebased-aarch64.dmg",
      digest: String? = DIGEST,
    ): PlatformUpdates.Loaded {
      val digestAttribute = digest?.let { """ digest="$it"""" }.orEmpty()
      val downloadButton = downloadUrl?.let {
        """<button name="Download" url="$it"$digestAttribute download="true"/>"""
      }.orEmpty()
      val product = parseUpdateData(
        """
          <products>
            <product name="Rebased">
              <code>IU</code>
              <channel id="release" status="release" url="$RELEASE_PAGE">
                <build number="$buildNumber" fullNumber="$buildNumber" version="$version">
                  <blogPost url="$BLOG_PAGE"/>
                  $downloadButton
                </build>
              </channel>
            </product>
          </products>
        """.trimIndent(),
        "IU",
      )!!
      val channel = product.channels.single()
      return PlatformUpdates.Loaded(channel.builds.single(), channel)
    }

    private fun preparedDirect(version: String = VERSION): PreparedRebasedMacUpdate =
      PreparedRebasedMacUpdate(
        version = version,
        strategy = RebasedMacUpdateStrategy.DIRECT,
        stagedApp = Path.of("/tmp/rebased-update/$version/Rebased.app"),
        verifiedDmg = Path.of("/tmp/rebased-update/$version/rebased.dmg"),
        verifiedDmgSha256 = DIGEST,
        brewExecutable = null,
        releasePageUrl = RELEASE_PAGE,
      )

    private fun preparedHomebrew(): PreparedRebasedMacUpdate =
      PreparedRebasedMacUpdate(
        version = VERSION,
        strategy = RebasedMacUpdateStrategy.HOMEBREW,
        stagedApp = null,
        verifiedDmg = null,
        verifiedDmgSha256 = null,
        brewExecutable = Path.of("/opt/homebrew/bin/brew"),
        releasePageUrl = RELEASE_PAGE,
      )
  }
}
