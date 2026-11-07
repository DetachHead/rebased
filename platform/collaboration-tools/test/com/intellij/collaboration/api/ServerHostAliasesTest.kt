// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.collaboration.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

private val DEFAULT_SERVER = TestServer("https://default.example.com")

class ServerHostAliasesTest {

  @Test
  fun `alias without a server maps to the default server`() {
    assertThat(parse("my-old-alias")).isEqualTo(mapOf("my-old-alias" to DEFAULT_SERVER))
  }

  @Test
  fun `alias with an empty server maps to the default server`() {
    assertThat(parse("my.alias=, other.alias =  ")).isEqualTo(mapOf(
      "my.alias" to DEFAULT_SERVER,
      "other.alias" to DEFAULT_SERVER,
    ))
  }

  @Test
  fun `server parser gets the trimmed server text`() {
    val serverTexts = mutableListOf<String>()

    parseServerHostAliases("a= https://Server.example.com/Path , b=host", DEFAULT_SERVER) {
      serverTexts.add(it)
      TestServer("https://$it")
    }

    assertThat(serverTexts).containsExactly("https://Server.example.com/Path", "host")
  }

  @Test
  fun `alias is trimmed and lowercased`() {
    assertThat(parse("  My.Alias = https://server.example.com  "))
      .isEqualTo(mapOf("my.alias" to TestServer("https://server.example.com")))
  }

  @Test
  fun `mixed list of entries`() {
    assertThat(parse("work=https://work.example.com, old-alias, legacy=http://legacy.example.com:8080/web")).isEqualTo(mapOf(
      "work" to TestServer("https://work.example.com"),
      "old-alias" to DEFAULT_SERVER,
      "legacy" to TestServer("http://legacy.example.com:8080/web"),
    ))
  }

  @Test
  fun `empty entries are ignored`() {
    assertThat(parse(" , ,my.alias,,")).isEqualTo(mapOf("my.alias" to DEFAULT_SERVER))
    assertThat(parse("")).isEmpty()
  }

  @Test
  fun `entry with an empty alias is ignored`() {
    assertThat(parse("=https://server.example.com, my.alias")).isEqualTo(mapOf("my.alias" to DEFAULT_SERVER))
  }

  @Test
  fun `entry with an invalid server is ignored`() {
    assertThat(parse("bad=invalid, my.alias")).isEqualTo(mapOf("my.alias" to DEFAULT_SERVER))
  }

  @Test
  fun `last entry wins for a repeated alias`() {
    assertThat(parse("work=https://first.example.com, Work=https://second.example.com"))
      .isEqualTo(mapOf("work" to TestServer("https://second.example.com")))
  }

  @Test
  fun `alias prefers the server with the same URI`() {
    val sameHostServer = TestServer("http://git.example.com:8080")
    val sameUriServer = TestServer("https://git.example.com:8443")

    val server = listOf(sameHostServer, sameUriServer).findServerForAlias(TestServer("https://git.example.com:8443"))

    assertThat(server).isSameAs(sameUriServer)
  }

  @Test
  fun `alias uses the first server with the same host when no URI matches`() {
    val firstServer = TestServer("http://git.example.com:8080")
    val servers = listOf(TestServer("https://other.example.com"), firstServer, TestServer("https://git.example.com:8443"))

    val server = servers.findServerForAlias(TestServer("https://git.example.com"))

    assertThat(server).isSameAs(firstServer)
  }

  @Test
  fun `alias matches the server host in any case`() {
    val accountServer = TestServer("https://Git.Example.com/web")

    assertThat(listOf(accountServer).findServerForAlias(TestServer("https://git.example.com"))).isSameAs(accountServer)
  }

  @Test
  fun `alias keeps its server without a server on the same host`() {
    val aliasServer = TestServer("https://git.example.com")

    assertThat(listOf(TestServer("https://other.example.com")).findServerForAlias(aliasServer)).isSameAs(aliasServer)
    assertThat(emptyList<TestServer>().findServerForAlias(aliasServer)).isSameAs(aliasServer)
  }

  private fun parse(value: String): Map<String, TestServer> =
    parseServerHostAliases(value, DEFAULT_SERVER) { text -> if (text == "invalid") null else TestServer(text) }
}

private data class TestServer(private val uri: String) : ServerPath {
  override fun toURI(): URI = URI(uri)

  override fun toString(): String = uri
}
