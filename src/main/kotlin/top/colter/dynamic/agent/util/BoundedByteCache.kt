package top.colter.dynamic.agent.util

/** 仅缓存编码后的资源字节，不共享有关闭生命周期的 Skia Image。 */
internal class BoundedByteCache(private val maxBytes: Int = 16 * 1024 * 1024, private val maxEntries: Int = 256) {
    private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var size = 0

    @Synchronized fun get(key: String): ByteArray? = entries[key]

    @Synchronized fun put(key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > maxBytes) return
        entries.remove(key)?.let { size -= it.size }
        entries[key] = bytes
        size += bytes.size
        while (size > maxBytes || entries.size > maxEntries) {
            val iterator = entries.entries.iterator()
            size -= iterator.next().value.size
            iterator.remove()
        }
    }
}
