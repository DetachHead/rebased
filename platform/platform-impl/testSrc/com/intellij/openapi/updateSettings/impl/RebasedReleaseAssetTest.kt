// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.system.CpuArch
import com.intellij.util.io.HttpRequests
import com.intellij.util.io.RequestBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import javax.net.ssl.HostnameVerifier

@TestApplication
internal class RebasedReleaseAssetTest {
  @Test
  fun `selects exact Apple Silicon asset`() {
    val release = release(
      asset("rebased-aarch64.dmg.sha256", "https://github.com/rebased/rebased/releases/download/v1/rebased-aarch64.dmg.sha256"),
      asset("rebased.dmg", "https://github.com/rebased/rebased/releases/download/v1/rebased.dmg", ABC_SHA256),
      asset("rebased-aarch64.dmg", "https://github.com/rebased/rebased/releases/download/v1/rebased-aarch64.dmg"),
    )

    assertEquals(
      RebasedReleaseAsset(
        "rebased-aarch64.dmg",
        "https://github.com/rebased/rebased/releases/download/v1/rebased-aarch64.dmg",
        EMPTY_SHA256,
      ),
      selectRebasedMacAsset(release, CpuArch.ARM64),
    )
  }

  @Test
  fun `selects exact Intel asset`() {
    val release = release(
      asset("rebased-aarch64.dmg", "https://github.com/rebased/rebased/releases/download/v1/rebased-aarch64.dmg"),
      asset("rebased.dmg", "https://github.com/rebased/rebased/releases/download/v1/rebased.dmg", ABC_SHA256),
    )

    assertEquals(
      RebasedReleaseAsset(
        "rebased.dmg",
        "https://github.com/rebased/rebased/releases/download/v1/rebased.dmg",
        ABC_SHA256.removePrefix("sha256:"),
      ),
      selectRebasedMacAsset(release, CpuArch.X86_64),
    )
  }

  @Test
  fun `rejects unsupported architecture`() {
    assertNull(selectRebasedMacAsset(release(asset("rebased.dmg")), CpuArch.OTHER))
  }

  @Test
  fun `rejects non HTTPS download URL`() {
    val release = release(asset("rebased-aarch64.dmg", "http://github.com/rebased/rebased/releases/download/v1/rebased-aarch64.dmg"))

    assertNull(selectRebasedMacAsset(release, CpuArch.ARM64))
  }

  @Test
  fun `rejects HTTPS download URL without host`() {
    val release = release(asset("rebased-aarch64.dmg", "https://"))

    assertNull(selectRebasedMacAsset(release, CpuArch.ARM64))
  }

  @Test
  fun `rejects HTTPS download URL with out of range port`() {
    val release = release(asset("rebased-aarch64.dmg", "https://example.test:65536/rebased-aarch64.dmg"))

    assertNull(selectRebasedMacAsset(release, CpuArch.ARM64))
  }

  @Test
  fun `rejects missing digest`() {
    val release = release(asset("rebased-aarch64.dmg", digest = null))

    assertNull(selectRebasedMacAsset(release, CpuArch.ARM64))
  }

  @Test
  fun `rejects wrong length digest`() {
    val release = release(asset("rebased-aarch64.dmg", digest = "sha256:${EMPTY_SHA256.dropLast(1)}"))

    assertNull(selectRebasedMacAsset(release, CpuArch.ARM64))
  }

  @Test
  fun `rejects non hexadecimal digest`() {
    val release = release(asset("rebased-aarch64.dmg", digest = "sha256:${EMPTY_SHA256.dropLast(1)}z"))

    assertNull(selectRebasedMacAsset(release, CpuArch.ARM64))
  }

  @Test
  fun `normalizes digest to lowercase`() {
    val release = release(asset("rebased-aarch64.dmg", digest = "sha256:${EMPTY_SHA256.uppercase()}"))

    assertEquals(EMPTY_SHA256, selectRebasedMacAsset(release, CpuArch.ARM64)?.sha256)
  }

  @Test
  fun `selects normalized fresh digest for exact tag and architecture`() {
    val release = release(
      asset("rebased-aarch64.dmg", digest = "sha256:${EMPTY_SHA256.uppercase()}"),
      tag = "1.2.3",
    )

    assertEquals(EMPTY_SHA256, selectFreshRebasedMacDigest(release, "1.2.3", CpuArch.ARM64))
  }

  @Test
  fun `rejects fresh digest when tag does not exactly match version`() {
    val release = release(asset("rebased-aarch64.dmg"), tag = "v1.2.3")

    assertNull(selectFreshRebasedMacDigest(release, "1.2.3", CpuArch.ARM64))
  }

  @Test
  fun `rejects fresh digest when architecture asset is missing`() {
    val release = release(asset("rebased.dmg"), tag = "1.2.3")

    assertNull(selectFreshRebasedMacDigest(release, "1.2.3", CpuArch.ARM64))
  }

  @Test
  fun `rejects fresh digest when architecture asset digest is missing`() {
    val release = release(asset("rebased-aarch64.dmg", digest = null), tag = "1.2.3")

    assertNull(selectFreshRebasedMacDigest(release, "1.2.3", CpuArch.ARM64))
  }

  @Test
  fun `fresh digest read uses cancellation-aware request API`() {
    val indicator = EmptyProgressIndicator()
    val request = TestRequestBuilder(
      release(asset("rebased-aarch64.dmg"), tag = "1.2.3").toString(),
    )

    assertEquals(
      EMPTY_SHA256,
      loadFreshRebasedMacDigest("1.2.3", indicator, CpuArch.ARM64, request),
    )
    assertSame(indicator, request.readIndicator)
  }

  @Test
  fun `fresh digest read propagates cancellation during response body`() {
    val indicator = EmptyProgressIndicator()
    val request = TestRequestBuilder(
      release(asset("rebased-aarch64.dmg"), tag = "1.2.3").toString(),
      cancelDuringRead = true,
    )

    assertThrows(ProcessCanceledException::class.java) {
      loadFreshRebasedMacDigest("1.2.3", indicator, CpuArch.ARM64, request)
    }
    assertSame(indicator, request.readIndicator)
  }

  @Test
  fun `parses and normalizes build download digest`() {
    assertEquals(EMPTY_SHA256, buildInfo(EMPTY_SHA256.uppercase()).downloadDigest)
  }

  @Test
  fun `rejects malformed build download digest`() {
    assertNull(buildInfo("${EMPTY_SHA256.dropLast(1)}z").downloadDigest)
  }

  @Test
  fun `uses selected asset in macOS update data`() {
    assumeTrue(SystemInfoRt.isMac)
    val releaseNotes = """
      ## Update highlights

      The updater keeps **signed downloads** and links to the [upgrade guide](https://example.test/upgrade-guide).
    """.trimIndent()
    val expected = when (CpuArch.CURRENT) {
      CpuArch.ARM64 -> RebasedReleaseAsset(
        "rebased-aarch64.dmg",
        "https://github.com/rebased/rebased/releases/download/v1/rebased-aarch64.dmg",
        EMPTY_SHA256,
      )
      CpuArch.X86_64 -> RebasedReleaseAsset(
        "rebased.dmg",
        "https://github.com/rebased/rebased/releases/download/v1/rebased.dmg",
        ABC_SHA256.removePrefix("sha256:"),
      )
      else -> {
        assumeTrue(false, "unsupported macOS test architecture")
        return
      }
    }
    val build = parseGithubBuild(
      release(
        asset("rebased-aarch64.dmg", "https://github.com/rebased/rebased/releases/download/v1/rebased-aarch64.dmg"),
        asset("rebased.dmg", "https://github.com/rebased/rebased/releases/download/v1/rebased.dmg", ABC_SHA256),
        body = releaseNotes,
      )
    )

    assertEquals(expected.downloadUrl, build.downloadUrl)
    assertEquals(expected.sha256, build.downloadDigest)
    assertTrue(build.message.contains("<strong>signed downloads</strong>"))
    assertTrue(build.message.contains("""<a href="https://example.test/upgrade-guide">upgrade guide</a>"""))
  }

  @Test
  fun `falls back to releases page when macOS asset is invalid`() {
    assumeTrue(SystemInfoRt.isMac)
    val assetName = if (CpuArch.CURRENT == CpuArch.ARM64) "rebased-aarch64.dmg" else "rebased.dmg"
    val build = parseGithubBuild(release(asset(assetName, digest = null)))

    assertTrue(build.downloadUrl!!.endsWith("/releases/latest"))
    assertNull(build.downloadDigest)
  }

  @Test
  fun `falls back to releases page for unsafe HTTPS asset URL`() {
    assumeTrue(SystemInfoRt.isMac)
    val assetName = if (CpuArch.CURRENT == CpuArch.ARM64) "rebased-aarch64.dmg" else "rebased.dmg"
    val downloadUrl = """https://github.com/rebased/rebased/releases/download/v1/$assetName?source="release"&arch=mac"""
    val build = parseGithubBuild(release(asset(assetName, downloadUrl)))

    assertTrue(build.downloadUrl!!.endsWith("/releases/latest"))
    assertNull(build.downloadDigest)
  }

  @Test
  fun `preserves XML characters in selected HTTPS asset URL`() {
    assumeTrue(SystemInfoRt.isMac)
    val assetName = if (CpuArch.CURRENT == CpuArch.ARM64) "rebased-aarch64.dmg" else "rebased.dmg"
    val downloadUrl = "https://github.com/rebased/rebased/releases/download/v1/$assetName?source=release&arch=mac"
    val build = parseGithubBuild(release(asset(assetName, downloadUrl)))

    assertEquals(downloadUrl, build.downloadUrl)
    assertEquals(EMPTY_SHA256, build.downloadDigest)
  }

  private fun parseGithubBuild(release: JsonObject): BuildInfo {
    val productCode = ApplicationInfo.getInstance().build.productCode
    val product = parseUpdateData(release, productCode)!!
    return product.channels.single().builds.single()
  }

  private fun buildInfo(digest: String): BuildInfo {
    val product = parseUpdateData(
      """
        <products>
          <product name="Rebased">
            <code>IU</code>
            <channel id="release" status="release">
              <build number="251.1" version="1">
                <button name="Download" url="https://github.com/rebased/rebased/releases/download/v1/rebased.dmg"
                        download="true" digest="$digest"/>
              </build>
            </channel>
          </product>
        </products>
      """.trimIndent(),
      "IU",
    )!!
    return product.channels.single().builds.single()
  }

  private fun release(
    vararg assets: JsonObject,
    body: String = "Release notes",
    tag: String = "999.0",
  ): JsonObject =
    JsonObject(
      mapOf(
        "tag_name" to JsonPrimitive(tag),
        "body" to JsonPrimitive(body),
        "assets" to JsonArray(assets.toList()),
      )
    )

  private fun asset(
    name: String,
    downloadUrl: String = "https://github.com/rebased/rebased/releases/download/v1/$name",
    digest: String? = "sha256:$EMPTY_SHA256",
  ): JsonObject {
    val fields = mutableMapOf(
      "name" to JsonPrimitive(name),
      "browser_download_url" to JsonPrimitive(downloadUrl),
    )
    if (digest != null) {
      fields["digest"] = JsonPrimitive(digest)
    }
    return JsonObject(fields)
  }

  private class TestRequestBuilder(
    private val body: String,
    private val cancelDuringRead: Boolean = false,
  ) : RequestBuilder() {
    var readIndicator: ProgressIndicator? = null

    override fun connectTimeout(value: Int): RequestBuilder = this
    override fun readTimeout(value: Int): RequestBuilder = this
    override fun followRedirects(value: Boolean): RequestBuilder = this
    override fun redirectLimit(redirectLimit: Int): RequestBuilder = this
    override fun gzip(value: Boolean): RequestBuilder = this
    override fun forceHttps(forceHttps: Boolean): RequestBuilder = this
    override fun useProxy(useProxy: Boolean): RequestBuilder = this
    override fun hostNameVerifier(hostnameVerifier: HostnameVerifier?): RequestBuilder = this
    override fun userAgent(userAgent: String?): RequestBuilder = this
    override fun productNameAsUserAgent(): RequestBuilder = this
    override fun accept(mimeType: String?): RequestBuilder = this
    override fun tuner(tuner: HttpRequests.ConnectionTuner?): RequestBuilder = this
    override fun isReadResponseOnError(isReadResponseOnError: Boolean): RequestBuilder = this
    override fun throwStatusCodeException(shouldThrow: Boolean): RequestBuilder = this

    override fun readString(indicator: ProgressIndicator?): String {
      readIndicator = indicator
      if (cancelDuringRead) indicator?.cancel()
      indicator?.checkCanceled()
      return body
    }

    override fun <T : Any?> connect(processor: HttpRequests.RequestProcessor<T>): T =
      error("Fresh release loading must use readString(indicator)")
  }

  companion object {
    private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private const val ABC_SHA256 = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  }
}
