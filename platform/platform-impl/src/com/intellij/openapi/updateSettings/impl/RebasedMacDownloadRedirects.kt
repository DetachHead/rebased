// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import java.io.IOException
import java.net.URI

internal class RebasedMacDownloadRedirects(
  initialUrl: String,
  private val redirectLimit: Int = DEFAULT_REDIRECT_LIMIT,
) {
  private val visited = mutableSetOf<URI>()
  private var redirectCount = 0

  var current: URI = requireTrustedUri(initialUrl, "download URL")
    private set

  init {
    require(redirectLimit > 0) { "Redirect limit must be positive" }
    visited.add(current.normalize())
  }

  fun resolve(location: String?): URI {
    if (redirectCount >= redirectLimit) {
      throw IOException("Rebased download exceeded the redirect limit")
    }
    if (location.isNullOrBlank()) {
      throw IOException("Rebased download redirect has no Location")
    }

    val redirect = try {
      URI(location)
    }
    catch (e: Exception) {
      throw IOException("Rebased download redirect has an invalid Location", e)
    }
    val resolved = resolveReference(current, redirect).normalize()
    val trusted = requireTrustedUri(resolved.toString(), "redirect Location")
    if (!visited.add(trusted)) {
      throw IOException("Rebased download redirect loop detected")
    }

    redirectCount++
    current = trusted
    return trusted
  }
}

private fun resolveReference(base: URI, reference: URI): URI {
  if (!reference.isAbsolute && reference.rawAuthority == null && reference.rawPath.isEmpty()) {
    val query = reference.rawQuery ?: base.rawQuery
    return URI(buildString {
      append(base.toASCIIString().substringBefore('?'))
      if (query != null) {
        append('?')
        append(query)
      }
      if (reference.rawFragment != null) {
        append('#')
        append(reference.rawFragment)
      }
    })
  }
  return base.resolve(reference)
}

private fun requireTrustedUri(value: String, description: String): URI {
  val uri = runCatching { URI(value) }.getOrNull()
  if (uri == null || !isAbsoluteHttpsUri(uri)) {
    throw IOException("Rebased $description must be an absolute HTTPS URL without userinfo or fragment")
  }
  return uri
}

private const val DEFAULT_REDIRECT_LIMIT = 5
