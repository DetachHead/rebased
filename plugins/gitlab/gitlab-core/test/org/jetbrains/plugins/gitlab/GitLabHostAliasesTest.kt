// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.plugins.gitlab.api.GitLabServerPath
import org.junit.jupiter.api.Test

internal class GitLabHostAliasesTest {

  @Test
  fun `server host gets the https scheme`() {
    assertThat(parseGitLabHostAliases("my.alias=realgitlab.com"))
      .isEqualTo(mapOf("my.alias" to GitLabServerPath("https://realgitlab.com")))
  }

  @Test
  fun `server host keeps its port`() {
    assertThat(parseGitLabHostAliases("my.alias=realgitlab.com:8443"))
      .isEqualTo(mapOf("my.alias" to GitLabServerPath("https://realgitlab.com:8443")))
  }

  @Test
  fun `server URL keeps its scheme, port and path`() {
    assertThat(parseGitLabHostAliases("legacy=http://git.example.com:8080/gitlab"))
      .isEqualTo(mapOf("legacy" to GitLabServerPath("http://git.example.com:8080/gitlab")))
  }

  @Test
  fun `server URL loses the trailing slashes`() {
    assertThat(parseGitLabHostAliases("my.alias=https://realgitlab.com/gitlab//"))
      .isEqualTo(mapOf("my.alias" to GitLabServerPath("https://realgitlab.com/gitlab")))
  }

  @Test
  fun `gitlab com as the server maps to the default server`() {
    assertThat(parseGitLabHostAliases("a=GitLab.com, b=https://gitlab.com/")).isEqualTo(mapOf(
      "a" to GitLabServerPath.DEFAULT_SERVER,
      "b" to GitLabServerPath.DEFAULT_SERVER,
    ))
  }

  @Test
  fun `scheme and host are lowercased, the path keeps its case`() {
    assertThat(parseGitLabHostAliases("my.alias=HTTPS://RealGitLab.com/GitLab"))
      .isEqualTo(mapOf("my.alias" to GitLabServerPath("https://realgitlab.com/GitLab")))
  }

  @Test
  fun `entry with an invalid server is ignored`() {
    assertThat(parseGitLabHostAliases("bad=real gitlab.com, ssh=ssh://realgitlab.com, my.alias"))
      .isEqualTo(mapOf("my.alias" to GitLabServerPath.DEFAULT_SERVER))
  }

  @Test
  fun `default value of the setting keeps gitlab com as is`() {
    assertThat(parseGitLabHostAliases("gitlab.com")).isEqualTo(mapOf("gitlab.com" to GitLabServerPath.DEFAULT_SERVER))
  }
}
