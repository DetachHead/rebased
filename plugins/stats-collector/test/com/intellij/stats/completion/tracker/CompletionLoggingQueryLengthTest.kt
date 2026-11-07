// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.stats.completion.tracker

import com.intellij.completion.ml.util.queryLength
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CompletionLoggingQueryLengthTest: CompletionLoggingTestBase() {
  @BeforeEach
  fun addClasses(): Unit = onEdt {
    myFixture.addClass("interface Rum {}")
    myFixture.addClass("interface Runn {}")
  }

  @Test
  fun `test completion with query length 1 after dot`(): Unit = onEdt {
    myFixture.type('.')
    myFixture.completeBasic()

    val prefixLength = lookup.queryLength()

    Assertions.assertThat(prefixLength).isEqualTo(1)
  }

  @Test
  fun `test completion with query length 3 after dot`(): Unit = onEdt {
    myFixture.type(".r")
    myFixture.completeBasic()
    myFixture.type("u")
    myFixture.type("n")

    val prefixLength = lookup.queryLength()

    Assertions.assertThat(prefixLength).isEqualTo(3)
  }


  @Test
  fun `test completion with query length 1`(): Unit = onEdt {
    myFixture.type('\b')
    myFixture.type("Run")
    myFixture.completeBasic()

    val prefixLength = lookup.queryLength()

    Assertions.assertThat(prefixLength).isEqualTo(1)
  }


  @Test
  fun `test completion with query length 2`(): Unit = onEdt {
    myFixture.type('\b')
    myFixture.type('R')
    myFixture.completeBasic()
    myFixture.type('u')

    val prefixLength = lookup.queryLength()

    Assertions.assertThat(prefixLength).isEqualTo(2)
  }
}