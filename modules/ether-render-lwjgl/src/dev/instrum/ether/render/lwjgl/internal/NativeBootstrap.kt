package dev.instrum.ether.render.lwjgl

import java.nio.file.Files

/**
 * Extracts `liblwjgl.so` shipped as a module resource and points
 * `org.lwjgl.librarypath` at it. Amper publishes JVM jars without the native
 * classifier artifacts at runtime, so the library travels inside the jar
 * instead.
 *
 * The bootstrap must run before the first LWJGL class is loaded; the backend
 * probe calls [ensure] itself, so application code never needs to touch it.
 * Only Linux/x64 carries a resource right now: when macOS or Windows support
 * is measured, extract the matching `liblwjgl.dylib`/`lwjgl.dll` there.
 */
internal object NativeBootstrap {
    @Volatile
    private var done = false

    /**
     * Prepares the LWJGL native library path exactly once per process.
     */
    fun ensure() {
        if (done) return
        synchronized(this) {
            if (done) return
            val candidates = listOf(
                "natives/linux/liblwjgl.so",
                "linux/x64/org/lwjgl/liblwjgl.so",
            )
            var so: ByteArray? = null
            for (path in candidates) {
                so = NativeBootstrap::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
                if (so != null) break
            }
            if (so != null) {
                val dir = Files.createTempDirectory("ether-lwjgl")
                Files.write(dir.resolve("liblwjgl.so"), so)
                // LWJGL reads this property when it loads its own natives; setting it before the
                // first LWJGL class use makes the extraction the authoritative location.
                if (System.getProperty("org.lwjgl.librarypath") == null) {
                    System.setProperty("org.lwjgl.librarypath", dir.toString())
                }
            }
            // Without a bundled native the machine is expected to provide LWJGL natives on the
            // JVM library path; probing decides availability either way.
            done = true
        }
    }
}
