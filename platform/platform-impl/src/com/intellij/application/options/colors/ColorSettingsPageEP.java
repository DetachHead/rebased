// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.application.options.colors;

import com.intellij.BundleBase;
import com.intellij.DynamicBundle;
import com.intellij.diagnostic.PluginException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.psi.codeStyle.DisplayPriority;
import com.intellij.serviceContainer.BaseKeyedLazyInstance;
import com.intellij.util.PlatformUtils;
import com.intellij.util.xmlb.annotations.Attribute;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ResourceBundle;

/**
 * Declares a page of the "Colors and Fonts" settings node.
 * <p>
 * The declaration states the id, the name and the order of the page, so the settings tree builds the node
 * and loads no class. The platform creates the {@link ColorSettingsPage} when the user opens the page,
 * or when a caller asks {@link com.intellij.openapi.options.colors.ColorSettingsPages#getRegisteredPages()}
 * for the pages themselves.
 * <p>
 * Declare a page here instead of {@link ColorSettingsPage#EP_NAME}. The interface extension point stays for
 * a page that the product registers at run time, and for a plugin that supports an older IDE.
 */
public final class ColorSettingsPageEP extends BaseKeyedLazyInstance<ColorSettingsPage> {
  private static final Logger LOG = Logger.getInstance(ColorSettingsPageEP.class);

  public static final ExtensionPointName<ColorSettingsPageEP> EP_NAME =
    new ExtensionPointName<>("com.intellij.colorSettings");

  /**
   * This attribute names the {@link ColorSettingsPage} class.
   */
  @Attribute("implementation")
  public String implementationClass;

  /**
   * This attribute states the id of the page. The settings tree builds the node id from it.
   * The default is the {@link #implementationClass} string, which is what
   * {@link com.intellij.openapi.options.colors.ColorAndFontDescriptorsProvider#getId()} returns by default.
   * <p>
   * State the attribute when the class overrides {@code getId()}, and state the same value.
   * <p>
   * The attribute is {@code pageId} and not {@code id}, because the platform reads {@code id} as the id of
   * the extension, which {@code order} names. Several colour page declarations carry such an id already,
   * for example {@code id="general"}, and that string is not the id of the page.
   */
  @Attribute("pageId")
  public String declaredPageId;

  /**
   * This attribute states the name of the page. It has precedence over {@link #key} and {@link #bundle}.
   * State either this attribute or the pair, because the settings tree sorts the nodes by name
   * and a page with no declared name loads its class.
   */
  @Attribute("displayName")
  public @NlsContexts.ConfigurableName String displayName;

  /**
   * This attribute states the resource key of the name in {@link #bundle}.
   */
  @Attribute("key")
  public @NonNls String key;

  /**
   * This attribute states the resource bundle that holds {@link #key}.
   * The resource bundle of the plugin is the default.
   */
  @Attribute("bundle")
  public @NonNls String bundle;

  /**
   * This attribute states the band of the page in the "Colors and Fonts" node.
   * It is the value that {@link com.intellij.psi.codeStyle.DisplayPrioritySortable#getPriority()} returns.
   * Read the band with {@link #getPriority()}, because {@link #keyLanguageIn} can raise it.
   * <p>
   * The field name differs from the attribute name, for the reason that {@link #declaredPageId} states.
   */
  @Attribute("priority")
  public DisplayPriority declaredPriority = DisplayPriority.LANGUAGE_SETTINGS;

  /**
   * This attribute names the products where the page takes the {@link DisplayPriority#KEY_LANGUAGE_SETTINGS}
   * band, as a comma separated list of platform prefixes, for example {@code keyLanguageIn="WebStorm,PhpStorm"}.
   * The page takes {@link #declaredPriority} in every other product.
   * <p>
   * State the attribute when the class returns a band that depends on the product, which reads as
   * {@code PlatformUtils.isWebStorm() ? KEY_LANGUAGE_SETTINGS : LANGUAGE_SETTINGS} in the source.
   * The comparison reads {@link PlatformUtils#getPlatformPrefix()}, so it loads no class.
   * <p>
   * A prefix is a constant of {@link PlatformUtils}, for example {@code PhpStorm}, {@code WebStorm},
   * {@code Ruby}, {@code CLion} or {@code Rider}. Name every prefix of a product family: PyCharm is
   * {@code Python,PyCharmCore,PyCharmEdu}, as {@link PlatformUtils#isPyCharm()} states.
   */
  @Attribute("keyLanguageIn")
  public @NonNls String keyLanguageIn;

  /**
   * This attribute states the order of the page inside its {@link #getPriority() band}.
   * A higher weight comes first. Pages of equal weight sort by name.
   */
  @Attribute("groupWeight")
  public int groupWeight;

  /**
   * This attribute states that the page is beta, as the {@code beta} attribute of a configurable declaration does.
   * The declaration is the only source of the badge, so the answer costs no class load.
   */
  @Attribute("beta")
  public boolean beta;

  /**
   * Returns the band of the page in this product, and loads no class.
   * The name differs from the {@link #declaredPriority} field, for the reason that {@link #getPageId()} states.
   */
  public @NotNull DisplayPriority getPriority() {
    return isKeyLanguageHere() ? DisplayPriority.KEY_LANGUAGE_SETTINGS : declaredPriority;
  }

  private boolean isKeyLanguageHere() {
    if (keyLanguageIn == null) {
      return false;
    }
    String prefix = PlatformUtils.getPlatformPrefix();
    for (String named : keyLanguageIn.split(",")) {
      if (named.trim().equals(prefix)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected @Nullable String getImplementationClassName() {
    return implementationClass;
  }

  /**
   * Returns the page, and creates it on the first call.
   * Every colour page of a declaration is created here, so a caller that must not create one states it against
   * this method.
   */
  public @NotNull ColorSettingsPage getPage() {
    return getInstance();
  }

  /**
   * Returns the id of the page, and loads no class.
   * The name differs from the {@link #declaredPageId} field, because a Kotlin caller cannot tell a field
   * and a getter of one name apart.
   */
  public @NotNull @NonNls String getPageId() {
    return declaredPageId != null ? declaredPageId : implementationClass;
  }

  /**
   * Returns the name of the page, and loads no class.
   * Reports an error and returns the id when the declaration states no name.
   * The name differs from the {@link #displayName} attribute, for the reason that {@link #getPageId()} states.
   */
  public @NotNull @NlsContexts.ConfigurableName String getPageName() {
    if (displayName != null) {
      return displayName;
    }

    ResourceBundle resourceBundle = findBundle();
    if (resourceBundle != null && key != null) {
      return BundleBase.messageOrDefault(resourceBundle, key, null);
    }

    LOG.error(new PluginException("A colorSettings declaration states no name. " +
                                  "State displayName, or key and bundle, for " + implementationClass,
                                  getPluginDescriptor().getPluginId()));
    @NlsSafe String fallback = getPageId();
    return fallback;
  }

  /**
   * Returns the class of the page, and creates no page.
   * Returns {@code null} when the class is absent, and reports an error.
   */
  public @Nullable Class<? extends ColorSettingsPage> findPageClass() {
    try {
      return Class.forName(implementationClass, false, getLoaderForClass()).asSubclass(ColorSettingsPage.class);
    }
    catch (ClassNotFoundException | ClassCastException e) {
      LOG.error(new PluginException("Cannot load the colour page " + implementationClass, e,
                                    getPluginDescriptor().getPluginId()));
      return null;
    }
  }

  private @Nullable ResourceBundle findBundle() {
    String baseName = bundle != null ? bundle : getPluginDescriptor().getResourceBundleBaseName();
    return baseName == null ? null : DynamicBundle.getResourceBundle(getLoaderForClass(), baseName);
  }
}
