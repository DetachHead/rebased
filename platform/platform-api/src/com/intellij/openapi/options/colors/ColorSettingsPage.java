// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.options.colors;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.Point;
import java.util.Map;

/**
 * Interface for a custom page shown in the "Colors and Fonts" settings dialog.
 * <p>
 * Declare a page with {@code com.intellij.colorSettings} and not with {@link #EP_NAME}.
 */
public interface ColorSettingsPage extends ColorAndFontDescriptorsProvider {
  /**
   * The extension point of a colour page that the platform must create to read its id, its name and its order.
   * <p>
   * <b>Declare a new page with {@code com.intellij.colorSettings} instead.</b> That declaration states
   * the id, the name and the order, so the settings tree builds the node and loads no class, and the platform
   * creates the page when the user opens it. See
   * {@code com.intellij.application.options.colors.ColorSettingsPageEP} for every attribute.
   * <p>
   * This extension point stays, for three cases:
   * <ul>
   *   <li>a product registers a page at run time, for example the remote development client, which builds one
   *   page per page of its host;</li>
   *   <li>a page cannot state its id, its name or its order in a declaration, because it computes one of them;</li>
   *   <li>a plugin supports an older IDE with one archive. Such a plugin may carry both declarations of one class.
   *   The new declaration answers, and the class stays unloaded. An older IDE drops the unknown declaration with no
   *   message, and it asks for a restart on an install, because it cannot load the plugin dynamically.</li>
   * </ul>
   * A caller that needs every colour page reads
   * {@link ColorSettingsPages#getRegisteredPages()}, which answers the pages of both extension points.
   * This extension point alone answers the pages of one declaration kind.
   */
  ExtensionPointName<ColorSettingsPage> EP_NAME = ExtensionPointName.create("com.intellij.colorSettingsPage");

  /**
   * Returns the icon for the page, shown in the dialog tab.
   *
   * @return the icon for the page, or null if the page does not have a custom icon.
   */
  @Nullable Icon getIcon();

  /**
   * Returns the syntax highlighter which is used to highlight the text shown in the preview
   * pane of the page.
   *
   * @return the syntax highlighter instance.
   */
  @NotNull SyntaxHighlighter getHighlighter();

  /**
   * Returns the text shown in the preview pane. If some elements need to be highlighted in
   * the preview text which are not highlighted by the syntax highlighter, they need to be
   * surrounded by XML-like tags, for example: {@code <class>MyClass</class>}.
   * The mapping between the names of the tags and the text attribute keys used for highlighting
   * is defined by the {@link #getAdditionalHighlightingTagToDescriptorMap()} method.
   * <p>The returned text <strong>must use {@code \n} as a line separator</strong>, so if you read it from a file make sure to adjust it via 
   * {@link com.intellij.openapi.util.text.StringUtil#convertLineSeparators(String)}.
   * </p>
   *
   * @return the text to show in the preview pane or empty text to hide it.
   */
  @NonNls @NotNull String getDemoText();

  /**
   * Returns the mapping from special tag names surrounding the regions to be highlighted
   * in the preview text (see {@link #getDemoText()}) to text attribute keys used to
   * highlight the regions.
   *
   * @return the mapping from tag names to text attribute keys, or null if the demo text
   * does not contain any additional highlighting tags.
   */
  @Nullable Map<String,TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap();

  default @Nullable Map<String,TextAttributesKey> getAdditionalInlineElementToDescriptorMap() { return null; }

  /**
   * Specifies tag-to-'color key' mapping for regions with presentation containing additional colors from color map. 
   * It's used to implement navigation between the list of keys and regions in sample editor.
   */
  default @Nullable Map<String, ColorKey> getAdditionalHighlightingTagToColorKeyMap() { return null; }

  /**
   * Allows to define additional customizations for the preview editor, which cannot be configured by markup in demo text.
   */
  default @Nullable PreviewCustomizer getPreviewEditorCustomizer() { return null; }

  default @NotNull EditorColorsScheme customizeColorScheme(@NotNull EditorColorsScheme scheme) { return scheme; }

  /**
   * Specifies customizations for the preview editor, which cannot be configured by markup in demo text.
   */
  interface PreviewCustomizer {
    /**
     * Add customizations which are to be demonstrated by the preview editor. If {@code selectedKeyName} is not null, feature corresponding
     * to the {@link TextAttributesKey} or {@link ColorKey} with that name should be highlighted, and associated text range in the document
     * should be returned. Otherwise, {@code null} should be returned.
     */
    @Nullable TextRange addCustomizations(@NotNull Editor editor, @Nullable String selectedKeyName);

    /**
     * Should remove any customizations, which are added by {@link #addCustomizations(Editor, String)} method.
     */
    void removeCustomizations(@NotNull Editor editor);

    /**
     * Returns the name of {@link TextAttributesKey} or {@link ColorKey} corresponding for the feature at given location.
     */
    @Nullable String getCustomizationAt(@NotNull Editor editor, @NotNull Point location);
  }
}
