// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.github.util

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.plugins.github.api.GithubServerPath
import org.junit.jupiter.api.Test

internal class GHHostAliasesTest {

  @Test
  fun `server host maps to that host`() {
    assertThat(parseGitHubHostAliases("my.alias=realgithub.com"))
      .isEqualTo(mapOf("my.alias" to GithubServerPath("realgithub.com")))
  }

  @Test
  fun `server host keeps its port`() {
    assertThat(parseGitHubHostAliases("my.alias=realgithub.com:8443"))
      .isEqualTo(mapOf("my.alias" to GithubServerPath(null, "realgithub.com", 8443, null)))
  }

  @Test
  fun `server URL keeps its scheme, port and path`() {
    assertThat(parseGitHubHostAliases("legacy=http://git.example.com:8080/github"))
      .isEqualTo(mapOf("legacy" to GithubServerPath(true, "git.example.com", 8080, "/github")))
  }

  @Test
  fun `server URL loses the trailing slashes`() {
    assertThat(parseGitHubHostAliases("my.alias=https://realgithub.com/github//"))
      .isEqualTo(mapOf("my.alias" to GithubServerPath(false, "realgithub.com", null, "/github")))
  }

  @Test
  fun `github com as the server maps to the default server`() {
    assertThat(parseGitHubHostAliases("a=GitHub.com, b=https://github.com/")).isEqualTo(mapOf(
      "a" to GithubServerPath.DEFAULT_SERVER,
      "b" to GithubServerPath.DEFAULT_SERVER,
    ))
  }

  @Test
  fun `explicit HTTP scheme is preserved for github com`() {
    assertThat(parseGitHubHostAliases("work=http://github.com"))
      .isEqualTo(mapOf("work" to GithubServerPath(true, "github.com", null, null)))
  }

  @Test
  fun `data residency host maps to that host`() {
    assertThat(parseGitHubHostAliases("work=tenant.ghe.com")).isEqualTo(mapOf("work" to GithubServerPath("tenant.ghe.com")))
  }

  @Test
  fun `host is lowercased, the path keeps its case`() {
    assertThat(parseGitHubHostAliases("my.alias=HTTPS://RealGitHub.com/GitHub"))
      .isEqualTo(mapOf("my.alias" to GithubServerPath(false, "realgithub.com", null, "/GitHub")))
  }

  @Test
  fun `entry with an invalid server is ignored`() {
    assertThat(parseGitHubHostAliases("bad=real github.com, ssh=ssh://realgithub.com, my.alias"))
      .isEqualTo(mapOf("my.alias" to GithubServerPath.DEFAULT_SERVER))
  }

  @Test
  fun `default value of the setting keeps github com as is`() {
    assertThat(parseGitHubHostAliases("github.com")).isEqualTo(mapOf("github.com" to GithubServerPath.DEFAULT_SERVER))
  }
}
