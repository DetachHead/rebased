// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.github.util

import com.intellij.collaboration.async.timeoutRunBlockingWithBackgroundScope
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.options.advanced.AdvancedSettingsImpl
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.replaceService
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates
import org.jetbrains.plugins.github.api.GHRepositoryPath
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val ALIASES_SETTING_ID = "github.aliases"
private const val ALIASED_URL = "git@github-work:group/repo.git"
private const val GITHUB_COM_URL = "git@github.com:upstream/repo.git"

@OptIn(ExperimentalCoroutinesApi::class)
@TestApplication
internal class GHHostedRepositoriesManagerTest {

  private val project by projectFixture()

  @TestDisposable
  private lateinit var disposable: Disposable

  // The servers that the discovery checks, in the order of the checks.
  private val checkedServers = Channel<GithubServerPath>(Channel.UNLIMITED)

  private val accounts = MutableStateFlow(emptySet<GithubAccount>())

  @BeforeEach
  fun setUp() {
    val accountManager = mockk<GHAccountManager>(relaxUnitFun = true) {
      every { accountsState } returns accounts
    }
    val metadataLoader = mockk<GHEnterpriseServerMetadataLoader> {
      coEvery { loadMetadata(any()) } coAnswers {
        checkedServers.send(firstArg())
        throw IllegalStateException("Not a GitHub server")
      }
    }
    val application = ApplicationManager.getApplication()
    application.replaceService(GHAccountManager::class.java, accountManager, disposable)
    application.replaceService(GHEnterpriseServerMetadataLoader::class.java, metadataLoader, disposable)
  }

  @Test
  fun `remote with an alias maps to github com`() = timeoutRunBlockingWithBackgroundScope { bg ->
    setAliases("github.com, github-work")
    registerRemotes(gitRemote("origin", ALIASED_URL))

    val repositories = GHHostedRepositoriesManager(project, bg).knownRepositoriesState.first { it.isNotEmpty() }

    assertThat(repositories.map { it.repository }).containsExactly(defaultServerRepository("group"))
  }

  @Test
  fun `alias matches the host in any case of the setting`() = timeoutRunBlockingWithBackgroundScope { bg ->
    setAliases("GitHub-Work")
    registerRemotes(gitRemote("origin", ALIASED_URL))

    val repositories = GHHostedRepositoriesManager(project, bg).knownRepositoriesState.first { it.isNotEmpty() }

    assertThat(repositories.map { it.repository }).containsExactly(defaultServerRepository("group"))
  }

  @Test
  fun `remote with an alias of an enterprise server maps to that server`() = timeoutRunBlockingWithBackgroundScope { bg ->
    setAliases("github-work=github.example.com, old-alias")
    registerRemotes(gitRemote("origin", ALIASED_URL), gitRemote("old", "git@old-alias:old/repo.git"))

    val repositories = GHHostedRepositoriesManager(project, bg).knownRepositoriesState.first { it.size == 2 }

    assertThat(repositories.map { it.repository }).containsExactlyInAnyOrder(
      GHRepositoryCoordinates(GithubServerPath("github.example.com"), GHRepositoryPath("group", "repo")),
      defaultServerRepository("old"),
    )
    assertThat(checkedServers.tryReceive().getOrNull()).isNull()
  }

  @Test
  fun `remote with an alias skips server discovery`() = timeoutRunBlockingWithBackgroundScope { bg ->
    setAliases("github.com, github-work")
    registerRemotes(gitRemote("origin", ALIASED_URL), gitRemote("other", "git@other-host:other/repo.git"))

    GHHostedRepositoriesManager(project, bg).knownRepositoriesState.first { it.isNotEmpty() }

    // The discovery checks three server URIs for each host.
    assertThat(List(3) { checkedServers.receive().host }).containsOnly("other-host")
    assertThat(checkedServers.tryReceive().getOrNull()).isNull()
  }

  @Test
  fun `remote without an alias goes to server discovery`() = timeoutRunBlockingWithBackgroundScope { bg ->
    registerRemotes(gitRemote("origin", ALIASED_URL), gitRemote("upstream", GITHUB_COM_URL))

    val repositories = GHHostedRepositoriesManager(project, bg).knownRepositoriesState.first { it.isNotEmpty() }

    assertThat(repositories.map { it.repository }).containsExactly(defaultServerRepository("upstream"))
    assertThat(checkedServers.receive()).isEqualTo(GithubServerPath.from("https://github-work"))
  }

  @Test
  fun `change of the aliases setting updates the known repositories`() = timeoutRunBlockingWithBackgroundScope { bg ->
    registerRemotes(gitRemote("origin", ALIASED_URL), gitRemote("upstream", GITHUB_COM_URL))
    val state = GHHostedRepositoriesManager(project, bg).knownRepositoriesState
    state.first { it.isNotEmpty() }

    setAliases("github.com,github-work")
    val repositories = state.first { it.size == 2 }

    assertThat(repositories.map { it.repository })
      .containsExactlyInAnyOrder(defaultServerRepository("group"), defaultServerRepository("upstream"))
  }

  @Test
  fun `account with the same host overrides an explicit alias URL`() = timeoutRunBlockingWithBackgroundScope { bg ->
    val accountServer = GithubServerPath.from("http://git.example.com:8080/github")
    accounts.value = setOf(GithubAccount(name = "user", server = accountServer))
    setAliases("github-work=https://git.example.com:8443")
    registerRemotes(gitRemote("origin", ALIASED_URL))

    val repositories = GHHostedRepositoriesManager(project, bg).knownRepositoriesState.first { it.isNotEmpty() }

    assertThat(repositories.single().repository.serverPath).isEqualTo(accountServer)
    assertThat(repositories.single().repository.repositoryPath).isEqualTo(GHRepositoryPath("group", "repo"))
  }

  @Test
  fun `new account updates the server of the alias`() = timeoutRunBlockingWithBackgroundScope { bg ->
    setAliases("github-work=git.example.com")
    registerRemotes(gitRemote("origin", ALIASED_URL))
    val state = GHHostedRepositoriesManager(project, bg).knownRepositoriesState
    state.first { it.isNotEmpty() }

    val accountServer = GithubServerPath.from("http://git.example.com:8080/github")
    accounts.value = setOf(GithubAccount(name = "user", server = accountServer))
    val repositories = state.first { mappings -> mappings.any { it.repository.serverPath == accountServer } }

    assertThat(repositories.single().repository.serverPath).isSameAs(accountServer)

    accounts.value = emptySet()
    val resetRepositories = state.first { mappings -> mappings.any { it.repository.serverPath == GithubServerPath("git.example.com") } }
    assertThat(resetRepositories.single().repository.serverPath).isEqualTo(GithubServerPath("git.example.com"))
  }

  @Test
  fun `SSH alias preserves an owner that equals the server web path`() = timeoutRunBlockingWithBackgroundScope { bg ->
    val aliasServer = GithubServerPath.from("https://git.example.com/github")
    setAliases("github-work=https://git.example.com/github")
    registerRemotes(gitRemote("origin", "git@github-work:github/repo.git"))

    val repositories = GHHostedRepositoriesManager(project, bg).knownRepositoriesState.first { it.isNotEmpty() }

    assertThat(repositories.map { it.repository }).containsExactly(
      GHRepositoryCoordinates(aliasServer, GHRepositoryPath("github", "repo"))
    )
  }

  private fun setAliases(value: String) {
    (AdvancedSettings.getInstance() as AdvancedSettingsImpl).setSetting(ALIASES_SETTING_ID, value, disposable)
  }

  private fun registerRemotes(vararg gitRemotes: GitRemote) {
    val repository = mockk<GitRepository> {
      every { remotes } returns gitRemotes.toList()
    }
    val repositoryManager = mockk<GitRepositoryManager>(relaxUnitFun = true) {
      every { repositories } returns listOf(repository)
    }
    project.replaceService(GitRepositoryManager::class.java, repositoryManager, disposable)
  }

  private fun gitRemote(name: String, url: String): GitRemote =
    GitRemote(name = name, urls = listOf(url), pushUrls = listOf(url), fetchRefSpecs = listOf(), pushRefSpecs = listOf())

  private fun defaultServerRepository(owner: String): GHRepositoryCoordinates =
    GHRepositoryCoordinates(GithubServerPath.DEFAULT_SERVER, GHRepositoryPath(owner, "repo"))
}
