// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.stats.completion.sender

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DailySendingLimitTest {
  @Test
  fun testLimitInfo() {
    val info = DailyLimitSendingWatcher.SentDataInfo.DumbInfo()
    assertEquals(0, info.sentToday(10))
    info.dataSent(10, 100)
    assertEquals(100, info.sentToday(10))
    info.dataSent(10, 1)
    assertEquals(101, info.sentToday(10))
    val nextDay: Long = 10 + 24 * 60 * 60 * 1000
    assertEquals(0, info.sentToday(nextDay))
    info.dataSent(nextDay, 1)
    assertEquals(1, info.sentToday(nextDay))
  }

  @Test
  fun testSendingWatcher() {
    val watcher = DailyLimitSendingWatcher(2500, DailyLimitSendingWatcher.SentDataInfo.DumbInfo())
    assertFalse(watcher.isLimitReached())
    watcher.dataSent(2300)
    assertFalse(watcher.isLimitReached())
    watcher.dataSent(500)
    if (!watcher.isLimitReached()) {
      println("is it really 12 o'clock?!")
      watcher.dataSent(2300)
      watcher.dataSent(500)
    }

    assertTrue(watcher.isLimitReached())
  }
}