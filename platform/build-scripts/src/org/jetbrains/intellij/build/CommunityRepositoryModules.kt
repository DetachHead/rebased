// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("LiftReturnOrAssignment", "ReplaceJavaStaticMethodWithKotlinAnalog")

package org.jetbrains.intellij.build

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.intellij.build.dependencies.BuildDependenciesDownloader
import org.jetbrains.intellij.build.dependencies.BuildDependenciesExtractOptions
import org.jetbrains.intellij.build.impl.BundledMavenDownloader
import org.jetbrains.intellij.build.impl.LibraryPackMode
import org.jetbrains.intellij.build.impl.ModuleItem
import org.jetbrains.intellij.build.impl.PluginLayout
import org.jetbrains.intellij.build.impl.PluginLayout.Companion.plugin
import org.jetbrains.intellij.build.impl.PluginLayout.Companion.pluginAuto
import org.jetbrains.intellij.build.impl.PluginLayout.Companion.pluginAutoWithCustomDirName
import org.jetbrains.intellij.build.impl.PluginVersionEvaluatorResult
import org.jetbrains.intellij.build.impl.ProjectLibraryData
import org.jetbrains.intellij.build.impl.SUPPORTED_DISTRIBUTIONS
import org.jetbrains.intellij.build.impl.SupportedDistribution
import org.jetbrains.intellij.build.impl.patchOsSpecificPluginXml
import org.jetbrains.intellij.build.impl.projectStructureMapping.DistributionFileEntry
import org.jetbrains.intellij.build.impl.projectStructureMapping.ProjectLibraryEntry
import org.jetbrains.intellij.build.io.copyDir
import org.jetbrains.intellij.build.kotlin.CommunityKotlinPluginBuilder
import org.jetbrains.intellij.build.python.PythonCommunityPluginModules
import org.jetbrains.intellij.build.telemetry.TraceManager.spanBuilder
import org.jetbrains.intellij.build.telemetry.use
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

object CommunityRepositoryModules {
  /**
   * Specifies non-trivial layout for all plugins that sources are located in 'community' and 'contrib' repositories
   */
  val COMMUNITY_REPOSITORY_PLUGINS: PersistentList<PluginLayout> = persistentListOf(
    plugin("intellij.ant") { spec ->
      spec.mainJarName = "antIntegration.jar"
      spec.withModule("intellij.ant.jps", "ant-jps.jar")

      spec.withGeneratedResources { dir, buildContext ->
        copyAnt(mainModule = spec.mainModule, pluginDir = dir, context = buildContext)
      }
    },
    plugin("intellij.laf.macos") { spec ->
      spec.bundlingRestrictions.supportedOs = persistentListOf(OsFamily.MACOS)
    },
    plugin("intellij.webp") { spec ->
      spec.withPlatformBin(OsFamily.WINDOWS, JvmArchitecture.x64, WindowsLibcImpl.DEFAULT, "plugins/webp/lib/libwebp/win", "lib/libwebp/win")
      spec.withPlatformBin(OsFamily.MACOS, JvmArchitecture.x64, MacLibcImpl.DEFAULT, "plugins/webp/lib/libwebp/mac", "lib/libwebp/mac")
      spec.withPlatformBin(OsFamily.MACOS, JvmArchitecture.aarch64, MacLibcImpl.DEFAULT, "plugins/webp/lib/libwebp/mac", "lib/libwebp/mac")
      spec.withPlatformBin(OsFamily.LINUX, JvmArchitecture.x64, LinuxLibcImpl.GLIBC, "plugins/webp/lib/libwebp/linux", "lib/libwebp/linux")
    },
    plugin("intellij.webp") { spec ->
      spec.bundlingRestrictions.marketplace = true
      spec.withResource("lib/libwebp/linux", "lib/libwebp/linux")
      spec.withResource("lib/libwebp/mac", "lib/libwebp/mac")
      spec.withResource("lib/libwebp/win", "lib/libwebp/win")
    },
    plugin("intellij.laf.win10") { spec ->
      spec.bundlingRestrictions.supportedOs = persistentListOf(OsFamily.WINDOWS)
    },
    plugin("intellij.java.guiForms.designer") { spec ->
      spec.directoryName = "uiDesigner"
      spec.mainJarName = "uiDesigner.jar"
      spec.withModule("intellij.java.guiForms.jps", "jps/java-guiForms-jps.jar")
    },
    CommunityKotlinPluginBuilder.kotlinPlugin(),
    pluginAuto(listOf("intellij.vcs.git")) { spec ->
      spec.withModule("intellij.vcs.git.rt", "git4idea-rt.jar")
    },
    pluginAuto(listOf("intellij.xpath")) { spec ->
      spec.withModule("intellij.xpath.rt", "rt/xslt-rt.jar")
    },
    pluginAutoWithCustomDirName("intellij.tasks.core") { spec ->
      spec.directoryName = "tasks"
      spec.withModule("intellij.tasks")
      spec.withModule("intellij.tasks.compatibility")
      spec.withModule("intellij.tasks.java")
    },

    pluginAuto(
      listOf(
        "intellij.gradle.plugin",
        "intellij.gradle",
        "intellij.gradle.common",
      )
    ) { spec ->
      spec.withModule("intellij.gradle.toolingProxy", "gradle-tooling-proxy.jar")
      spec.withModule("intellij.gradle.toolingExtension", "gradle-tooling-extension-api.jar")
      spec.withModule("intellij.gradle.toolingExtension.impl", "gradle-tooling-extension-impl.jar")
      spec.withModule("intellij.libraries.groovy", "groovy.jar")
      spec.withModule("intellij.libraries.groovy.ant", "groovy-ant.jar")
      spec.withProjectLibrary("Gradle", LibraryPackMode.STANDALONE_SEPARATE)
      spec.withProjectLibrary("Ant", "ant", LibraryPackMode.STANDALONE_SEPARATE)
    },
    pluginAuto(listOf("intellij.gradle.java.plugin", "intellij.gradle.java", "intellij.gradle.jps")) {
      it.excludeProjectLibrary("Ant")
      it.excludeProjectLibrary("Gradle")
    },
    pluginAuto("intellij.junit") { spec ->
      spec.withModule("intellij.junit.rt", "junit-rt.jar")
      spec.withModule("intellij.junit.v5.rt", "junit5-rt.jar")
      spec.withModule("intellij.junit.v6.rt", "junit6-rt.jar")
    },
    plugin("intellij.testng") { spec ->
      spec.mainJarName = "testng-plugin.jar"
      spec.withModule("intellij.testng.rt", "testng-rt.jar")
      spec.withProjectLibrary("TestNG")
    },
    pluginAuto(listOf("intellij.devkit")) { spec ->
      spec.withModule("intellij.devkit.jps")

      spec.bundlingRestrictions.includeInDistribution = PluginDistribution.NOT_FOR_PUBLIC_BUILDS
    },
    pluginAuto(listOf("intellij.eclipse")) { spec ->
      spec.withModule("intellij.eclipse.jps", "eclipse-jps.jar")
      spec.withModule("intellij.eclipse.common", "eclipse-common.jar")
    },
    plugin("intellij.java.coverage") { spec ->
      spec.withModule("intellij.java.coverage.rt")
      // explicitly pack JaCoCo as a separate JAR
      spec.withModuleLibrary(libraryName = "JaCoCo", moduleName = "intellij.java.coverage", relativeOutputPath = "jacoco.jar")
    },
    plugin("intellij.java.decompiler") { spec ->
      spec.directoryName = "java-decompiler"
      spec.mainJarName = "java-decompiler.jar"
      spec.withModule("intellij.java.decompiler.engine", spec.mainJarName)
    },
    javaFXPlugin("intellij.javaFX.community"),
    pluginAuto("intellij.terminal") { spec ->
      spec.withModule("intellij.terminal.completion")
      spec.withResource("resources/shell-integrations", "shell-integrations")
    },
    pluginAuto(listOf("intellij.textmate.plugin")) { spec ->
      spec.withResourceFromModule("intellij.textmate", "lib/bundles", "lib/bundles")
    },
    PythonCommunityPluginModules.pythonCommunityPluginLayout(),
    pluginAuto(listOf("intellij.completionMlRankingModels")) { spec ->
      spec.bundlingRestrictions.includeInDistribution = PluginDistribution.NOT_FOR_RELEASE
    },
    pluginAuto(listOf("intellij.statsCollector")) { spec ->
      spec.bundlingRestrictions.includeInDistribution = PluginDistribution.NOT_FOR_RELEASE
    },
    pluginAuto(listOf("intellij.findUsagesMl")) { spec ->
      spec.bundlingRestrictions.includeInDistribution = PluginDistribution.NOT_FOR_RELEASE
    },
    pluginAuto(listOf("intellij.lombok", "intellij.lombok.generated")),
    pluginAuto(listOf("intellij.performanceTesting.ui")),
    pluginAuto(listOf("intellij.vcs.github")),
    pluginAuto(listOf("intellij.vcs.gitlab")),
    pluginAuto(listOf("intellij.compilation.charts")) { spec ->
      spec.withModule("intellij.compilation.charts.jps")
    },
    pluginAuto("intellij.java.jshell") { spec ->
      spec.withModule("intellij.java.jshell.protocol", "jshell-protocol.jar")
      spec.withModuleLibrary("jshell-frontend", "intellij.java.jshell.execution", "jshell-frontend.jar")
    },
    *allJcefPlugins()
  )

  val CONTRIB_REPOSITORY_PLUGINS: List<PluginLayout> = java.util.List.of(
    pluginAuto("intellij.errorProne") { spec ->
      spec.withModule("intellij.errorProne.jps", "jps/errorProne-jps.jar")
    },
    pluginAuto("intellij.cucumber.java") { spec ->
      spec.withModule("intellij.cucumber.jvmFormatter", "cucumber-jvmFormatter.jar")
      spec.withModule("intellij.cucumber.jvmFormatter3", "cucumber-jvmFormatter3.jar")
      spec.withModule("intellij.cucumber.jvmFormatter4", "cucumber-jvmFormatter4.jar")
      spec.withModule("intellij.cucumber.jvmFormatter5", "cucumber-jvmFormatter5.jar")
    },
    pluginAuto("intellij.serial.monitor") { spec ->
      // jSerialComm java JAR - Remember to update the binary dependency when updating to a new version!
      spec.withProjectLibrary("jetbrains.intellij.deps.jSerialComm", LibraryPackMode.STANDALONE_SEPARATE)

      // jSerialComm native library
      spec.withGeneratedResources { targetDir, context ->
        val uri = URI.create("https://packages.jetbrains.team/files/p/ij/intellij-build-dependencies/jSerialComm/9a7813435b79aa2e23c7f2a78f1b66b48c0504c4/jSerialComm.zip")
        val downloaded = BuildDependenciesDownloader.downloadFileToCacheLocation(context.paths.communityHomeDirRoot, uri)
        BuildDependenciesDownloader.extractFile(downloaded, targetDir.resolve("bin"), context.paths.communityHomeDirRoot)
      }
    },
  )

  fun allJcefPlugins(): Array<PluginLayout> {
    val supportedOsArch = listOf(
      SupportedDistribution(os = OsFamily.MACOS, arch = JvmArchitecture.x64, MacLibcImpl.DEFAULT),
      SupportedDistribution(os = OsFamily.MACOS, arch = JvmArchitecture.aarch64, MacLibcImpl.DEFAULT),
      SupportedDistribution(os = OsFamily.WINDOWS, arch = JvmArchitecture.x64, WindowsLibcImpl.DEFAULT),
      SupportedDistribution(os = OsFamily.WINDOWS, arch = JvmArchitecture.aarch64, WindowsLibcImpl.DEFAULT),
      SupportedDistribution(os = OsFamily.LINUX, arch = JvmArchitecture.x64, LinuxLibcImpl.GLIBC),
      SupportedDistribution(os = OsFamily.LINUX, arch = JvmArchitecture.aarch64, LinuxLibcImpl.GLIBC),
    )

    val allLayouts = ArrayList(supportedOsArch.map { (os, arch, _) -> jcefPlugin(os, arch) })
    allLayouts += jcefCrossPlatformEmpty()
    return allLayouts.toTypedArray()
  }

  private fun jcefCrossPlatformEmpty(): PluginLayout {
    return plugin("intellij.jcef.plugin") { // cross-platform distribution comes without JCEF binaries
      it.bundlingRestrictions.includeInDistribution = PluginDistribution.CROSS_PLATFORM_DIST_ONLY
    }
  }

  fun jcefPlugin(os: OsFamily, arch: JvmArchitecture): PluginLayout {
    return plugin("intellij.jcef.plugin") { spec ->
      spec.bundlingRestrictions.supportedOs = persistentListOf(os)
      spec.bundlingRestrictions.supportedArch = persistentListOf(arch)

      fun archSuffix(arch: JvmArchitecture): String = when (arch) {
        JvmArchitecture.x64 -> "x64"
        JvmArchitecture.aarch64 -> "aarch64"
      }

      fun jcefArchiveName(os: OsFamily, arch: JvmArchitecture, build: String): String =
        "jcef-${os.jbrArchiveSuffix}-${archSuffix(arch)}-${build}.tar.gz"

      fun downloadUrlFor(os: OsFamily, arch: JvmArchitecture, build: String): String =
        "https://cache-redirector.jetbrains.com/intellij-jbr/${jcefArchiveName(os, arch, build)}"

      patchOsSpecificPluginXml(spec, os, arch)

      spec.withCustomVersion { _, ideBuildNumber, _ ->
        // be careful, Marketplace expects linux/macos/windows for os and x86_64/x86/arm64/arm32 for arch
        val pluginVersion = "$ideBuildNumber-${os.osId}-${arch.marketplaceName}"
        PluginVersionEvaluatorResult(pluginVersion)
      }

      spec.withGeneratedResources { targetDir, context ->
        val communityRoot = context.paths.communityHomeDirRoot
        val properties = BuildDependenciesDownloader.getDependencyProperties(communityRoot)
        val jcefBuildNumber = properties.property("jcefBuild")

        val archivePath = downloadFileToCacheLocation(downloadUrlFor(os, arch, jcefBuildNumber), communityRoot)
        val subDir = targetDir.resolve("jcef-tmp") // to not clean up root plugin directory on BuildDependenciesDownloader.extractFile
        Files.createDirectories(subDir)

        BuildDependenciesDownloader.extractFile(archivePath, subDir, communityRoot, BuildDependenciesExtractOptions.STRIP_ROOT)

        // Unix ZIP does not have root `jcef` directory
        val jcefOutputDir = if (Files.exists(subDir.resolve("jcef"))) subDir.resolve("jcef") else subDir
        Files.move(jcefOutputDir, targetDir.resolve("jcef"), StandardCopyOption.REPLACE_EXISTING)
        Files.deleteIfExists(subDir)
      }

      spec.enableSymlinksAndExecutableResources()
    }
  }

  fun javaFXPlugin(mainModuleName: String): PluginLayout {
    return pluginAutoWithCustomDirName(mainModuleName, "javaFX") { spec ->
      spec.withModule("intellij.javaFX.jps")
      spec.withModule("intellij.javaFX.common", "javaFX-common.jar")
      spec.withModule("intellij.javaFX.sceneBuilder", "rt/sceneBuilderBridge.jar")
    }
  }

  fun groovyPlugin(additionalModules: List<String> = emptyList(), addition: ((PluginLayout.PluginLayoutSpec) -> Unit)? = null): PluginLayout {
    return pluginAutoWithCustomDirName("intellij.groovy") { spec ->
      spec.directoryName = "Groovy"
      spec.mainJarName = "Groovy.jar"
      spec.withModules(
        listOf(
          "intellij.groovy.psi",
          "intellij.groovy.structuralSearch",
        )
      )
      spec.withModule("intellij.groovy.jps", "groovy-jps.jar")
      spec.withModule("intellij.groovy.rt", "groovy-rt.jar")
      spec.withModule("intellij.groovy.spock.rt", "groovy-spock-rt.jar")
      spec.withModule("intellij.groovy.rt.classLoader", "groovy-rt-class-loader.jar")
      spec.withModule("intellij.groovy.constants.rt", "groovy-constants-rt.jar")
      spec.withModules(additionalModules)

      spec.excludeFromModule("intellij.groovy.psi", "standardDsls/**")
      spec.withResource("groovy-psi/resources/standardDsls", "lib/standardDsls")
      spec.withResource("hotswap/gragent.jar", "lib/agent")
      spec.withResource("groovy-psi/resources/conf", "lib")
      addition?.invoke(spec)
    }
  }
}

private suspend fun copyAnt(mainModule: String, pluginDir: Path, context: BuildContext): List<DistributionFileEntry> {
  val antDir = pluginDir.resolve("dist")
  return spanBuilder("copy Ant lib").setAttribute("antDir", antDir.toString()).use {
    val sources = ArrayList<ZipSource>()
    val antTargetFile = antDir.resolve("ant.jar")
    val antModuleItem = ModuleItem(mainModule, relativeOutputFile = antTargetFile.fileName.toString(), reason = "ant")
    val libraryData = ProjectLibraryData(libraryName = "Ant", packMode = LibraryPackMode.STANDALONE_MERGED, reason = "ant", owner = antModuleItem)
    copyDir(
      sourceDir = context.paths.communityHomeDir.resolve("lib/ant"),
      targetDir = antDir,
      dirFilter = { !it.endsWith("src") },
      fileFilter = { file ->
        if (file.toString().endsWith(".jar")) {
          sources.add(ZipSource(file = file, distributionFileEntryProducer = null, filter = ::defaultLibrarySourcesNamesFilter, moduleName = null))
          false
        }
        else {
          true
        }
      },
    )
    sources.sort()

    checkForNoDiskSpace(context) {
      buildJar(targetFile = antTargetFile, sources = sources)
    }

    sources.map { source ->
      ProjectLibraryEntry(
        path = antTargetFile,
        data = libraryData,
        libraryFile = source.file,
        canonicalLibraryPath = context.paths.communityHomeDir.relativize(source.file).toString(),
        hash = 0,
        size = 0,
        relativeOutputFile = "dist/ant.jar",
      )
    }
  }
}
