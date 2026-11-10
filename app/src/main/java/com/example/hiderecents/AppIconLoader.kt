package com.example.hiderecents

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * Async app icon loader with LRU cache.
 * Avoids blocking the main thread when loading large numbers of icons.
 */
object AppIconLoader {

    private val cache = LruCache<String, Drawable>(200)
    private val executor = Executors.newFixedThreadPool(2)
    private val pending = mutableSetOf<String>()

    fun getCached(packageName: String): Drawable? = cache.get(packageName)

    fun put(packageName: String, icon: Drawable) { cache.put(packageName, icon) }

    /**
     * Load icon asynchronously, call [onLoaded] on the calling thread when done.
     * Skips if already cached or already loading.
     */
    fun load(pm: PackageManager, packageName: String, onLoaded: (Drawable) -> Unit) {
        val cached = cache.get(packageName)
        if (cached != null) {
            onLoaded(cached)
            return
        }
        synchronized(pending) {
            if (packageName in pending) return
            pending.add(packageName)
        }
        executor.submit {
            try {
                val info = pm.getApplicationInfo(packageName, 0)
                val icon = pm.getApplicationIcon(info)
                cache.put(packageName, icon)
                onLoaded(icon)
            } catch (_: Exception) {
            } finally {
                synchronized(pending) { pending.remove(packageName) }
            }
        }
    }

    /**
     * Batch-load icons for a list of package names.
     * [onEach] is called per icon (may be on worker thread).
     * [onDone] is called once when all are finished (also on worker thread).
     */
    fun loadBatch(
        pm: PackageManager,
        packageNames: List<String>,
        onEach: (String, Drawable) -> Unit,
        onDone: () -> Unit
    ) {
        executor.submit {
            for (pkg in packageNames) {
                val cached = cache.get(pkg)
                if (cached != null) {
                    onEach(pkg, cached)
                    continue
                }
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    val icon = pm.getApplicationIcon(info)
                    cache.put(pkg, icon)
                    onEach(pkg, icon)
                } catch (_: Exception) {}
            }
            onDone()
        }
    }
}
