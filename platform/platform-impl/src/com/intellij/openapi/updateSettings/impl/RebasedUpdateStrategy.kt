package com.intellij.openapi.updateSettings.impl

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.updateSettings.UpdateStrategyCustomization
import com.intellij.openapi.util.Version

/**
 * customized version of the update checker that compares the major/minor version number instead of the build number.
 * we do this because the rebased version number is stored as the major/minor version instead of the build number,
 * which is preserved from upstream so that plugins that expect a specific platform version number can still work.
 */
class RebasedUpdateStrategy(
  private val applicationInfo: ApplicationInfo,
  product: Product? = null,
  settings: UpdateSettings = UpdateSettings.getInstance(),
  customization: UpdateStrategyCustomization = UpdateStrategyCustomization.getInstance(),
) : UpdateStrategy(applicationInfo.build, product, settings, customization) {
  override fun isApplicable(candidate: BuildInfo, ignoredBuilds: Set<String>): Boolean =
    Version.parseVersion(candidate.version)!! > Version.parseVersion(applicationInfo.fullVersion)!!
}