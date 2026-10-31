// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.util

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.plugins.gitlab.api.GitLabServerPath
import org.junit.jupiter.api.Test

class GitLabProjectPathTest {

  @Test
  fun `extractProjectPath with valid path`() {
    val result = GitLabProjectPath.extractProjectPath("owner/project")
    assertThat(result).isNotNull
    assertThat(result?.owner).isEqualTo("owner")
    assertThat(result?.name).isEqualTo("project")
  }

  @Test
  fun `extractProjectPath with nested owner path`() {
    val result = GitLabProjectPath.extractProjectPath("group/subgroup/project")
    assertThat(result).isNotNull
    assertThat(result?.owner).isEqualTo("group/subgroup")
    assertThat(result?.name).isEqualTo("project")
  }

  @Test
  fun `extractProjectPath with deeply nested path`() {
    val result = GitLabProjectPath.extractProjectPath("org/team/subteam/project")
    assertThat(result).isNotNull
    assertThat(result?.owner).isEqualTo("org/team/subteam")
    assertThat(result?.name).isEqualTo("project")
  }

  @Test
  fun `extractProjectPath with no slash returns null`() {
    val result = GitLabProjectPath.extractProjectPath("project")
    assertThat(result).isNull()
  }

  @Test
  fun `extractProjectPath with empty string returns null`() {
    val result = GitLabProjectPath.extractProjectPath("")
    assertThat(result).isNull()
  }

  @Test
  fun `extractProjectPath with only slash returns null`() {
    val result = GitLabProjectPath.extractProjectPath("/")
    assertThat(result).isNull()
  }

  @Test
  fun `extractProjectPath with trailing slash returns null`() {
    val result = GitLabProjectPath.extractProjectPath("owner/project/")
    assertThat(result).isNull()
  }

  @Test
  fun `extractProjectPath with leading slash`() {
    val result = GitLabProjectPath.extractProjectPath("/owner/project")
    assertThat(result).isNotNull
    assertThat(result?.owner).isEqualTo("/owner")
    assertThat(result?.name).isEqualTo("project")
  }

  @Test
  fun `extractProjectPath with empty owner returns null`() {
    val result = GitLabProjectPath.extractProjectPath("/project")
    assertThat(result).isNull()
  }

  @Test
  fun `extractProjectPath with whitespace in path`() {
    val result = GitLabProjectPath.extractProjectPath("owner with spaces/project name")
    assertThat(result).isNotNull
    assertThat(result?.owner).isEqualTo("owner with spaces")
    assertThat(result?.name).isEqualTo("project name")
  }

  @Test
  fun `extractProjectPath with special characters`() {
    val result = GitLabProjectPath.extractProjectPath("owner-name/project.name")
    assertThat(result).isNotNull
    assertThat(result?.owner).isEqualTo("owner-name")
    assertThat(result?.name).isEqualTo("project.name")
  }

  @Test
  fun `create with an HTTP remote of a server without a web path`() {
    val result = GitLabProjectPath.create(GitLabServerPath.DEFAULT_SERVER, "https://gitlab.com/group/subgroup/project.git")
    assertThat(result).isEqualTo(GitLabProjectPath("group/subgroup", "project"))
  }

  @Test
  fun `create with an SSH remote of a server without a web path`() {
    val result = GitLabProjectPath.create(GitLabServerPath.DEFAULT_SERVER, "git@gitlab.com:group/project.git")
    assertThat(result).isEqualTo(GitLabProjectPath("group", "project"))
  }

  @Test
  fun `create with an HTTP remote of a server with a web path`() {
    val result = GitLabProjectPath.create(SERVER_WITH_WEB_PATH, "http://git.example.com:8080/gitlab/group/project.git")
    assertThat(result).isEqualTo(GitLabProjectPath("group", "project"))
  }

  @Test
  fun `create keeps an SSH namespace that starts with the server web path`() {
    val result = GitLabProjectPath.create(SERVER_WITH_WEB_PATH, "ssh://git@git.example.com/gitlab/group/project.git")
    assertThat(result).isEqualTo(GitLabProjectPath("gitlab/group", "project"))
  }

  @Test
  fun `create keeps an SCP-style SSH namespace that starts with the server web path`() {
    val result = GitLabProjectPath.create(SERVER_WITH_WEB_PATH, "git@git.example.com:gitlab/group/project.git")
    assertThat(result).isEqualTo(GitLabProjectPath("gitlab/group", "project"))
  }

  @Test
  fun `create with an SSH remote without the web path`() {
    val result = GitLabProjectPath.create(SERVER_WITH_WEB_PATH, "git@git.example.com:group/subgroup/project.git")
    assertThat(result).isEqualTo(GitLabProjectPath("group/subgroup", "project"))
  }

  @Test
  fun `create with an SSH URL remote without the web path`() {
    val result = GitLabProjectPath.create(SERVER_WITH_WEB_PATH, "ssh://git@git.example.com:2222/group/project.git")
    assertThat(result).isEqualTo(GitLabProjectPath("group", "project"))
  }

  @Test
  fun `create keeps an SSH alias namespace that starts with the server web path`() {
    val result = GitLabProjectPath.create(SERVER_WITH_WEB_PATH, "git@work:gitlab/team/project.git")

    assertThat(result).isEqualTo(GitLabProjectPath("gitlab/team", "project"))
  }

  @Test
  fun `create keeps an SSH URL namespace that equals the server web path`() {
    val result = GitLabProjectPath.create(SERVER_WITH_WEB_PATH, "ssh://git@work:2222/gitlab/project.git")

    assertThat(result).isEqualTo(GitLabProjectPath("gitlab", "project"))
  }

  @Test
  fun `create with an HTTP remote outside the web path returns null`() {
    val result = GitLabProjectPath.create(SERVER_WITH_WEB_PATH, "http://git.example.com:8080/other/group/project.git")
    assertThat(result).isNull()
  }

  companion object {
    private val SERVER_WITH_WEB_PATH = GitLabServerPath("http://git.example.com:8080/gitlab")
  }
}
