// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.stats.completion.storage

import com.intellij.stats.completion.logger.LineStorage
import com.intellij.stats.completion.logger.LogFileManager
import com.intellij.testFramework.junit5.TestApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random


class FilesProviderTest {
    private lateinit var provider: UniqueFilesProvider

    @BeforeEach
    fun setUp() {
        provider = UniqueFilesProvider("chunk", ".", "logs-data")
        provider.getStatsDataDirectory().deleteRecursively()
    }

    @AfterEach
    fun tearDown() {
        provider.getStatsDataDirectory().deleteRecursively()
    }
    
    @Test
    fun `test three new files created`() {
        provider.getUniqueFile().createNewFile()
        provider.getUniqueFile().createNewFile()
        provider.getUniqueFile().createNewFile()
        
        val createdFiles = provider.getDataFiles().count()
        
        assertThat(createdFiles).isEqualTo(3)
    }
}

class AsciiMessageStorageTest {
    private lateinit var storage: LineStorage
    private lateinit var tmpFile: File

    @BeforeEach
    fun setUp() {
        storage = LineStorage()
        tmpFile = File("tmp_test.gz")
        tmpFile.delete()
    }

    @AfterEach
    fun tearDown() {
        tmpFile.delete()
    }

    @Test
    fun `test size with new lines`() {
        val line = "text"
        storage.appendLine(line)
        val initialSize = storage.size
        storage.appendLine("one more line")
        assertThat(storage.size).isGreaterThan(initialSize)
    }

    @Test
    fun `test file content is expected`() {
        val line = "text"
        storage.appendLine(line)
        storage.appendLine(line)

        storage.dump(tmpFile)
        val lines = LineStorage.readAsLines(tmpFile)
        assertThat(lines).isEqualTo(listOf(line, line))
    }
}

private const val MAX_STORAGE_SIZE = 1024
private const val MAX_CHUNK_SIZE = 50

@TestApplication
class FileLoggerTest {
    @TempDir
    lateinit var tempDirectory: File

    private lateinit var fileLogger: LogFileManager
    private lateinit var filesProvider: UniqueFilesProvider

    @BeforeEach
    fun setUp() {
        filesProvider = UniqueFilesProvider("chunk", tempDirectory.absolutePath, "logs-data", MAX_STORAGE_SIZE)
        fileLogger = LogFileManager(filesProvider, MAX_CHUNK_SIZE)
    }

    @Test
    fun `test chunk not empty`() {
        fileLogger.addChunk()
        val file = filesProvider.getDataFiles().single()
        assertThat(file.length()).isGreaterThan(0).withFailMessage { "Chunk must not be empty" }
    }

    @Test
    fun `test single chunk`() {
        assertTrue(filesProvider.getDataFiles().isEmpty())
        fileLogger.addChunk()
        assertTrue(filesProvider.getDataFiles().isNotEmpty())
    }

    @Test
    fun `test chunk size has limit`() {
        val random = Random(42)
        val iterationLimit = 10_000 // second chunk must be created after adding not more than this number of session


        for (i in 0 until iterationLimit) {
            if (filesProvider.getDataFiles().size < 2) {
                fileLogger.printLines(listOf(random.nextFloat().toString()))
            }
            else {
                break
            }
        }

        val chunks = filesProvider.getDataFiles().map { it.name }
        assertThat(chunks).hasSizeGreaterThan(1).withFailMessage { "logger has not created few chunks: $chunks" }
    }

    @Test
    fun `test multiple chunks`() {
        fileLogger.addChunk()
        fileLogger.addChunk()

        val files = filesProvider.getDataFiles()
        val fileIndexes = files.mapNotNull { UniqueFilesProvider.extractChunkNumber(it.name) }
        assertThat(files.isNotEmpty()).isTrue
        assertThat(fileIndexes).isEqualTo((files.indices).toList())
    }

    @Test
    fun `test delete old stuff`() {
        var minChunkNumber = 0
        var chunks = 0
        while (minChunkNumber == 0 && chunks < MAX_STORAGE_SIZE) { // chunk must be at least one byte
            fileLogger.addChunk()
            chunks += 1

            val files = filesProvider.getDataFiles()
            val totalSizeAfterCleanup = files.fold(0L) { total, file -> total + file.length() }
            assertThat(totalSizeAfterCleanup < MAX_STORAGE_SIZE).isTrue
            minChunkNumber = files.minOf { UniqueFilesProvider.extractChunkNumber(it.name)!! }
        }

        assertThat(minChunkNumber).isGreaterThan(0).withFailMessage { "chunk_0 is not removed when storage size limit exceeded" }
    }

    @Test
    fun `test legacy files in storage`() {
        val oldFile = File(filesProvider.getStatsDataDirectory(), "chunk_0")
        assertFalse(oldFile.exists())
        oldFile.writer().use { it.appendLine("Hello!") }
        assertEquals(listOf("Hello!"), LineStorage.readAsLines(oldFile))
    }

    private fun LogFileManager.addChunk() {
        printLines(listOf("code completion session", "with multiple", "events"))
        flush()
    }
}
