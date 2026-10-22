/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.stats.completion.logger

import com.intellij.openapi.application.ApplicationManager
import com.intellij.stats.completion.DeserializedLogEvent
import com.intellij.stats.completion.LogEventSerializer
import com.intellij.stats.completion.LookupState
import com.intellij.stats.completion.ValidationStatus
import com.intellij.stats.completion.events.DownPressedEvent
import com.intellij.stats.completion.events.LogEvent
import com.intellij.stats.completion.withSelected
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue

/**
 * @author Vitaliy.Bibaev
 */
@TestApplication
class ValidationOnClientTest {
    private companion object {
        val EMPTY_STATE = LookupState(emptyList(), emptyList(), emptyList(), 1, emptyMap())
        const val bucket = "0"
        const val language = "java"
    }

    @Test
    fun `test validation before log`() {
        val event1 = DownPressedEvent("1", "1", EMPTY_STATE, bucket, System.currentTimeMillis(), language)
        val event2 = DownPressedEvent("1", "2", EMPTY_STATE, bucket, System.currentTimeMillis(), language)

        assertEquals(ValidationStatus.UNKNOWN, event1.validationStatus)
        val queue = LinkedBlockingQueue<DeserializedLogEvent>()
        val logger = createLogger { queue.addAll(it) }

        logger.log(event1)
        logger.log(event2)

        val event = queue.take().event!!
        assertEquals(ValidationStatus.VALID, event.validationStatus)
    }

    @Test
    fun `test log after session finished`() {
        val event1 = DownPressedEvent("1", "1", EMPTY_STATE, bucket, System.currentTimeMillis(), language)
        val event2 = DownPressedEvent("1", "1", EMPTY_STATE.withSelected(2), bucket, System.currentTimeMillis(), language)
        val event3 = DownPressedEvent("1", "2", EMPTY_STATE, bucket, System.currentTimeMillis(), language)

        val queue = LinkedBlockingQueue<DeserializedLogEvent>()

        val logger = createLogger { queue.addAll(it) }

        logger.log(event1)
        assertTrue(queue.isEmpty())

        logger.log(event2)
        assertTrue(queue.isEmpty())
        logger.log(event3)

        val e1 = queue.take().event!!
        assertEquals(event1.sessionUid, e1.sessionUid)
        val e2 = queue.take().event!!
        assertEquals(event2.sessionUid, e2.sessionUid)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `test log executed on pooled thread`() {
        val event1 = DownPressedEvent("1", "1", EMPTY_STATE, bucket, System.currentTimeMillis(), language)
        val event2 = DownPressedEvent("1", "2", EMPTY_STATE, bucket, System.currentTimeMillis(), language)

        val queue = LinkedBlockingQueue<Boolean>()
        val logger = createLogger { queue.add(ApplicationManager.getApplication().isDispatchThread) }
        logger.log(event1)
        logger.log(event2)

        assertFalse(queue.take())
    }

    private class DefaultValidator : SessionValidator {
        override fun validate(session: List<LogEvent>) {
            session.forEach { it.validationStatus = ValidationStatus.VALID }
        }
    }

    private fun createLogger(onLogCallback: (List<DeserializedLogEvent>) -> Unit): EventLoggerWithValidation {
        return EventLoggerWithValidation(object : FileLogger {

            override fun printLines(lines: List<String>) {
                val session = lines.map { LogEventSerializer.fromString(it) }
                onLogCallback(session)
            }

            override fun flush() = Unit
        }, DefaultValidator())
    }
}