package coil.memory

import android.content.ComponentCallbacks2
import android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
import android.graphics.Bitmap
import android.util.Log
import androidx.collection.LruCache
import coil.memory.MemoryCache.Value
import coil.util.Logger
import coil.util.getAllocationByteCountCompat
import coil.util.log

/** An in-memory cache for [Bitmap]s. */
internal interface MemoryCache {

    companion object {
        operator fun invoke(
            weakMemoryCache: WeakMemoryCache,
            referenceCounter: BitmapReferenceCounter,
            maxSize: Int,
            logger: Logger?
        ): MemoryCache {
            return when {
                maxSize > 0 -> RealMemoryCache(weakMemoryCache, referenceCounter, maxSize, logger)
                weakMemoryCache is RealWeakMemoryCache -> ForwardingMemoryCache(weakMemoryCache)
                else -> EmptyMemoryCache
            }
        }
    }

    /** Get the value associated with [key]. */
    fun get(key: String): Value?

    /** Set the value associated with [key]. */
    fun set(key: String, bitmap: Bitmap, isSampled: Boolean)

    /** Return the **current size** of the memory cache in bytes. */
    fun size(): Int

    /** Return the **maximum size** of the memory cache in bytes. */
    fun maxSize(): Int

    /** Remove all values from this cache. */
    fun clearMemory()

    /** @see ComponentCallbacks2.onTrimMemory */
    fun trimMemory(level: Int)

    /** Remove one value from this cache. */
    fun invalidate(key: String)

    interface Value {
        val bitmap: Bitmap
        val isSampled: Boolean
    }
}

/** A [MemoryCache] implementation that caches nothing. */
private object EmptyMemoryCache : MemoryCache {

    override fun get(key: String): Value? = null

    override fun set(key: String, bitmap: Bitmap, isSampled: Boolean) {}

    override fun size(): Int = 0

    override fun maxSize(): Int = 0

    override fun clearMemory() {}

    override fun invalidate(key: String) {}

    override fun trimMemory(level: Int) {}
}

/** A [MemoryCache] implementation that caches nothing and delegates to [weakMemoryCache]. */
private class ForwardingMemoryCache(
    private val weakMemoryCache: WeakMemoryCache
) : MemoryCache {

    override fun get(key: String) = weakMemoryCache.get(key)

    override fun set(key: String, bitmap: Bitmap, isSampled: Boolean) {
        weakMemoryCache.set(key, bitmap, isSampled, bitmap.getAllocationByteCountCompat())
    }

    override fun size() = 0

    override fun maxSize() = 0

    override fun clearMemory() {}

    override fun trimMemory(level: Int) {}

    override fun invalidate(key: String) = get(key)?.bitmap?.let { weakMemoryCache.invalidate(it) } ?: Unit
}

/** A [MemoryCache] implementation backed by an [LruCache]. */
private class RealMemoryCache(
    private val weakMemoryCache: WeakMemoryCache,
    private val referenceCounter: BitmapReferenceCounter,
    maxSize: Int,
    private val logger: Logger?
) : MemoryCache {

    companion object {
        private const val TAG = "RealMemoryCache"
    }

    private val cache = object : LruCache<String, InternalValue>(maxSize) {
        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: InternalValue,
            newValue: InternalValue?
        ) {
            val isPooled = referenceCounter.decrement(oldValue.bitmap)
            if (!isPooled) {
                // Add the bitmap to the WeakMemoryCache if it wasn't just added to the BitmapPool.
                weakMemoryCache.set(key, oldValue.bitmap, oldValue.isSampled, oldValue.size)
            }
        }

        override fun sizeOf(key: String, value: InternalValue) = value.size
    }

    override fun get(key: String) = cache.get(key) ?: weakMemoryCache.get(key)

    override fun set(key: String, bitmap: Bitmap, isSampled: Boolean) {
        // If the bitmap is too big for the cache, don't even attempt to store it. Doing so will cause
        // the cache to be cleared. Instead just evict an existing element with the same key if it exists.
        val size = bitmap.getAllocationByteCountCompat()
        if (size > maxSize()) {
            val previous = cache.remove(key)
            if (previous == null) {
                // If previous != null, the value was already added to the weak memory cache in LruCache.entryRemoved.
                weakMemoryCache.set(key, bitmap, isSampled, size)
            }
            return
        }

        referenceCounter.increment(bitmap)
        cache.put(key, InternalValue(bitmap, isSampled, size))
    }

    override fun size() = cache.size()

    override fun maxSize() = cache.maxSize()

    override fun clearMemory() {
        logger?.log(TAG, Log.DEBUG) { "clearMemory" }
        cache.trimToSize(-1)
    }

    override fun invalidate(key: String) {
        logger?.log(TAG, Log.DEBUG) { "invalidate($key)" }
        cache.remove(key)
    }

    override fun trimMemory(level: Int) {
        logger?.log(TAG, Log.DEBUG) { "trimMemory, level=$level" }
        if (level >= TRIM_MEMORY_BACKGROUND) {
            clearMemory()
        } else if (level in TRIM_MEMORY_RUNNING_LOW until TRIM_MEMORY_UI_HIDDEN) {
            cache.trimToSize(size() / 2)
        }
    }

    private class InternalValue(
        override val bitmap: Bitmap,
        override val isSampled: Boolean,
        val size: Int
    ) : Value
}
