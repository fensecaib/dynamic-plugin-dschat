package top.colter.dynamic.agent.dota2

import kotlinx.serialization.json.*
import top.colter.dynamic.agent.util.CacheUtils
import top.colter.dynamic.agent.util.HttpUtils
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class Dota2BindingsTest {
    private fun withDirectory(test: (Path) -> Unit) {
        val dir = Files.createTempDirectory("dota-bindings-review-")
        try {
            test(dir)
        } finally {
            val resolved = dir.toAbsolutePath().normalize()
            check(resolved.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            check(dir.fileName.toString().startsWith("dota-bindings-review-"))
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `concurrent bindings persist every successful update as complete JSON`() = withDirectory { dir ->
        val service = Dota2Service(dir.toFile(), CacheUtils(dir))
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val updates = (1..32).map { id -> executor.submit {
                check(start.await(5, TimeUnit.SECONDS))
                service.setBinding("sender-$id", id.toLong())
                assertEquals(id.toLong(), service.getBinding("sender-$id"))
            } }
            start.countDown()
            updates.forEach { it.get(10, TimeUnit.SECONDS) }
            val stored = HttpUtils.json.parseToJsonElement(Files.readString(dir.resolve("dota2-bindings.json"))).jsonObject
            assertEquals(32, stored.size)
            val reloaded = Dota2Service(dir.toFile(), CacheUtils(dir)).also { it.init() }
            (1..32).forEach { id -> assertEquals(id.toLong(), reloaded.getBinding("sender-$id")) }
            Files.list(dir).use { files -> assertEquals(1L, files.count()) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun `corrupt bindings are preserved without partial loading or erasing memory`() = withDirectory { dir ->
        val service = Dota2Service(dir.toFile(), CacheUtils(dir))
        service.setBinding("existing", 10)
        val path = dir.resolve("dota2-bindings.json")
        for (corrupt in listOf("{", "{\"valid-first\":1,\"invalid-second\":\"not-an-id\"}")) {
            Files.writeString(path, corrupt)
            service.init()
            assertEquals(corrupt, Files.readString(path))
            assertEquals(10L, service.getBinding("existing"))
            val fresh = Dota2Service(dir.toFile(), CacheUtils(dir)).also { it.init() }
            assertNull(fresh.getBinding("valid-first"))
            assertEquals(corrupt, Files.readString(path))
        }
        val original = Files.readString(path)
        service.setBinding("new", 20)
        val backups = Files.list(dir).use { files ->
            files.filter { it.fileName.toString().startsWith("dota2-bindings-corrupt-") }.toList()
        }
        assertEquals(listOf(original), backups.map { Files.readString(it) })
        val reloaded = Dota2Service(dir.toFile(), CacheUtils(dir)).also { it.init() }
        assertEquals(10L, reloaded.getBinding("existing"))
        assertEquals(20L, reloaded.getBinding("new"))
        assertNull(reloaded.getBinding("valid-first"))
    }

    @Test fun `failed persistence leaves the last successful in-memory bindings intact`() = withDirectory { dir ->
        val service = Dota2Service(dir.toFile(), CacheUtils(dir))
        service.setBinding("existing", 10)
        val path = dir.resolve("dota2-bindings.json")
        Files.delete(path)
        Files.createDirectory(path)
        Files.writeString(path.resolve("block-replacement"), "keep")
        assertFailsWith<IOException> { service.setBinding("new", 20) }
        assertEquals(10L, service.getBinding("existing"))
        assertNull(service.getBinding("new"))
        assertEquals("keep", Files.readString(path.resolve("block-replacement")))
        Files.list(dir).use { files ->
            assertFalse(files.anyMatch { it.fileName.toString().endsWith(".tmp") })
        }
    }
}
