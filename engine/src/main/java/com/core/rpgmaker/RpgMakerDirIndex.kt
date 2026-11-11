package com.core.rpgmaker

import java.io.File
import java.util.LinkedHashMap

/**
 * 目录名索引：把 `File.list()` 的结果按「目录 + mtime」缓存，避免同一次会话里对同一个
 * 目录反复 readdir、反复分配名字数组。
 *
 * 使用方（两处都只用于**名字匹配**，不承担存在性判定）：
 *  - [RpgMakerFsBridge] 的宽松名解析（精确未命中时逐段列目录）；
 *  - [RpgMakerLocalHttpServer] 的 `fuzzyNames` 回退。
 *
 * 失效条件：
 *  1. 目录 `lastModified()` 变化（外部增删条目）；
 *  2. 写入方主动 [invalidate] / [invalidateAll]（fs 桥自己的写/删/建目录）。
 *
 * 刻意接受的取舍：条目变更与 mtime 落在同一毫秒（或 exFAT 这类粗粒度时间戳的文件
 * 系统）时，索引可能短暂偏旧。风险被调用方的结构挡住——匹配结果只是「候选名字」，
 * 拿到路径后仍会 `exists()`/`isFile()` 校验，最坏情况退化为未命中，不会读到别的文件。
 *
 * 与 [RpgMakerNameMatcher.DEFAULT_MAX_FUZZY_ENTRIES] 的关系：两个 4096 是**不同策略**。
 * 这里限制「缓存多少个名字」（内存），那里限制「要不要做空白容忍比对」（CPU）；
 * 数值相同只是巧合，改动其一不影响另一。
 *
 * 未接入 `TyranoLocalHttpServer`：Tyrano 的目录解析语义不同（无空白容忍、大小写
 * 不敏感取首个命中），本索引只服务 RPG Maker 壳；若要共用，应连同语义一起迁到
 * `com.core.web`。
 */
internal class RpgMakerDirIndex {

    private class Entry(val stamp: Long, val children: List<String>)

    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > MAX_CACHED_DIRS
    }

    /** 子项名列表；`null` 表示目录不存在或读取失败（与「空目录」区分）。 */
    @Synchronized
    fun children(dir: File): List<String>? {
        val path = dir.path
        val stamp = dir.lastModified()
        // mtime 为 0（stat 失败/文件系统不提供时间戳）时无法判断是否变更 → 不缓存
        if (stamp != 0L) cache[path]?.let { if (it.stamp == stamp) return it.children }
        // list() 返回 null 是 IO 错误：不缓存，让下次仍有机会读到
        val listed = dir.list() ?: return null
        val children = listed.toList()
        // 超大目录只读不缓存：名字列表本身可能占几 MB，而条目数不会因为缓存而减少扫描成本
        if (stamp != 0L && children.size <= MAX_CACHED_NAMES_PER_DIR) {
            cache[path] = Entry(stamp, children)
        }
        return children
    }

    /**
     * 只失效某个目录（增删条目后调用）。传文件路径是空操作——缓存键始终是目录。
     *
     * 定向失效比整表清空精准：插件的配置目录会频繁写入，而素材目录只读——
     * 一起清掉会让「名字宽松匹配」的收益在每次配置落盘后归零。
     */
    @Synchronized
    fun invalidate(dir: File) {
        cache.remove(dir.path)
    }

    /** 清空整表：多级目录被创建/删除、无法逐层定位时使用。 */
    @Synchronized
    fun invalidateAll() {
        cache.clear()
    }

    private companion object {
        /** 缓存多少个目录：典型 MV 游戏 `img/` 下的子目录数量级，够覆盖一轮素材加载。 */
        const val MAX_CACHED_DIRS = 64

        /** 单目录缓存的名字上限（与匹配器阈值无关，见类注释）。 */
        const val MAX_CACHED_NAMES_PER_DIR = 4096
    }
}
