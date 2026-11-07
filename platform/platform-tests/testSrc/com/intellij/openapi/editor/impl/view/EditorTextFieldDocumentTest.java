// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.view;

import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.editor.impl.AbstractEditorTest;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.editor.impl.EditorTextFieldRendererDocument;
import com.intellij.openapi.util.text.StringUtil;

public class EditorTextFieldDocumentTest extends AbstractEditorTest {
  public void testSelection() {
    EditorTextFieldRendererDocument document = new EditorTextFieldRendererDocument();
    document.setText("12345\n67890\n\r12345\r11111");
    assertEquals(5, document.getLineEndOffset(0));
    assertEquals(6, document.getLineStartOffset(1));
    assertEquals(11, document.getLineEndOffset(1));
    assertEquals(12, document.getLineStartOffset(2));
    assertEquals(12, document.getLineEndOffset(2));
    assertEquals(13, document.getLineStartOffset(3));
    assertEquals(18, document.getLineEndOffset(3));
  }

  public void testEmptyText() {
    var document = new EditorTextFieldRendererDocument();
    document.setText("");
    assertEquals(0, document.getLineCount());
    assertEquals(0, document.getLineNumber(0));
    assertEquals(0, document.getLineStartOffset(0));
    assertEquals(0, document.getLineEndOffset(0));
  }

  public void testLineMappingMatchesDocumentImpl() {
    for (var text : new String[]{"", "a", "a\n", "\n", "\n\n", "ab\ncd", "ab\ncd\n", "ab\r\ncd\ref\n"}) {
      var expected = new DocumentImpl(StringUtil.convertLineSeparators(text));
      var actual = new EditorTextFieldRendererDocument();
      actual.setText(text);
      assertSameLineMapping(expected, actual, "\"" + StringUtil.escapeStringCharacters(text) + "\"");
    }
  }

  private static void assertSameLineMapping(DocumentEx expected, DocumentEx actual, String label) {
    assertEquals(label, expected.getText(), actual.getText());
    assertEquals(label, expected.getLineCount(), actual.getLineCount());
    for (var line = 0; line < expected.getLineCount(); line++) {
      var context = label + ", line " + line;
      assertEquals(context, expected.getLineStartOffset(line), actual.getLineStartOffset(line));
      assertEquals(context, expected.getLineEndOffset(line), actual.getLineEndOffset(line));
      assertEquals(context, expected.getLineSeparatorLength(line), actual.getLineSeparatorLength(line));
    }
    for (var offset = 0; offset <= expected.getTextLength(); offset++) {
      assertEquals(label + ", offset " + offset, expected.getLineNumber(offset), actual.getLineNumber(offset));
    }
    var expectedLines = expected.createLineIterator();
    var actualLines = actual.createLineIterator();
    while (!expectedLines.atEnd()) {
      var context = label + ", iterator line " + expectedLines.getLineNumber();
      assertFalse(context, actualLines.atEnd());
      assertEquals(context, expectedLines.getLineNumber(), actualLines.getLineNumber());
      assertEquals(context, expectedLines.getStart(), actualLines.getStart());
      assertEquals(context, expectedLines.getEnd(), actualLines.getEnd());
      assertEquals(context, expectedLines.getSeparatorLength(), actualLines.getSeparatorLength());
      expectedLines.advance();
      actualLines.advance();
    }
    assertTrue(label, actualLines.atEnd());
  }
}
