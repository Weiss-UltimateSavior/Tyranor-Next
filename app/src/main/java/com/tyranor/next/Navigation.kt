package com.tyranor.next

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.tyranor.next.ui.main.MainScreen

@Composable
fun MainNavigation(
  coachPage: Int? = null,
  onCoachAnchorChanged: (Int, Rect) -> Unit = { _, _ -> },
) {
  MainScreen(
    coachPage = coachPage,
    onCoachAnchorChanged = onCoachAnchorChanged,
  )
}
