// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.mergerequest.data

import git4idea.commands.Git
import git4idea.remote.GitRemoteUrlCoordinates
import git4idea.remote.hosting.GitRemoteBranchesUtil
import git4idea.remote.hosting.GitRemoteBranchesUtil.findOrCreateRemote
import git4idea.repo.GitRemote
import git4idea.repo.GitRepoInfo
import git4idea.repo.GitRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.plugins.gitlab.util.GitLabProjectPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI

private const val ALIASED_URL = "git@gitlab-work:group/repo.git"

internal class GitLabMergeRequestRemoteDescriptorTest {

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `descriptor uses the project data from the API`() {
    val coordinates = remoteCoordinates(gitRemote(ALIASED_URL))

    val descriptor = projectDetails("group").getRemoteDescriptor(coordinates)

    assertThat(descriptor.serverUri).isEqualTo(URI("https://gitlab-work/"))
    assertThat(descriptor.name).isEqualTo("group")
    assertThat(descriptor.path).isEqualTo("group/repo")
    assertThat(descriptor.httpUrl).isEqualTo("https://gitlab.com/group/repo.git")
    assertThat(descriptor.sshUrl).isEqualTo("git@gitlab-work:group/repo.git")
  }

  @Test
  fun `source descriptor finds the remote with an SSH alias`() {
    val origin = gitRemote(ALIASED_URL)
    val coordinates = remoteCoordinates(origin)
    val project = projectDetails("group")
    val details = mergeRequestDetails(source = project, target = project)

    val descriptor = details.getSourceRemoteDescriptor(coordinates)

    assertThat(descriptor).isNotNull
    assertThat(GitRemoteBranchesUtil.findRemote(coordinates.repository, descriptor!!)).isEqualTo(origin)
  }

  @Test
  fun `target descriptor of a fork finds the remote with an SSH alias`() {
    val origin = gitRemote(ALIASED_URL)
    val coordinates = remoteCoordinates(origin)
    val details = mergeRequestDetails(source = projectDetails("fork-owner"), target = projectDetails("group"))

    val sourceDescriptor = details.getSourceRemoteDescriptor(coordinates)!!
    val targetDescriptor = details.getTargetRemoteDescriptor(coordinates)

    assertThat(GitRemoteBranchesUtil.findRemote(coordinates.repository, targetDescriptor)).isEqualTo(origin)
    assertThat(GitRemoteBranchesUtil.findRemote(coordinates.repository, sourceDescriptor)).isNull()
  }

  @Test
  fun `creating a fork remote preserves the SSH alias`() {
    val origin = gitRemote(ALIASED_URL)
    val coordinates = remoteCoordinates(origin)
    val repository = coordinates.repository
    val remotes = mutableListOf(origin)
    every { repository.remotes } returns remotes
    every { repository.update() } returns Unit
    val details = mergeRequestDetails(source = projectDetails("fork-owner"), target = projectDetails("group"))
    val descriptor = details.getSourceRemoteDescriptor(coordinates)!!
    val addedUrl = slot<String>()
    val git = mockk<Git> {
      every { addRemote(repository, "fork-owner", capture(addedUrl)) } answers {
        remotes.add(gitRemote(addedUrl.captured, "fork-owner"))
        mockk()
      }
    }

    val createdRemote = git.findOrCreateRemote(repository, descriptor)

    verify(exactly = 1) { git.addRemote(repository, "fork-owner", any()) }
    assertThat(createdRemote).isNotNull
    assertThat(addedUrl.captured).isEqualTo("git@gitlab-work:fork-owner/repo.git")
  }

  @Test
  fun `source descriptor is null without a source project`() {
    val coordinates = remoteCoordinates(gitRemote(ALIASED_URL))
    val details = mergeRequestDetails(source = null, target = projectDetails("group"))

    assertThat(details.getSourceRemoteDescriptor(coordinates)).isNull()
  }

  private fun gitRemote(url: String, name: String = "origin"): GitRemote =
    GitRemote(name = name, urls = listOf(url), pushUrls = listOf(url), fetchRefSpecs = listOf(), pushRefSpecs = listOf())

  private fun remoteCoordinates(remote: GitRemote): GitRemoteUrlCoordinates {
    val repositoryInfo = mockk<GitRepoInfo> {
      every { remotes } returns listOf(remote)
    }
    val repository = mockk<GitRepository> {
      every { info } returns repositoryInfo
    }
    return GitRemoteUrlCoordinates(remote.firstUrl!!, remote, repository)
  }

  // The API returns the canonical URLs of gitlab.com, not the URLs with the SSH alias.
  private fun projectDetails(owner: String): GitLabMergeRequestFullDetails.ProjectDetails =
    GitLabMergeRequestFullDetails.ProjectDetails(
      path = GitLabProjectPath(owner, "repo"),
      httpUrlToRepo = "https://gitlab.com/$owner/repo.git",
      sshUrlToRepo = "git@gitlab.com:$owner/repo.git"
    )

  private fun mergeRequestDetails(
    source: GitLabMergeRequestFullDetails.ProjectDetails?,
    target: GitLabMergeRequestFullDetails.ProjectDetails,
  ): GitLabMergeRequestFullDetails = mockk {
    every { sourceProject } returns source
    every { targetProject } returns target
  }
}