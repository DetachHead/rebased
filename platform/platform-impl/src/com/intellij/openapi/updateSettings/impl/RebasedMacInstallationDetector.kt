// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path

internal class RebasedMacInstallationDetector(
  private val runner: RebasedCommandRunner,
  private val brewCandidates: List<Path>,
  private val caskroomRoots: List<Path>,
  private val currentApp: Path? = null,
) {
  fun detect(): RebasedMacInstallationSource {
    var unverifiableHomebrew = false
    for (brew in brewCandidates) {
      if (!Files.isRegularFile(brew) || !Files.isExecutable(brew)) continue

      val result = try {
        runner.run(listOf(brew.toString(), "list", "--cask", "--versions", CASK_NAME))
      }
      catch (_: ExecutionException) {
        continue
      }
      if (result.exitCode == 0 && result.stdout.lineSequence().any { it.trim().startsWith("$CASK_NAME ") }) {
        val runningApp = currentApp?.toAbsolutePath()?.normalize()
          ?: return RebasedMacInstallationSource.Homebrew(brew)
        val listedFiles = try {
          runner.run(listOf(brew.toString(), "list", "--cask", "--verbose", CASK_NAME))
        }
        catch (_: ExecutionException) {
          unverifiableHomebrew = true
          continue
        }
        if (listedFiles.exitCode != 0) {
          unverifiableHomebrew = true
          continue
        }
        if (listedFiles.stdout.lineSequence().any { isSamePath(it, runningApp) }) {
          return RebasedMacInstallationSource.Homebrew(brew)
        }
      }
    }

    return if (unverifiableHomebrew || caskroomRoots.any { Files.isDirectory(it.resolve(CASK_NAME)) }) {
      RebasedMacInstallationSource.HomebrewUnavailable
    }
    else {
      RebasedMacInstallationSource.Direct
    }
  }

  companion object {
    fun default(runner: RebasedCommandRunner): RebasedMacInstallationDetector =
      RebasedMacInstallationDetector(
        runner = runner,
        brewCandidates = collectDefaultBrewCandidates(),
        caskroomRoots = defaultCaskroomRoots(),
        currentApp = PathManager.getHomeDir().parent,
      )

    fun default(
      runner: RebasedCommandRunner,
      brewCandidates: () -> List<Path>,
    ): RebasedMacInstallationDetector =
      RebasedMacInstallationDetector(
        runner = runner,
        brewCandidates = brewCandidates(),
        caskroomRoots = defaultCaskroomRoots(),
      )
  }
}

private fun defaultCaskroomRoots(): List<Path> =
  listOf(
    Path.of("/opt/homebrew/Caskroom"),
    Path.of("/usr/local/Caskroom"),
  )

private fun collectDefaultBrewCandidates(): List<Path> =
  listOfNotNull(
    PathEnvironmentVariableUtil.findInPath("brew")?.toPath(),
    Path.of("/opt/homebrew/bin/brew"),
    Path.of("/usr/local/bin/brew"),
  ).distinct()

private fun isSamePath(value: String, expected: Path): Boolean =
  runCatching { Path.of(value.trim()).toAbsolutePath().normalize() }
    .getOrNull()
    ?.let { candidate ->
      candidate == expected || runCatching { candidate.toRealPath() == expected.toRealPath() }.getOrDefault(false)
    }
  ?: false

private const val CASK_NAME = "rebased"
