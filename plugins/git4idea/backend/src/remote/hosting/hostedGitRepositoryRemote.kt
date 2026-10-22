// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remote.hosting

import com.intellij.util.io.URLUtil
import git4idea.remote.GitRemoteUrlCoordinates
import git4idea.remote.hosting.GitHostingUrlUtil.getUriFromRemoteUrl
import org.jetbrains.annotations.ApiStatus
import java.net.URI

/**
 * Creates the descriptor of the remote for the hosted repository at [path].
 * The descriptor uses the host of [defaultCoordinates], so it also matches a remote with an SSH alias.
 * [httpUrl] and [sshUrl] are the repository URLs from the hosting API.
 */
@ApiStatus.Internal
fun createHostedGitRepositoryRemote(
  name: String,
  path: String,
  httpUrl: String?,
  sshUrl: String?,
  defaultCoordinates: GitRemoteUrlCoordinates,
): HostedGitRepositoryRemote =
  HostedGitRepositoryRemote(
    name,
    getServerUri(path, httpUrl, defaultCoordinates),
    path,
    httpUrl,
    sshUrl?.let { getSshUrl(it, defaultCoordinates) }
  )

/**
 * Gets the server URI with the host of [defaultCoordinates].
 * HTTP remotes keep the web path from [httpUrl]. SSH remotes use the root path.
 */
private fun getServerUri(path: String, httpUrl: String?, defaultCoordinates: GitRemoteUrlCoordinates): URI {
  val remoteUri = getUriFromRemoteUrl(defaultCoordinates.url) ?: throw IllegalArgumentException("Invalid remote URL: ${defaultCoordinates.url}")
  if (GitHostingUrlUtil.isSshUrl(defaultCoordinates.url)) return remoteUri.resolve("/")

  val webPath = httpUrl?.let { getUriFromRemoteUrl(it)?.path }
    ?.takeIf { it.endsWith("/$path") }
    ?.removeSuffix(path) ?: "/"
  return remoteUri.resolve(webPath)
}

/**
 * Gets [sshUrl] with the host of [defaultCoordinates].
 * Keeps an explicit port from [defaultCoordinates].
 * Without such a port, the URL keeps the port of [sshUrl] only when the host does not change.
 * With another host, such as an SSH alias, the SSH config gives the port, so the URL has no port.
 */
private fun getSshUrl(sshUrl: String, defaultCoordinates: GitRemoteUrlCoordinates): String {
  if (!GitHostingUrlUtil.isSshUrl(defaultCoordinates.url) || !GitHostingUrlUtil.isSshUrl(sshUrl)) return sshUrl
  val remoteUri = getUriFromRemoteUrl(defaultCoordinates.url) ?: return sshUrl
  val sshUri = getUriFromRemoteUrl(sshUrl) ?: return sshUrl
  if (remoteUri.host.equals(sshUri.host, true) && (remoteUri.port == -1 || remoteUri.port == sshUri.port)) return sshUrl

  if (sshUrl.contains(URLUtil.SCHEME_SEPARATOR)) {
    val uri = runCatching { URI(sshUrl) }.getOrNull() ?: return sshUrl
    if (remoteUri.port == -1) {
      val userInfo = uri.userInfo?.let { "$it@" }.orEmpty()
      return "$userInfo${remoteUri.host}:${uri.path.removePrefix("/")}"
    }
    return URI(uri.scheme, uri.userInfo, remoteUri.host, remoteUri.port, uri.path, uri.query, uri.fragment).toString()
  }
  val hostStart = sshUrl.indexOf('@') + 1
  val hostEnd = sshUrl.indexOf(':', hostStart)
  if (hostEnd < 0) return sshUrl
  if (remoteUri.port != -1) {
    val userInfo = sshUrl.substring(0, hostStart).removeSuffix("@").takeIf { it.isNotEmpty() }
    val path = "/" + sshUrl.substring(hostEnd + 1).removePrefix("/")
    return URI("ssh", userInfo, remoteUri.host, remoteUri.port, path, null, null).toString()
  }
  return sshUrl.substring(0, hostStart) + remoteUri.host + sshUrl.substring(hostEnd)
}
