package com.tyranor.next.ui.main

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** A fast back gesture can start and commit without ever delivering a progress sample. */
internal class MainScreenBackCallback(enabled: Boolean, private val commit: (fromLeft: Boolean) -> Unit) :
    OnBackPressedCallback(enabled) {
    private var fromLeft = false

    override fun handleOnBackStarted(backEvent: BackEventCompat) {
        fromLeft = backEvent.swipeEdge == BackEventCompat.EDGE_LEFT
    }

    override fun handleOnBackProgressed(backEvent: BackEventCompat) = handleOnBackStarted(backEvent)

    override fun handleOnBackCancelled() {
        fromLeft = false
    }

    override fun handleOnBackPressed() {
        val openPanel = fromLeft
        fromLeft = false
        commit(openPanel)
    }
}

@Composable
internal fun MainScreenBackHandler(enabled: Boolean, onCommit: (fromLeft: Boolean) -> Unit) {
    val dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
    val owner = LocalLifecycleOwner.current
    val current = rememberUpdatedState(onCommit)
    val callback = remember { MainScreenBackCallback(enabled) { current.value(it) } }
    SideEffect {
        if (!enabled) callback.handleOnBackCancelled()
        callback.isEnabled = enabled
    }
    DisposableEffect(dispatcher, owner, callback) {
        dispatcher.addCallback(owner, callback)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) callback.handleOnBackCancelled()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            callback.handleOnBackCancelled()
            callback.remove()
        }
    }
}
