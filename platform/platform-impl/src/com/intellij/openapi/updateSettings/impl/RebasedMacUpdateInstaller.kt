// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.IdeBundle
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.SuperUserStatus
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Locale

internal class RebasedMacUpdateInstaller(
  updateRoot: Path,
  private val revalidateVerifiedDmg: (PreparedRebasedMacUpdate, String, ProgressIndicator) -> Path,
  private val isTargetParentWritable: (Path) -> Boolean = Files::isWritable,
  private val isSuperUser: () -> Boolean = { SuperUserStatus.isSuperUser },
  private val sudoCommand: (GeneralCommandLine, String) -> GeneralCommandLine = { commandLine, prompt ->
    ExecUtil.sudoCommand(commandLine, prompt)
  },
  private val tools: RebasedMacInstallerTools = RebasedMacInstallerTools(),
) {
  private val updateRoot = updateRoot.toAbsolutePath().normalize()

  constructor(
    updateRoot: Path,
    preparer: RebasedMacUpdatePreparer,
  ) : this(updateRoot, preparer::revalidateVerifiedDmg)

  fun command(
    prepared: PreparedRebasedMacUpdate,
    targetApp: Path,
    trustedDigest: String?,
    indicator: ProgressIndicator,
    elevate: Boolean,
  ): List<String> {
    indicator.checkCanceled()
    require(prepared.strategy != RebasedMacUpdateStrategy.HOMEBREW || (!elevate && !isSuperUser())) {
      "A Homebrew Rebased update must run as the login user"
    }
    val realTarget = requireTargetApp(
      targetApp,
      requireWritableParent = prepared.strategy == RebasedMacUpdateStrategy.DIRECT && !elevate,
    )
    val rawCommand = when (prepared.strategy) {
      RebasedMacUpdateStrategy.DIRECT -> {
        val digest = trustedDigest
          ?.takeIf(SHA256_PATTERN::matches)
          ?.lowercase(Locale.ROOT)
          ?: throw RebasedMacUpdateException.Verification(
            "A valid trusted Rebased DMG SHA-256 digest is required",
          )
        val verifiedDmg = revalidateVerifiedDmg(prepared, trustedDigest, indicator)
        val realRoot = requireSafeDirectory(updateRoot, "update root", writable = true)
        val realDmg = requireCanonicalFile(verifiedDmg, realRoot)
        val resultFile = realRoot.resolve(RESULT_FILE_NAME)
        sanitizedBashCommand(
          installerScript(tools),
          realRoot.resolve(SCRIPT_NAME).toString(),
          DIRECT_MODE,
          realTarget.toString(),
          realDmg.toString(),
          digest,
          prepared.version,
          if (elevate) STDOUT_RESULT_PATH else resultFile.toString(),
        )
      }
      RebasedMacUpdateStrategy.HOMEBREW -> {
        if (trustedDigest != null) {
          throw RebasedMacUpdateException.Verification(
            "A Homebrew Rebased update must not have a DMG digest",
          )
        }
        val brew = requireExecutable(prepared.brewExecutable)
        val realRoot = requireSafeDirectory(updateRoot, "update root", writable = true)
        sanitizedBashCommand(
          installerScript(tools),
          realRoot.resolve(SCRIPT_NAME).toString(),
          HOMEBREW_MODE,
          realTarget.toString(),
          brew.toString(),
          prepared.version,
          realRoot.resolve(RESULT_FILE_NAME).toString(),
        )
      }
    }
    Files.deleteIfExists(updateRoot.resolve(RESULT_FILE_NAME))
    return if (elevate) {
      val elevatedCommand = sudoCommand(
        GeneralCommandLine(rawCommand),
        IdeBundle.message("rebased.mac.update.install.authorization.prompt"),
      ).getCommandLineList(null)
      sanitizedBashCommand(
        ELEVATED_RESULT_WRAPPER_SCRIPT,
        SCRIPT_NAME,
        updateRoot.resolve(RESULT_FILE_NAME).toString(),
      ) + elevatedCommand
    }
    else {
      rawCommand
    }
  }

  private fun requireTargetApp(targetApp: Path, requireWritableParent: Boolean): Path {
    require(targetApp.isAbsolute) { "The Rebased target application path must be absolute" }
    val normalized = targetApp.normalize()
    require(normalized.fileName?.toString() == APP_NAME) { "The Rebased target must be an application bundle" }
    require(!Files.isSymbolicLink(normalized) && Files.isDirectory(normalized, NOFOLLOW_LINKS)) {
      "The Rebased target application is missing or symbolic"
    }
    val parent = normalized.parent ?: throw IllegalArgumentException("The Rebased target has no parent")
    val realParent = requireSafeDirectory(parent, "target parent", writable = false)
    require(!requireWritableParent || isTargetParentWritable(realParent)) { "The Rebased target parent is not writable" }
    val realTarget = normalized.toRealPath()
    require(realTarget == normalized) { "The Rebased target application path must be canonical" }
    return realTarget
  }

  private fun requireCanonicalFile(path: Path, root: Path): Path {
    require(path.isAbsolute && !Files.isSymbolicLink(path) && Files.isRegularFile(path, NOFOLLOW_LINKS)) {
      "The verified Rebased DMG is missing or symbolic"
    }
    val normalized = path.normalize()
    val realPath = normalized.toRealPath()
    require(realPath == normalized && realPath.startsWith(root) && realPath != root) {
      "The verified Rebased DMG must be canonical and contained by the update root"
    }
    return realPath
  }

  private fun requireExecutable(path: Path?): Path {
    if (path == null || !path.isAbsolute) {
      throw RebasedMacUpdateException.HomebrewUnavailable("The Homebrew executable path must be absolute")
    }
    val normalized = path.normalize()
    if (!Files.isRegularFile(normalized) || !Files.isExecutable(normalized)) {
      throw RebasedMacUpdateException.HomebrewUnavailable(
        "The stored Homebrew executable is no longer usable",
      )
    }
    return normalized
  }

  private fun requireSafeDirectory(path: Path, description: String, writable: Boolean): Path {
    require(path.isAbsolute && !Files.isSymbolicLink(path) && Files.isDirectory(path, NOFOLLOW_LINKS)) {
      "The Rebased $description is missing or symbolic"
    }
    val normalized = path.normalize()
    val realPath = normalized.toRealPath()
    require(realPath == normalized) { "The Rebased $description must be canonical" }
    require(!writable || Files.isWritable(realPath)) { "The Rebased $description is not writable" }
    return realPath
  }
}

private fun sanitizedBashCommand(vararg arguments: String): List<String> =
  SANITIZED_BASH_PREFIX + arguments.asList()

internal data class RebasedMacInstallerTools(
  val shasum: Path = Path.of("/usr/bin/shasum"),
  val hdiutil: Path = Path.of("/usr/bin/hdiutil"),
  val ditto: Path = Path.of("/usr/bin/ditto"),
  val plutil: Path = Path.of("/usr/bin/plutil"),
  val lipo: Path = Path.of("/usr/bin/lipo"),
	  val codesign: Path = Path.of("/usr/bin/codesign"),
  val uname: Path = Path.of("/usr/bin/uname"),
  val xattr: Path = Path.of("/usr/bin/xattr"),
  val copy: Path = Path.of("/bin/cp"),
  val move: Path = Path.of("/bin/mv"),
  val remove: Path = Path.of("/bin/rm"),
  val chmod: Path = Path.of("/bin/chmod"),
  val mktemp: Path = Path.of("/usr/bin/mktemp"),
  val rmdir: Path = Path.of("/bin/rmdir"),
  val osascript: Path = Path.of("/usr/bin/osascript"),
)

private fun installerScript(tools: RebasedMacInstallerTools): String {
  val template = INSTALLER_SCRIPT_TEMPLATE.replace("@D@", "$")
  return sequenceOf(
    "__SHASUM__" to tools.shasum,
    "__HDIUTIL__" to tools.hdiutil,
    "__DITTO__" to tools.ditto,
    "__PLUTIL__" to tools.plutil,
    "__LIPO__" to tools.lipo,
	    "__CODESIGN__" to tools.codesign,
    "__UNAME__" to tools.uname,
    "__XATTR__" to tools.xattr,
    "__CP__" to tools.copy,
    "__MV__" to tools.move,
    "__RM__" to tools.remove,
    "__CHMOD__" to tools.chmod,
    "__MKTEMP__" to tools.mktemp,
    "__RMDIR__" to tools.rmdir,
    "__OSASCRIPT__" to tools.osascript,
  ).fold(template) { script, (placeholder, path) ->
    script.replace(placeholder, shellQuote(path.toString()))
  }
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

private val INSTALLER_SCRIPT_TEMPLATE = """
  #!/bin/bash
  set -u
  IFS=@D@' \t\n'
  export LC_ALL=C
  umask 077

  SHASUM=__SHASUM__
  HDIUTIL=__HDIUTIL__
  DITTO=__DITTO__
  PLUTIL=__PLUTIL__
  LIPO=__LIPO__
	  CODESIGN=__CODESIGN__
  UNAME=__UNAME__
  XATTR=__XATTR__
  CP=__CP__
  MV=__MV__
  RM=__RM__
  CHMOD=__CHMOD__
  MKTEMP=__MKTEMP__
  RMDIR=__RMDIR__
  OSASCRIPT=__OSASCRIPT__

  attach_attempted=0
  attached=0
  attach_device=''
  mount_point=''
  private_dir=''
  result_file=''
  expected_version=''
  strategy=''

  canonical_directory() {
    (cd -P -- "@D@1" 2>/dev/null && pwd -P)
  }

  escape_property() {
    local value="@D@1"
    value=@D@{value//\\/\\\\}
    value=@D@{value//@D@'\r'/\\r}
    value=@D@{value//@D@'\n'/\\n}
    value=@D@{value//@D@'\t'/\\t}
    value=@D@{value//@D@'\f'/\\f}
    printf '%s' "@D@value"
  }

  write_result() {
    local status="@D@1"
    local message="@D@2"
    local backup_value="@D@3"
    local version_value="@D@4"
    local strategy_value="@D@5"
    local result_tmp
    if [[ "@D@result_file" == "-" ]]; then
      printf 'status=%s\nmessage=%s\nbackup=%s\nversion=%s\nstrategy=%s\n' \
        "@D@(escape_property "@D@status")" \
        "@D@(escape_property "@D@message")" \
        "@D@(escape_property "@D@backup_value")" \
        "@D@(escape_property "@D@version_value")" \
        "@D@(escape_property "@D@strategy_value")"
      return
    fi
    result_tmp=@D@("@D@MKTEMP" "@D@{result_file}.tmp.XXXXXX") || return 1
    if ! printf 'status=%s\nmessage=%s\nbackup=%s\nversion=%s\nstrategy=%s\n' \
      "@D@(escape_property "@D@status")" \
      "@D@(escape_property "@D@message")" \
      "@D@(escape_property "@D@backup_value")" \
      "@D@(escape_property "@D@version_value")" \
      "@D@(escape_property "@D@strategy_value")" >"@D@result_tmp"; then
      "@D@RM" -f -- "@D@result_tmp"
      return 1
    fi
    if ! "@D@CHMOD" 644 "@D@result_tmp" || ! "@D@MV" -f "@D@result_tmp" "@D@result_file"; then
      "@D@RM" -f -- "@D@result_tmp"
      return 1
    fi
  }

  notify_success() {
    "@D@OSASCRIPT" -e 'display notification "The update was installed successfully." with title "Rebased Update"' \
      >/dev/null 2>&1 || true
  }

  notify_failure() {
    "@D@OSASCRIPT" -e 'display notification "The update could not be installed. The previous app was preserved when possible." with title "Rebased Update"' \
      >/dev/null 2>&1 || true
  }

  write_failure() {
    local message="@D@1"
    local backup_value="@D@2"
    if ! cleanup; then
      message="@D@message; detach/cleanup failed"
    fi
    trap - EXIT
    if write_result failed "@D@message" "@D@backup_value" "@D@expected_version" "@D@strategy"; then
      notify_failure
      exit 0
    fi
    exit 1
  }

  write_success() {
    local backup_value="@D@1"
    if ! cleanup; then
      trap - EXIT
      if write_result failed \
        "The update was installed, but the verified DMG could not be detached" \
        "@D@backup_value" "@D@expected_version" "@D@strategy"; then
        notify_failure
        exit 0
      fi
      exit 1
    fi
    trap - EXIT
    if write_result success "Update installed successfully" "@D@backup_value" "@D@expected_version" "@D@strategy"; then
      notify_success
      exit 0
    fi
    exit 1
  }

  cleanup_mount() {
    local detach_target
    if [[ "@D@attach_attempted" -eq 0 && "@D@attached" -eq 0 ]]; then
      return 0
    fi
    detach_target="@D@mount_point"
    if [[ -n "@D@attach_device" ]]; then
      detach_target="@D@attach_device"
    fi
    if "@D@HDIUTIL" detach "@D@detach_target" >/dev/null 2>&1 ||
       "@D@HDIUTIL" detach -force "@D@detach_target" >/dev/null 2>&1; then
      attach_attempted=0
      attached=0
      attach_device=''
      return 0
    fi
    return 1
  }

  cleanup_private_dir() {
    local suffix
    local real_private_dir
    [[ -n "@D@private_dir" && "@D@private_dir" == /private/var/tmp/rebased-update.* ]] || return 0
    suffix="@D@{private_dir#/private/var/tmp/rebased-update.}"
    [[ -n "@D@suffix" && "@D@suffix" != */* && -d "@D@private_dir" && ! -L "@D@private_dir" ]] || return 0
    real_private_dir=@D@(canonical_directory "@D@private_dir") || return 1
    [[ "@D@real_private_dir" == "@D@private_dir" ]] || return 1
    "@D@RM" -rf -- "@D@private_dir"
  }

  cleanup() {
    cleanup_mount || return 1
    cleanup_private_dir
  }
  trap cleanup EXIT

  plist_value() {
    "@D@PLUTIL" -extract "@D@2" raw -o - "@D@1" 2>/dev/null
  }

  validate_homebrew_app() {
    local app="@D@1"
    local plist
    local bundle_id
    local executable_name
    local executable
    [[ -d "@D@app" && ! -L "@D@app" ]] || return 1
    [[ -d "@D@app/Contents" && ! -L "@D@app/Contents" ]] || return 1
    [[ -d "@D@app/Contents/MacOS" && ! -L "@D@app/Contents/MacOS" ]] || return 1
    plist="@D@app/Contents/Info.plist"
    [[ -f "@D@plist" && ! -L "@D@plist" ]] || return 1
    bundle_id=@D@(plist_value "@D@plist" CFBundleIdentifier) || return 1
    [[ "@D@bundle_id" == "io.github.detachhead.rebased" ]] || return 1
    executable_name=@D@(plist_value "@D@plist" CFBundleExecutable) || return 1
    [[ "@D@executable_name" == "rebased" ]] || return 1
    executable="@D@app/Contents/MacOS/@D@executable_name"
    [[ -f "@D@executable" && ! -L "@D@executable" && -x "@D@executable" ]]
  }

  validate_homebrew_app_version() {
    local app="@D@1"
    local required_version="@D@2"
    local plist
    local actual_version
    validate_homebrew_app "@D@app" || return 1
    plist="@D@app/Contents/Info.plist"
    actual_version=@D@(plist_value "@D@plist" CFBundleShortVersionString) || return 1
    [[ "@D@actual_version" == "@D@required_version" ]]
  }

  validate_homebrew_receipt() {
    local brew="@D@1"
    local required_version="@D@2"
    local receipt_output
    local cask
    local version
    local extra
    receipt_output=@D@("@D@brew" list --cask --versions rebased 2>/dev/null) || return 1
    [[ -n "@D@receipt_output" && "@D@receipt_output" != *@D@'\n'* ]] || return 1
    read -r cask version extra <<<"@D@receipt_output"
    [[ "@D@cask" == "rebased" && "@D@version" == "@D@required_version" && -z "@D@extra" ]]
  }

  validate_direct_app() {
    local app="@D@1"
    local required_version="@D@2"
    local plist
    local actual_version
    local executable_name
    local executable
    local machine
    local required_arch
    local architectures
    validate_homebrew_app "@D@app" || return 1
    plist="@D@app/Contents/Info.plist"
    actual_version=@D@(plist_value "@D@plist" CFBundleShortVersionString) || return 1
    [[ "@D@actual_version" == "@D@required_version" ]] || return 1
    executable_name=@D@(plist_value "@D@plist" CFBundleExecutable) || return 1
    executable="@D@app/Contents/MacOS/@D@executable_name"
    machine=@D@("@D@UNAME" -m) || return 1
    case "@D@machine" in
      arm64|aarch64) required_arch=arm64 ;;
      x86_64|amd64) required_arch=x86_64 ;;
      *) return 1 ;;
    esac
    architectures=@D@("@D@LIPO" -archs "@D@executable" 2>/dev/null) || return 1
	    [[ " @D@architectures " == *" @D@required_arch "* ]] || return 1
	    "@D@CODESIGN" -dv "@D@app" >/dev/null 2>&1
  }

  validate_target_location() {
    local target="@D@1"
    local parent
    local real_parent
    [[ "@D@target" == /* && "@D@{target##*/}" == "Rebased.app" ]] || return 1
    [[ -d "@D@target" && ! -L "@D@target" ]] || return 1
    parent="@D@{target%/*}"
    [[ -d "@D@parent" && ! -L "@D@parent" ]] || return 1
    real_parent=@D@(canonical_directory "@D@parent") || return 1
    [[ "@D@real_parent" == "@D@parent" ]]
  }

  validate_direct_target_location() {
    local target="@D@1"
    local parent
    validate_target_location "@D@target" || return 1
    parent="@D@{target%/*}"
    [[ -w "@D@parent" ]]
  }

  validate_result_path() {
    [[ "@D@result_file" == "-" || "@D@result_file" == "@D@script_dir/install-result.properties" ]]
  }

  validate_pinned_dmg() {
    local dmg="@D@1"
    local parent
    local real_parent
    [[ "@D@dmg" == "@D@script_dir/"* && -f "@D@dmg" && ! -L "@D@dmg" ]] || return 1
    parent="@D@{dmg%/*}"
    real_parent=@D@(canonical_directory "@D@parent") || return 1
    [[ "@D@real_parent" == "@D@parent" && "@D@real_parent" == "@D@script_dir"* ]]
  }

  restore_backup() {
    local target="@D@1"
    local backup="@D@2"
    [[ -d "@D@backup" && ! -L "@D@backup" ]] || return 1
    if [[ -e "@D@target" || -L "@D@target" ]]; then
      "@D@RM" -rf -- "@D@target" || return 1
    fi
    "@D@MV" "@D@backup" "@D@target"
  }

  install_direct() {
    local target="@D@1"
    local dmg="@D@2"
    local trusted_digest="@D@3"
    local hash_output
    local actual_digest
    local protected_dmg
    local attach_output
    local attach_status
    local source_app
    local candidate="@D@{target}.rebased-update-candidate"
    local backup="@D@{target}.rebased-update-backup"
    local failure_backup

    expected_version="@D@4"
    result_file="@D@5"
    strategy=direct
    validate_result_path || exit 2
    [[ "@D@trusted_digest" =~ ^[0-9a-f]{64}@D@ ]] ||
      write_failure "The trusted DMG digest is invalid" ""
    validate_direct_target_location "@D@target" ||
      write_failure "The target application path is invalid" ""
    validate_homebrew_app "@D@target" ||
      write_failure "The existing application is invalid" ""
    validate_pinned_dmg "@D@dmg" ||
      write_failure "The verified DMG path is invalid" ""

    private_dir=@D@("@D@MKTEMP" -d "/private/var/tmp/rebased-update.XXXXXX") ||
      write_failure "A protected update directory could not be created" ""
    [[ "@D@private_dir" == /private/var/tmp/rebased-update.* && -d "@D@private_dir" && ! -L "@D@private_dir" ]] ||
      write_failure "The protected update directory is invalid" ""
    "@D@CHMOD" 700 "@D@private_dir" ||
      write_failure "The protected update directory could not be secured" ""
    protected_dmg="@D@private_dir/rebased-update.dmg"
    "@D@CP" "@D@dmg" "@D@protected_dmg" ||
      write_failure "The verified DMG could not be protected" ""
    [[ -f "@D@protected_dmg" && ! -L "@D@protected_dmg" ]] ||
      write_failure "The protected DMG is invalid" ""

    hash_output=@D@("@D@SHASUM" -a 256 "@D@protected_dmg" 2>/dev/null) ||
      write_failure "The verified DMG could not be hashed" ""
    read -r actual_digest _ <<<"@D@hash_output"
    [[ "@D@actual_digest" =~ ^[0-9a-f]{64}@D@ && "@D@actual_digest" == "@D@trusted_digest" ]] ||
      write_failure "The verified DMG digest no longer matches" ""

    mount_point=@D@("@D@MKTEMP" -d "@D@private_dir/mount.XXXXXX") ||
      write_failure "An isolated mount point could not be created" ""
    attach_attempted=1
    attach_output=@D@("@D@HDIUTIL" attach -readonly -nobrowse -noautoopen -noautofsck -plist \
      -mountpoint "@D@mount_point" "@D@protected_dmg")
    attach_status="@D@?"
    attach_device=@D@(printf '%s' "@D@attach_output" |
      "@D@PLUTIL" -extract system-entities.0.dev-entry raw -o - - 2>/dev/null) || attach_device=''
    if [[ "@D@attach_status" -ne 0 ]]; then
      write_failure "The verified DMG could not be mounted" ""
    fi
    attached=1

    source_app="@D@mount_point/Rebased.app"
    validate_direct_app "@D@source_app" "@D@expected_version" ||
      write_failure "The mounted application failed validation" ""

    "@D@RM" -rf -- "@D@candidate" "@D@backup" ||
      write_failure "Old update work items could not be removed" ""
    if ! "@D@DITTO" "@D@source_app" "@D@candidate"; then
      "@D@RM" -rf -- "@D@candidate"
      write_failure "The update candidate could not be copied" ""
    fi
    if ! validate_direct_app "@D@candidate" "@D@expected_version"; then
      "@D@RM" -rf -- "@D@candidate"
      write_failure "The copied update candidate failed validation" ""
    fi
    if ! "@D@XATTR" -dr com.apple.quarantine "@D@candidate"; then
      "@D@RM" -rf -- "@D@candidate"
      write_failure "Quarantine could not be removed from the update candidate" ""
    fi

    if ! "@D@MV" "@D@target" "@D@backup"; then
      "@D@RM" -rf -- "@D@candidate"
      write_failure "The existing application could not be backed up" ""
    fi
    if ! "@D@MV" "@D@candidate" "@D@target"; then
      failure_backup=''
      restore_backup "@D@target" "@D@backup" || true
      if [[ -d "@D@backup" && ! -L "@D@backup" ]]; then
        failure_backup="@D@backup"
      fi
      "@D@RM" -rf -- "@D@candidate"
      write_failure "The update candidate could not replace the application" "@D@failure_backup"
    fi
    if ! validate_direct_app "@D@target" "@D@expected_version"; then
      failure_backup=''
      restore_backup "@D@target" "@D@backup" || true
      if [[ -d "@D@backup" && ! -L "@D@backup" ]]; then
        failure_backup="@D@backup"
      fi
      write_failure "The installed application failed validation" "@D@failure_backup"
    fi
    write_success "@D@backup"
  }

  install_homebrew() {
    [[ "@D@EUID" -eq 0 ]] && exit 2
    local target="@D@1"
    local brew="@D@2"

    expected_version="@D@3"
    result_file="@D@4"
    strategy=homebrew
    validate_result_path || exit 2
    validate_target_location "@D@target" ||
      write_failure "The target application path is invalid" ""
    validate_homebrew_app "@D@target" ||
      write_failure "The existing application is invalid" ""
    [[ "@D@brew" == /* && -f "@D@brew" && -x "@D@brew" ]] ||
      write_failure "The stored Homebrew executable is unavailable" ""

    "@D@brew" upgrade --cask rebased || true
    if validate_homebrew_app_version "@D@target" "@D@expected_version" &&
       validate_homebrew_receipt "@D@brew" "@D@expected_version"; then
      write_success ""
    fi
    write_failure "Homebrew could not install a valid Rebased application" ""
  }

  script_parent="@D@{0%/*}"
  [[ "@D@script_parent" != "@D@0" ]] || exit 2
  script_dir=@D@(canonical_directory "@D@script_parent") || exit 2

  [[ "@D@#" -ge 1 ]] || exit 2
  mode="@D@1"
  shift
  case "@D@mode" in
    direct)
      [[ "@D@#" -eq 5 ]] || exit 2
      install_direct "@D@@"
      ;;
    homebrew)
      [[ "@D@#" -eq 4 ]] || exit 2
      install_homebrew "@D@@"
      ;;
    *)
      exit 2
      ;;
  esac
""".trimIndent() + "\n"

private val ELEVATED_RESULT_WRAPPER_SCRIPT = """
  set -u
  IFS=${'$'}' \t\n'
  export LC_ALL=C
  umask 077

  [[ "${'$'}#" -ge 2 ]] || exit 2
  result_file="${'$'}1"
  shift
  result_output=$("${'$'}@")
  result_status="${'$'}?"
  [[ "${'$'}result_status" -eq 0 ]] || exit "${'$'}result_status"
  [[ -n "${'$'}result_output" ]] || exit 1
  result_output=${'$'}{result_output//${'$'}'\r'/${'$'}'\n'}

  result_tmp=$(/usr/bin/mktemp "${'$'}{result_file}.tmp.XXXXXX") || exit 1
  if ! printf '%s\n' "${'$'}result_output" >"${'$'}result_tmp"; then
    /bin/rm -f -- "${'$'}result_tmp"
    exit 1
  fi
  if ! /bin/chmod 644 "${'$'}result_tmp" || ! /bin/mv -f "${'$'}result_tmp" "${'$'}result_file"; then
    /bin/rm -f -- "${'$'}result_tmp"
    exit 1
  fi
""".trimIndent() + "\n"

private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
private const val APP_NAME = "Rebased.app"
private const val SCRIPT_NAME = "rebased-update-installer.sh"
private const val RESULT_FILE_NAME = "install-result.properties"
private const val STDOUT_RESULT_PATH = "-"
private const val DIRECT_MODE = "direct"
private const val HOMEBREW_MODE = "homebrew"
private val SANITIZED_BASH_PREFIX = listOf(
  "/usr/bin/env",
  "-i",
  "HOME=${System.getProperty("user.home", "")}",
  "PATH=/usr/bin:/bin:/usr/sbin:/sbin",
  "LC_ALL=C",
  "/bin/bash",
  "-c",
)
