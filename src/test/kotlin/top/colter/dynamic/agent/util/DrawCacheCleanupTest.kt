package top.colter.dynamic.agent.util

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DrawCacheCleanupTest {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val cutoff = now.minus(Duration.ofHours(72))

    @Test fun `deletes only images strictly older than 72 hours`() = withTemp { base ->
        val old = file(base, "cache/draw/old.png", cutoff.minusMillis(1), 3)
        val boundary = file(base, "cache/draw/boundary.png", cutoff, 5)
        val recent = file(base, "cache/draw/recent.png", cutoff.plusMillis(1), 7)
        val future = file(base, "cache/draw/future.png", now.plusSeconds(60), 9)

        val result = DrawCacheCleanup.cleanup(base, now)

        assertFalse(Files.exists(old))
        listOf(boundary, recent, future).forEach { assertTrue(Files.exists(it)) }
        assertEquals(1, result.deletedFiles)
        assertEquals(3L, result.deletedBytes)
        assertEquals(0, result.failedEntries)
        assertEquals(3, result.skippedEntries)
    }

    @Test fun `custom retention hours changes the expiry cutoff`() = withTemp { base ->
        val thirtyHoursOld = file(base, "cache/draw/thirty-hours.png", now.minus(Duration.ofHours(30)))
        val eightyHoursOld = file(base, "cache/draw/eighty-hours.png", now.minus(Duration.ofHours(80)))
        val recent = file(base, "cache/draw/recent.png", now.minus(Duration.ofHours(10)))

        val defaultResult = DrawCacheCleanup.cleanup(base, now)

        assertEquals(1, defaultResult.deletedFiles)
        assertFalse(Files.exists(eightyHoursOld))
        assertTrue(Files.exists(thirtyHoursOld))
        assertTrue(Files.exists(recent))

        val customResult = DrawCacheCleanup.cleanup(base, now, retentionHours = 24)

        assertEquals(1, customResult.deletedFiles)
        assertFalse(Files.exists(thirtyHoursOld))
        assertTrue(Files.exists(recent))
    }

    @Test fun `zero and negative retention are rejected without deleting pictures`() = withTemp { base ->
        val drawing = file(base, "cache/draw/report.png", now.minus(Duration.ofHours(80)))

        for (invalidHours in listOf(0L, -1L)) {
            assertFailsWith<IllegalArgumentException> {
                DrawCacheCleanup.cleanup(base, now, retentionHours = invalidHours)
            }
            assertTrue(Files.exists(drawing))
        }
    }

    @Test fun `recurses generated pictures but preserves unknown files and every directory`() = withTemp { base ->
        val extensions = listOf("PNG", "jpg", "JPEG", "webp", "gif")
        val images = extensions.map { file(base, "cache/draw/nested/picture.$it", cutoff.minusSeconds(1), 2) }
        val unknown = file(base, "cache/draw/nested/notes.json", cutoff.minusSeconds(1))
        val noExtension = file(base, "cache/draw/nested/no-extension", cutoff.minusSeconds(1))
        val imageNamedDirectory = Files.createDirectories(base.resolve("cache/draw/empty.png"))

        val result = DrawCacheCleanup.cleanup(base, now)

        images.forEach { assertFalse(Files.exists(it)) }
        listOf(unknown, noExtension, imageNamedDirectory, imageNamedDirectory.parent.resolve("nested")).forEach {
            assertTrue(Files.exists(it))
        }
        assertEquals(5, result.deletedFiles)
        assertEquals(10L, result.deletedBytes)
        assertEquals(2, result.skippedEntries)
    }

    @Test fun `never touches hero item avatar emoji or downloaded image caches`() = withTemp { base ->
        val kept = listOf(
            "cache/dota2/icons/heroes/hero.png", "cache/dota2/icons/items/item.png",
            "cache/dota2/icons/avatars/avatar.png", "cache/images/image.png", "cache/user/user.png",
            "cache/emoji/emoji.png", "cache/other/other.png", "draw/picture.png",
        ).map { file(base, it, cutoff.minusSeconds(1)) }
        val drawing = file(base, "cache/draw/report.png", cutoff.minusSeconds(1))

        val result = DrawCacheCleanup.cleanup(base, now)

        assertFalse(Files.exists(drawing))
        kept.forEach { assertTrue(Files.exists(it)) }
        assertEquals(1, result.deletedFiles)
        assertEquals(0, result.failedEntries)
    }

    @Test fun `missing draw directory causes no directory creation`() = withTemp { base ->
        assertEquals(DrawCacheCleanupResult(), DrawCacheCleanup.cleanup(base, now))
        assertFalse(Files.exists(base.resolve("cache")))
    }

    @Test fun `a file in place of draw is preserved`() = withTemp { base ->
        val draw = file(base, "cache/draw", cutoff.minusSeconds(1))

        val result = DrawCacheCleanup.cleanup(base, now)

        assertTrue(Files.exists(draw))
        assertEquals(0, result.deletedFiles)
        assertEquals(1, result.skippedEntries)
    }

    @Test fun `a single deletion failure does not stop other pictures`() = withTemp { base ->
        val failed = file(base, "cache/draw/locked.png", cutoff.minusSeconds(1), 3)
        val deleted = file(base, "cache/draw/other.png", cutoff.minusSeconds(1), 5)

        val result = DrawCacheCleanup.cleanup(base, now) { path ->
            if (path == failed) throw IOException("simulated locked image")
            Files.delete(path)
        }

        assertTrue(Files.exists(failed))
        assertFalse(Files.exists(deleted))
        assertEquals(1, result.deletedFiles)
        assertEquals(5L, result.deletedBytes)
        assertEquals(1, result.failedEntries)
        assertTrue(result.failureDetails.single().contains("simulated locked image"))
    }

    @Test fun `an already interrupted cleanup exits before deleting any file`() = withTemp { base ->
        val drawing = file(base, "cache/draw/report.png", cutoff.minusSeconds(1))
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<InterruptedException> { DrawCacheCleanup.cleanup(base, now) }
        } finally {
            Thread.interrupted()
        }
        assertTrue(Files.exists(drawing))
    }

    @Test fun `interruption during deletion is propagated instead of counted as a file failure`() = withTemp { base ->
        val drawing = file(base, "cache/draw/report.png", cutoff.minusSeconds(1))
        val other = file(base, "cache/draw/other.png", cutoff.minusSeconds(1))
        try {
            assertFailsWith<InterruptedException> {
                DrawCacheCleanup.cleanup(base, now) {
                    Thread.currentThread().interrupt()
                    throw InterruptedException("cancel cleanup")
                }
            }
        } finally {
            Thread.interrupted()
        }
        assertTrue(Files.exists(drawing))
        assertTrue(Files.exists(other))
    }

    @Test fun `normalized absolute paths still limit cleanup to draw`() = withTemp { base ->
        val drawing = file(base, "cache/draw/report.png", cutoff.minusSeconds(1))
        val icon = file(base, "cache/images/icon.png", cutoff.minusSeconds(1))

        val result = DrawCacheCleanup.cleanup(base.resolve("unused/.."), now)

        assertEquals(1, result.deletedFiles)
        assertFalse(Files.exists(drawing))
        assertTrue(Files.exists(icon))
    }

    @Test fun `does not follow directory or image symbolic links out of draw`() = withTemp { base ->
        val outside = file(base, "outside/keep.png", cutoff.minusSeconds(1))
        val draw = Files.createDirectories(base.resolve("cache/draw"))
        if (!createLink(draw.resolve("escaped"), outside.parent)) return@withTemp
        assertTrue(createLink(draw.resolve("linked.png"), outside))
        val drawing = file(base, "cache/draw/report.png", cutoff.minusSeconds(1))

        val result = DrawCacheCleanup.cleanup(base, now)

        assertTrue(Files.exists(outside))
        assertTrue(Files.isSymbolicLink(draw.resolve("escaped")))
        assertTrue(Files.isSymbolicLink(draw.resolve("linked.png")))
        assertFalse(Files.exists(drawing))
        assertEquals(1, result.deletedFiles)
        assertEquals(2, result.skippedEntries)
    }

    @Test fun `a symbolic link used as draw root is never traversed`() = withTemp { base ->
        val outside = file(base, "outside/keep.png", cutoff.minusSeconds(1))
        Files.createDirectories(base.resolve("cache"))
        val draw = base.resolve("cache/draw")
        if (!createLink(draw, outside.parent)) return@withTemp

        val result = DrawCacheCleanup.cleanup(base, now)

        assertTrue(Files.exists(outside))
        assertTrue(Files.isSymbolicLink(draw))
        assertEquals(0, result.deletedFiles)
        assertEquals(1, result.skippedEntries)
    }

    @Test fun `a symbolic link used as cache root is never traversed`() = withTemp { base ->
        val outside = file(base, "outside/draw/keep.png", cutoff.minusSeconds(1))
        if (!createLink(base.resolve("cache"), outside.parent.parent)) return@withTemp

        val result = DrawCacheCleanup.cleanup(base, now)

        assertTrue(Files.exists(outside))
        assertEquals(0, result.deletedFiles)
        assertEquals(1, result.skippedEntries)
    }

    private fun file(base: Path, relative: String, modified: Instant, size: Int = 1): Path {
        val path = base.resolve(relative)
        Files.createDirectories(path.parent)
        Files.write(path, ByteArray(size))
        Files.setLastModifiedTime(path, FileTime.from(modified))
        return path
    }

    private fun createLink(link: Path, target: Path): Boolean = try {
        Files.createSymbolicLink(link, target)
        true
    } catch (_: IOException) {
        // Windows can require Developer Mode or an elevated process to create symbolic links.
        false
    } catch (_: UnsupportedOperationException) {
        false
    }

    private fun withTemp(test: (Path) -> Unit) {
        val root = Files.createTempDirectory("draw-cleanup-test").toAbsolutePath().normalize()
        try {
            test(root)
        } finally {
            val temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
            check(root.startsWith(temporaryRoot) && root.fileName.toString().startsWith("draw-cleanup-test"))
            // File-tree traversal does not follow links, including the escape links made by these tests.
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
