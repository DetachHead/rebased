// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI

internal class RebasedMacDownloadRedirectsTest {
  @Test
  fun `absolute HTTPS redirect is accepted`() {
    val redirects = redirects()

    assertEquals(
      URI("https://downloads.example.com/releases/rebased.dmg"),
      redirects.resolve("https://downloads.example.com/releases/rebased.dmg"),
    )
  }

  @Test
  fun `relative redirect is resolved against current HTTPS URL`() {
    val redirects = redirects("https://example.com/releases/1.2.3/rebased.dmg")

    assertEquals(
      URI("https://example.com/releases/download/rebased.dmg"),
      redirects.resolve("../download/rebased.dmg"),
    )
  }

  @Test
  fun `query-only redirect preserves current absolute filename and replaces query`() {
    val redirects = redirects("https://example.com/releases/1.2.3/rebased.dmg?old=discarded")

    assertEquals(
      URI("https://example.com/releases/1.2.3/rebased.dmg?token=trusted"),
      redirects.resolve("?token=trusted"),
    )
  }

  @Test
  fun `query-only redirect preserves percent-encoded URL components`() {
    val redirects = redirects("https://example.test/releases/rebased%20arm.dmg?old=a%2Fb")

    val resolved = redirects.resolve("?token=x%2Fy")

    assertEquals("/releases/rebased%20arm.dmg", resolved.rawPath)
    assertEquals("token=x%2Fy", resolved.rawQuery)
    assertEquals("https://example.test/releases/rebased%20arm.dmg?token=x%2Fy", resolved.toASCIIString())
    assertFalse(resolved.toASCIIString().contains("%25"))
  }

  @Test
  fun `HTTP downgrade is rejected`() {
    assertRedirectRejected("http://example.com/releases/rebased.dmg")
  }

  @Test
  fun `scheme-relative redirect keeps HTTPS`() {
    assertEquals(
      URI("https://downloads.example.com/releases/rebased.dmg"),
      redirects().resolve("//downloads.example.com/releases/rebased.dmg"),
    )
  }

  @Test
  fun `malformed redirect is rejected`() {
    assertRedirectRejected("https://exa mple.com/releases/rebased.dmg")
  }

  @Test
  fun `hostless redirect is rejected`() {
    assertRedirectRejected("https:///releases/rebased.dmg")
  }

  @Test
  fun `redirect with userinfo is rejected`() {
    assertRedirectRejected("https://user@example.com/releases/rebased.dmg")
  }

  @Test
  fun `redirect with fragment is rejected`() {
    assertRedirectRejected("https://example.com/releases/rebased.dmg#payload")
  }

  @Test
  fun `redirect loop is rejected`() {
    val redirects = redirects()
    redirects.resolve("/releases/next.dmg")

    assertThrows(IOException::class.java) {
      redirects.resolve("/releases/rebased.dmg")
    }
  }

  @Test
  fun `redirect limit is rejected`() {
    val redirects = RebasedMacDownloadRedirects(
      "https://example.com/releases/rebased.dmg",
      redirectLimit = 1,
    )
    redirects.resolve("/releases/next.dmg")

    assertThrows(IOException::class.java) {
      redirects.resolve("/releases/final.dmg")
    }
  }

  private fun assertRedirectRejected(location: String) {
    assertThrows(
      IOException::class.java,
      { redirects().resolve(location) },
      location,
    )
  }

  private fun redirects(
    initialUrl: String = "https://example.com/releases/rebased.dmg",
  ): RebasedMacDownloadRedirects = RebasedMacDownloadRedirects(initialUrl)
}
