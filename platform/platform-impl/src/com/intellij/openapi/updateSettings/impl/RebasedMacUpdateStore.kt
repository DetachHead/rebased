// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.Properties
import java.util.UUID

internal class RebasedMacUpdateStore(
  root: Path,
  private val move: (Path, Path, Array<out CopyOption>) -> Unit = ::moveFile,
) {
  private val root = root.toAbsolutePath().normalize()
  private val stateFile = this.root.resolve(PREPARED_STATE_FILE)
  private var deleteRecursively: (Path) -> Unit = ::deletePathRecursively
  private var readClaimedResult: (Path) -> ByteArray? = ::readInstallResultBytes
  private var deleteClaimedResult: (Path) -> Unit = ::deletePathRecursively

  internal constructor(
    root: Path,
    move: (Path, Path, Array<out CopyOption>) -> Unit = ::moveFile,
    deleteRecursively: (Path) -> Unit,
  ) : this(root, move) {
    this.deleteRecursively = deleteRecursively
  }

  internal constructor(
    root: Path,
    readClaimedResult: (Path) -> ByteArray?,
    deleteClaimedResult: (Path) -> Unit = ::deletePathRecursively,
    move: (Path, Path, Array<out CopyOption>) -> Unit = ::moveFile,
  ) : this(root, move) {
    this.readClaimedResult = readClaimedResult
    this.deleteClaimedResult = deleteClaimedResult
  }

  fun versionDirectory(version: String): Path {
    val sanitized = INVALID_VERSION_CHARACTER.replace(version, "_")
    val directoryPrefix = when (sanitized) {
      "" -> "_"
      "." -> "_"
      ".." -> "__"
      else -> sanitized
    }.take(MAX_VERSION_DIRECTORY_PREFIX_LENGTH)
    val digest = MessageDigest.getInstance("SHA-256").digest(version.toByteArray(Charsets.UTF_8))
    val hash = HexFormat.of().formatHex(digest)
    return root.resolve("$directoryPrefix-$hash")
  }

  fun save(update: PreparedRebasedMacUpdate) {
    val rootExists = checkRootDirectory()
    val normalizedUpdate = validate(update)
                           ?: throw IllegalArgumentException("Invalid prepared Rebased update")
    val properties = Properties().apply {
      setProperty(VERSION_PROPERTY, normalizedUpdate.version)
      setProperty(STRATEGY_PROPERTY, normalizedUpdate.strategy.name)
      normalizedUpdate.stagedApp?.let { setProperty(STAGED_APP_PROPERTY, it.toString()) }
      normalizedUpdate.verifiedDmg?.let { setProperty(VERIFIED_DMG_PROPERTY, it.toString()) }
      normalizedUpdate.verifiedDmgSha256?.let { setProperty(VERIFIED_DMG_SHA256_PROPERTY, it) }
      normalizedUpdate.brewExecutable?.let { setProperty(BREW_EXECUTABLE_PROPERTY, it.toString()) }
      setProperty(RELEASE_PAGE_URL_PROPERTY, normalizedUpdate.releasePageUrl)
    }

    if (!rootExists) {
      Files.createDirectories(root)
      if (!checkRootDirectory()) throw NoSuchFileException(root.toString())
    }
    val temporaryFile = Files.createTempFile(root, "prepared.", ".tmp")
    try {
      Files.newOutputStream(temporaryFile).use {
        properties.store(it, null)
      }
      try {
        move(temporaryFile, stateFile, arrayOf(ATOMIC_MOVE, REPLACE_EXISTING))
      }
      catch (_: AtomicMoveNotSupportedException) {
        move(temporaryFile, stateFile, arrayOf(REPLACE_EXISTING))
      }
    }
    finally {
      Files.deleteIfExists(temporaryFile)
    }
  }

  fun load(): PreparedRebasedMacUpdate? {
    return try {
      if (!checkRootDirectory() || !Files.isRegularFile(stateFile, NOFOLLOW_LINKS)) return null

      val properties = Properties()
      Files.newInputStream(stateFile).use(properties::load)

      val version = properties.getProperty(VERSION_PROPERTY)?.takeIf { it.isNotBlank() } ?: return null
      val strategy = properties.getProperty(STRATEGY_PROPERTY)
        ?.let(RebasedMacUpdateStrategy::valueOf)
        ?: return null
      val stagedApp = properties.optionalPath(STAGED_APP_PROPERTY)
      val verifiedDmg = properties.optionalPath(VERIFIED_DMG_PROPERTY)
      val verifiedDmgSha256 = properties.getProperty(VERIFIED_DMG_SHA256_PROPERTY)
      val brewExecutable = properties.optionalPath(BREW_EXECUTABLE_PROPERTY)
      val releasePageUrl = properties.getProperty(RELEASE_PAGE_URL_PROPERTY)?.takeIf { it.isNotBlank() } ?: return null

      validate(
        PreparedRebasedMacUpdate(
          version = version,
          strategy = strategy,
          stagedApp = stagedApp,
          verifiedDmg = verifiedDmg,
          verifiedDmgSha256 = verifiedDmgSha256,
          brewExecutable = brewExecutable,
          releasePageUrl = releasePageUrl,
        ),
      )
    }
    catch (_: IOException) {
      null
    }
    catch (_: IllegalArgumentException) {
      null
    }
    catch (_: SecurityException) {
      null
    }
  }

  fun clear() {
    canonicalRootDirectory() ?: return
    try {
      Files.deleteIfExists(stateFile)
    }
    catch (_: DirectoryNotEmptyException) {
      deleteRecursively(stateFile)
    }
  }

  fun hasPreparedState(): Boolean {
    return try {
      val canonicalRoot = canonicalRootDirectory() ?: return false
      Files.exists(canonicalRoot.resolve(PREPARED_STATE_FILE), NOFOLLOW_LINKS)
    }
    catch (_: IOException) {
      false
    }
    catch (_: SecurityException) {
      false
    }
  }

  fun clearStaleData(retainedVersion: String?, retainPreparedState: Boolean = true) {
    val canonicalRoot = canonicalRootDirectory() ?: return

    val retainedChildren = buildSet {
      if (retainPreparedState) add(stateFile)
      add(root.resolve(INSTALL_RESULT_FILE))
      retainedVersion?.let { add(versionDirectory(it)) }
    }
    Files.newDirectoryStream(canonicalRoot).use { children ->
      for (child in children) {
        if (child !in retainedChildren && !isActiveInstallResultClaim(child)) {
          deleteRecursively(child)
        }
      }
    }
  }

  fun consumeInstallResult(currentVersion: String, currentApp: Path): RebasedMacInstallResult {
    return try {
      consumeInstallResultSafely(currentVersion, currentApp)
    }
    catch (_: IOException) {
      RebasedMacInstallResult.None
    }
    catch (_: IllegalArgumentException) {
      RebasedMacInstallResult.None
    }
    catch (_: SecurityException) {
      RebasedMacInstallResult.None
    }
  }

  fun discardPreparedState(prepared: PreparedRebasedMacUpdate, currentApp: Path? = null) {
    val canonicalRoot = canonicalRootDirectory()
      ?: throw NoSuchFileException(root.toString())
    val stored = load()
    val validated = validate(prepared)
    if (stored == null || validated == null || stored != validated) {
      throw IOException("Prepared Rebased update state is no longer valid")
    }

    when (validated.strategy) {
      RebasedMacUpdateStrategy.DIRECT -> {
        val backup = currentApp?.let(::expectedBackup)
        if (backup != null && Files.exists(backup, NOFOLLOW_LINKS)) {
          requireNonSymbolicDirectory(backup, "Rebased update backup")
          deleteRecursively(backup)
        }

        val versionDirectory = canonicalRoot.resolve(versionDirectory(validated.version).fileName)
        requireNonSymbolicDirectory(versionDirectory, "Rebased update version directory")
        val realVersionDirectory = versionDirectory.toRealPath()
        if (realVersionDirectory.parent != canonicalRoot || realVersionDirectory != versionDirectory) {
          throw IOException("Rebased update version directory is not canonical")
        }
        deleteRecursively(versionDirectory)
      }
      RebasedMacUpdateStrategy.HOMEBREW -> Unit
    }
    clear()
  }

  private fun consumeInstallResultSafely(currentVersion: String, currentApp: Path): RebasedMacInstallResult {
    val canonicalRoot = canonicalRootDirectory() ?: return RebasedMacInstallResult.None
    val marker = canonicalRoot.resolve(INSTALL_RESULT_FILE)
    val consuming = canonicalRoot.resolve("$INSTALL_RESULT_FILE.consuming-${UUID.randomUUID()}")
    try {
      move(marker, consuming, arrayOf(ATOMIC_MOVE))
    }
    catch (_: AtomicMoveNotSupportedException) {
      return consumeInstallResultWithoutAtomicMove(marker, currentVersion, currentApp)
    }
    catch (_: NoSuchFileException) {
      return consumeAbandonedInstallResult(canonicalRoot, currentVersion, currentApp)
    }

    return consumeClaimedInstallResult(consuming, currentVersion, currentApp)
  }

  private fun consumeAbandonedInstallResult(
    canonicalRoot: Path,
    currentVersion: String,
    currentApp: Path,
  ): RebasedMacInstallResult {
    Files.newDirectoryStream(canonicalRoot, "$INSTALL_RESULT_FILE.consuming-*").use { markers ->
      for (marker in markers) {
        val attributes = try {
          Files.readAttributes(marker, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        }
        catch (_: NoSuchFileException) {
          continue
        }
        if (!isAbandonedInstallResultClaim(attributes)) {
          continue
        }
        return consumeInstallResultWithoutAtomicMove(marker, currentVersion, currentApp)
      }
    }
    return RebasedMacInstallResult.None
  }

  private fun isAbandonedInstallResultClaim(attributes: BasicFileAttributes): Boolean =
    attributes.lastModifiedTime().toMillis() <= System.currentTimeMillis() - ABANDONED_INSTALL_RESULT_CLAIM_AGE_MILLIS

  private fun isActiveInstallResultClaim(path: Path): Boolean {
    if (!path.fileName.toString().startsWith("$INSTALL_RESULT_FILE.consuming-")) return false
    val attributes = try {
      Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    }
    catch (_: IOException) {
      return false
    }
    catch (_: SecurityException) {
      return false
    }
    return !isAbandonedInstallResultClaim(attributes)
  }

  private fun consumeClaimedInstallResult(
    marker: Path,
    currentVersion: String,
    currentApp: Path,
  ): RebasedMacInstallResult {
    val result = try {
      readClaimedResult(marker)?.let(::parseInstallResult)
    }
    catch (_: IOException) {
      null
    }
    catch (_: IllegalArgumentException) {
      null
    }
    catch (_: SecurityException) {
      null
    }
    try {
      deleteClaimedResult(marker)
    }
    catch (_: IOException) {
	      retireClaimedResult(marker)
      return RebasedMacInstallResult.Failed(INVALID_RESULT_MESSAGE)
    }
    catch (_: IllegalArgumentException) {
	      retireClaimedResult(marker)
      return RebasedMacInstallResult.Failed(INVALID_RESULT_MESSAGE)
    }
    catch (_: SecurityException) {
	      retireClaimedResult(marker)
      return RebasedMacInstallResult.Failed(INVALID_RESULT_MESSAGE)
    }
    return consumeClaimedInstallResult(result, currentVersion, currentApp)
  }

	  private fun retireClaimedResult(marker: Path) {
	    val retired = marker.resolveSibling("$INSTALL_RESULT_FILE.consumed-${UUID.randomUUID()}")
	    try {
	      move(marker, retired, arrayOf(ATOMIC_MOVE))
	    }
	    catch (_: AtomicMoveNotSupportedException) {
	      try {
	        move(marker, retired, arrayOf(REPLACE_EXISTING))
	      }
	      catch (_: Exception) {
	      }
	    }
	    catch (_: NoSuchFileException) {
	    }
	    catch (_: IOException) {
	    }
	    catch (_: IllegalArgumentException) {
	    }
	    catch (_: SecurityException) {
	    }
	  }

  private fun consumeInstallResultWithoutAtomicMove(
    marker: Path,
    currentVersion: String,
    currentApp: Path,
  ): RebasedMacInstallResult {
    var claimed = false
    return try {
      val attributes = Files.readAttributes(marker, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
      if (!attributes.isRegularFile) {
        return consumeNonRegularInstallResult(marker, attributes.isDirectory)
      }

      FileChannel.open(marker, READ, WRITE, NOFOLLOW_LINKS).use { channel ->
        val lock = try {
          channel.tryLock()
        }
        catch (_: OverlappingFileLockException) {
          null
        } ?: return RebasedMacInstallResult.None
        lock.use {
          val bytes = try {
            readInstallResultBytes(channel)
          }
          catch (_: IOException) {
            null
          }
          catch (_: SecurityException) {
            null
          }
          if (!deleteInstallResultForClaim(marker)) return RebasedMacInstallResult.None
          claimed = true
          consumeClaimedInstallResult(bytes?.let(::parseInstallResult), currentVersion, currentApp)
        }
      }
    }
    catch (_: IOException) {
      if (claimed) RebasedMacInstallResult.Failed(INVALID_RESULT_MESSAGE) else RebasedMacInstallResult.None
    }
    catch (_: IllegalArgumentException) {
      if (claimed) RebasedMacInstallResult.Failed(INVALID_RESULT_MESSAGE) else RebasedMacInstallResult.None
    }
    catch (_: SecurityException) {
      if (claimed) RebasedMacInstallResult.Failed(INVALID_RESULT_MESSAGE) else RebasedMacInstallResult.None
    }
  }

  private fun consumeNonRegularInstallResult(marker: Path, isDirectory: Boolean): RebasedMacInstallResult {
    return try {
      val claimed = if (isDirectory) {
        synchronized(DIRECTORY_INSTALL_RESULT_CLAIM_LOCK) {
          deletePathRecursivelyForClaim(marker)
        }
      }
      else {
        deleteInstallResultForClaim(marker)
      }
      if (!claimed) RebasedMacInstallResult.None
      else RebasedMacInstallResult.Failed(INVALID_RESULT_MESSAGE)
    }
    catch (_: IOException) {
      RebasedMacInstallResult.None
    }
    catch (_: IllegalArgumentException) {
      RebasedMacInstallResult.None
    }
    catch (_: SecurityException) {
      RebasedMacInstallResult.None
    }
  }

  private fun deleteInstallResultForClaim(marker: Path): Boolean {
    return try {
      Files.delete(marker)
      true
    }
    catch (_: NoSuchFileException) {
      false
    }
    catch (_: DirectoryNotEmptyException) {
      deletePathRecursivelyForClaim(marker)
    }
  }

  private fun consumeClaimedInstallResult(
    result: InstallResultMarker?,
    currentVersion: String,
    currentApp: Path,
  ): RebasedMacInstallResult {
    if (result == null) return RebasedMacInstallResult.Failed(INVALID_RESULT_MESSAGE)
    val prepared = load()
      ?: return RebasedMacInstallResult.Failed(INVALID_RESULT_MESSAGE)
    if (result.status == FAILED_STATUS) {
      if (result.version != prepared.version || result.strategy != prepared.strategy) {
        return RebasedMacInstallResult.None
      }
      val validBackup = when (prepared.strategy) {
        RebasedMacUpdateStrategy.DIRECT ->
          result.backup.isEmpty() || result.backup == expectedBackup(currentApp).toString()
        RebasedMacUpdateStrategy.HOMEBREW -> result.backup.isEmpty()
      }
      if (!validBackup) return RebasedMacInstallResult.None
      return RebasedMacInstallResult.Failed(
        result.message.takeIf { it.isNotBlank() } ?: INSTALLER_FAILURE_MESSAGE,
      )
    }

    if (result.status != SUCCESS_STATUS ||
        result.version != currentVersion ||
        result.version != prepared.version ||
        result.strategy != prepared.strategy) {
      return RebasedMacInstallResult.Failed(INVALID_RESULT_MESSAGE)
    }

    when (prepared.strategy) {
      RebasedMacUpdateStrategy.DIRECT -> {
        val backup = expectedBackup(currentApp)
        if (result.backup != backup.toString()) {
          return RebasedMacInstallResult.Failed(INVALID_RESULT_MESSAGE)
        }
        try {
          requireNonSymbolicDirectory(backup, "Rebased update backup")
        }
        catch (_: IOException) {
          return RebasedMacInstallResult.Failed(INVALID_RESULT_MESSAGE)
        }
        catch (_: SecurityException) {
          return RebasedMacInstallResult.Failed(INVALID_RESULT_MESSAGE)
        }
        try {
          discardPreparedState(prepared, currentApp)
        }
        catch (_: Exception) {
          // Installation is already committed; stale cleanup will retry retained state later.
        }
      }
      RebasedMacUpdateStrategy.HOMEBREW -> {
        if (result.backup.isNotEmpty()) {
          return RebasedMacInstallResult.Failed(INVALID_RESULT_MESSAGE)
        }
        try {
          discardPreparedState(prepared)
        }
        catch (_: Exception) {
          // Installation is already committed; stale cleanup will retry retained state later.
        }
      }
    }
    return RebasedMacInstallResult.Success
  }

  private fun readInstallResultBytes(channel: FileChannel): ByteArray? {
    if (channel.size() > MAX_INSTALL_RESULT_SIZE) return null
    channel.position(0)
    val buffer = ByteBuffer.allocate(MAX_INSTALL_RESULT_SIZE + 1)
    while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
      // Read through EOF or the bounded buffer.
    }
    if (buffer.position() > MAX_INSTALL_RESULT_SIZE) return null
    return buffer.array().copyOf(buffer.position())
  }

  private fun parseInstallResult(bytes: ByteArray): InstallResultMarker? {
    val properties = Properties()
    ByteArrayInputStream(bytes).use(properties::load)
    if (properties.stringPropertyNames() != INSTALL_RESULT_PROPERTIES) return null

    val status = properties.getProperty(STATUS_PROPERTY) ?: return null
    val message = properties.getProperty(MESSAGE_PROPERTY) ?: return null
    val backup = properties.getProperty(BACKUP_PROPERTY) ?: return null
    val version = properties.getProperty(VERSION_PROPERTY)?.takeIf { it.isNotBlank() } ?: return null
    val strategy = when (properties.getProperty(STRATEGY_PROPERTY)) {
      DIRECT_STRATEGY -> RebasedMacUpdateStrategy.DIRECT
      HOMEBREW_STRATEGY -> RebasedMacUpdateStrategy.HOMEBREW
      else -> return null
    }
    if (status != SUCCESS_STATUS && status != FAILED_STATUS) return null
    return InstallResultMarker(status, message, backup, version, strategy)
  }

  private fun canonicalRootDirectory(): Path? {
    if (!checkRootDirectory()) return null
    if (Files.isSymbolicLink(root)) throw NotDirectoryException(root.toString())
    val canonicalRoot = root.toRealPath()
    if (canonicalRoot != root) throw IOException("Rebased update root is not canonical")
    return canonicalRoot
  }

  private fun expectedBackup(currentApp: Path): Path {
    val normalizedApp = currentApp.toAbsolutePath().normalize()
    if (normalizedApp.fileName?.toString() != REBASED_APP_NAME) {
      throw IllegalArgumentException("Unexpected Rebased application path")
    }
    return normalizedApp.resolveSibling(REBASED_BACKUP_NAME)
  }

  private fun requireNonSymbolicDirectory(path: Path, description: String) {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    if (!attributes.isDirectory || Files.isSymbolicLink(path)) {
      throw IOException("$description is missing or symbolic")
    }
  }

  private fun checkRootDirectory(): Boolean {
    val attributes = try {
      Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    }
    catch (_: NoSuchFileException) {
      return false
    }
    if (!attributes.isDirectory) throw NotDirectoryException(root.toString())
    return true
  }

  internal fun validate(update: PreparedRebasedMacUpdate): PreparedRebasedMacUpdate? {
    if (update.version.isBlank() || !isAbsoluteHttpsUri(update.releasePageUrl)) return null

    return when (update.strategy) {
      RebasedMacUpdateStrategy.DIRECT -> {
        if (update.brewExecutable != null) return null
        val stagedApp = update.stagedApp?.takeIf(Path::isAbsolute)?.normalize() ?: return null
        val verifiedDmg = update.verifiedDmg?.takeIf(Path::isAbsolute)?.normalize() ?: return null
        val verifiedDmgSha256 = update.verifiedDmgSha256
          ?.takeIf(SHA256_PATTERN::matches)
          ?.lowercase(Locale.ROOT)
          ?: return null
        val versionDirectory = versionDirectory(update.version)
        if (!Files.isDirectory(versionDirectory, NOFOLLOW_LINKS)) return null

        val realRoot = root.toRealPath()
        val realVersionDirectory = versionDirectory.toRealPath()
        if (!realVersionDirectory.startsWith(realRoot)) return null
        val realStagedApp = validateVersionChild(
          path = stagedApp,
          versionDirectory = versionDirectory,
          realRoot = realRoot,
          realVersionDirectory = realVersionDirectory,
          isExpectedType = { Files.isDirectory(it, NOFOLLOW_LINKS) },
        ) ?: return null
        val realVerifiedDmg = validateVersionChild(
          path = verifiedDmg,
          versionDirectory = versionDirectory,
          realRoot = realRoot,
          realVersionDirectory = realVersionDirectory,
          isExpectedType = { Files.isRegularFile(it, NOFOLLOW_LINKS) },
        ) ?: return null
        update.copy(
          stagedApp = realStagedApp,
          verifiedDmg = realVerifiedDmg,
          verifiedDmgSha256 = verifiedDmgSha256,
        )
      }
      RebasedMacUpdateStrategy.HOMEBREW -> {
        if (update.stagedApp != null || update.verifiedDmg != null || update.verifiedDmgSha256 != null) return null
        val brewExecutable = update.brewExecutable?.takeIf(Path::isAbsolute)?.normalize() ?: return null
        if (!Files.isRegularFile(brewExecutable) || !Files.isExecutable(brewExecutable)) return null
        update.copy(brewExecutable = brewExecutable)
      }
    }
  }

  private fun validateVersionChild(
    path: Path,
    versionDirectory: Path,
    realRoot: Path,
    realVersionDirectory: Path,
    isExpectedType: (Path) -> Boolean,
  ): Path? {
    val pathRoot = when {
      path.startsWith(root) -> root
      path.startsWith(realRoot) -> realRoot
      else -> return null
    }
    val expectedVersionDirectory = if (pathRoot == root) versionDirectory else realVersionDirectory
    if (path == expectedVersionDirectory || !path.startsWith(expectedVersionDirectory)) return null
    if (hasSymbolicLinkComponent(pathRoot, path) || !isExpectedType(path)) return null

    val realPath = path.toRealPath()
    return realPath.takeIf { it.startsWith(realVersionDirectory) && it != realVersionDirectory }
  }

  private fun hasSymbolicLinkComponent(base: Path, path: Path): Boolean {
    var component = base
    if (Files.isSymbolicLink(component)) return true
    for (name in base.relativize(path)) {
      component = component.resolve(name)
      if (Files.isSymbolicLink(component)) return true
    }
    return false
  }

}

private data class InstallResultMarker(
  val status: String,
  val message: String,
  val backup: String,
  val version: String,
  val strategy: RebasedMacUpdateStrategy,
)

private fun moveFile(source: Path, target: Path, options: Array<out CopyOption>) {
  Files.move(source, target, *options)
}

private fun readInstallResultBytes(path: Path): ByteArray? {
  val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  if (!attributes.isRegularFile || attributes.size() > MAX_INSTALL_RESULT_SIZE) return null

  val bytes = Files.newByteChannel(path, setOf<OpenOption>(READ, NOFOLLOW_LINKS)).use { channel ->
    Channels.newInputStream(channel).use { input ->
      input.readNBytes(MAX_INSTALL_RESULT_SIZE + 1)
    }
  }
  if (bytes.size > MAX_INSTALL_RESULT_SIZE) return null
  return bytes
}

private fun deletePathRecursively(path: Path) {
  if (Files.isSymbolicLink(path)) {
    Files.deleteIfExists(path)
    return
  }
  Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
      Files.deleteIfExists(file)
      return FileVisitResult.CONTINUE
    }

    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
      if (exc != null) throw exc
      Files.deleteIfExists(dir)
      return FileVisitResult.CONTINUE
    }
  })
}

private fun deletePathRecursivelyForClaim(path: Path): Boolean {
  var claimed = false
  if (Files.isSymbolicLink(path)) return Files.deleteIfExists(path)
  Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
      val deleted = Files.deleteIfExists(file)
      if (file == path) claimed = deleted
      return FileVisitResult.CONTINUE
    }

    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
      if (exc is NoSuchFileException) return FileVisitResult.CONTINUE
      throw exc
    }

    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
      if (exc != null && exc !is NoSuchFileException) throw exc
      try {
        Files.delete(dir)
        if (dir == path) claimed = true
      }
      catch (_: NoSuchFileException) {
        // Another consumer deleted this node.
      }
      return FileVisitResult.CONTINUE
    }
  })
  return claimed
}

private fun Properties.optionalPath(key: String): Path? {
  val value = getProperty(key) ?: return null
  require(value.isNotBlank()) { "Blank path in $key" }
  return Path.of(value)
}

private val INVALID_VERSION_CHARACTER = Regex("[^0-9A-Za-z._-]")
private const val MAX_VERSION_DIRECTORY_PREFIX_LENGTH = 190
private const val PREPARED_STATE_FILE = "prepared.properties"
private const val INSTALL_RESULT_FILE = "install-result.properties"
private const val STATUS_PROPERTY = "status"
private const val MESSAGE_PROPERTY = "message"
private const val BACKUP_PROPERTY = "backup"
private const val VERSION_PROPERTY = "version"
private const val STRATEGY_PROPERTY = "strategy"
private const val STAGED_APP_PROPERTY = "stagedApp"
private const val VERIFIED_DMG_PROPERTY = "verifiedDmg"
private const val VERIFIED_DMG_SHA256_PROPERTY = "verifiedDmgSha256"
private const val BREW_EXECUTABLE_PROPERTY = "brewExecutable"
private const val RELEASE_PAGE_URL_PROPERTY = "releasePageUrl"
private const val SUCCESS_STATUS = "success"
private const val FAILED_STATUS = "failed"
private const val DIRECT_STRATEGY = "direct"
private const val HOMEBREW_STRATEGY = "homebrew"
private const val MAX_INSTALL_RESULT_SIZE = 64 * 1024
private const val REBASED_APP_NAME = "Rebased.app"
private const val REBASED_BACKUP_NAME = "Rebased.app.rebased-update-backup"
private const val ABANDONED_INSTALL_RESULT_CLAIM_AGE_MILLIS = 5 * 60 * 1000L
private val DIRECTORY_INSTALL_RESULT_CLAIM_LOCK = Any()
private const val INVALID_RESULT_MESSAGE = "The Rebased update installer result is invalid."
private const val INSTALLER_FAILURE_MESSAGE = "The Rebased update installer reported a failure."
private val INSTALL_RESULT_PROPERTIES = setOf(
  STATUS_PROPERTY,
  MESSAGE_PROPERTY,
  BACKUP_PROPERTY,
  VERSION_PROPERTY,
  STRATEGY_PROPERTY,
)
private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
