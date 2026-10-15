package top.colter.dynamic.agent.dota2

import kotlinx.coroutines.*
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import top.colter.dynamic.agent.util.CacheUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class Dota2AghsOwnershipTest {
    @Test fun `completed icons remain owned and reusable when slower sibling downloads are cancelled`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("dota-aghs-review-")
        val allocated = mutableListOf<Image>()
        fun image(): Image = Surface.makeRasterN32Premul(8, 8).use {
            it.makeImageSnapshot().also(allocated::add)
        }
        try {
            val service = Dota2Service(dir.toFile(), CacheUtils(dir))
            val fastIcon = image()
            val loading = launch {
                service.ensureAghsIcons { url ->
                    if (url.endsWith("/shard_0.png")) fastIcon else awaitCancellation()
                }
            }
            withTimeout(3_000) {
                while (service.getAghsIcon(has = false, isShard = true) == null) yield()
            }
            loading.cancelAndJoin()
            assertSame(fastIcon, service.getAghsIcon(has = false, isShard = true))
            assertFalse(fastIcon.isClosed)
            val retries = mutableListOf<String>()
            service.ensureAghsIcons { url -> retries.add(url); image() }
            assertEquals(3, retries.size)
            assertTrue(retries.none { it.endsWith("/shard_0.png") })
            assertSame(fastIcon, service.getAghsIcon(has = false, isShard = true))
        } finally {
            allocated.forEach { it.close() }
            val resolved = dir.toAbsolutePath().normalize()
            check(resolved.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            check(dir.fileName.toString().startsWith("dota-aghs-review-"))
            dir.toFile().deleteRecursively()
        }
    }
}
