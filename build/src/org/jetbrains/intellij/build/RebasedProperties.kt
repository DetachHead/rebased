// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.plus
import org.jetbrains.intellij.build.BuildPaths.Companion.COMMUNITY_ROOT
import org.jetbrains.intellij.build.impl.createBuildContext
import org.jetbrains.intellij.build.impl.qodana.QodanaProductProperties
import org.jetbrains.intellij.build.io.copyDir
import org.jetbrains.intellij.build.io.copyFileToDir
import org.jetbrains.intellij.build.productLayout.CommunityModuleSets
import org.jetbrains.intellij.build.productLayout.ProductModulesContentSpec
import org.jetbrains.intellij.build.productLayout.productModules
import java.nio.file.Path

val MAVEN_ARTIFACTS_ADDITIONAL_MODULES: PersistentList<String> = persistentListOf(
  "intellij.tools.jps.build.standalone",
  "intellij.devkit.jps",
  "intellij.idea.community.build.tasks",
  "intellij.platform.debugger.testFramework",
  "intellij.platform.vcs.testFramework",
  "intellij.platform.externalSystem.testFramework",
  "intellij.platform.uast.testFramework",
  "intellij.maven.testFramework",
  "intellij.tools.reproducibleBuilds.diff",
  "intellij.space.java.jps",
) + JewelMavenArtifacts.STANDALONE.keys

internal suspend fun createCommunityBuildContext(
  options: BuildOptions,
  projectHome: Path = COMMUNITY_ROOT.communityRoot,
): BuildContext {
  return createBuildContext(
    projectHome = projectHome,
    productProperties = RebasedProperties(COMMUNITY_ROOT.communityRoot),
    setupTracer = true,
    options = options,
  )
}


/**
 * we have renamed from `IdeaCommunityProperties` in the upstream repo and commented out stuff that's not relevant to the git client.
 *
 * to avoid upstream conflicts (and to make the code less confusing), we should probably create a separate subclass of
 * [JetBrainsProductProperties] for Rebased instead of modifying the intellij community one, but it was much easier to do it this way
 * because it's very difficult to narrow down which plugins depend on others when trying to cut out modules we don't want
 */
open class RebasedProperties(private val communityHomeDir: Path) : JetBrainsProductProperties() {
  init {
    configurePropertiesForAllEditionsOfIntelliJIdea(this)
    platformPrefix = "Rebased"
    applicationInfoModule = "intellij.idea.community.customization"
    scrambleMainJar = false
    useSplash = false
    buildCrossPlatformDistribution = true
    buildSourcesArchive = true
    runtimeDistribution = JetBrainsRuntimeDistribution.VANILLA

    imagesDirectoryPath = communityHomeDir.resolve("build/idea-community-images")

    productLayout.productImplementationModules = listOf(
      "intellij.platform.starter",
      "intellij.idea.community.customization",
    )


    // from upstream:
    //productLayout.bundledPluginModules = IDEA_BUNDLED_PLUGINS + sequenceOf(
    //  "intellij.javaFX.community"
    //)
    productLayout.bundledPluginModules = REBASED_BUNDLED_PLUGINS

    productLayout.prepareCustomPluginRepositoryForPublishedPlugins = false
    productLayout.buildAllCompatiblePlugins = true

    // from upstream:
    //productLayout.pluginLayouts = CommunityRepositoryModules.COMMUNITY_REPOSITORY_PLUGINS + persistentListOf(
    //  JavaPluginLayout.javaPlugin(),
    //  CommunityRepositoryModules.groovyPlugin(),
    //  *CommunityRepositoryModules.androidPlugin(),
    //)

    productLayout.skipUnresolvedContentModules = true

    mavenArtifacts.forIdeModules = true
    // from upstream:
    //mavenArtifacts.additionalModules += MAVEN_ARTIFACTS_ADDITIONAL_MODULES
    mavenArtifacts.additionalModules += JewelMavenArtifacts.STANDALONE.keys
    mavenArtifacts.squashedModules += persistentListOf(
      "intellij.platform.util.base",
      "intellij.platform.util.base.multiplatform",
      "intellij.platform.util.zip",
    )
    mavenArtifacts.validateForMavenCentralPublication = { module ->
      JewelMavenArtifacts.isPublishedJewelModule(module)
    }
    mavenArtifacts.patchCoordinates = { module, coordinates ->
      when {
        JewelMavenArtifacts.isPublishedJewelModule(module) -> JewelMavenArtifacts.patchCoordinates(module, coordinates)
        else -> coordinates
      }
    }
    mavenArtifacts.patchDependencies = { module, dependencies ->
      when {
        JewelMavenArtifacts.isPublishedJewelModule(module) -> JewelMavenArtifacts.patchDependencies(module, dependencies)
        else -> dependencies
      }
    }
    mavenArtifacts.addPomMetadata = { module, model ->
      when {
        JewelMavenArtifacts.isPublishedJewelModule(module) -> JewelMavenArtifacts.addPomMetadata(module, model)
      }
    }
    mavenArtifacts.isJavadocJarRequired = {
      JewelMavenArtifacts.isPublishedJewelModule(it) && it.name != "intellij.platform.jewel.intUi.decoratedWindow"
    }
    mavenArtifacts.validate = { context, artifacts ->
      JewelMavenArtifacts.validate(context, artifacts)
    }

    versionCheckerConfig = CE_CLASS_VERSIONS
    baseDownloadUrl = "https://download.jetbrains.com/idea/"
    buildDocAuthoringAssets = true

    @Suppress("SpellCheckingInspection")
    qodanaProductProperties = QodanaProductProperties("QDJVMC", "Qodana Community for JVM")
    additionalVmOptions = persistentListOf("-Dllm.show.ai.promotion.window.on.start=false")
  }

  override val baseFileName: String
    get() = "idea"

  override fun getProductContentDescriptor(): ProductModulesContentSpec = productModules {
    include(intellijCommunityBaseFragment(platformPrefix))
  }

  override suspend fun copyAdditionalFiles(targetDir: Path, context: BuildContext) {
    super.copyAdditionalFiles(targetDir, context)

    copyFileToDir(context.paths.communityHomeDir.resolve("LICENSE.txt"), targetDir)
    copyFileToDir(context.paths.communityHomeDir.resolve("NOTICE.txt"), targetDir)

    copyDir(
      sourceDir = context.paths.communityHomeDir.resolve("build/conf/ideaCE/common/bin"),
      targetDir = targetDir.resolve("bin"),
    )

    bundleExternalPlugins(context, targetDir)
  }

  protected open suspend fun bundleExternalPlugins(context: BuildContext, targetDirectory: Path) {}

  override fun createWindowsCustomizer(projectHome: Path): WindowsDistributionCustomizer = ideaCommunityWindowsCustomizer(communityHomeDir)

  override fun createLinuxCustomizer(projectHome: Path): LinuxDistributionCustomizer = ideaCommunityLinuxCustomizer(communityHomeDir)

  override fun createMacCustomizer(projectHome: Path): MacDistributionCustomizer = ideaCommunityMacCustomizer(communityHomeDir)

  override fun getSystemSelector(appInfo: ApplicationInfoProperties, buildNumber: String): String {
    return "IdeaIC${appInfo.majorVersion}.${appInfo.minorVersionMainPart}"
  }

  override fun getBaseArtifactName(appInfo: ApplicationInfoProperties, buildNumber: String): String = "rebased"

  override fun getOutputDirectoryName(appInfo: ApplicationInfoProperties): String = "idea-ce"
}

@Suppress("unused")
open class AndroidStudioProperties(communityHomeDir: Path) : RebasedProperties(communityHomeDir) {
  init {
    platformPrefix = "AndroidStudio"
    applicationInfoModule = "intellij.idea.android.customization"

    productLayout.productImplementationModules += "intellij.idea.android.customization"

    val defaultBundledPlugins = IDEA_BUNDLED_PLUGINS
      .removing("intellij.mcpserver")
      .removing("intellij.featuresTrainer")

    productLayout.bundledPluginModules = defaultBundledPlugins + persistentListOf(
      "intellij.android.compose-ide-plugin",
      "intellij.android.design-plugin.descriptor",
      "intellij.android.plugin.descriptor",
      "intellij.android.smali",
    )
  }

  override fun getProductContentDescriptor(): ProductModulesContentSpec = productModules {
    include(intellijCommunityBaseFragment(platformPrefix))
    // no community extensions
  }
}

/**
 * Base IntelliJ Community content fragment.
 * This fragment is composable - subclasses can include this and optionally add community extensions.
 */
fun intellijCommunityBaseFragment(platformPrefix: String? = null): ProductModulesContentSpec = productModules {
  if (platformPrefix == "AndroidStudio") {
    alias("com.intellij.modules.androidstudio")
  }

  // from upstream:
  //if (platformPrefix != "AndroidStudio") {
  //  alias("com.intellij.platform.ide.provisioner")
  //}
  //
  //include(CommunityProductFragments.javaIdeBaseFragment())
  deprecatedInclude("intellij.platform.resources", "META-INF/PlatformLangPlugin.xml")
  deprecatedInclude("intellij.idea.community.customization", "META-INF/tips-intellij-idea-community.xml")

  // from upstream:
  //module("intellij.platform.coverage")
  //module("intellij.platform.coverage.agent")
  //module("intellij.xml.xmlbeans")
  //module("intellij.platform.ide.newUiOnboarding")
  //module("intellij.platform.ide.newUsersOnboarding")
  //module("intellij.ide.startup.importSettings")

  module("intellij.platform.customization.min")
  module("intellij.idea.customization.base")
  module("intellij.idea.customization.backend")

  // from upstream:
  //module("intellij.platform.tips")
  //if (System.getProperty("idea.platform.prefix") == "AndroidStudio") {
  //  module("intellij.idea.android.customization")
  //}

  moduleSet(CommunityModuleSets.ideCommon())
  // from upstream:
  //moduleSet(CommunityModuleSets.rdCommon())

  deprecatedInclude("intellij.idea.community.customization", "META-INF/community-customization.xml")
}

inline fun ideaCommunityWindowsCustomizer(
  projectHome: Path,
  configure: WindowsCustomizerBuilder.() -> Unit = {}
): WindowsDistributionCustomizer = windowsCustomizer(projectHome) {
  fileAssociations = emptyList()

  fullName { "Rebased" }
  installDirNameHandler { "Rebased" }

  configure()
}

inline fun ideaCommunityMacCustomizer(
  projectHome: Path,
  configure: MacCustomizerBuilder.() -> Unit = {}
): MacDistributionCustomizer = macCustomizer(projectHome) {
  urlSchemes = listOf("rebased")
  associateIpr = true
  fileAssociations = emptyList()
  bundleIdentifier = "io.github.detachhead.rebased"

  rootDirectoryName { _, _ -> "Rebased.app" }

  // Rebased bundles no Kotlin plugin / kotlinc binaries, so don't declare executable patterns for them
  // (os_specific_distributions fails the build on patterns that match no files).
  executableFilePatterns { base, _, _, _ ->
    base.filterNot { it == "plugins/**/*.sh" }
  }

  configure()
}

inline fun ideaCommunityLinuxCustomizer(
  projectHome: Path,
  configure: LinuxCustomizerBuilder.() -> Unit = {}
): LinuxDistributionCustomizer = linuxCustomizer(projectHome) {

  rootDirectoryName { _, _ -> "idea-oss" }

  // Rebased bundles no Kotlin plugin / kotlinc binaries, so don't declare executable patterns for them
  // (os_specific_distributions fails the build on patterns that match no files).
  executableFilePatterns { base, _, _, _, _ ->
    base.filterNot { it == "plugins/**/*.sh" }
  }

  configure()
}
