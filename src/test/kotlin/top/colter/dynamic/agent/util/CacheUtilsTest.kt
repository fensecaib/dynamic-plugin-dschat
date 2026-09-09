package top.colter.dynamic.agent.util

import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class CacheUtilsTest {
    @Test fun `encoded cache evicts least recently used by byte and entry limits`() {
        val cache = BoundedByteCache(maxBytes=6, maxEntries=2)
        cache.put("a", byteArrayOf(1,2)); cache.put("b", byteArrayOf(3,4))
        assertNotNull(cache.get("a"))
        cache.put("c", byteArrayOf(5,6))
        assertNull(cache.get("b"))
        cache.put("d", byteArrayOf(7,8,9,10,11))
        assertNull(cache.get("a")); assertNull(cache.get("c"))
        cache.put("too-big", ByteArray(7))
        assertNotNull(cache.get("d")); assertNull(cache.get("too-big"))
    }

    @Test fun `concurrent reads share one download and reuse memory and disk`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("dota-cache-test")
        try {
            val cache = CacheUtils(dir)
            val requests = AtomicInteger()
            val bytes = byteArrayOf(1,2,3,4)
            val loaded = (1..20).map { async(Dispatchers.Default) {
                cache.getOrDownload("https://test.invalid/hero.png", CacheType.ICON_HERO) { requests.incrementAndGet(); delay(20); bytes }
            } }.awaitAll()
            assertEquals(1, requests.get())
            loaded.forEach { assertContentEquals(bytes, it) }
            val fromDisk = CacheUtils(dir).getOrDownload("https://test.invalid/hero.png", CacheType.ICON_HERO) { error("disk cache must hit") }
            assertContentEquals(bytes, fromDisk)
            cache.cacheFile(CacheType.ICON_HERO, "hero.png").delete()
            assertContentEquals(bytes, cache.getOrDownload("https://test.invalid/hero.png", CacheType.ICON_HERO) { error("memory cache must hit") })
        } finally { cleanTemp(dir) }
    }

    @Test fun `failed and cancelled downloads are retryable`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("dota-cache-test")
        try {
            val cache = CacheUtils(dir)
            val url = "https://test.invalid/item.png"
            assertNull(cache.getOrDownload(url, CacheType.ICON_ITEM) { null })
            assertFailsWith<CancellationException> { cache.getOrDownload(url, CacheType.ICON_ITEM) { throw CancellationException("cancel") } }
            assertContentEquals(byteArrayOf(9), cache.getOrDownload(url, CacheType.ICON_ITEM) { byteArrayOf(9) })
        } finally { cleanTemp(dir) }
    }

    private fun cleanTemp(dir: java.nio.file.Path) {
        check(dir.toAbsolutePath().normalize().startsWith(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
        check(dir.fileName.toString().startsWith("dota-cache-test"))
        dir.toFile().deleteRecursively()
    }
}
