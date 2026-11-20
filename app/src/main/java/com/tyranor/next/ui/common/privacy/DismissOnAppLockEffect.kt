package com.tyranor.next.ui.common.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.core.privacy.AppLockController

/**
 * 应用锁生效时关闭所在窗口（供独立窗口宿主统一复用）。
 *
 * 弹窗 / 抽屉 / 侧栏 / 下拉浮层都是**独立 Window**，层级在解锁门之上：锁门只能覆盖页面窗口，
 * 盖不住这些浮层，锁定状态下仍能操作其中的条目（绕过应用锁）。因此每个窗口宿主在内部挂本
 * effect，`lockedState` 变为 true 时自行收起——调用点无需逐个处理。
 *
 * 写在宿主内部（而不是各调用点）是刻意的：新增调用点自动继承，不会漏。
 */
@Composable
fun DismissOnAppLockEffect(onDismiss: () -> Unit) {
    val locked by AppLockController.lockedState.collectAsStateWithLifecycle()
    LaunchedEffect(locked) {
        if (locked) onDismiss()
    }
}
