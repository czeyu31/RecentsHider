package com.example.hiderecents

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.util.concurrent.Executors
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Async app icon loader with LRU cache.
 * Avoids blocking the main thread when loading large numbers of icons.
 */
object AppIconLoader {

    private val cache = LruCache<String, Drawable>(200)
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bitmapCache = LruCache<String, Bitmap>(600)

    /** 进程级图标位图缓存：反复进入应用管理时不再重复解码。 */
    fun getCachedBitmap(packageName: String): Bitmap? = bitmapCache.get(packageName)

    fun putBitmap(packageName: String, bitmap: Bitmap) {
        bitmapCache.put(packageName, bitmap)
    }

    /** 取图标位图：优先位图缓存，其次 drawable 缓存，最后从包管理器取并转换。 */
    fun bitmapFor(pm: PackageManager, packageName: String, size: Int): Bitmap? {
        bitmapCache.get(packageName)?.let { return it }
        val drawable = getCached(packageName)
            ?: runCatching { pm.getApplicationIcon(pm.getApplicationInfo(packageName, 0)) }.getOrNull()
            ?: return null
        put(packageName, drawable)
        val bmp = toSafeBitmap(drawable, size)
        bitmapCache.put(packageName, bmp)
        return bmp
    }
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

    /**
     * 线程安全的 Drawable -> Bitmap 转换（图标崩溃修复）。
     *
     * 崩溃证据（小米 cupid / Android 15，logcat -b crash）：
     *   libhwui SkMatrixPriv::MapRect <- SkCanvas::internalQuickReject
     *   <- SkCanvas::onDrawImageRect2 <- drawBitmap <- BitmapDrawable.draw
     *   <- AdaptiveIconDrawable.draw；线程是 Thread-10 / Thread-12。
     * 图标线程池的两个工作线程同时在画 AdaptiveIconDrawable，而 hwui/Skia
     * 这条路径不是线程安全的，直接 SIGSEGV(null deref) 杀进程 ——
     * 用户看到的就是「点应用管理 / 无障碍被弹回首页」。
     *
     * 因此所有转换统一切到主线程串行执行；本身就在主线程调用时直接转换，避免死锁。
     */
    fun toSafeBitmap(drawable: Drawable, size: Int): Bitmap {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching { drawable.toBitmap(size, size) }
                .getOrElse { Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888) }
        }
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        mainHandler.post {
            result = runCatching { drawable.toBitmap(size, size) }.getOrNull()
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return result ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    }

}
