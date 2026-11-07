// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.github.pullrequest.ui.details.model

import git4idea.commands.Git
import git4idea.remote.GitRemoteUrlCoordinates
import git4idea.remote.hosting.GitRemoteBranchesUtil.findOrCreateRemote
import git4idea.repo.GitRemote
import git4idea.repo.GitRepoInfo
import git4idea.repo.GitRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.plugins.github.api.data.GHRepository
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequest
import org.jetbrains.plugins.github.pullrequest.ui.details.model.GHPRBranchesViewModel.Companion.getHeadRemoteDescriptor
import org.junit.jupiter.api.Test
import java.net.URI

internal class GHPRRemoteDescriptorTest {
  @Test
  fun `creating a fork remote preserves the SSH alias`() {
    val origin = gitRemote("git@github-work:group/repo.git")
    val coordinates = remoteCoordinates(origin)
    val repository = coordinates.repository
    val remotes = mutableListOf(origin)
    every { repository.remotes } returns remotes
    every { repository.update() } returns Unit
    val descriptor = pullRequest().getHeadRemoteDescriptor(coordinates)!!
    val addedUrl = slot<String>()
    val git = mockk<Git> {
      every { addRemote(repository, "fork-owner", capture(addedUrl)) } answers {
        remotes.add(gitRemote(addedUrl.captured, "fork-owner"))
        mockk()
      }
    }

    val createdRemote = git.findOrCreateRemote(repository, descriptor)

    verify(exactly = 1) { git.addRemote(repository, "fork-owner", "git@github-work:fork-owner/repo.git") }
    assertThat(createdRemote?.firstUrl).isEqualTo("git@github-work:fork-owner/repo.git")
    assertThat(descriptor.httpUrl).isEqualTo("https://github.com/fork-owner/repo")
  }

  @Test
  fun `head descriptor uses the repository data from the API`() {
    val coordinates = remoteCoordinates(gitRemote("git@github-work:group/repo.git"))

    val descriptor = pullRequest().getHeadRemoteDescriptor(coordinates)!!

    assertThat(descriptor.name).isEqualTo("fork-owner")
    assertThat(descriptor.path).isEqualTo("fork-owner/repo")
    assertThat(descriptor.serverUri).isEqualTo(URI("https://github-work/"))
    assertThat(descriptor.httpUrl).isEqualTo("https://github.com/fork-owner/repo")
    assertThat(descriptor.sshUrl).isEqualTo("git@github-work:fork-owner/repo.git")
  }

  // The API returns the canonical URLs of github.com, not the URLs with the SSH alias.
  private fun pullRequest(): GHPullRequest {
    val repository = mockk<GHRepository> {
      every { owner } returns mockk { every { login } returns "fork-owner" }
      every { nameWithOwner } returns "fork-owner/repo"
      every { url } returns "https://github.com/fork-owner/repo"
      every { sshUrl } returns "git@github.com:fork-owner/repo.git"
    }
    return mockk { every { headRepository } returns repository }
  }

  private fun gitRemote(url: String, name: String = "origin"): GitRemote =
    GitRemote(name = name, urls = listOf(url), pushUrls = listOf(url), fetchRefSpecs = listOf(), pushRefSpecs = listOf())

  private fun remoteCoordinates(remote: GitRemote): GitRemoteUrlCoordinates {
    val repositoryInfo = mockk<GitRepoInfo> { every { remotes } returns listOf(remote) }
    val repository = mockk<GitRepository> { every { info } returns repositoryInfo }
    return GitRemoteUrlCoordinates(remote.firstUrl!!, remote, repository)
  }
}
