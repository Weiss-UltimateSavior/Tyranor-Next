#!/usr/bin/env python3
"""Positive and negative source fixtures for the offline motion gate (no Android SDK)."""
import importlib.util
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest

spec = importlib.util.spec_from_file_location("motion_gate", Path(__file__).with_name("check-motion-boundaries.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

PAGE = "ui/game/Fixture.kt"


class MotionBoundariesTest(unittest.TestCase):
    def test_raw_list_and_disguised_variant(self):
        # Also check that registered implementation owners cannot reimplement list motion.
        source = """fun row() = Modifier
            .animateItem(
                fadeInSpec = enter,
                placementSpec = move(),
                fadeOutSpec = exit,
            )
            fun other() = alternate(fadeOutSpec = x, fadeInSpec = y, placementSpec = z)
        """
        for path in (PAGE, "ui/engine/EngineStorageMotion.kt", "core/misc/New.kt"):
            with self.subTest(path=path):
                self.assertEqual([line for line, _ in gate.check_source(path, source)], [2, 5, 7])
        self.assertEqual(gate.check_source(gate.LIST_OWNER, source), [])

    def test_variant_public_value_is_legal_but_not_internal_spec(self):
        source = """import com.tyranor.next.ui.common.motion.ListItemPlacement as Places
        import com.tyranor.next.ui.common.motion.ListItemPlacement.Layout as Normal
        fun ok() = listItemMotion(placement = if (quick) Places.Reflow else Normal, enabled = on)
        fun one() = Normal.spec
        fun two() = Places
            .Reflow.spec
        """
        self.assertEqual([line for line, _ in gate.check_source(PAGE, source)], [4, 5])

    def test_no_substring_import_literal_or_container_false_positives(self):
        source = '''
        import androidx.compose.animation.core.Animatable
        import androidx.compose.animation.core.tween
        import example.app.spring
        /* animateItem() /* tween(99) */ Animatable(0f) */
        val docs = """ MotionTokens.reflow(); test.animateItem() """
        val log = "quoted \\" example of animateItem("
        fun row() = AnimatedContent(target, transitionSpec = { archivePaneTransition(targetState) }) {
            // No policy on idle graphicsLayer or state used by the component.
            Modifier.then(listItemMotion()).graphicsLayer { alpha = fraction.value }
            useTypedHandle(someHandle as Animatable<Float, AnimationVector1D>)
            widget.tween(50)
            spring()
            animator(placementSpec = changed(fadeInSpec = a, fadeOutSpec = b))
            update { fadeInSpec = a; placementSpec = b; fadeOutSpec = c }
            listState.animateScrollToItem(1)
        }
        '''
        self.assertEqual(gate.check_source(PAGE, source), [])

    def test_aliased_qualified_and_multiline_primitives(self):
        source = """import androidx.compose.animation.core.tween as timing
        import androidx.compose.animation.core.*
        import androidx.compose.animation.animateContentSize as resize
        import androidx.compose.ui.Modifier
        import com.tyranor.next.ui.common.motion.MotionTokens as Tokens
        import com.tyranor.next.ui.common.motion.MotionTokens.layout as layoutSpec
        val a = timing<Float>(140)
        val b = androidx.compose.animation.core.Animatable(0f)
        val c = animateFloatAsState
            (1f)
        val d = Tokens.reflow<IntOffset>(IntOffset.VisibilityThreshold)
        val e = layoutSpec(IntOffset.VisibilityThreshold)
        val f = Modifier.resize()
        val g = keyframes { durationMillis = 140 }
        """
        self.assertEqual([line for line, _ in gate.check_source(PAGE, source)], [7, 8, 9, 11, 12, 13, 14])

    def test_fake_owner_and_package_local_tokens(self):
        source = "package com.tyranor.next.ui.common.motion\nval custom = MotionTokens.layout(1f)"
        for path in ("ui/game/SneakyMotion.kt", "ui/common/motion/Sneaky.kt", "ui/common/glass/Feature.kt"):
            self.assertTrue(gate.check_source(path, source), path)
        self.assertFalse(gate.check_source("ui/engine/EngineStorageMotion.kt", source))

    def test_protected_scope_ends_at_closing_brace(self):
        source = '''
        import androidx.compose.animation.core.tween
        fun DefaultBottomNavigationBar(onSelect: (Int) -> Unit = { call(1) }) {
            if (on) { log("}"); tween<Float>(180); Modifier.animateItem() }
        }
        fun DefaultBottomNavigationBarCopy() = tween<Float>(210)
        fun next() = Modifier.animateItem()
        '''
        self.assertEqual([line for line, _ in gate.check_source(gate.MAIN, source)], [6, 7])
        self.assertEqual(gate.check_source("ui/common/FloatingGlassNavButton.kt", source), [])
        self.assertTrue(gate.check_source("ui/home/FloatingGlassNavButton.kt", source))

    def test_existing_clock_allowance_is_callee_symbol_and_count_scoped(self):
        source = """import androidx.compose.animation.core.updateTransition
        import androidx.compose.animation.core.spring
        fun MainScreen() {
            updateTransition(0)
            updateTransition(1)
            spring<Float>()
        }
        fun another() = updateTransition(2)
        """
        self.assertEqual([n for n, _ in gate.check_source(gate.MAIN, source)], [5, 6, 8])
        self.assertEqual(len(gate.check_source(PAGE, source)), 4)

    def test_migrated_app_entry_has_no_legacy_exit_slot(self):
        source = """import androidx.compose.animation.fadeOut
        fun AppEntry() { CoachOverlayVisibility(visible) {}; fadeOut() }
        """
        self.assertEqual([line for line, _ in gate.check_source("MainActivity.kt", source)], [2])

    def test_cli_exit_status_and_repository_relative_diagnostic(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            owner = root / "app/src/main/java" / gate.PACKAGE / gate.LIST_OWNER
            page = root / "app/src/main/kotlin" / gate.PACKAGE / PAGE
            page.parent.mkdir(parents=True)
            owner.parent.mkdir(parents=True)
            owner.write_text("fun effect() = Modifier.animateItem()", encoding="utf-8")
            page.write_text("fun row() = listItemMotion()", encoding="utf-8")
            cmd = [sys.executable, "-B", gate.__file__, "--root", tmp]
            ok = subprocess.run(cmd, capture_output=True, text=True)
            self.assertEqual(ok.returncode, 0, ok.stderr)
            page.write_text("// legal comment\nfun row() = animateItem()", encoding="utf-8")
            bad = subprocess.run(cmd, capture_output=True, text=True)
            self.assertEqual(bad.returncode, 1)
            self.assertIn(page.relative_to(root).as_posix() + ":2:", bad.stderr)
            owner.unlink()
            missing = subprocess.run(cmd, capture_output=True, text=True)
            self.assertEqual(missing.returncode, 1)
            self.assertIn("Missing list effect owner", missing.stderr)


if __name__ == "__main__":
    unittest.main()
