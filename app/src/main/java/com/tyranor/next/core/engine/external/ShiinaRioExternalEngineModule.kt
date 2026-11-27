package com.tyranor.next.core.engine.external

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.scan.ShiinaRioGameCatalog
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.ShiinaRioSettings
import java.io.File

object ShiinaRioExternalEngineModule : ExternalEngineModule {
    override val id = "shiinario"
    override val engine = EngineType.SHIINARIO
    override val displayName = engine.displayName
    override val displayNameRes = R.string.external_shiinario_module_name
    override val packageName = ShiinaRioHostContract.PACKAGE
    override val action = ShiinaRioHostContract.ACTION_LAUNCH
    override val defaultAlias = "internal.shiinario"
    override val supportedAliases = setOf("external.shiinario")
    override val updateSource = ExternalEngineReleaseSource("SIRENA-Core/Tyranor-Next-ShiinaRio", "v")
    override val installUrl = "${updateSource.releasesUrl}/latest/download/Tyranor-Next-ShiinaRio.apk"
    // Version 0.1.0 can crash before the player starts due to its AppCompat theme.
    override val minimumVersionCode = 2L

    override fun prepareForLaunch(
        context: Context,
        request: ExternalEngineLaunchRequest,
    ): ExternalEngineLaunchResult? = if (isValidGameDirectory(request.gameDirectoryPath.trim())) {
        null
    } else {
        ExternalEngineLaunchResult.failure(ExternalEngineErrorCode.INVALID_GAME_PATH, engineName = displayName)
    }

    override fun buildLaunchIntent(context: Context, request: ExternalEngineLaunchRequest): Intent {
        val folder = request.gameDirectoryPath.trim().trimEnd('/', '\\').ifEmpty { "/" }
        val target = resolveLaunchTarget(folder, request.launchTarget) { ShiinaRioGameCatalog.load(context) }
        return Intent(action).setComponent(ComponentName(packageName, ShiinaRioHostContract.LAUNCH_ACTIVITY)).apply {
            putExtra(ShiinaRioHostContract.EXTRA_PROTOCOL_VERSION, ShiinaRioHostContract.PROTOCOL_VERSION)
            putExtra(ShiinaRioHostContract.EXTRA_ENGINE, id)
            putExtra(ShiinaRioHostContract.EXTRA_GAME_DIR, folder)
            putExtra(ShiinaRioHostContract.EXTRA_GAME_NAME, request.game.title)
            target?.let { putExtra(ShiinaRioHostContract.EXTRA_LAUNCH_TARGET, it) }
            putExtra(
                ShiinaRioHostContract.EXTRA_SETTINGS_JSON,
                (request.resolvedSettings?.shiinaRio ?: ShiinaRioSettings.Defaults).toJson().toString(),
            )
            putExtra(ShiinaRioHostContract.EXTRA_LOCALE, AppLocaleController.engineUiLanguageTag(context))
            // The host derives GAME_KEY using its resolved scheme and the normalized directory.
        }
    }

    internal fun resolveLaunchTarget(
        folder: String,
        savedTarget: String,
        catalog: () -> ShiinaRioGameCatalog,
    ): String? {
        val dir = File(folder)
        if (ShiinaRioGameCatalog.isLaunchTarget(savedTarget) && File(dir, savedTarget).isFile) {
            return savedTarget
        }
        val names = dir.listFiles()?.asSequence()
            ?.filter { it.isFile && ShiinaRioGameCatalog.isLaunchTarget(it.name) }
            ?.map { it.name }
            ?.toList().orEmpty()
        return if (names.isEmpty()) null else catalog().launchTargetFor(names)
    }

    internal fun isValidGameDirectory(path: String): Boolean =
        path.startsWith('/') && path.none { it.isISOControl() } &&
            path.split('/', '\\').none { it == "." || it == ".." }
}

internal object ShiinaRioHostContract {
    const val PACKAGE = ShiinaRioGameCatalog.HOST_PACKAGE
    const val LAUNCH_ACTIVITY = "com.tyranor.next.shiinario.ShiinaRioLaunchActivity"
    const val ACTION_LAUNCH = "com.tyranor.next.action.LAUNCH_SHIINARIO"
    const val PROTOCOL_VERSION = 1
    const val EXTRA_PROTOCOL_VERSION = "com.tyranor.next.extra.PROTOCOL_VERSION"
    const val EXTRA_ENGINE = "com.tyranor.next.extra.ENGINE"
    const val EXTRA_GAME_DIR = "com.tyranor.next.extra.GAME_DIR"
    const val EXTRA_GAME_NAME = "com.tyranor.next.extra.GAME_NAME"
    const val EXTRA_LAUNCH_TARGET = "com.tyranor.next.extra.LAUNCH_TARGET"
    const val EXTRA_SETTINGS_JSON = "com.tyranor.next.extra.SETTINGS_JSON"
    const val EXTRA_LOCALE = "com.tyranor.next.extra.LOCALE"
}
