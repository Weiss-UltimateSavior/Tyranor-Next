package com.tyranor.next.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 线程安全的内存 SharedPreferences 测试实现（替代各测试内重复的 Proxy 桩）：
 * - 全字段同步加锁，兼容多线程并发刷新/读写；
 * - 完整支持 `putString(k, null)` / `putStringSet(k, null)` 移除语义与 `StringSet` 防御性拷贝；
 * - `clear()` 先于同批次写入执行，与 Android `SharedPreferencesImpl` 提交语义一致。
 */
class InMemorySharedPreferences(
    private val backing: MutableMap<String, Any> = mutableMapOf(),
    private val onCommit: (() -> Unit)? = null,
) : SharedPreferences {
    private val lock = Any()
    private val listeners = Collections.newSetFromMap(WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Boolean>())

    fun snapshot(): Map<String, Any> = synchronized(lock) { backing.toMap() }

    override fun getAll(): MutableMap<String, *> = synchronized(lock) { LinkedHashMap(backing) }

    override fun getString(key: String, defValue: String?): String? = synchronized(lock) {
        backing[key] as? String ?: defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = synchronized(lock) {
        val stored = backing[key] as? Set<String> ?: return@synchronized defValues?.let(::LinkedHashSet)
        LinkedHashSet(stored)
    }

    override fun getInt(key: String, defValue: Int): Int = synchronized(lock) {
        backing[key] as? Int ?: defValue
    }

    override fun getLong(key: String, defValue: Long): Long = synchronized(lock) {
        backing[key] as? Long ?: defValue
    }

    override fun getFloat(key: String, defValue: Float): Float = synchronized(lock) {
        backing[key] as? Float ?: defValue
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(lock) {
        backing[key] as? Boolean ?: defValue
    }

    override fun contains(key: String): Boolean = synchronized(lock) {
        backing.containsKey(key)
    }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(lock) { listeners.add(listener) }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(lock) { listeners.remove(listener) }
    }

    private inner class Editor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            pending[key] = value
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            pending[key] = values?.let(::LinkedHashSet)
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            pending[key] = value
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            pending[key] = value
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            pending[key] = value
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            pending[key] = value
        }

        override fun remove(key: String): SharedPreferences.Editor = apply {
            pending[key] = null
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearRequested = true
            pending.clear()
        }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() {
            applyChanges()
        }

        private fun applyChanges() {
            val changedKeys = mutableListOf<String>()
            val activeListeners: List<SharedPreferences.OnSharedPreferenceChangeListener>
            synchronized(lock) {
                if (clearRequested) {
                    if (backing.isNotEmpty()) {
                        changedKeys.addAll(backing.keys)
                        backing.clear()
                    }
                }
                for ((key, value) in pending) {
                    if (value == null) {
                        if (backing.containsKey(key)) {
                            backing.remove(key)
                            if (key !in changedKeys) changedKeys.add(key)
                        }
                    } else {
                        val stored = if (value is Set<*>) LinkedHashSet(value) else value
                        if (backing[key] != stored) {
                            backing[key] = stored
                            if (key !in changedKeys) changedKeys.add(key)
                        }
                    }
                }
                activeListeners = listeners.toList()
            }
            onCommit?.invoke()
            for (listener in activeListeners) {
                for (key in changedKeys) {
                    listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, key)
                }
            }
        }
    }
}

/** 面向 JVM 单测的内存 ContextWrapper：提供线程安全按文件名隔离的 [InMemorySharedPreferences]。 */
class InMemoryPreferencesContext(
    private val filesDirProvider: () -> File = { error("filesDir not configured") },
    private val cacheDirProvider: () -> File = { error("cacheDir not configured") },
    private val noBackupFilesDirProvider: () -> File = { error("noBackupFilesDir not configured") },
    private val onCommit: ((prefsName: String) -> Unit)? = null,
) : ContextWrapper(null) {
    private val stores = ConcurrentHashMap<String, InMemorySharedPreferences>()

    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = filesDirProvider()

    override fun getCacheDir(): File = cacheDirProvider()

    override fun getNoBackupFilesDir(): File = noBackupFilesDirProvider()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        stores.computeIfAbsent(name) {
            InMemorySharedPreferences(
                onCommit = onCommit?.let { callback -> { callback(name) } },
            )
        }

    fun prefSnapshot(name: String): Map<String, Any> =
        stores[name]?.snapshot().orEmpty()

    fun clearAllPreferences() {
        stores.clear()
    }
}
