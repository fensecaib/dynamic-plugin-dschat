package top.colter.dynamic.agent.util

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import java.util.Locale

data class DrawCacheCleanupResult(
    val deletedFiles: Int = 0,
    val deletedBytes: Long = 0,
    val failedEntries: Int = 0,
    val skippedEntries: Int = 0,
    val failureDetails: List<String> = emptyList(),
)

/** Cleans generated pictures only; downloaded images and game assets are outside this root. */
object DrawCacheCleanup {
    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif")

    fun cleanup(
        baseDir: Path,
        now: Instant = Instant.now(),
        retentionHours: Long = 72,
    ): DrawCacheCleanupResult = cleanup(baseDir, now, retentionHours) { Files.delete(it) }

    internal fun cleanup(
        baseDir: Path,
        now: Instant,
        retentionHours: Long = 72,
        deleteFile: (Path) -> Unit,
    ): DrawCacheCleanupResult {
        require(retentionHours > 0) { "Draw cache retention hours must be greater than zero" }
        checkInterrupted()
        val cutoff = now.minus(Duration.ofHours(retentionHours))
        val state = CleanupState()
        val base = baseDir.toAbsolutePath().normalize()
        val cache = base.resolve("cache").normalize()
        val draw = cache.resolve("draw").normalize()
        if (!draw.isAbsolute || !draw.startsWith(base) || draw.parent != cache || cache.parent != base) {
            state.skippedEntries++
            return state.result()
        }

        val realDraw: Path
        try {
            // Do not create a cache directory simply because the maintenance job has run.
            val drawAttrs = readAttributes(draw)
            if (!isPlainDirectory(drawAttrs) || !isPlainDirectory(readAttributes(base)) ||
                !isPlainDirectory(readAttributes(cache))
            ) {
                state.skippedEntries++
                return state.result()
            }
            val realBase = base.toRealPath()
            val realCache = cache.toRealPath()
            realDraw = draw.toRealPath()
            // This also rejects Windows junctions even if a provider reports them as directories.
            if (realCache != realBase.resolve("cache") || realDraw != realCache.resolve("draw")) {
                state.skippedEntries++
                return state.result()
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (_: NoSuchFileException) {
            return state.result()
        } catch (e: Exception) {
            state.failed(draw, e)
            return state.result()
        }

        fun visit(directory: Path) {
            checkInterrupted()
            try {
                if (validatedAttributes(directory, draw, realDraw)?.isDirectory != true) {
                    state.skippedEntries++
                    return
                }
                Files.newDirectoryStream(directory).use { entries ->
                    for (entry in entries) {
                        checkInterrupted()
                        try {
                            val attrs = validatedAttributes(entry, draw, realDraw)
                            if (attrs == null) {
                                state.skippedEntries++
                            } else if (attrs.isDirectory) {
                                visit(entry)
                            } else if (attrs.isRegularFile &&
                                entry.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT) in imageExtensions &&
                                attrs.lastModifiedTime().toInstant().isBefore(cutoff)
                            ) {
                                // Recheck both the ancestors and the file immediately before deletion.
                                val current = validatedAttributes(entry, draw, realDraw)
                                if (current?.isRegularFile == true && current.lastModifiedTime().toInstant().isBefore(cutoff)) {
                                    checkInterrupted()
                                    deleteFile(entry)
                                    state.deletedFiles++
                                    state.deletedBytes += current.size()
                                } else {
                                    state.skippedEntries++
                                }
                            } else {
                                state.skippedEntries++
                            }
                        } catch (e: InterruptedException) {
                            throw e
                        } catch (e: Exception) {
                            state.failed(entry, e)
                        }
                    }
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                state.failed(directory, e)
            }
        }

        visit(draw)
        return state.result()
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Draw cache cleanup interrupted")
    }

    /** A null result means a link, reparse point, special file or unexpected real path. */
    private fun validatedAttributes(path: Path, draw: Path, realDraw: Path): BasicFileAttributes? {
        val absolute = path.toAbsolutePath().normalize()
        if (!absolute.startsWith(draw)) return null
        var current = draw
        var attrs = readAttributes(current)
        if (!isPlainDirectory(attrs) || current.toRealPath() != realDraw) return null
        val relative = draw.relativize(absolute)
        if (relative.toString().isEmpty()) return attrs
        val segments = relative.toList()
        for ((index, segment) in segments.withIndex()) {
            current = current.resolve(segment)
            attrs = readAttributes(current)
            if (attrs.isSymbolicLink || attrs.isOther) return null
            if (index < segments.lastIndex && !attrs.isDirectory) return null
            val expectedRealPath = realDraw.resolve(draw.relativize(current))
            if (current.toRealPath() != expectedRealPath) return null
        }
        return attrs
    }

    private fun readAttributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    private fun isPlainDirectory(attrs: BasicFileAttributes) =
        attrs.isDirectory && !attrs.isSymbolicLink && !attrs.isOther

    private class CleanupState {
        var deletedFiles = 0
        var deletedBytes = 0L
        var failedEntries = 0
        var skippedEntries = 0
        val failureDetails = mutableListOf<String>()

        fun failed(path: Path, cause: Exception) {
            failedEntries++
            if (failureDetails.size < 10) {
                failureDetails += "$path: ${cause.javaClass.simpleName}: ${cause.message.orEmpty()}"
            }
        }

        fun result() = DrawCacheCleanupResult(deletedFiles, deletedBytes, failedEntries, skippedEntries, failureDetails.toList())
    }
}
