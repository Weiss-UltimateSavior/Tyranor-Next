#!/usr/bin/env python3
"""Unit tests for tools/check-shelf-book-boundaries.py."""
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import sys
import unittest

TOOLS = Path(__file__).resolve().parent
SPEC = spec_from_file_location("check_shelf_book_boundaries", TOOLS / "check-shelf-book-boundaries.py")
MOD = module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


class ShelfBookBoundariesTest(unittest.TestCase):
    def test_repo_passes(self):
        self.assertEqual(MOD.main(["--root", str(TOOLS.parent)]), 0)

    def test_blocks_activity_launch_in_shared_settings_view(self):
        src = """
package com.tyranor.next.ui.settings

@Composable
private fun PerGameSettingsDialog(game: ScanGame, onDismiss: () -> Unit) {}

@Composable
private fun PerGameSettingsView(game: ScanGame, inBook: Boolean) {
    startActivityWithPageTransition(ctx, Intent())
}

@Composable
private fun SectionCard(title: String) {}

@Composable
private fun OverrideFontPreference() {}
"""
        errors = MOD.check_file("ui/settings/PerGameSettingsScreen.kt", src)
        self.assertEqual(len(errors), 1)
        self.assertIn("startActivityWithPageTransition", errors[0][1])

    def test_blocks_bottom_sheet_and_raw_dialog_in_book_only_file(self):
        src = """
package com.tyranor.next.ui.game

@Composable
internal fun GameQuickActionsDialog() {
    AppBottomSheet {}
    Dialog(onDismissRequest = {}) {}
}
"""
        errors = MOD.check_file("ui/game/GameQuickActionsDialog.kt", src)
        self.assertEqual(len(errors), 2)

    def test_blocks_manual_dialog_host_check_or_open_details_escape_hatch(self):
        src = """
package com.tyranor.next.ui.game

internal interface GameActionsHost {
    fun openDetails(ordinaryDismiss: Boolean = true, open: () -> Unit)
}

@Composable
internal fun GameActions(onEngineSettings: () -> Unit) {
    val inBook = LocalAppDialogHost.current != null
}

@Composable
internal fun LaunchFileDialog() {}

@Composable
internal fun CoverSourcePickerDialog() {}

@Composable
private fun CoverCandidateCard() {}

@Composable
internal fun CoverSearchDialog() {}
"""
        errors = MOD.check_file("ui/game/GameScreen.kt", src)
        messages = [msg for _, msg in errors]
        self.assertTrue(any("LocalAppDialogHost" in m for m in messages))
        self.assertTrue(any("onEngineSettings" in m for m in messages))
        self.assertTrue(any("openDetails" in m for m in messages))

    def test_allows_global_activity_outside_protected_functions(self):
        src = """
package com.tyranor.next.ui.settings

@Composable
fun PerGameSettingsScreen(game: ScanGame) {
    startActivityWithPageTransition(ctx, Intent())
}

@Composable
internal fun PerGameSettingsDialog(game: ScanGame, onDismiss: () -> Unit) {}

@Composable
private fun PerGameSettingsView(game: ScanGame, inBook: Boolean) {}

@Composable
private fun SectionCard(title: String) {}

@Composable
private fun OverrideFontPreference() {}
"""
        self.assertEqual(MOD.check_file("ui/settings/PerGameSettingsScreen.kt", src), [])

    def test_blocks_global_card_and_button_styles_in_book_and_shared_paths(self):
        book_src = """
package com.tyranor.next.ui.game

@Composable
internal fun PlayStatusDialog() {
    AppNavItem(containerColor = DialogItemSurface)
    MiuixCard {}
    Card {}
    Button(onClick = {}) {}
}
"""
        book_errors = MOD.check_file("ui/game/PlayStatusDialog.kt", book_src)
        self.assertEqual(len(book_errors), 4)

        shared_src = """
package com.tyranor.next.ui.settings

@Composable
internal fun SettingsScreen() {
    // 非书页入口允许使用 MiuixCard / DialogItemSurface
    MiuixCard { val x = DialogItemSurface }
}

@Composable
internal fun WinlatorGlobalSettingsDialog() {}

@Composable
internal fun WinlatorGlobalSettingsCard() {
    TextButton(onClick = {}) {}
}

@Composable
internal fun EngineCard() {
    val c = MiuixTheme.colorScheme.surfaceContainer
}
"""
        shared_errors = MOD.check_file("ui/settings/SettingsScreen.kt", shared_src)
        messages = [msg for _, msg in shared_errors]
        self.assertEqual(len(shared_errors), 2)
        self.assertTrue(any("TextButton" in m for m in messages))
        self.assertTrue(any("surfaceContainer" in m for m in messages))

    def test_hosted_raw_dialog_fallback_requires_local_app_dialog_host(self):
        unhosted = """
package com.tyranor.next.ui.game

@Composable
internal fun GameShortcutCropDialog() {
    Dialog(onDismissRequest = {}) {}
}
"""
        errors = MOD.check_file("ui/game/GameShortcutCropDialog.kt", unhosted)
        self.assertEqual(len(errors), 1)
        self.assertIn("Dialog()", errors[0][1])

        hosted_present = """
package com.tyranor.next.ui.game

@Composable
private fun CropDialogCard() {
    Card(colors = CardDefaults.cardColors(containerColor = NavWhite)) {}
}

@Composable
internal fun GameShortcutCropDialog() {
    val host = LocalAppDialogHost.current
    if (host != null) {
        host.Present(spec)
        return
    }
    Dialog(onDismissRequest = {}) {}
}
"""
        self.assertEqual(MOD.check_file("ui/game/GameShortcutCropDialog.kt", hosted_present), [])

        hosted_alert = """
package com.tyranor.next.ui.game

@Composable
internal fun GameShortcutCropDialog() {
    if (LocalAppDialogHost.current != null) {
        AppAlertDialog(onDismissRequest = {}, title = {}, text = {}, confirmButton = {})
        return
    }
    Dialog(onDismissRequest = {}) {}
}
"""
        self.assertEqual(MOD.check_file("ui/game/GameShortcutCropDialog.kt", hosted_alert), [])

        for bad_exit in ("", "if (busy) return", "return@callback"):
            with self.subTest(bad_exit=bad_exit):
                source = hosted_alert.replace("        return\n", f"        {bad_exit}\n")
                errors = MOD.check_file("ui/game/GameShortcutCropDialog.kt", source)
                self.assertTrue(any("Dialog()" in message for _, message in errors))

        inverted_var = """
package com.tyranor.next.ui.game

@Composable
internal fun GameShortcutCropDialog() {
    val host = LocalAppDialogHost.current
    if (host == null) {
        AppAlertDialog(onDismissRequest = {}, title = {}, text = {}, confirmButton = {})
        return
    }
    Dialog(onDismissRequest = {}) {}
}
"""
        inverted_direct = """
package com.tyranor.next.ui.game

@Composable
internal fun GameShortcutCropDialog() {
    if (LocalAppDialogHost.current == null) {
        AppAlertDialog(onDismissRequest = {}, title = {}, text = {}, confirmButton = {})
        return
    }
    Dialog(onDismissRequest = {}) {}
}
"""
        for label, bad_src in (("inverted_var", inverted_var), ("inverted_direct", inverted_direct)):
            with self.subTest(label=label):
                errors = MOD.check_file("ui/game/GameShortcutCropDialog.kt", bad_src)
                self.assertTrue(any("Dialog()" in message for _, message in errors))

    def test_blocks_aliased_activity_launch_and_raw_dialog_imports(self):
        for label, imp, call in (
            (
                "original_transition",
                "import com.tyranor.next.ui.common.motion.startActivityWithPageTransition",
                "startActivityWithPageTransition(ctx, Intent())",
            ),
            (
                "aliased_transition",
                "import com.tyranor.next.ui.common.motion.startActivityWithPageTransition as pushPage",
                "pushPage(ctx, Intent())",
            ),
        ):
            with self.subTest(label=label):
                src = f"""
package com.tyranor.next.ui.settings

{imp}

@Composable
private fun PerGameSettingsDialog(game: ScanGame, onDismiss: () -> Unit) {{}}

@Composable
private fun PerGameSettingsView(game: ScanGame, inBook: Boolean) {{
    {call}
}}

@Composable
private fun SectionCard(title: String) {{}}

@Composable
private fun OverrideFontPreference() {{}}
"""
                errors = MOD.check_file("ui/settings/PerGameSettingsScreen.kt", src)
                self.assertEqual(len(errors), 1)
                self.assertIn("startActivityWithPageTransition", errors[0][1])

        for label, imp, call in (
            ("original_dialog", "import androidx.compose.ui.window.Dialog", "Dialog(onDismissRequest = {}) {}"),
            ("aliased_dialog", "import androidx.compose.ui.window.Dialog as RawPopupDialog", "RawPopupDialog(onDismissRequest = {}) {}"),
        ):
            with self.subTest(label=label):
                src = f"""
package com.tyranor.next.ui.game

{imp}

@Composable
internal fun PlayStatusDialog() {{
    {call}
}}
"""
                errors = MOD.check_file("ui/game/PlayStatusDialog.kt", src)
                self.assertEqual(len(errors), 1)
                self.assertIn("Dialog()", errors[0][1])


if __name__ == "__main__":
    unittest.main()
