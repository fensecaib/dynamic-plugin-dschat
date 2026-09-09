package top.colter.dynamic.agent.util

import org.jetbrains.skia.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Path

enum class CacheType(val dir: String) {
    DRAW("draw"),
    IMAGES("images"),
    EMOJI("emoji"),
    USER("user"),
    ICON_HERO("dota2/icons/heroes"),
    ICON_ITEM("dota2/icons/items"),
    ICON_AGHS("dota2/icons/aghs"),
    ICON_AVATAR("dota2/icons/avatars"),
    OTHER("other"),
}

class CacheUtils(private val baseDir: Path) {
    private val resourceBytes = BoundedByteCache()
    // 固定锁条带避免每个 URL 永久保留一把锁；同文件的并发下载与磁盘写入合并。
    private val resourceLocks = Array(64) { Mutex() }

    fun cacheDir(type: CacheType): File {
        val dir = baseDir.resolve("cache").resolve(type.dir).toFile()
        dir.mkdirs()
        return dir
    }

    fun cacheFile(type: CacheType, filename: String): File {
        return cacheDir(type).resolve(filename)
    }

    fun findCachedFile(type: CacheType, filename: String): File? {
        val file = cacheFile(type, filename)
        return if (file.exists() && file.length() > 0) file else null
    }

    fun cacheImage(image: Image, type: CacheType, filename: String): File {
        val file = cacheFile(type, filename)
        val data = image.encodeToData() ?: return file
        file.writeBytes(data.bytes)
        return file
    }

    suspend fun getOrDownload(
        url: String,
        type: CacheType,
        httpGetBytes: suspend (String) -> ByteArray?
    ): ByteArray? {
        val rawName = url.substringAfterLast("/").substringBefore("?")
        val filename = rawName.replace(Regex("""[<>:"/\\|?*]"""), "_")
        if (filename.isBlank()) return null
        val key = "${type.dir}/$filename"
        resourceBytes.get(key)?.let { return it }
        return resourceLocks[(key.hashCode() and Int.MAX_VALUE) % resourceLocks.size].withLock {
            resourceBytes.get(key)?.let { return@withLock it }
            val cached = withContext(Dispatchers.IO) { findCachedFile(type, filename)?.readBytes() }
            val bytes = cached ?: httpGetBytes(url)?.takeIf { it.isNotEmpty() } ?: return@withLock null
            if (cached == null) withContext(Dispatchers.IO) { cacheFile(type, filename).writeBytes(bytes) }
            resourceBytes.put(key, bytes)
            bytes
        }
    }

    suspend fun getOrDownloadImage(
        url: String,
        type: CacheType,
        httpGetBytes: suspend (String) -> ByteArray?
    ): Image? {
        val bytes = getOrDownload(url, type, httpGetBytes) ?: return null
        return try {
            Image.makeFromEncoded(bytes)
        } catch (_: Exception) {
            null
        }
    }

    fun resolvePath(relative: String): File {
        return baseDir.resolve(relative).toFile().also { it.parentFile?.mkdirs() }
    }
}
