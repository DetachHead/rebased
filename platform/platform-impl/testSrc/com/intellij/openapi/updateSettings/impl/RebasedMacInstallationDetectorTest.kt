// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import com.intellij.execution.ExecutionException
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class RebasedMacInstallationDetectorTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `default detector uses supplied runner`() {
    val brew = executable(tempDir.resolve("path/bin/brew"))
    val runner = RecordingRunner(
      mapOf(brew to RebasedCommandResult(0, "rebased 1.2.3\n", "")),
    )

    val source = RebasedMacInstallationDetector.default(
      runner = runner,
      brewCandidates = { listOf(brew) },
    ).detect()

    assertEquals(RebasedMacInstallationSource.Homebrew(brew), source)
    assertEquals(listOf(brewCommand(brew)), runner.commands)
  }

  @Test
  fun `detects Apple Silicon Homebrew installation`() {
    val brew = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val runner = RecordingRunner(
      mapOf(brew to RebasedCommandResult(0, "\n  rebased 1.2.3  \n", "")),
    )

    val source = detector(runner, listOf(brew)).detect()

    assertEquals(RebasedMacInstallationSource.Homebrew(brew), source)
    assertEquals(listOf(brewCommand(brew)), runner.commands)
  }

  @Test
  fun `detects Intel Homebrew installation`() {
    val brew = executable(tempDir.resolve("usr/local/bin/brew"))
    val runner = RecordingRunner(
      mapOf(brew to RebasedCommandResult(0, "rebased 2.0.0\n", "")),
    )

    val source = detector(runner, listOf(brew)).detect()

    assertEquals(RebasedMacInstallationSource.Homebrew(brew), source)
    assertEquals(listOf(brewCommand(brew)), runner.commands)
  }

  @Test
  fun `detects direct installation when brew exists without the Cask`() {
    val brew = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val runner = RecordingRunner(
      mapOf(brew to RebasedCommandResult(0, "another-cask 1.0.0\n", "")),
    )

    val source = detector(runner, listOf(brew)).detect()

    assertEquals(RebasedMacInstallationSource.Direct, source)
    assertEquals(listOf(brewCommand(brew)), runner.commands)
  }

  @Test
  fun `propagates cancellation from the command runner`() {
    val brew = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val cancellation = ProcessCanceledException()
    val runner = RebasedCommandRunner { throw cancellation }

    val thrown = assertThrows(ProcessCanceledException::class.java) {
      detector(runner, listOf(brew)).detect()
    }

    assertSame(cancellation, thrown)
  }

  @Test
  fun `detects unavailable Homebrew when receipt exists without a usable executable`() {
    val caskroom = tempDir.resolve("opt/homebrew/Caskroom")
    createReceipt(caskroom)
    val runner = RecordingRunner(emptyMap())

    val source = detector(
      runner = runner,
      brewCandidates = listOf(tempDir.resolve("missing/brew")),
      caskroomRoots = listOf(caskroom),
    ).detect()

    assertEquals(RebasedMacInstallationSource.HomebrewUnavailable, source)
    assertTrue(runner.commands.isEmpty())
  }

  @Test
  fun `detects direct installation when receipt path is a regular file`() {
    val caskroom = tempDir.resolve("opt/homebrew/Caskroom")
    Files.createDirectories(caskroom)
    Files.createFile(caskroom.resolve("rebased"))

    val source = detector(
      runner = RecordingRunner(emptyMap()),
      brewCandidates = emptyList(),
      caskroomRoots = listOf(caskroom),
    ).detect()

    assertEquals(RebasedMacInstallationSource.Direct, source)
  }

  @Test
  fun `detects unavailable Homebrew when brew query fails and receipt exists`() {
    val brew = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val caskroom = tempDir.resolve("opt/homebrew/Caskroom")
    createReceipt(caskroom)
    val runner = RecordingRunner(
      mapOf(brew to RebasedCommandResult(1, "", "brew failed")),
    )

    val source = detector(runner, listOf(brew), listOf(caskroom)).detect()

    assertEquals(RebasedMacInstallationSource.HomebrewUnavailable, source)
    assertEquals(listOf(brewCommand(brew)), runner.commands)
  }

  @Test
  fun `checks later Homebrew candidates for the installed Cask`() {
    val firstBrew = executable(tempDir.resolve("custom/bin/brew"))
    val secondBrew = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val runner = RecordingRunner(
      mapOf(
        firstBrew to RebasedCommandResult(0, "another-cask 1.0.0\n", ""),
        secondBrew to RebasedCommandResult(0, "rebased 3.0.0\n", ""),
      ),
    )

    val source = detector(runner, listOf(firstBrew, secondBrew)).detect()

    assertEquals(RebasedMacInstallationSource.Homebrew(secondBrew), source)
    assertEquals(listOf(brewCommand(firstBrew), brewCommand(secondBrew)), runner.commands)
  }

  @Test
  fun `checks later Homebrew candidates after a failed query`() {
    val firstBrew = executable(tempDir.resolve("custom/bin/brew"))
    val secondBrew = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val runner = RecordingRunner(
      mapOf(
        firstBrew to RebasedCommandResult(1, "", "brew failed"),
        secondBrew to RebasedCommandResult(0, "rebased 3.0.0\n", ""),
      ),
    )

    val source = detector(runner, listOf(firstBrew, secondBrew)).detect()

    assertEquals(RebasedMacInstallationSource.Homebrew(secondBrew), source)
    assertEquals(listOf(brewCommand(firstBrew), brewCommand(secondBrew)), runner.commands)
  }

  @Test
  fun `checks later Homebrew candidates when an earlier process cannot start`() {
    val firstBrew = executable(tempDir.resolve("custom/bin/brew"))
    val secondBrew = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val commands = mutableListOf<List<String>>()
    val runner = RebasedCommandRunner { command ->
      commands.add(command.toList())
      when (Path.of(command.first())) {
        firstBrew -> throw ExecutionException("Cannot start Homebrew")
        secondBrew -> RebasedCommandResult(0, "rebased 1.1.13\n", "")
        else -> error("Unexpected command: $command")
      }
    }

    val source = detector(runner, listOf(firstBrew, secondBrew)).detect()

    assertEquals(RebasedMacInstallationSource.Homebrew(secondBrew), source)
    assertEquals(listOf(brewCommand(firstBrew), brewCommand(secondBrew)), commands)
  }

  @Test
  fun `detects Homebrew only when verbose listing contains the running app`() {
    val brew = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val currentApp = tempDir.resolve("Applications/Rebased.app").toAbsolutePath().normalize()
    val runner = ExactRunner(
      mapOf(
        brewCommand(brew) to RebasedCommandResult(0, "rebased 1.2.3\n", ""),
        verboseBrewCommand(brew) to RebasedCommandResult(0, "$currentApp\n", ""),
      )
    )

    val source = detector(runner, listOf(brew), currentApp = currentApp).detect()

    assertEquals(RebasedMacInstallationSource.Homebrew(brew), source)
    assertEquals(listOf(brewCommand(brew), verboseBrewCommand(brew)), runner.commands)
  }

  @Test
  fun `detects Homebrew when verbose listing contains a symlink to the running app`() {
    val brew = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val currentApp = Files.createDirectories(tempDir.resolve("Applications/Rebased.app")).toRealPath()
    val caskApp = Files.createSymbolicLink(
      Files.createDirectories(tempDir.resolve("opt/homebrew/Caskroom/rebased/1.2.3")).resolve("Rebased.app"),
      currentApp,
    ).toAbsolutePath().normalize()
    val runner = ExactRunner(
      mapOf(
        brewCommand(brew) to RebasedCommandResult(0, "rebased 1.2.3\n", ""),
        verboseBrewCommand(brew) to RebasedCommandResult(0, "$caskApp\n", ""),
      )
    )

    val source = detector(runner, listOf(brew), currentApp = currentApp).detect()

    assertEquals(RebasedMacInstallationSource.Homebrew(brew), source)
    assertEquals(listOf(brewCommand(brew), verboseBrewCommand(brew)), runner.commands)
  }

  @Test
  fun `detects direct when Homebrew cask does not contain the running app`() {
    val brew = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val currentApp = tempDir.resolve("Applications/Rebased.app").toAbsolutePath().normalize()
    val runner = ExactRunner(
      mapOf(
        brewCommand(brew) to RebasedCommandResult(0, "rebased 1.2.3\n", ""),
        verboseBrewCommand(brew) to RebasedCommandResult(
          0,
          "${tempDir.resolve("Other/Rebased.app").toAbsolutePath().normalize()}\n",
          "",
        ),
      )
    )

    val source = detector(runner, listOf(brew), currentApp = currentApp).detect()

    assertEquals(RebasedMacInstallationSource.Direct, source)
    assertEquals(listOf(brewCommand(brew), verboseBrewCommand(brew)), runner.commands)
  }

  @Test
  fun `reports unavailable Homebrew when installed cask cannot list managed files`() {
    val brew = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val currentApp = tempDir.resolve("Applications/Rebased.app").toAbsolutePath().normalize()
    val runner = ExactRunner(
      mapOf(
        brewCommand(brew) to RebasedCommandResult(0, "rebased 1.2.3\n", ""),
        verboseBrewCommand(brew) to RebasedCommandResult(1, "", "not linked"),
      )
    )

    val source = detector(runner, listOf(brew), currentApp = currentApp).detect()

    assertEquals(RebasedMacInstallationSource.HomebrewUnavailable, source)
    assertEquals(listOf(brewCommand(brew), verboseBrewCommand(brew)), runner.commands)
  }

  @Test
  fun `does not accept a different Cask name while parsing whitespace`() {
    val brew = executable(tempDir.resolve("opt/homebrew/bin/brew"))
    val runner = RecordingRunner(
      mapOf(
        brew to RebasedCommandResult(
          0,
          """
              rebased-beta 1.0.0
            another-rebased 2.0.0
            rebased
          """.trimIndent(),
          "",
        ),
      ),
    )

    val source = detector(runner, listOf(brew)).detect()

    assertEquals(RebasedMacInstallationSource.Direct, source)
    assertEquals(listOf(brewCommand(brew)), runner.commands)
  }

  private fun detector(
    runner: RebasedCommandRunner,
    brewCandidates: List<Path>,
    caskroomRoots: List<Path> = listOf(tempDir.resolve("Caskroom")),
    currentApp: Path? = null,
  ): RebasedMacInstallationDetector =
    RebasedMacInstallationDetector(runner, brewCandidates, caskroomRoots, currentApp)

  private fun executable(path: Path): Path {
    Files.createDirectories(path.parent)
    Files.createFile(path)
    assertTrue(path.toFile().setExecutable(true), "Failed to make $path executable")
    return path
  }

  private fun createReceipt(caskroom: Path) {
    Files.createDirectories(caskroom.resolve("rebased").resolve("1.0.0"))
  }

  private fun brewCommand(brew: Path): List<String> =
    listOf(brew.toString(), "list", "--cask", "--versions", "rebased")

  private fun verboseBrewCommand(brew: Path): List<String> =
    listOf(brew.toString(), "list", "--cask", "--verbose", "rebased")

  private class RecordingRunner(
    private val results: Map<Path, RebasedCommandResult>,
  ) : RebasedCommandRunner {
    val commands = mutableListOf<List<String>>()

    override fun run(command: List<String>): RebasedCommandResult {
      commands.add(command.toList())
      return results[Path.of(command.first())] ?: error("Unexpected command: $command")
    }
  }

  private class ExactRunner(
    private val results: Map<List<String>, RebasedCommandResult>,
  ) : RebasedCommandRunner {
    val commands = mutableListOf<List<String>>()

    override fun run(command: List<String>): RebasedCommandResult {
      val key = command.toList()
      commands += key
      return results[key] ?: error("Unexpected command: $command")
    }
  }
}
