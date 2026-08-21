// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import com.intellij.util.system.CpuArch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.annotations.ApiStatus
import java.net.URI
import java.nio.file.Path
import java.util.Locale

private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")

internal enum class RebasedMacUpdateStrategy {
  DIRECT,
  HOMEBREW,
}

/**
 * Persisted update preparation state.
 *
 * @property verifiedDmgSha256 untrusted cache metadata for diagnostics and consistency checks;
 * it must never authorize installation without a caller-supplied digest from fresh release metadata
 */
internal data class PreparedRebasedMacUpdate(
  val version: String,
  val strategy: RebasedMacUpdateStrategy,
  val stagedApp: Path?,
  val verifiedDmg: Path?,
  val verifiedDmgSha256: String?,
  val brewExecutable: Path?,
  val releasePageUrl: String,
)

internal sealed interface RebasedMacInstallResult {
  data object None : RebasedMacInstallResult

  data object Success : RebasedMacInstallResult

  data class Failed(val message: String) : RebasedMacInstallResult
}

internal data class RebasedCommandResult(
  val exitCode: Int,
  val stdout: String,
  val stderr: String,
  val timedOut: Boolean = false,
)

internal sealed class RebasedMacUpdateException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause) {
  class Download(message: String, cause: Throwable? = null) : RebasedMacUpdateException(message, cause)

  class Verification(message: String, cause: Throwable? = null) : RebasedMacUpdateException(message, cause)

  class Preparation(message: String, cause: Throwable? = null) : RebasedMacUpdateException(message, cause)

  class HomebrewUnavailable(message: String, cause: Throwable? = null) : RebasedMacUpdateException(message, cause)
}

internal fun interface RebasedCommandRunner {
  fun run(command: List<String>): RebasedCommandResult
}

internal sealed interface RebasedMacInstallationSource {
  data object Direct : RebasedMacInstallationSource

  data class Homebrew(val executable: Path) : RebasedMacInstallationSource

  data object HomebrewUnavailable : RebasedMacInstallationSource
}

internal data class RebasedReleaseAsset(
  val name: String,
  val downloadUrl: String,
  val sha256: String,
)

internal fun selectRebasedMacAsset(release: JsonObject, arch: CpuArch): RebasedReleaseAsset? {
  val expectedName = when (arch) {
    CpuArch.ARM64 -> "rebased-aarch64.dmg"
    CpuArch.X86_64 -> "rebased.dmg"
    else -> return null
  }
  val assets = release["assets"] as? JsonArray ?: return null

  for (element in assets) {
    val asset = element as? JsonObject ?: continue
    val name = (asset["name"] as? JsonPrimitive)?.contentOrNull ?: continue
    if (name != expectedName) continue

    val downloadUrl = (asset["browser_download_url"] as? JsonPrimitive)?.contentOrNull
      ?.takeIf(::isAbsoluteHttpsUri)
      ?: continue
    val digest = (asset["digest"] as? JsonPrimitive)?.contentOrNull
      ?.takeIf { it.startsWith("sha256:") }
      ?.removePrefix("sha256:")
      ?.takeIf(SHA256_PATTERN::matches)
      ?.lowercase(Locale.ROOT)
      ?: continue

    return RebasedReleaseAsset(name, downloadUrl, digest)
  }
  return null
}

@ApiStatus.Internal
fun selectFreshRebasedMacDigest(release: JsonObject, expectedVersion: String, arch: CpuArch): String? {
  val tag = (release["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return null
  if (tag != expectedVersion) return null
  return selectRebasedMacAsset(release, arch)?.sha256
}

internal fun isAbsoluteHttpsUri(value: String): Boolean {
  val uri = runCatching { URI(value) }.getOrNull() ?: return false
  return isAbsoluteHttpsUri(uri)
}

internal fun isAbsoluteHttpsUri(uri: URI): Boolean {
  return uri.isAbsolute &&
         uri.scheme.equals("https", ignoreCase = true) &&
         !uri.host.isNullOrBlank() &&
         (uri.port == -1 || uri.port in 1..65535) &&
         uri.rawUserInfo == null &&
         uri.rawFragment == null
}
