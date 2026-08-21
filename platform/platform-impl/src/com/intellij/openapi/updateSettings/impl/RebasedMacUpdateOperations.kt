// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.io.HttpRequests
import com.intellij.util.io.RequestBuilder
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat

internal interface RebasedMacUpdateOperations {
  fun download(url: String, target: Path, indicator: ProgressIndicator)

  fun sha256(path: Path, indicator: ProgressIndicator): String

  fun run(arguments: List<String>, indicator: ProgressIndicator, timeoutMillis: Int): RebasedCommandResult

  fun deleteRecursively(path: Path)

  fun moveAtomically(source: Path, target: Path)
}

internal class DefaultRebasedMacUpdateOperations(
  private val move: (Path, Path, Array<out CopyOption>) -> Unit = ::moveFile,
  private val requestBuilder: (String) -> RequestBuilder = HttpRequests::request,
) : RebasedMacUpdateOperations {
  override fun download(url: String, target: Path, indicator: ProgressIndicator) {
    val redirects = RebasedMacDownloadRedirects(url)
    while (true) {
      indicator.checkCanceled()
      var redirectLocation: String? = null
      var redirected = false
      requestBuilder(redirects.current.toASCIIString())
        .followRedirects(false)
        .gzip(false)
        .throwStatusCodeException(false)
        .connect { request ->
          val connection = request.connection as? HttpURLConnection
                           ?: throw IOException("Rebased download did not use an HTTP connection")
          val status = connection.responseCode
          when {
            status == HttpURLConnection.HTTP_OK -> request.saveToFile(target, indicator)
            status in REDIRECT_STATUS_CODES -> {
              redirectLocation = connection.getHeaderField("Location")
              redirected = true
              connection.disconnect()
            }
            else -> throw IOException("Rebased download failed with HTTP status $status")
          }
        }

      if (!redirected) return
      indicator.checkCanceled()
      redirects.resolve(redirectLocation)
    }
  }

  override fun sha256(path: Path, indicator: ProgressIndicator): String {
    val size = runCatching { Files.size(path) }.getOrNull()?.takeIf { it >= 0 }
    if (size != null) {
      indicator.isIndeterminate = false
      indicator.fraction = 0.0
    }

    val digest = MessageDigest.getInstance("SHA-256")
    var processed = 0L
    Files.newInputStream(path).buffered(HASH_BUFFER_SIZE).use { input ->
      val buffer = ByteArray(HASH_BUFFER_SIZE)
      while (true) {
        indicator.checkCanceled()
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
        processed += read
        if (size != null && size > 0) {
          indicator.fraction = (processed.toDouble() / size.toDouble()).coerceIn(0.0, 1.0)
        }
        indicator.checkCanceled()
      }
    }
    indicator.checkCanceled()
    if (size != null) {
      indicator.fraction = 1.0
    }
    return HexFormat.of().formatHex(digest.digest())
  }

  override fun run(
    arguments: List<String>,
    indicator: ProgressIndicator,
    timeoutMillis: Int,
  ): RebasedCommandResult {
    val output = CapturingProcessHandler(GeneralCommandLine(arguments))
      .runProcessWithProgressIndicator(indicator, timeoutMillis, true)
    if (output.isCancelled) {
      indicator.checkCanceled()
      throw ProcessCanceledException()
    }
    return RebasedCommandResult(
      exitCode = output.exitCode,
      stdout = output.stdout,
      stderr = output.stderr,
      timedOut = output.isTimeout,
    )
  }

  override fun deleteRecursively(path: Path) {
    NioFiles.deleteRecursively(path)
  }

  override fun moveAtomically(source: Path, target: Path) {
    try {
      move(source, target, arrayOf(ATOMIC_MOVE, REPLACE_EXISTING))
    }
    catch (_: AtomicMoveNotSupportedException) {
      move(source, target, arrayOf(REPLACE_EXISTING))
    }
  }
}

private fun moveFile(source: Path, target: Path, options: Array<out CopyOption>) {
  Files.move(source, target, *options)
}

private val REDIRECT_STATUS_CODES = setOf(
  HttpURLConnection.HTTP_MOVED_PERM,
  HttpURLConnection.HTTP_MOVED_TEMP,
  HttpURLConnection.HTTP_SEE_OTHER,
  307,
  308,
)
private const val HASH_BUFFER_SIZE = 64 * 1024
