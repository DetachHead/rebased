// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.options.colors.pages;

import com.intellij.application.options.colors.ColorSettingsPageCatalog;
import com.intellij.application.options.colors.ColorSettingsPageEP;
import com.intellij.application.options.colors.ColorSettingsPageEntry;
import com.intellij.application.options.colors.ColorSettingsUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.options.colors.AbstractKeyDescriptor;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorAndFontDescriptorsProvider;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import com.intellij.openapi.options.colors.ColorSettingsPages;
import com.intellij.openapi.util.Pair;
import com.intellij.util.containers.ConcurrentFactoryMap;
import com.intellij.util.containers.JBIterable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class ColorSettingsPagesImpl extends ColorSettingsPages implements Disposable {
  private final Map<Object, Pair<ColorAndFontDescriptorsProvider, ? extends AbstractKeyDescriptor<?>>> myCache =
    ConcurrentFactoryMap.createMap(this::getDescriptorImpl);

  ColorSettingsPagesImpl() {
    ColorAndFontDescriptorsProvider.EP_NAME.addChangeListener(myCache::clear, this);
    ColorSettingsPage.EP_NAME.addChangeListener(myCache::clear, this);
    ColorSettingsPageEP.EP_NAME.addChangeListener(myCache::clear, this);
  }

  @Override
  public void registerPage(ColorSettingsPage page) {
    ColorSettingsPage.EP_NAME.getPoint().registerExtension(page);
  }

  /**
   * Returns one page per entry of {@link ColorSettingsPageCatalog}, and creates the page of a declaration to do it.
   * A caller that needs the id, the name or the order only must read the catalog itself,
   * because this method loads every page class. The catalog counts both for the settings debug report.
   */
  @Override
  public ColorSettingsPage[] getRegisteredPages() {
    List<ColorSettingsPageEntry> entries = ColorSettingsPageCatalog.getEntries();
    List<ColorSettingsPage> pages = new ArrayList<>(entries.size());
    for (ColorSettingsPageEntry entry : entries) {
      pages.add(entry.getPage());
    }
    return pages.toArray(new ColorSettingsPage[0]);
  }

  @Override
  public @Nullable Pair<ColorAndFontDescriptorsProvider, AttributesDescriptor> getAttributeDescriptor(TextAttributesKey key) {
    //noinspection unchecked
    return (Pair<ColorAndFontDescriptorsProvider, AttributesDescriptor>)myCache.get(key);
  }

  @Override
  public @Nullable Pair<ColorAndFontDescriptorsProvider, ColorDescriptor> getColorDescriptor(ColorKey key) {
    //noinspection unchecked
    return (Pair<ColorAndFontDescriptorsProvider, ColorDescriptor>)myCache.get(key);
  }

  private @Nullable Pair<ColorAndFontDescriptorsProvider, ? extends AbstractKeyDescriptor<?>> getDescriptorImpl(Object key) {
    JBIterable<ColorAndFontDescriptorsProvider> providers = JBIterable.empty();
    for (ColorAndFontDescriptorsProvider page : providers.append(getRegisteredPages()).append(ColorAndFontDescriptorsProvider.EP_NAME.getExtensionList())) {
      Iterable<? extends AbstractKeyDescriptor<?>> descriptors;
      if (key instanceof TextAttributesKey) {
        descriptors = ColorSettingsUtil.getAllAttributeDescriptors(page);
      }
      else {
        descriptors = key instanceof ColorKey ? JBIterable.of(page.getColorDescriptors()) : Collections.emptyList();
      }

      for (AbstractKeyDescriptor<?> descriptor : descriptors) {
        if (descriptor.getKey() == key) {
          return new Pair<>(page, descriptor);
        }
      }
    }
    return null;
  }

  @Override
  public void dispose() {
  }
}
