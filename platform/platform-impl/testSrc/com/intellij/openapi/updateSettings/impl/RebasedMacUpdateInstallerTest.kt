// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.EmptyProgressIndicatorBase
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.system.CpuArch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
import java.nio.file.attribute.PosixFilePermission.GROUP_READ
import java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OTHERS_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties

@TestApplication
internal class RebasedMacUpdateInstallerTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `direct command revalidates DMG and preserves separate arguments`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates ; \$(touch injected)"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications & local").resolve(APP_NAME)).toRealPath()
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified update.dmg"), "verified").toRealPath()
    val prepared = directUpdate(verifiedDmg)
    val indicator = ProgressIndicatorBase()
    val revalidations = mutableListOf<Triple<PreparedRebasedMacUpdate, String, ProgressIndicator>>()
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { update, digest, progress ->
        revalidations += Triple(update, digest, progress)
        verifiedDmg
      },
    )

    val command = installer.command(prepared, targetApp, SHA256.uppercase(), indicator, elevate = false)

    assertEquals(listOf(Triple(prepared, SHA256.uppercase(), indicator)), revalidations)
    assertSame(indicator, revalidations.single().third)
    assertEquals(SANITIZED_BASH_PREFIX, command.take(SANITIZED_BASH_PREFIX.size))
    assertTrue(command[7].startsWith("#!/bin/bash\nset -u\n"))
    assertEquals(updateRoot.resolve("rebased-update-installer.sh").toString(), command[8])
    assertEquals(
      listOf(
        "direct",
        targetApp.toString(),
        verifiedDmg.toString(),
        SHA256,
        VERSION,
        updateRoot.resolve("install-result.properties").toString(),
      ),
      command.drop(9),
    )
    assertFalse(Files.exists(updateRoot.resolve("mounts")))
    assertFalse(Files.exists(realTempDir.resolve("injected")))
    assertFalse(Files.exists(updateRoot.resolve("rebased-update-installer.sh")))
  }

  @Test
  fun `direct installer does not source inherited BASH_ENV`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), "0.9.0", "old")
    val mountedApp = createApp(realTempDir.resolve("mounted fixture").resolve(APP_NAME), VERSION, "new")
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified dmg").toRealPath()
    val digest = sha256(verifiedDmg)
    val probe = realTempDir.resolve("bash-env-probe")
    val maliciousBashEnv = Files.writeString(
      realTempDir.resolve("malicious-bash-env"),
      "/usr/bin/touch ${shellLiteral(probe.toString())}\n",
    )
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      tools = fakeTools(mountedApp),
    )

    val process = run(
      installer.command(
        directUpdate(verifiedDmg, digest),
        targetApp,
        digest,
        ProgressIndicatorBase(),
        elevate = false,
      ),
      mapOf("BASH_ENV" to maliciousBashEnv.toString(), "ENV" to maliciousBashEnv.toString()),
    )

    assertEquals(0, process.exitCode, process.output)
    assertFalse(Files.exists(probe))
  }

  @Test
  fun `direct installer does not import inherited bash functions`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), "0.9.0", "old")
    val mountedApp = createApp(realTempDir.resolve("mounted fixture").resolve(APP_NAME), VERSION, "new")
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified dmg").toRealPath()
    val digest = sha256(verifiedDmg)
    val probe = realTempDir.resolve("bash-function-probe")
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      tools = fakeTools(mountedApp),
    )

    val process = run(
      installer.command(
        directUpdate(verifiedDmg, digest),
        targetApp,
        digest,
        ProgressIndicatorBase(),
        elevate = false,
      ),
      mapOf(
        "BASH_FUNC_printf%%" to "() { /usr/bin/touch ${shellLiteral(probe.toString())}; command printf \"${'$'}@\"; }",
      ),
    )

    assertEquals(0, process.exitCode, process.output)
    assertFalse(Files.exists(probe))
  }

  @Test
  fun `elevated direct command publishes sudo stdout as a user owned marker`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates with spaces"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified update.dmg"), "verified").toRealPath()
    val resultFile = Files.writeString(updateRoot.resolve("install-result.properties"), "status=success\n")
    val sudoArguments = realTempDir.resolve("sudo arguments")
    val properties = """
      status=success
      message=Installed with escaped \n text
      backup=/Applications/Rebased.app.rebased-update-backup
      version=$VERSION
      strategy=direct
    """.trimIndent() + "\n"
    val fakeSudo = executable(
      realTempDir.resolve("fake sudo"),
      """
        #!/bin/bash
        printf '%s\n%s\n' "${'$'}1" "${'$'}2" >${shellLiteral(sudoArguments.toString())}
        printf '%s' ${shellLiteral(properties)}
      """.trimIndent() + "\n",
    )
    val wrappedCommands = mutableListOf<List<String>>()
    val prompts = mutableListOf<String>()
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      isTargetParentWritable = { false },
      sudoCommand = { commandLine, prompt ->
        val rawCommand = commandLine.getCommandLineList(null)
        wrappedCommands += rawCommand
        prompts += prompt
        GeneralCommandLine(fakeSudo.toString()).withParameters("first argument", "second;argument")
      },
    )

    val command = installer.command(
      directUpdate(verifiedDmg),
      targetApp,
      SHA256,
      ProgressIndicatorBase(),
      elevate = true,
    )

    val rawCommand = wrappedCommands.single()
    assertEquals(SANITIZED_BASH_PREFIX, rawCommand.take(SANITIZED_BASH_PREFIX.size))
    assertTrue(rawCommand[7].startsWith("#!/bin/bash\nset -u\n"))
    assertTrue(rawCommand[7].contains("install_direct"))
    assertEquals(updateRoot.resolve("rebased-update-installer.sh").toString(), rawCommand[8])
    assertEquals(
      listOf(
        "direct",
        targetApp.toString(),
        verifiedDmg.toString(),
        SHA256,
        VERSION,
        "-",
      ),
      rawCommand.drop(9),
    )
    assertEquals(SANITIZED_BASH_PREFIX, command.take(SANITIZED_BASH_PREFIX.size))
    assertFalse(command[7].contains("eval"))
    assertEquals(updateRoot.resolve("install-result.properties").toString(), command[9])
    assertEquals(
      listOf(fakeSudo.toString(), "first argument", "second;argument"),
      command.drop(10),
    )
    assertEquals(listOf("Rebased needs administrator privileges to install the update."), prompts)
    assertFalse(Files.exists(resultFile))
    val process = run(command)
    assertEquals(0, process.exitCode, process.output)
    assertEquals(properties, Files.readString(resultFile))
    assertEquals(Files.getOwner(updateRoot), Files.getOwner(resultFile))
    assertEquals(
      setOf(OWNER_READ, OWNER_WRITE, GROUP_READ, OTHERS_READ),
      Files.getPosixFilePermissions(resultFile),
    )
    assertEquals("first argument\nsecond;argument\n", Files.readString(sudoArguments))
    assertFalse(Files.exists(updateRoot.resolve("rebased-update-installer.sh")))
  }

  @Test
  fun `elevated direct command produces compilable macOS AppleScript wrapper`() {
    assumeTrue(SystemInfoRt.isMac)
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates with spaces"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified update.dmg"), "verified").toRealPath()
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      isTargetParentWritable = { false },
    )

    val command = installer.command(
      directUpdate(verifiedDmg),
      targetApp,
      SHA256,
      ProgressIndicatorBase(),
      elevate = true,
    )

    assertEquals(SANITIZED_BASH_PREFIX, command.take(SANITIZED_BASH_PREFIX.size))
    val osascriptCommand = command.drop(10)
    assertEquals(listOf("/usr/bin/osascript", "-e"), osascriptCommand.take(2))
    assertEquals(3, osascriptCommand.size)
    assertTrue(osascriptCommand[2].contains("/usr/bin/env"))
    assertTrue(osascriptCommand[2].contains("-i"))
    assertTrue(osascriptCommand[2].contains("LC_ALL=C"))
    assertFalse(osascriptCommand[2].contains("BASH_ENV"))
    assertTrue(osascriptCommand[2].contains("/bin/bash"))
    val compiledScript = realTempDir.resolve("installer-wrapper.scpt")
    val compilation = run(
      listOf("/usr/bin/osacompile", "-e", osascriptCommand[2], "-o", compiledScript.toString()),
    )
    assertEquals(0, compilation.exitCode, compilation.output)
    assertTrue(Files.isRegularFile(compiledScript))
  }

  @Test
  fun `elevated outer wrapper preserves properties through osascript stdout`() {
    assumeTrue(SystemInfoRt.isMac)
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified").toRealPath()
    val expectedProperties = """
      status=success
      message=escaped\nvalue
      backup=
      version=1.2.3
      strategy=direct
    """.trimIndent() + "\n"
    val appleScript = "do shell script \"/usr/bin/printf 'status=success\\\\n" +
                      "message=escaped\\\\\\\\nvalue\\\\nbackup=\\\\nversion=1.2.3\\\\nstrategy=direct\\\\n'\""
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      isTargetParentWritable = { false },
      sudoCommand = { _, _ -> GeneralCommandLine("/usr/bin/osascript").withParameters("-e", appleScript) },
    )

    val process = run(
      installer.command(
        directUpdate(verifiedDmg),
        targetApp,
        SHA256,
        ProgressIndicatorBase(),
        elevate = true,
      ),
    )

    assertEquals(0, process.exitCode, process.output)
    assertEquals(expectedProperties, Files.readString(updateRoot.resolve("install-result.properties")))
  }

  @Test
  fun `elevated direct command does not publish failed or empty sudo output`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified").toRealPath()
    val resultFile = updateRoot.resolve("install-result.properties")
    val failingSudo = executable(
      realTempDir.resolve("failing sudo"),
      "#!/bin/bash\nprintf '%s\\n' 'status=failed'\nexit 23\n",
    )
    val emptySudo = executable(realTempDir.resolve("empty sudo"), "#!/bin/bash\nexit 0\n")

    for ((sudo, expectedExitCode) in listOf(failingSudo to 23, emptySudo to 1)) {
      Files.writeString(resultFile, "stale=true\n")
      val installer = RebasedMacUpdateInstaller(
        updateRoot = updateRoot,
        revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
        isTargetParentWritable = { false },
        sudoCommand = { _, _ -> GeneralCommandLine(sudo.toString()) },
      )

      val process = run(
        installer.command(
          directUpdate(verifiedDmg),
          targetApp,
          SHA256,
          ProgressIndicatorBase(),
          elevate = true,
        ),
      )

      assertEquals(expectedExitCode, process.exitCode, process.output)
      assertFalse(Files.exists(resultFile))
    }
  }

  @Test
  fun `direct installer result dash prints complete properties without writing a marker`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), "0.9.0", "old")
    val mountedApp = createApp(realTempDir.resolve("mounted fixture").resolve(APP_NAME), VERSION, "new")
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified dmg").toRealPath()
    val digest = sha256(verifiedDmg)
    val command = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      tools = fakeTools(mountedApp),
    ).command(
      directUpdate(verifiedDmg, digest),
      targetApp,
      digest,
      ProgressIndicatorBase(),
      elevate = false,
    ).toMutableList().apply {
      this[lastIndex] = "-"
    }

    val process = run(command)
    val result = Properties().apply {
      ByteArrayInputStream(process.output.toByteArray()).use(::load)
    }

    assertEquals(0, process.exitCode, process.output)
    assertEquals("success", result.getProperty("status"))
    assertEquals("Update installed successfully", result.getProperty("message"))
    assertEquals(VERSION, result.getProperty("version"))
    assertEquals("direct", result.getProperty("strategy"))
    assertFalse(Files.exists(updateRoot.resolve("install-result.properties")))
  }

  @Test
  fun `command removes a result symlink without deleting its target`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified").toRealPath()
    val externalResult = Files.writeString(realTempDir.resolve("external result"), "status=success\n")
    val resultLink = updateRoot.resolve("install-result.properties")
    Files.createSymbolicLink(resultLink, externalResult)
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
    )

    installer.command(
      directUpdate(verifiedDmg),
      targetApp,
      SHA256,
      ProgressIndicatorBase(),
      elevate = false,
    )

    assertFalse(Files.exists(resultLink))
    assertEquals("status=success\n", Files.readString(externalResult))
  }

  @Test
  fun `production constructor delegates direct authorization to preparer`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val store = RebasedMacUpdateStore(updateRoot)
    val version = "1.2.3"
    val versionDirectory = Files.createDirectories(store.versionDirectory(version))
    val stagedApp = Files.createDirectories(versionDirectory.resolve(APP_NAME))
    val verifiedDmg = Files.writeString(versionDirectory.resolve("rebased.dmg"), "verified").toRealPath()
    val digest = sha256(verifiedDmg)
    val prepared = PreparedRebasedMacUpdate(
      version = version,
      strategy = RebasedMacUpdateStrategy.DIRECT,
      stagedApp = stagedApp,
      verifiedDmg = verifiedDmg,
      verifiedDmgSha256 = digest,
      brewExecutable = null,
      releasePageUrl = "https://github.com/DetachHead/RebaSed/releases/tag/v$version",
    )
    store.save(prepared)
    val preparer = RebasedMacUpdatePreparer(
      store = store,
      operations = DefaultRebasedMacUpdateOperations(),
      sourceDetector = { RebasedMacInstallationSource.Direct },
      arch = CpuArch.ARM64,
      mountRoot = realTempDir.resolve("preparer mounts"),
    )
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val installer = RebasedMacUpdateInstaller(updateRoot, preparer)

    val command = installer.command(prepared, targetApp, digest, ProgressIndicatorBase(), elevate = false)

    assertEquals(verifiedDmg.toString(), command[11])
    assertEquals(digest, command[12])
  }

  @Test
  fun `command rejects a symlink update root without creating a helper`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val symlinkRoot = realTempDir.resolve("linked updates")
    Files.createSymbolicLink(symlinkRoot, updateRoot)
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val brew = homebrewExecutable()

    assertThrows(IllegalArgumentException::class.java) {
      RebasedMacUpdateInstaller(
        updateRoot = symlinkRoot,
        revalidateVerifiedDmg = { _, _, _ -> error("Homebrew must not revalidate a DMG") },
      ).command(homebrewUpdate(brew), targetApp, null, ProgressIndicatorBase(), elevate = false)
    }
    assertFalse(Files.exists(updateRoot.resolve("rebased-update-installer.sh")))
  }

  @Test
  fun `direct command rejects an invalid trusted digest before revalidation`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified").toRealPath()
    var revalidations = 0
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ ->
        revalidations++
        verifiedDmg
      },
    )

    for (digest in listOf(null, "", "not-a-digest", SHA256.dropLast(1))) {
      assertThrows(RebasedMacUpdateException.Verification::class.java) {
        installer.command(directUpdate(verifiedDmg), targetApp, digest, ProgressIndicatorBase(), elevate = false)
      }
    }

    assertEquals(0, revalidations)
    assertFalse(Files.exists(updateRoot.resolve("rebased-update-installer.sh")))
  }

  @Test
  fun `revalidation failure leaves target and script untouched`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val marker = Files.writeString(targetApp.resolve("unchanged"), "old")
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified").toRealPath()
    val failure = RebasedMacUpdateException.Verification("fresh release verification failed")
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> throw failure },
    )

    val thrown = assertThrows(RebasedMacUpdateException.Verification::class.java) {
      installer.command(directUpdate(verifiedDmg), targetApp, SHA256, ProgressIndicatorBase(), elevate = false)
    }

    assertSame(failure, thrown)
    assertEquals("old", Files.readString(marker))
    assertFalse(Files.exists(updateRoot.resolve("rebased-update-installer.sh")))
  }

  @Test
  fun `symlink target is rejected before revalidation`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val applications = Files.createDirectories(realTempDir.resolve("Applications"))
    val realApp = Files.createDirectories(realTempDir.resolve("elsewhere").resolve(APP_NAME))
    val targetApp = applications.resolve(APP_NAME)
    Files.createSymbolicLink(targetApp, realApp)
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified").toRealPath()
    val resultFile = Files.writeString(updateRoot.resolve("install-result.properties"), "status=success\n")
    var revalidations = 0
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ ->
        revalidations++
        verifiedDmg
      },
    )

    assertThrows(IllegalArgumentException::class.java) {
      installer.command(directUpdate(verifiedDmg), targetApp, SHA256, ProgressIndicatorBase(), elevate = false)
    }

    assertEquals(0, revalidations)
    assertEquals("status=success\n", Files.readString(resultFile))
    assertFalse(Files.exists(updateRoot.resolve("rebased-update-installer.sh")))
  }

  @Test
  fun `cancellation prevents revalidation and command generation`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified").toRealPath()
    var revalidations = 0
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ ->
        revalidations++
        verifiedDmg
      },
    )
    val indicator = TestProgressIndicator().apply { cancel() }

    assertThrows(ProcessCanceledException::class.java) {
      installer.command(directUpdate(verifiedDmg), targetApp, SHA256, indicator, elevate = false)
    }

    assertEquals(0, revalidations)
    assertFalse(Files.exists(updateRoot.resolve("rebased-update-installer.sh")))
  }

  @Test
  fun `Homebrew command preserves separate arguments and accepts no digest`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val brew = Files.writeString(realTempDir.resolve("brew ; \$(touch brew-injected)"), "#!/bin/sh\n").toRealPath()
    check(brew.toFile().setExecutable(true, false))
    val prepared = homebrewUpdate(brew)
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> error("Homebrew must not revalidate a DMG") },
    )

    val command = installer.command(prepared, targetApp, null, ProgressIndicatorBase(), elevate = false)

    assertEquals(SANITIZED_BASH_PREFIX, command.take(SANITIZED_BASH_PREFIX.size))
    assertTrue(command[7].startsWith("#!/bin/bash\nset -u\n"))
    assertTrue(command[7].contains("install_homebrew"))
    assertEquals(updateRoot.resolve("rebased-update-installer.sh").toString(), command[8])
    assertEquals(
      listOf(
        "homebrew",
        targetApp.toString(),
        brew.toString(),
        VERSION,
        updateRoot.resolve("install-result.properties").toString(),
      ),
      command.drop(9),
    )
    assertFalse(Files.exists(realTempDir.resolve("brew-injected")))
    assertFalse(Files.exists(updateRoot.resolve("rebased-update-installer.sh")))
  }

  @Test
  fun `Homebrew command rejects elevation without invoking sudo wrapper`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val brew = homebrewExecutable()
    var sudoCommandBuilt = false
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> error("Homebrew must not revalidate a DMG") },
      sudoCommand = { commandLine, _ ->
        sudoCommandBuilt = true
        commandLine
      },
    )

    assertThrows(IllegalArgumentException::class.java) {
      installer.command(homebrewUpdate(brew), targetApp, null, ProgressIndicatorBase(), elevate = true)
    }

    assertFalse(sudoCommandBuilt)
  }

  @Test
  fun `Homebrew command rejects a superuser process before running any command`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val brewMarker = realTempDir.resolve("brew-ran")
    val brew = executable(
      realTempDir.resolve("brew"),
      "#!/bin/bash\n/usr/bin/touch ${shellLiteral(brewMarker.toString())}\n",
    )
    val resultFile = Files.writeString(updateRoot.resolve("install-result.properties"), "stale-result\n")
    var superUserChecks = 0
    var sudoCommandBuilt = false
    var command: List<String>? = null
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> error("Homebrew must not revalidate a DMG") },
      sudoCommand = { commandLine, _ ->
        sudoCommandBuilt = true
        commandLine
      },
      isSuperUser = {
        superUserChecks++
        true
      },
    )

    assertThrows(IllegalArgumentException::class.java) {
      command = installer.command(homebrewUpdate(brew), targetApp, null, ProgressIndicatorBase(), elevate = false)
    }

    assertEquals(1, superUserChecks)
    assertEquals(null, command)
    assertFalse(sudoCommandBuilt)
    assertFalse(Files.exists(brewMarker))
    assertEquals("stale-result\n", Files.readString(resultFile))
  }

  @Test
  fun `direct command is unaffected by a superuser process`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified").toRealPath()
    var superUserChecks = 0
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      isSuperUser = {
        superUserChecks++
        true
      },
    )

    val command = installer.command(
      directUpdate(verifiedDmg),
      targetApp,
      SHA256,
      ProgressIndicatorBase(),
      elevate = false,
    )

    assertEquals("direct", command[9])
    assertEquals(0, superUserChecks)
  }

  @Test
  fun `Homebrew command rejects digests and revalidates its stored executable`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val brew = Files.writeString(realTempDir.resolve("brew"), "#!/bin/sh\n").toRealPath()
    check(brew.toFile().setExecutable(true, false))
    val prepared = homebrewUpdate(brew)
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> error("Homebrew must not revalidate a DMG") },
    )

    assertThrows(RebasedMacUpdateException.Verification::class.java) {
      installer.command(prepared, targetApp, SHA256, ProgressIndicatorBase(), elevate = false)
    }
    Files.delete(brew)
    assertThrows(RebasedMacUpdateException.HomebrewUnavailable::class.java) {
      installer.command(prepared, targetApp, null, ProgressIndicatorBase(), elevate = false)
    }

    assertFalse(Files.exists(updateRoot.resolve("rebased-update-installer.sh")))
  }

  @Test
  fun `non elevated Homebrew command runs with a nonwritable target parent`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val applications = Files.createDirectories(realTempDir.resolve("Applications"))
    val version = "1.2.3-beta"
    val targetApp = createApp(applications.resolve(APP_NAME), version, "unchanged")
    val brew = executable(
      realTempDir.resolve("brew"),
      """
        #!/bin/bash
        if [[ "${'$'}#" -eq 3 && "${'$'}1" == "upgrade" && "${'$'}2" == "--cask" && "${'$'}3" == "rebased" ]]; then
          exit 0
        fi
        if [[ "${'$'}#" -eq 4 && "${'$'}1" == "list" && "${'$'}2" == "--cask" && "${'$'}3" == "--versions" && "${'$'}4" == "rebased" ]]; then
          printf '%s\n' ${shellLiteral("rebased $version")}
          exit 0
        fi
        exit 2
      """.trimIndent() + "\n",
    )
    var sudoCommandBuilt = false
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> error("Homebrew must not revalidate a DMG") },
      isTargetParentWritable = { path ->
        assertEquals(targetApp.parent, path)
        false
      },
      sudoCommand = { _, _ ->
        sudoCommandBuilt = true
        error("A non-elevated command must not build a sudo wrapper")
      },
    )
    val originalPermissions = Files.getPosixFilePermissions(applications)

    try {
      Files.setPosixFilePermissions(
        applications,
        setOf(OWNER_READ, OWNER_EXECUTE, GROUP_READ, GROUP_EXECUTE, OTHERS_READ, OTHERS_EXECUTE),
      )
      val command = installer.command(homebrewUpdate(brew, version), targetApp, null, ProgressIndicatorBase(), elevate = false)
      val process = run(command)
      val result = loadProperties(updateRoot.resolve("install-result.properties"))

      assertEquals(0, process.exitCode, process.output)
      assertEquals("success", result.getProperty("status"))
      assertEquals("unchanged", appMarker(targetApp))
    }
    finally {
      Files.setPosixFilePermissions(applications, originalPermissions)
    }

    assertFalse(sudoCommandBuilt)
    assertFalse(Files.exists(updateRoot.resolve("rebased-update-installer.sh")))
  }

  @Test
  fun `non elevated direct command rejects a nonwritable target parent before revalidation`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified").toRealPath()
    var revalidations = 0
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ ->
        revalidations++
        verifiedDmg
      },
      isTargetParentWritable = { false },
    )

    assertThrows(IllegalArgumentException::class.java) {
      installer.command(directUpdate(verifiedDmg), targetApp, SHA256, ProgressIndicatorBase(), elevate = false)
    }

    assertEquals(0, revalidations)
  }

  @Test
  fun `authorization cancellation propagates without returning a command`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified").toRealPath()
    val resultFile = Files.writeString(updateRoot.resolve("install-result.properties"), "status=success\n")
    val cancellation = ProcessCanceledException()
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      isTargetParentWritable = { false },
      sudoCommand = { _, _ -> throw cancellation },
    )
    var command: List<String>? = null

    val thrown = assertThrows(ProcessCanceledException::class.java) {
      command = installer.command(
        directUpdate(verifiedDmg),
        targetApp,
        SHA256,
        ProgressIndicatorBase(),
        elevate = true,
      )
    }

    assertSame(cancellation, thrown)
    assertEquals(null, command)
    assertFalse(Files.exists(resultFile))
  }

  @Test
  fun `authorization wrapper creation failure propagates without returning a command`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = Files.createDirectories(realTempDir.resolve("Applications").resolve(APP_NAME)).toRealPath()
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified").toRealPath()
    val resultFile = Files.writeString(updateRoot.resolve("install-result.properties"), "status=success\n")
    val failure = IllegalStateException("authorization wrapper unavailable")
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      isTargetParentWritable = { false },
      sudoCommand = { _, _ -> throw failure },
    )
    var command: List<String>? = null

    val thrown = assertThrows(IllegalStateException::class.java) {
      command = installer.command(
        directUpdate(verifiedDmg),
        targetApp,
        SHA256,
        ProgressIndicatorBase(),
        elevate = true,
      )
    }

    assertSame(failure, thrown)
    assertEquals(null, command)
    assertFalse(Files.exists(resultFile))
  }

  @Test
  fun `script rehashes mounts and validates direct candidates without eval`() {
    val script = inlineInstallerScript()

    assertTrue(script.startsWith("#!/bin/bash\nset -u\n"))
    assertFalse(script.contains("eval"))
    assertTrue(script.contains("SHASUM='/usr/bin/shasum'"))
    assertTrue(script.contains("CP='/bin/cp'"))
	    assertTrue(script.contains("CODESIGN='/usr/bin/codesign'"))
    assertTrue(script.contains("MKTEMP\" -d \"/private/var/tmp/rebased-update.XXXXXX\""))
    assertTrue(script.contains("\"\$CHMOD\" 700 \"\$private_dir\""))
    assertTrue(script.contains("\"\$CP\" \"\$dmg\" \"\$protected_dmg\""))
    assertTrue(script.contains("\"\$SHASUM\" -a 256 \"\$protected_dmg\""))
    assertTrue(script.contains("HDIUTIL='/usr/bin/hdiutil'"))
    assertTrue(script.contains("attach -readonly -nobrowse -noautoopen -noautofsck"))
    assertTrue(script.contains("-plist"))
    assertTrue(script.contains("-mountpoint \"\$mount_point\" \"\$protected_dmg\""))
    assertTrue(script.contains("attach_attempted=1"))
    assertTrue(script.contains("system-entities.0.dev-entry"))
    assertTrue(script.contains("trap cleanup EXIT"))
    assertTrue(script.contains("\"\$HDIUTIL\" detach \"\$detach_target\""))
    assertTrue(script.contains("\"\$HDIUTIL\" detach -force \"\$detach_target\""))
    assertFalse(script.contains("\"\$RMDIR\" \"\$mount_point\""))
    assertTrue(script.contains("\"\$RM\" -rf -- \"\$private_dir\""))
    assertTrue(script.contains("CFBundleIdentifier"))
    assertTrue(script.contains("CFBundleShortVersionString"))
    assertTrue(script.contains("CFBundleExecutable"))
    assertTrue(script.contains("io.github.detachhead.rebased"))
    assertTrue(script.contains("[[ -d \"\$app/Contents\" && ! -L \"\$app/Contents\" ]]"))
    assertTrue(script.contains("[[ -d \"\$app/Contents/MacOS\" && ! -L \"\$app/Contents/MacOS\" ]]"))
    assertTrue(script.contains("\"\$LIPO\" -archs"))
	    assertTrue(script.contains("\"\$CODESIGN\" -dv \"\$app\""))
    assertTrue(script.contains("validate_direct_app \"\$source_app\" \"\$expected_version\""))
    assertTrue(script.contains("validate_direct_app \"\$candidate\" \"\$expected_version\""))
    assertTrue(script.contains("validate_direct_app \"\$target\" \"\$expected_version\""))
    assertTrue(script.contains("\"\$XATTR\" -dr com.apple.quarantine \"\$candidate\""))
    assertFalse(script.contains("com.apple.quarantine \"\$source_app\""))
    assertFalse(script.contains("com.apple.quarantine \"\$target\""))
  }

  @Test
  fun `Homebrew script rejects root before invoking tools or writing results`() {
    val script = inlineInstallerScript()
    val homebrewScript = script.substringAfter("install_homebrew() {\n").substringBefore("\n  }\n\n  script_parent=")
    val rootGuard = """[[ "${'$'}EUID" -eq 0 ]] && exit 2"""

    assertTrue(homebrewScript.trimStart().startsWith(rootGuard))
    assertTrue(homebrewScript.indexOf(rootGuard) < homebrewScript.indexOf("result_file="))
    assertTrue(homebrewScript.indexOf(rootGuard) < homebrewScript.indexOf("\"${'$'}brew\""))
  }

  @Test
  fun `script writes atomic results and restores failed replacements`() {
    val script = inlineInstallerScript()
    val homebrewScript = script.substringAfter("install_homebrew()").substringBefore("\n  script_parent=")

    assertTrue(script.contains("candidate=\"\${target}.rebased-update-candidate\""))
    assertTrue(script.contains("backup=\"\${target}.rebased-update-backup\""))
    assertTrue(script.contains("restore_backup"))
    assertTrue(script.contains("\"\$MV\" \"\$target\" \"\$backup\""))
    assertTrue(script.contains("\"\$MV\" \"\$candidate\" \"\$target\""))
    assertTrue(script.contains("result_tmp=\$(\"\$MKTEMP\" \"\${result_file}.tmp.XXXXXX\")"))
    assertTrue(script.contains("\"\$CHMOD\" 644 \"\$result_tmp\""))
    assertFalse(script.contains("\"\$CHMOD\" 600 \"\$result_tmp\""))
    assertFalse(script.contains("\"\$CHMOD\" 666 \"\$result_tmp\""))
    assertTrue(script.contains("\"\$MV\" -f \"\$result_tmp\" \"\$result_file\""))
    assertTrue(script.contains("status=%s\\nmessage=%s\\nbackup=%s\\nversion=%s\\nstrategy=%s\\n"))
    assertFalse(script.contains("rebased-homebrew-safety"))
    assertFalse(homebrewScript.contains("safety_backup"))
    assertFalse(homebrewScript.contains("restore_backup"))
    assertFalse(homebrewScript.contains("\"\$DITTO\""))
    assertTrue(homebrewScript.contains("\"${'$'}brew\" upgrade --cask rebased"))
    assertTrue(script.contains("\"${'$'}brew\" list --cask --versions rebased"))
    assertTrue(homebrewScript.contains("validate_homebrew_app_version"))
    assertTrue(homebrewScript.contains("validate_homebrew_receipt"))
    assertFalse(script.contains("Caskroom"))
    assertTrue(script.contains("write_failure"))
    assertTrue(script.contains("exit 0"))
  }

  @Test
  fun `direct script replaces target and retains rollback backup`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates ; safe"))
    val targetApp = createApp(realTempDir.resolve("Applications & local").resolve(APP_NAME), "0.9.0", "old")
    val mountedApp = createApp(realTempDir.resolve("mounted fixture").resolve(APP_NAME), VERSION, "new")
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified update.dmg"), "verified dmg").toRealPath()
    val digest = sha256(verifiedDmg)
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      tools = fakeTools(mountedApp),
    )

    val command = installer.command(
      directUpdate(verifiedDmg, digest),
      targetApp,
      digest,
      ProgressIndicatorBase(),
      elevate = false,
    )
    val process = run(command)
    val backup = targetApp.resolveSibling("${targetApp.fileName}.rebased-update-backup")
    val result = loadProperties(updateRoot.resolve("install-result.properties"))

    assertEquals(0, process.exitCode, process.output)
    assertEquals("new", appMarker(targetApp))
    assertEquals("old", appMarker(backup))
    assertEquals("success", result.getProperty("status"))
    assertEquals("Update installed successfully", result.getProperty("message"))
    assertEquals(backup.toString(), result.getProperty("backup"))
    assertEquals(VERSION, result.getProperty("version"))
    assertEquals("direct", result.getProperty("strategy"))
    assertTrue(command.none { it == "/Applications" || it.startsWith("/Applications/") })
  }

  @Test
  fun `direct script mounts the protected DMG copy after the original changes`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates ; \$(touch unsafe)"))
    val targetApp = createApp(realTempDir.resolve("Applications & local").resolve(APP_NAME), "0.9.0", "old")
    val trustedApp = createApp(realTempDir.resolve("trusted mounted fixture").resolve(APP_NAME), VERSION, "trusted")
    val attackerApp = createApp(realTempDir.resolve("attacker mounted fixture").resolve(APP_NAME), VERSION, "attacker")
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified update ; \$(touch dmg).dmg"), "trusted bytes").toRealPath()
    val digest = sha256(verifiedDmg)
    val mountedDmgLog = realTempDir.resolve("mounted dmg path")
    val shasum = executable(
      realTempDir.resolve("hash then replace"),
      """
        #!/bin/bash
        set -u
        output=$(/usr/bin/shasum "${'$'}@") || exit
        printf '%s' 'attacker bytes' >${shellLiteral(verifiedDmg.toString())}
        printf '%s\n' "${'$'}output"
      """.trimIndent() + "\n",
    )
    val hdiutil = executable(
      realTempDir.resolve("recording hdiutil"),
      """
        #!/bin/bash
        set -u
        if [[ "${'$'}1" == "attach" ]]; then
          previous=''
          mount_point=''
          for argument in "${'$'}@"; do
            if [[ "${'$'}previous" == "-mountpoint" ]]; then
              mount_point="${'$'}argument"
            fi
            previous="${'$'}argument"
          done
          dmg="${'$'}{!#}"
          printf '%s' "${'$'}dmg" >${shellLiteral(mountedDmgLog.toString())}
          if [[ "$(/bin/cat "${'$'}dmg")" == "trusted bytes" ]]; then
            /bin/cp -R ${shellLiteral(trustedApp.toString())} "${'$'}mount_point/Rebased.app"
          else
            /bin/cp -R ${shellLiteral(attackerApp.toString())} "${'$'}mount_point/Rebased.app"
          fi
          exit 0
        fi
        [[ "${'$'}1" == "detach" ]] && exit 0
        exit 2
      """.trimIndent() + "\n",
    )
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      tools = fakeTools(trustedApp).copy(shasum = shasum, hdiutil = hdiutil),
    )

    val process = run(
      installer.command(
        directUpdate(verifiedDmg, digest),
        targetApp,
        digest,
        ProgressIndicatorBase(),
        elevate = false,
      ),
    )
    val mountedDmg = Path.of(Files.readString(mountedDmgLog))

    assertEquals(0, process.exitCode, process.output)
    assertEquals("trusted", appMarker(targetApp))
    assertEquals("attacker bytes", Files.readString(verifiedDmg))
    assertTrue(mountedDmg.toString().startsWith("/private/var/tmp/rebased-update."))
    assertEquals("rebased-update.dmg", mountedDmg.fileName.toString())
    assertFalse(Files.exists(mountedDmg.parent))
    assertFalse(Files.exists(realTempDir.resolve("unsafe")))
    assertFalse(Files.exists(realTempDir.resolve("dmg")))
  }

  @Test
  fun `direct script keeps mount and source below the protected private directory`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val oldMountRoot = updateRoot.resolve("mounts")
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), "0.9.0", "old")
    val trustedApp = createApp(realTempDir.resolve("trusted mounted fixture").resolve(APP_NAME), VERSION, "trusted")
    val attackerApp = createApp(realTempDir.resolve("attacker mounted fixture").resolve(APP_NAME), VERSION, "attacker")
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified dmg").toRealPath()
    val digest = sha256(verifiedDmg)
    val mountPointLog = realTempDir.resolve("mount point")
    val sourceAppLog = realTempDir.resolve("source app")
    val attackLog = realTempDir.resolve("ancestor replaced")
    val hdiutil = executable(
      realTempDir.resolve("ancestor replacing hdiutil"),
      """
        #!/bin/bash
        set -u
        if [[ "${'$'}1" == "attach" ]]; then
          previous=''
          mount_point=''
          for argument in "${'$'}@"; do
            if [[ "${'$'}previous" == "-mountpoint" ]]; then
              mount_point="${'$'}argument"
            fi
            previous="${'$'}argument"
          done
          printf '%s' "${'$'}mount_point" >${shellLiteral(mountPointLog.toString())}
          if [[ "${'$'}mount_point" == ${shellLiteral("$oldMountRoot/")}* ]]; then
            /bin/mv ${shellLiteral(oldMountRoot.toString())} ${shellLiteral("${oldMountRoot}.replaced")}
            /bin/mkdir -p "${'$'}mount_point"
            /bin/cp -R ${shellLiteral(attackerApp.toString())} "${'$'}mount_point/Rebased.app"
            /usr/bin/touch ${shellLiteral(attackLog.toString())}
          else
            /bin/cp -R ${shellLiteral(trustedApp.toString())} "${'$'}mount_point/Rebased.app"
          fi
          exit 0
        fi
        [[ "${'$'}1" == "detach" ]] && exit 0
        exit 2
      """.trimIndent() + "\n",
    )
    val ditto = executable(
      realTempDir.resolve("recording ditto"),
      """
        #!/bin/bash
        set -u
        printf '%s' "${'$'}1" >${shellLiteral(sourceAppLog.toString())}
        exec /bin/cp -R "${'$'}1" "${'$'}2"
      """.trimIndent() + "\n",
    )
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      tools = fakeTools(trustedApp).copy(hdiutil = hdiutil, ditto = ditto),
    )

    val process = run(
      installer.command(
        directUpdate(verifiedDmg, digest),
        targetApp,
        digest,
        ProgressIndicatorBase(),
        elevate = false,
      ),
    )
    val mountPoint = Path.of(Files.readString(mountPointLog))
    val sourceApp = Path.of(Files.readString(sourceAppLog))
    val privateDir = mountPoint.parent

    assertEquals(0, process.exitCode, process.output)
    assertEquals("trusted", appMarker(targetApp))
    assertFalse(Files.exists(attackLog))
    assertTrue(privateDir.toString().startsWith("/private/var/tmp/rebased-update."))
    assertEquals("Rebased.app", sourceApp.fileName.toString())
    assertEquals(mountPoint, sourceApp.parent)
    assertFalse(Files.exists(oldMountRoot))
    assertFalse(Files.exists(privateDir))
  }

  @Test
  fun `protected copy digest mismatch leaves target untouched and skips mounting`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), "0.9.0", "old")
    val mountedApp = createApp(realTempDir.resolve("mounted fixture").resolve(APP_NAME), VERSION, "new")
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "actual contents").toRealPath()
    val privateDirLog = realTempDir.resolve("private dir")
    val hdiutilCalled = realTempDir.resolve("hdiutil called")
    val mktemp = executable(
      realTempDir.resolve("recording mktemp"),
      """
        #!/bin/bash
        set -u
        if [[ "${'$'}1" == "-d" && "${'$'}2" == /private/var/tmp/rebased-update.* ]]; then
          result=$(/usr/bin/mktemp "${'$'}@") || exit
          printf '%s' "${'$'}result" >${shellLiteral(privateDirLog.toString())}
          printf '%s\n' "${'$'}result"
          exit 0
        fi
        exec /usr/bin/mktemp "${'$'}@"
      """.trimIndent() + "\n",
    )
    val hdiutil = executable(
      realTempDir.resolve("unexpected hdiutil"),
      """
        #!/bin/bash
        /usr/bin/touch ${shellLiteral(hdiutilCalled.toString())}
        exit 9
      """.trimIndent() + "\n",
    )
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      tools = fakeTools(mountedApp).copy(mktemp = mktemp, hdiutil = hdiutil),
    )

    val process = run(
      installer.command(
        directUpdate(verifiedDmg),
        targetApp,
        SHA256,
        ProgressIndicatorBase(),
        elevate = false,
      ),
    )
    val result = loadProperties(updateRoot.resolve("install-result.properties"))
    val privateDir = Path.of(Files.readString(privateDirLog))

    assertEquals(0, process.exitCode, process.output)
    assertEquals("old", appMarker(targetApp))
    assertFalse(Files.exists(targetApp.resolveSibling("${targetApp.fileName}.rebased-update-backup")))
    assertFalse(Files.exists(hdiutilCalled))
    assertFalse(Files.exists(privateDir))
    assertEquals("failed", result.getProperty("status"))
    assertEquals("The verified DMG digest no longer matches", result.getProperty("message"))
    assertEquals("", result.getProperty("backup"))
    assertEquals("direct", result.getProperty("strategy"))
  }

  @Test
  fun `detach failure downgrades success and preserves protected update state`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val resultFile = updateRoot.resolve("install-result.properties")
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), "0.9.0", "old")
    val mountedApp = createApp(realTempDir.resolve("mounted fixture").resolve(APP_NAME), VERSION, "new")
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified dmg").toRealPath()
    val digest = sha256(verifiedDmg)
    val privateDirLog = realTempDir.resolve("private dir")
    val detachLog = realTempDir.resolve("detach attempts")
    val earlyMarkerLog = realTempDir.resolve("marker existed during cleanup")
    val mktemp = recordingPrivateMktemp(realTempDir.resolve("recording mktemp"), privateDirLog)
    val hdiutil = executable(
      realTempDir.resolve("undetachable hdiutil"),
      """
        #!/bin/bash
        set -u
        if [[ "${'$'}1" == "attach" ]]; then
          previous=''
          mount_point=''
          for argument in "${'$'}@"; do
            if [[ "${'$'}previous" == "-mountpoint" ]]; then
              mount_point="${'$'}argument"
            fi
            previous="${'$'}argument"
          done
          /bin/cp -R ${shellLiteral(mountedApp.toString())} "${'$'}mount_point/Rebased.app"
          exit 0
        fi
        if [[ "${'$'}1" == "detach" ]]; then
          printf '%s\n' "${'$'}*" >>${shellLiteral(detachLog.toString())}
          [[ -e ${shellLiteral(resultFile.toString())} ]] &&
            /usr/bin/touch ${shellLiteral(earlyMarkerLog.toString())}
          exit 9
        fi
        exit 2
      """.trimIndent() + "\n",
    )
    val busyRmdir = executable(realTempDir.resolve("busy rmdir"), "#!/bin/bash\nexit 1\n")
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      tools = fakeTools(mountedApp).copy(hdiutil = hdiutil, mktemp = mktemp, rmdir = busyRmdir),
    )

    val process = run(
      installer.command(
        directUpdate(verifiedDmg, digest),
        targetApp,
        digest,
        ProgressIndicatorBase(),
        elevate = false,
      ),
    )
    val privateDir = Path.of(Files.readString(privateDirLog))
    try {
      val backup = targetApp.resolveSibling("${targetApp.fileName}.rebased-update-backup")
      val result = loadProperties(resultFile)

      assertEquals(0, process.exitCode, process.output)
      assertEquals("new", appMarker(targetApp))
      assertEquals("old", appMarker(backup))
      assertEquals("failed", result.getProperty("status"))
      assertEquals(
        "The update was installed, but the verified DMG could not be detached",
        result.getProperty("message"),
      )
      assertEquals(backup.toString(), result.getProperty("backup"))
      val detachAttempts = Files.readAllLines(detachLog)
      assertEquals(2, detachAttempts.size)
      assertTrue(detachAttempts[0].startsWith("detach $privateDir/mount."))
      assertTrue(detachAttempts[1].startsWith("detach -force $privateDir/mount."))
      assertFalse(Files.exists(earlyMarkerLog))
      assertTrue(Files.isDirectory(privateDir))
      assertTrue(Files.isRegularFile(privateDir.resolve("rebased-update.dmg")))
    }
    finally {
      privateDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `partial attach detaches reported device before removing protected state`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), "0.9.0", "old")
    val mountedApp = createApp(realTempDir.resolve("mounted fixture").resolve(APP_NAME), VERSION, "new")
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified dmg").toRealPath()
    val digest = sha256(verifiedDmg)
    val privateDirLog = realTempDir.resolve("private dir")
    val detachLog = realTempDir.resolve("detach attempts")
    val mktemp = recordingPrivateMktemp(realTempDir.resolve("recording mktemp"), privateDirLog)
    val hdiutil = executable(
      realTempDir.resolve("partially attaching hdiutil"),
      """
        #!/bin/bash
        set -u
        if [[ "${'$'}1" == "attach" ]]; then
          [[ " ${'$'}* " == *" -plist "* ]] || exit 2
          printf '%s\n' \
            '<?xml version="1.0" encoding="UTF-8"?>' \
            '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">' \
            '<plist version="1.0"><dict><key>system-entities</key><array><dict>' \
            '<key>dev-entry</key><string>/dev/disk-test</string>' \
            '</dict></array></dict></plist>'
          exit 9
        fi
        if [[ "${'$'}1" == "detach" ]]; then
          printf '%s\n' "${'$'}*" >>${shellLiteral(detachLog.toString())}
          [[ "${'$'}#" -eq 3 && "${'$'}2" == "-force" && "${'$'}3" == "/dev/disk-test" ]] && exit 0
          exit 9
        fi
        exit 2
      """.trimIndent() + "\n",
    )
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      tools = fakeTools(mountedApp).copy(hdiutil = hdiutil, mktemp = mktemp),
    )

    val process = run(
      installer.command(
        directUpdate(verifiedDmg, digest),
        targetApp,
        digest,
        ProgressIndicatorBase(),
        elevate = false,
      ),
    )
    val privateDir = Path.of(Files.readString(privateDirLog))
    val result = loadProperties(updateRoot.resolve("install-result.properties"))
    val detachAttempts = Files.readAllLines(detachLog)

    assertEquals(0, process.exitCode, process.output)
    assertEquals("old", appMarker(targetApp))
    assertEquals("failed", result.getProperty("status"))
    assertEquals("The verified DMG could not be mounted", result.getProperty("message"))
    assertEquals(listOf("detach /dev/disk-test", "detach -force /dev/disk-test"), detachAttempts)
    assertFalse(Files.exists(privateDir))
  }

  @Test
  fun `failed attach without device preserves protected state when detach fails`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), "0.9.0", "old")
    val mountedApp = createApp(realTempDir.resolve("mounted fixture").resolve(APP_NAME), VERSION, "new")
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified dmg").toRealPath()
    val digest = sha256(verifiedDmg)
    val privateDirLog = realTempDir.resolve("private dir")
    val detachLog = realTempDir.resolve("detach attempts")
    val mktemp = recordingPrivateMktemp(realTempDir.resolve("recording mktemp"), privateDirLog)
    val hdiutil = executable(
      realTempDir.resolve("partially attaching hdiutil"),
      """
        #!/bin/bash
        set -u
        if [[ "${'$'}1" == "attach" ]]; then
          exit 9
        fi
        if [[ "${'$'}1" == "detach" ]]; then
          printf '%s\n' "${'$'}*" >>${shellLiteral(detachLog.toString())}
          exit 9
        fi
        exit 2
      """.trimIndent() + "\n",
    )
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      tools = fakeTools(mountedApp).copy(hdiutil = hdiutil, mktemp = mktemp),
    )

    val process = run(
      installer.command(
        directUpdate(verifiedDmg, digest),
        targetApp,
        digest,
        ProgressIndicatorBase(),
        elevate = false,
      ),
    )
    val privateDir = Path.of(Files.readString(privateDirLog))
    try {
      val result = loadProperties(updateRoot.resolve("install-result.properties"))
      assertTrue(Files.exists(detachLog))
      val detachAttempts = Files.readAllLines(detachLog)

      assertEquals(0, process.exitCode, process.output)
      assertEquals("old", appMarker(targetApp))
      assertEquals("failed", result.getProperty("status"))
      assertEquals(
        "The verified DMG could not be mounted; detach/cleanup failed",
        result.getProperty("message"),
      )
      assertEquals(2, detachAttempts.size)
      assertTrue(detachAttempts[0].startsWith("detach "))
      assertFalse(detachAttempts[0].contains("-force"))
      assertTrue(detachAttempts[1].startsWith("detach -force "))
      assertTrue(Files.isDirectory(privateDir))
      assertTrue(Files.isRegularFile(privateDir.resolve("rebased-update.dmg")))
    }
    finally {
      privateDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `direct script restores backup when candidate replacement fails`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), "0.9.0", "old")
    val mountedApp = createApp(realTempDir.resolve("mounted fixture").resolve(APP_NAME), VERSION, "new")
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified dmg").toRealPath()
    val digest = sha256(verifiedDmg)
    val candidate = targetApp.resolveSibling("${targetApp.fileName}.rebased-update-candidate")
    val failingMove = executable(
      realTempDir.resolve("failing-mv"),
      """
        #!/bin/bash
        if [[ "${'$'}1" == ${shellLiteral(candidate.toString())} && "${'$'}2" == ${shellLiteral(targetApp.toString())} ]]; then
          exit 9
        fi
        exec /bin/mv "${'$'}@"
      """.trimIndent() + "\n",
    )
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      tools = fakeTools(mountedApp, move = failingMove),
    )

    val process = run(
      installer.command(
        directUpdate(verifiedDmg, digest),
        targetApp,
        digest,
        ProgressIndicatorBase(),
        elevate = false,
      ),
    )
    val backup = targetApp.resolveSibling("${targetApp.fileName}.rebased-update-backup")
    val result = loadProperties(updateRoot.resolve("install-result.properties"))

    assertEquals(0, process.exitCode, process.output)
    assertEquals("old", appMarker(targetApp))
    assertFalse(Files.exists(backup))
    assertFalse(Files.exists(candidate))
    assertEquals("failed", result.getProperty("status"))
    assertEquals("The update candidate could not replace the application", result.getProperty("message"))
    assertEquals("", result.getProperty("backup"))
  }

  @Test
  fun `direct script clears restored backup from installed validation failure marker`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), "0.9.0", "old")
    val mountedApp = createApp(realTempDir.resolve("mounted fixture").resolve(APP_NAME), VERSION, "new")
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified dmg").toRealPath()
    val digest = sha256(verifiedDmg)
    val targetExecutable = targetApp.resolve("Contents/MacOS/rebased")
    val failingInstalledLipo = executable(
      realTempDir.resolve("failing installed lipo"),
      """
        #!/bin/bash
        set -u
        if [[ "${'$'}#" -eq 2 && "${'$'}1" == "-archs" &&
              "${'$'}2" == ${shellLiteral(targetExecutable.toString())} &&
              "$(/bin/cat "${'$'}2")" == "new" ]]; then
          exit 9
        fi
        printf '%s\n' 'arm64 x86_64'
      """.trimIndent() + "\n",
    )
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      tools = fakeTools(mountedApp).copy(lipo = failingInstalledLipo),
    )

    val process = run(
      installer.command(
        directUpdate(verifiedDmg, digest),
        targetApp,
        digest,
        ProgressIndicatorBase(),
        elevate = false,
      ),
    )
    val backup = targetApp.resolveSibling("${targetApp.fileName}.rebased-update-backup")
    val result = loadProperties(updateRoot.resolve("install-result.properties"))

    assertEquals(0, process.exitCode, process.output)
    assertEquals("old", appMarker(targetApp))
    assertFalse(Files.exists(backup))
    assertEquals("failed", result.getProperty("status"))
    assertEquals("The installed application failed validation", result.getProperty("message"))
    assertEquals("", result.getProperty("backup"))
  }

  @Test
  fun `direct script records valid backup when candidate restore fails`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), "0.9.0", "old")
    val mountedApp = createApp(realTempDir.resolve("mounted fixture").resolve(APP_NAME), VERSION, "new")
    val verifiedDmg = Files.writeString(updateRoot.resolve("verified.dmg"), "verified dmg").toRealPath()
    val digest = sha256(verifiedDmg)
    val candidate = targetApp.resolveSibling("${targetApp.fileName}.rebased-update-candidate")
    val backup = targetApp.resolveSibling("${targetApp.fileName}.rebased-update-backup")
    val failingMove = executable(
      realTempDir.resolve("failing replacement and restore mv"),
      """
        #!/bin/bash
        if [[ "${'$'}2" == ${shellLiteral(targetApp.toString())} &&
              ( "${'$'}1" == ${shellLiteral(candidate.toString())} ||
                "${'$'}1" == ${shellLiteral(backup.toString())} ) ]]; then
          exit 9
        fi
        exec /bin/mv "${'$'}@"
      """.trimIndent() + "\n",
    )
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> verifiedDmg },
      tools = fakeTools(mountedApp, move = failingMove),
    )

    val process = run(
      installer.command(
        directUpdate(verifiedDmg, digest),
        targetApp,
        digest,
        ProgressIndicatorBase(),
        elevate = false,
      ),
    )
    val result = loadProperties(updateRoot.resolve("install-result.properties"))

    assertEquals(0, process.exitCode, process.output)
    assertFalse(Files.exists(targetApp))
    assertEquals("old", appMarker(backup))
    assertFalse(Files.exists(candidate))
    assertEquals("failed", result.getProperty("status"))
    assertEquals("The update candidate could not replace the application", result.getProperty("message"))
    assertEquals(backup.toString(), result.getProperty("backup"))
  }

  @Test
  fun `Homebrew script records success for the exact app and receipt version`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val version = HOMEBREW_VERSION
    val targetApp = createApp(realTempDir.resolve("Applications with spaces").resolve(APP_NAME), "0.9.0", "old")
    val upgradedApp = createApp(realTempDir.resolve("brew fixture").resolve(APP_NAME), version, "new")
    val brew = executable(
      realTempDir.resolve("fake brew"),
      """
        #!/bin/bash
        if [[ "${'$'}#" -eq 3 && "${'$'}1" == "upgrade" && "${'$'}2" == "--cask" && "${'$'}3" == "rebased" ]]; then
          /bin/rm -rf ${shellLiteral(targetApp.toString())}
          /bin/cp -R ${shellLiteral(upgradedApp.toString())} ${shellLiteral(targetApp.toString())}
          exit 0
        fi
        if [[ "${'$'}#" -eq 4 && "${'$'}1" == "list" && "${'$'}2" == "--cask" && "${'$'}3" == "--versions" && "${'$'}4" == "rebased" ]]; then
          printf '%s\n' ${shellLiteral("rebased $version")}
          exit 0
        fi
        exit 2
      """.trimIndent() + "\n",
    )
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> error("Homebrew must not revalidate a DMG") },
      tools = fakeTools(upgradedApp),
    )

    val command = installer.command(
      homebrewUpdate(brew, version),
      targetApp,
      null,
      ProgressIndicatorBase(),
      elevate = false,
    )
    val process = run(command)
    val result = loadProperties(updateRoot.resolve("install-result.properties"))

    assertEquals(0, process.exitCode, process.output)
    assertEquals("new", appMarker(targetApp))
    assertFalse(Files.exists(targetApp.resolveSibling("${targetApp.fileName}.rebased-homebrew-safety")))
    assertEquals("success", result.getProperty("status"))
    assertEquals("", result.getProperty("backup"))
    assertEquals(version, result.getProperty("version"))
    assertEquals("homebrew", result.getProperty("strategy"))
    assertEquals(
      setOf(OWNER_READ, OWNER_WRITE, GROUP_READ, OTHERS_READ),
      Files.getPosixFilePermissions(updateRoot.resolve("install-result.properties")),
    )
    assertTrue(command.none { it == "/Applications" || it.startsWith("/Applications/") })
  }

  @Test
  fun `Homebrew script fails without restoring when receipt version is stale`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val version = HOMEBREW_VERSION
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), "0.9.0", "old")
    val upgradedApp = createApp(realTempDir.resolve("brew fixture").resolve(APP_NAME), version, "new from brew")
    val brew = executable(
      realTempDir.resolve("fake brew"),
      """
        #!/bin/bash
        if [[ "${'$'}#" -eq 3 && "${'$'}1" == "upgrade" && "${'$'}2" == "--cask" && "${'$'}3" == "rebased" ]]; then
          /bin/rm -rf ${shellLiteral(targetApp.toString())}
          /bin/cp -R ${shellLiteral(upgradedApp.toString())} ${shellLiteral(targetApp.toString())}
          exit 0
        fi
        if [[ "${'$'}#" -eq 4 && "${'$'}1" == "list" && "${'$'}2" == "--cask" && "${'$'}3" == "--versions" && "${'$'}4" == "rebased" ]]; then
          printf '%s\n' 'rebased 0.9.0'
          exit 0
        fi
        exit 2
      """.trimIndent() + "\n",
    )
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> error("Homebrew must not revalidate a DMG") },
      tools = fakeTools(upgradedApp),
    )

    val process = run(
      installer.command(
        homebrewUpdate(brew, version),
        targetApp,
        null,
        ProgressIndicatorBase(),
        elevate = false,
      ),
    )
    val result = loadProperties(updateRoot.resolve("install-result.properties"))

    assertEquals(0, process.exitCode, process.output)
    assertEquals("new from brew", appMarker(targetApp))
    assertEquals("failed", result.getProperty("status"))
    assertEquals("", result.getProperty("backup"))
    assertFalse(Files.exists(targetApp.resolveSibling("${targetApp.fileName}.rebased-homebrew-safety")))
  }

  @Test
  fun `Homebrew script leaves the old app untouched when upgrade and receipt remain old`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), "0.9.0", "old")
    val brew = executable(
      realTempDir.resolve("failing brew"),
      """
        #!/bin/bash
        if [[ "${'$'}#" -eq 3 && "${'$'}1" == "upgrade" && "${'$'}2" == "--cask" && "${'$'}3" == "rebased" ]]; then
          exit 7
        fi
        if [[ "${'$'}#" -eq 4 && "${'$'}1" == "list" && "${'$'}2" == "--cask" && "${'$'}3" == "--versions" && "${'$'}4" == "rebased" ]]; then
          printf '%s\n' 'rebased 0.9.0'
          exit 0
        fi
        exit 2
      """.trimIndent() + "\n",
    )
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> error("Homebrew must not revalidate a DMG") },
      tools = fakeTools(targetApp),
    )

    val process = run(
      installer.command(
        homebrewUpdate(brew, HOMEBREW_VERSION),
        targetApp,
        null,
        ProgressIndicatorBase(),
        elevate = false,
      ),
    )
    val result = loadProperties(updateRoot.resolve("install-result.properties"))

    assertEquals(0, process.exitCode, process.output)
    assertEquals("old", appMarker(targetApp))
    assertEquals("failed", result.getProperty("status"))
    assertEquals("", result.getProperty("backup"))
    assertFalse(Files.exists(targetApp.resolveSibling("${targetApp.fileName}.rebased-homebrew-safety")))
  }

  @Test
  fun `Homebrew script accepts nonzero upgrade when app and receipt reached the expected version`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), "0.9.0", "old")
    val upgradedApp = createApp(realTempDir.resolve("brew fixture").resolve(APP_NAME), HOMEBREW_VERSION, "new")
    val brew = executable(
      realTempDir.resolve("late failing brew"),
      """
        #!/bin/bash
        if [[ "${'$'}#" -eq 3 && "${'$'}1" == "upgrade" && "${'$'}2" == "--cask" && "${'$'}3" == "rebased" ]]; then
          /bin/rm -rf ${shellLiteral(targetApp.toString())}
          /bin/cp -R ${shellLiteral(upgradedApp.toString())} ${shellLiteral(targetApp.toString())}
          exit 7
        fi
        if [[ "${'$'}#" -eq 4 && "${'$'}1" == "list" && "${'$'}2" == "--cask" && "${'$'}3" == "--versions" && "${'$'}4" == "rebased" ]]; then
          printf '%s\n' ${shellLiteral("rebased $HOMEBREW_VERSION")}
          exit 0
        fi
        exit 2
      """.trimIndent() + "\n",
    )
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> error("Homebrew must not revalidate a DMG") },
      tools = fakeTools(upgradedApp),
    )

    val process = run(
      installer.command(
        homebrewUpdate(brew, HOMEBREW_VERSION),
        targetApp,
        null,
        ProgressIndicatorBase(),
        elevate = false,
      ),
    )
    val result = loadProperties(updateRoot.resolve("install-result.properties"))

    assertEquals(0, process.exitCode, process.output)
    assertEquals("new", appMarker(targetApp))
    assertEquals("success", result.getProperty("status"))
    assertEquals("", result.getProperty("backup"))
  }

  @Test
  fun `Homebrew script rejects receipt output with extra fields`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), HOMEBREW_VERSION, "unchanged")
    val brew = executable(
      realTempDir.resolve("fake brew"),
      """
        #!/bin/bash
        if [[ "${'$'}#" -eq 3 && "${'$'}1" == "upgrade" && "${'$'}2" == "--cask" && "${'$'}3" == "rebased" ]]; then
          exit 0
        fi
        if [[ "${'$'}#" -eq 4 && "${'$'}1" == "list" && "${'$'}2" == "--cask" && "${'$'}3" == "--versions" && "${'$'}4" == "rebased" ]]; then
          printf '%s\n' ${shellLiteral("rebased $HOMEBREW_VERSION unexpected")}
          exit 0
        fi
        exit 2
      """.trimIndent() + "\n",
    )
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> error("Homebrew must not revalidate a DMG") },
      tools = fakeTools(targetApp),
    )

    val process = run(
      installer.command(
        homebrewUpdate(brew, HOMEBREW_VERSION),
        targetApp,
        null,
        ProgressIndicatorBase(),
        elevate = false,
      ),
    )
    val result = loadProperties(updateRoot.resolve("install-result.properties"))

    assertEquals(0, process.exitCode, process.output)
    assertEquals("unchanged", appMarker(targetApp))
    assertEquals("failed", result.getProperty("status"))
  }

  @Test
  fun `Homebrew script rejects an expected receipt when the app remains old`() {
    val realTempDir = tempDir.toRealPath()
    val updateRoot = Files.createDirectories(realTempDir.resolve("updates"))
    val targetApp = createApp(realTempDir.resolve("Applications").resolve(APP_NAME), "0.9.0", "old")
    val brew = executable(
      realTempDir.resolve("fake brew"),
      """
        #!/bin/bash
        if [[ "${'$'}#" -eq 3 && "${'$'}1" == "upgrade" && "${'$'}2" == "--cask" && "${'$'}3" == "rebased" ]]; then
          exit 0
        fi
        if [[ "${'$'}#" -eq 4 && "${'$'}1" == "list" && "${'$'}2" == "--cask" && "${'$'}3" == "--versions" && "${'$'}4" == "rebased" ]]; then
          printf '%s\n' ${shellLiteral("rebased $HOMEBREW_VERSION")}
          exit 0
        fi
        exit 2
      """.trimIndent() + "\n",
    )
    val installer = RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> error("Homebrew must not revalidate a DMG") },
      tools = fakeTools(targetApp),
    )

    val process = run(
      installer.command(
        homebrewUpdate(brew, HOMEBREW_VERSION),
        targetApp,
        null,
        ProgressIndicatorBase(),
        elevate = false,
      ),
    )
    val result = loadProperties(updateRoot.resolve("install-result.properties"))

    assertEquals(0, process.exitCode, process.output)
    assertEquals("old", appMarker(targetApp))
    assertEquals("failed", result.getProperty("status"))
  }

  private fun targetApp(): Path =
    Files.createDirectories(tempDir.toRealPath().resolve("Applications").resolve(APP_NAME)).toRealPath()

  private fun homebrewExecutable(): Path {
    val brew = Files.writeString(tempDir.toRealPath().resolve("brew"), "#!/bin/sh\n").toRealPath()
    check(brew.toFile().setExecutable(true, false))
    return brew
  }

  private fun inlineInstallerScript(tools: RebasedMacInstallerTools = RebasedMacInstallerTools()): String {
    val updateRoot = Files.createDirectories(tempDir.toRealPath().resolve("script updates"))
    return RebasedMacUpdateInstaller(
      updateRoot = updateRoot,
      revalidateVerifiedDmg = { _, _, _ -> error("Homebrew must not revalidate a DMG") },
      tools = tools,
    ).command(
      homebrewUpdate(homebrewExecutable()),
      targetApp(),
      null,
      ProgressIndicatorBase(),
      elevate = false,
    )[7]
  }

  private fun directUpdate(verifiedDmg: Path, digest: String = SHA256): PreparedRebasedMacUpdate =
    PreparedRebasedMacUpdate(
      version = VERSION,
      strategy = RebasedMacUpdateStrategy.DIRECT,
      stagedApp = verifiedDmg.resolveSibling(APP_NAME),
      verifiedDmg = verifiedDmg,
      verifiedDmgSha256 = digest,
      brewExecutable = null,
      releasePageUrl = "https://github.com/DetachHead/RebaSed/releases/tag/v$VERSION",
    )

  private fun homebrewUpdate(brew: Path, version: String = VERSION): PreparedRebasedMacUpdate =
    PreparedRebasedMacUpdate(
      version = version,
      strategy = RebasedMacUpdateStrategy.HOMEBREW,
      stagedApp = null,
      verifiedDmg = null,
      verifiedDmgSha256 = null,
      brewExecutable = brew,
      releasePageUrl = "https://github.com/DetachHead/RebaSed/releases/tag/v$VERSION",
    )

  private fun createApp(path: Path, version: String, marker: String): Path {
    val executable = path.resolve("Contents/MacOS/rebased")
    Files.createDirectories(executable.parent)
    Files.writeString(
      path.resolve("Contents/Info.plist"),
      """
        <?xml version="1.0" encoding="UTF-8"?>
        <plist version="1.0">
        <dict>
          <key>CFBundleIdentifier</key><string>io.github.detachhead.rebased</string>
          <key>CFBundleShortVersionString</key><string>$version</string>
          <key>CFBundleExecutable</key><string>rebased</string>
        </dict>
        </plist>
      """.trimIndent(),
    )
    Files.writeString(executable, marker)
    check(executable.toFile().setExecutable(true, false))
    return path.toRealPath()
  }

  private fun fakeTools(mountedApp: Path, move: Path = Path.of("/bin/mv")): RebasedMacInstallerTools {
    val toolsRoot = Files.createDirectories(tempDir.toRealPath().resolve("fake tools"))
    val shasum = executable(
      toolsRoot.resolve("shasum"),
      "#!/bin/bash\nexec /usr/bin/shasum \"\$@\"\n",
    )
    val hdiutil = executable(
      toolsRoot.resolve("hdiutil"),
      """
        #!/bin/bash
        set -u
        if [[ "${'$'}1" == "attach" ]]; then
          previous=''
          mount_point=''
          for argument in "${'$'}@"; do
            if [[ "${'$'}previous" == "-mountpoint" ]]; then
              mount_point="${'$'}argument"
              break
            fi
            previous="${'$'}argument"
          done
          [[ -n "${'$'}mount_point" ]] || exit 2
          /bin/cp -R ${shellLiteral(mountedApp.toString())} "${'$'}mount_point/Rebased.app"
          exit 0
        fi
        [[ "${'$'}1" == "detach" ]] && exit 0
        exit 2
      """.trimIndent() + "\n",
    )
    val ditto = executable(
      toolsRoot.resolve("ditto"),
      "#!/bin/bash\nset -u\n/bin/cp -R \"\$1\" \"\$2\"\n",
    )
    val lipo = executable(
      toolsRoot.resolve("lipo"),
      "#!/bin/bash\nprintf '%s\\n' 'arm64 x86_64'\n",
    )
	    val codesign = executable(
	      toolsRoot.resolve("codesign"),
	      "#!/bin/bash\nexit 0\n",
	    )
    val xattr = executable(toolsRoot.resolve("xattr"), "#!/bin/bash\nexit 0\n")
    val osascript = executable(toolsRoot.resolve("osascript"), "#!/bin/bash\nexit 0\n")
    return RebasedMacInstallerTools(
      shasum = shasum,
      hdiutil = hdiutil,
      ditto = ditto,
      lipo = lipo,
	      codesign = codesign,
      xattr = xattr,
      move = move,
      osascript = osascript,
    )
  }

  private fun recordingPrivateMktemp(path: Path, privateDirLog: Path): Path =
    executable(
      path,
      """
        #!/bin/bash
        set -u
        result=$(/usr/bin/mktemp "${'$'}@") || exit
        template_suffix="${'$'}{2#/private/var/tmp/rebased-update.}"
        if [[ "${'$'}1" == "-d" && "${'$'}2" == /private/var/tmp/rebased-update.* &&
              "${'$'}template_suffix" != */* ]]; then
          printf '%s' "${'$'}result" >${shellLiteral(privateDirLog.toString())}
        fi
        printf '%s\n' "${'$'}result"
      """.trimIndent() + "\n",
    )

  private fun executable(path: Path, content: String): Path {
    Files.writeString(path, content)
    check(path.toFile().setExecutable(true, false))
    return path.toRealPath()
  }

  private fun run(command: List<String>, environment: Map<String, String> = emptyMap()): ProcessResult {
    val process = ProcessBuilder(command)
      .redirectErrorStream(true)
      .apply { environment().putAll(environment) }
      .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return ProcessResult(process.waitFor(), output)
  }

  private fun loadProperties(path: Path): Properties =
    Properties().apply {
      ByteArrayInputStream(Files.readAllBytes(path)).use(::load)
    }

  private fun appMarker(app: Path): String = Files.readString(app.resolve("Contents/MacOS/rebased"))

  private fun sha256(path: Path): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))

  private fun shellLiteral(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

  private data class ProcessResult(val exitCode: Int, val output: String)

  private class TestProgressIndicator : EmptyProgressIndicatorBase(ModalityState.nonModal()) {
    private var canceled = false

    override fun cancel() {
      canceled = true
    }

    override fun isCanceled(): Boolean = canceled
  }
}

private const val APP_NAME = "Rebased.app"
private const val VERSION = "1.2.3-beta ; \$(touch version-injected)"
private const val HOMEBREW_VERSION = "1.2.3-beta"
private const val SHA256 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
private val SANITIZED_BASH_PREFIX = listOf(
  "/usr/bin/env",
  "-i",
  "HOME=${System.getProperty("user.home", "")}",
  "PATH=/usr/bin:/bin:/usr/sbin:/sbin",
  "LC_ALL=C",
  "/bin/bash",
  "-c",
)
