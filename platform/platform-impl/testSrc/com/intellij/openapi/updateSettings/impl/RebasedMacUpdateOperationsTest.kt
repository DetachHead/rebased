// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.EmptyProgressIndicatorBase
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.util.io.HttpRequests
import com.intellij.util.io.RequestBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.net.ssl.HostnameVerifier

internal class RebasedMacUpdateOperationsTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `secure redirect is followed manually and final request is saved`() {
    val transport = RecordingTransport(
      Response(HttpURLConnection.HTTP_MOVED_TEMP, location = "../download/rebased.dmg", body = "redirect body"),
      Response(HttpURLConnection.HTTP_OK, body = "verified DMG"),
    )
    val operations = operations(transport)
    val indicator = ProgressIndicatorBase()
    val target = tempDir.resolve("download/rebased.dmg")

    operations.download(INITIAL_URL, target, indicator)

    assertEquals(
      listOf(INITIAL_URL, "https://example.com/download/rebased.dmg"),
      transport.urls,
    )
    assertEquals("verified DMG", Files.readString(target))
    assertFalse(transport.requests[0].saved)
    assertEquals(0, transport.requests[0].inputStreamReads)
    assertTrue(transport.requests[0].connection.disconnected)
    assertTrue(transport.requests[1].saved)
    assertSame(indicator, transport.requests[1].savedIndicator)
    for (builder in transport.builders) {
      assertEquals(false, builder.followRedirects)
      assertEquals(false, builder.gzip)
      assertEquals(false, builder.throwStatusCodeException)
      assertNull(builder.forceHttps)
    }
  }

  @Test
  fun `insecure initial URL is rejected without opening a request`() {
    val transport = RecordingTransport(Response(HttpURLConnection.HTTP_OK, body = "unexpected"))

    assertThrows(IOException::class.java) {
      operations(transport).download("http://example.com/releases/rebased.dmg", tempDir.resolve("download.dmg"), ProgressIndicatorBase())
    }

    assertTrue(transport.urls.isEmpty())
  }

  @Test
  fun `HTTP redirect downgrade is rejected without opening the downgraded request`() {
    val transport = RecordingTransport(
      Response(HttpURLConnection.HTTP_MOVED_TEMP, location = "http://example.com/insecure.dmg", body = "redirect body"),
    )

    assertThrows(IOException::class.java) {
      operations(transport).download(INITIAL_URL, tempDir.resolve("download.dmg"), ProgressIndicatorBase())
    }

    assertEquals(listOf(INITIAL_URL), transport.urls)
    assertFalse(transport.requests.single().saved)
    assertEquals(0, transport.requests.single().inputStreamReads)
    assertTrue(transport.requests.single().connection.disconnected)
  }

  @Test
  fun `redirect without Location is rejected without downloading its body`() {
    val transport = RecordingTransport(
      Response(HttpURLConnection.HTTP_MOVED_TEMP, body = "redirect body"),
    )

    assertThrows(IOException::class.java) {
      operations(transport).download(INITIAL_URL, tempDir.resolve("download.dmg"), ProgressIndicatorBase())
    }

    assertFalse(transport.requests.single().saved)
    assertEquals(0, transport.requests.single().inputStreamReads)
    assertTrue(transport.requests.single().connection.disconnected)
  }

  @Test
  fun `non-success response is a download error and its body is not downloaded`() {
    val transport = RecordingTransport(
      Response(HttpURLConnection.HTTP_NOT_FOUND, body = "not found"),
    )

    assertThrows(IOException::class.java) {
      operations(transport).download(INITIAL_URL, tempDir.resolve("download.dmg"), ProgressIndicatorBase())
    }

    assertFalse(transport.requests.single().saved)
    assertEquals(0, transport.requests.single().inputStreamReads)
    assertFalse(Files.exists(tempDir.resolve("download.dmg")))
  }

  @Test
  fun `only HTTP 200 is accepted for a full DMG download`() {
    for (status in listOf(HttpURLConnection.HTTP_CREATED, HttpURLConnection.HTTP_NO_CONTENT, HttpURLConnection.HTTP_PARTIAL, 299)) {
      val transport = RecordingTransport(Response(status, body = "not a full DMG"))
      val target = tempDir.resolve("$status.dmg")

      assertThrows(IOException::class.java) {
        operations(transport).download(INITIAL_URL, target, ProgressIndicatorBase())
      }

      assertFalse(transport.requests.single().saved, status.toString())
      assertEquals(0, transport.requests.single().inputStreamReads, status.toString())
      assertFalse(Files.exists(target), status.toString())
    }
  }

  @Test
  fun `manual redirect handling enforces the fixed redirect limit`() {
    val transport = RecordingTransport(
      *(1..6).map { Response(HttpURLConnection.HTTP_MOVED_TEMP, location = "/hop-$it.dmg") }.toTypedArray(),
    )

    assertThrows(IOException::class.java) {
      operations(transport).download(INITIAL_URL, tempDir.resolve("download.dmg"), ProgressIndicatorBase())
    }

    assertEquals(6, transport.urls.size)
    assertTrue(transport.requests.none(RecordingRequest::saved))
    assertTrue(transport.requests.all { it.inputStreamReads == 0 })
  }

  @Test
  fun `cancellation is checked before opening a request`() {
    val transport = RecordingTransport(Response(HttpURLConnection.HTTP_OK, body = "unexpected"))
    val indicator = TestProgressIndicator()
    indicator.cancel()

    assertThrows(ProcessCanceledException::class.java) {
      operations(transport).download(INITIAL_URL, tempDir.resolve("download.dmg"), indicator)
    }

    assertTrue(transport.urls.isEmpty())
  }

  private fun operations(transport: RecordingTransport): DefaultRebasedMacUpdateOperations =
    DefaultRebasedMacUpdateOperations(requestBuilder = transport::request)

  private class RecordingTransport(vararg responses: Response) {
    private val responses = ArrayDeque(responses.toList())
    val urls = mutableListOf<String>()
    val builders = mutableListOf<RecordingRequestBuilder>()
    val requests: List<RecordingRequest>
      get() = builders.map(RecordingRequestBuilder::request)

    fun request(url: String): RequestBuilder {
      urls.add(url)
      val builder = RecordingRequestBuilder(
        url,
        responses.removeFirstOrNull() ?: error("Unexpected request for $url"),
      )
      builders.add(builder)
      return builder
    }
  }

  private class RecordingRequestBuilder(url: String, response: Response) : RequestBuilder() {
    val request = RecordingRequest(url, response)
    var followRedirects: Boolean? = null
    var gzip: Boolean? = null
    var forceHttps: Boolean? = null
    var throwStatusCodeException: Boolean? = null

    override fun connectTimeout(value: Int): RequestBuilder = this

    override fun readTimeout(value: Int): RequestBuilder = this

    override fun followRedirects(value: Boolean): RequestBuilder = apply {
      followRedirects = value
    }

    override fun redirectLimit(redirectLimit: Int): RequestBuilder = this

    override fun gzip(value: Boolean): RequestBuilder = apply {
      gzip = value
    }

    override fun forceHttps(forceHttps: Boolean): RequestBuilder = apply {
      this.forceHttps = forceHttps
    }

    override fun useProxy(useProxy: Boolean): RequestBuilder = this

    override fun hostNameVerifier(hostnameVerifier: HostnameVerifier?): RequestBuilder = this

    override fun userAgent(userAgent: String?): RequestBuilder = this

    override fun productNameAsUserAgent(): RequestBuilder = this

    override fun accept(mimeType: String?): RequestBuilder = this

    override fun tuner(tuner: HttpRequests.ConnectionTuner?): RequestBuilder = this

    override fun isReadResponseOnError(isReadResponseOnError: Boolean): RequestBuilder = this

    override fun throwStatusCodeException(shouldThrow: Boolean): RequestBuilder = apply {
      throwStatusCodeException = shouldThrow
    }

    override fun <T : Any?> connect(processor: HttpRequests.RequestProcessor<T>): T = processor.process(request)
  }

  private class RecordingRequest(
    private val url: String,
    private val response: Response,
  ) : HttpRequests.Request {
    val connection = RecordingHttpURLConnection(url, response)
    var saved = false
    var savedIndicator: ProgressIndicator? = null
    var inputStreamReads = 0

    override fun getURL(): String = url

    override fun getConnection(): URLConnection = connection

    override fun getInputStream(): InputStream {
      inputStreamReads++
      return ByteArrayInputStream(response.body.toByteArray(StandardCharsets.UTF_8))
    }

    override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream))

    override fun getReader(indicator: ProgressIndicator?): BufferedReader = reader

    override fun saveToFile(file: Path, indicator: ProgressIndicator?): Path {
      saved = true
      savedIndicator = indicator
      Files.createDirectories(file.parent)
      return Files.writeString(file, response.body)
    }

    override fun saveToFile(file: Path, indicator: ProgressIndicator?, progressDescription: Boolean): Path =
      saveToFile(file, indicator)

    override fun readBytes(indicator: ProgressIndicator?): ByteArray = inputStream.readAllBytes()

    override fun readString(indicator: ProgressIndicator?): String =
      String(readBytes(indicator), StandardCharsets.UTF_8)

    override fun readChars(indicator: ProgressIndicator?): CharSequence = readString(indicator)

    override fun readError(): String? = response.body.takeIf { response.status >= 400 }
  }

  private class RecordingHttpURLConnection(url: String, private val response: Response) :
    HttpURLConnection(URI(url).toURL()) {
    var disconnected = false

    override fun disconnect() {
      disconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun connect() = Unit

    override fun getResponseCode(): Int = response.status

    override fun getHeaderField(name: String?): String? =
      response.location.takeIf { name.equals("Location", ignoreCase = true) }
  }

  private data class Response(
    val status: Int,
    val location: String? = null,
    val body: String = "",
  )

  private class TestProgressIndicator : EmptyProgressIndicatorBase(ModalityState.nonModal()) {
    private var canceled = false

    override fun cancel() {
      canceled = true
    }

    override fun isCanceled(): Boolean = canceled
  }

  companion object {
    private const val INITIAL_URL = "https://example.com/releases/rebased.dmg"
  }
}
