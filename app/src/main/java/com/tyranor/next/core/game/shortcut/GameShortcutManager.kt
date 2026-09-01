package com.tyranor.next.core.game.shortcut

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import com.tyranor.next.R
import com.tyranor.next.core.game.model.ScanGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Creates user-confirmed launcher shortcuts for imported games. */
object GameShortcutManager {
    enum class RequestResult { REQUESTED, UPDATED, UNSUPPORTED, FAILED }

    suspend fun requestPinShortcut(
        context: Context,
        game: ScanGame,
        launchIntent: Intent,
        customIconUri: Uri? = null,
    ): RequestResult {
        val appContext = context.applicationContext
        val shortcutManager = appContext.getSystemService(ShortcutManager::class.java)
            ?: return RequestResult.UNSUPPORTED

        val maxIconSize = min(shortcutManager.iconMaxWidth, shortcutManager.iconMaxHeight)
            .takeIf { it > 0 }
            ?: DEFAULT_ICON_SIZE_PX
        val targetIconSize = maxIconSize.coerceAtMost(MAX_ICON_SIZE_PX)
        val iconUri = customIconUri?.toString() ?: game.coverUri
        val coverBitmap = withContext(Dispatchers.IO) {
            iconUri?.let { decodeSquareIcon(appContext, it, targetIconSize) }
        }
        // The crop UI and the launcher confirmation should represent the same square bitmap.
        // Adaptive icons apply an additional launcher-specific safe-zone inset, which makes
        // the selected crop appear unexpectedly small in the confirmation sheet.
        val icon = coverBitmap?.let(Icon::createWithBitmap)
            ?: Icon.createWithResource(appContext, R.mipmap.ic_launcher)
        val label = game.title.trim().ifBlank { appContext.getString(R.string.app_name) }
        val shortcut = ShortcutInfo.Builder(appContext, shortcutId(game.uri))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(icon)
            .setIntent(launchIntent)
            .build()

        return withContext(Dispatchers.IO) {
            runCatching {
                val shortcutId = shortcut.id
                val alreadyPinned = shortcutManager.pinnedShortcuts.any { it.id == shortcutId }
                if (alreadyPinned) {
                    if (shortcutManager.updateShortcuts(listOf(shortcut))) {
                        return@runCatching RequestResult.UPDATED
                    }
                    return@runCatching RequestResult.FAILED
                }
                if (!shortcutManager.isRequestPinShortcutSupported) {
                    return@runCatching RequestResult.UNSUPPORTED
                }
                if (shortcutManager.requestPinShortcut(shortcut, null)) {
                    RequestResult.REQUESTED
                } else {
                    RequestResult.FAILED
                }
            }.getOrElse { RequestResult.FAILED }
        }
    }

    internal fun shortcutId(gameUri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(gameUri.toByteArray(Charsets.UTF_8))
        return ID_PREFIX + digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun decodeSquareIcon(context: Context, uriText: String, targetSize: Int): Bitmap? = runCatching {
        val uri = Uri.parse(uriText)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openShortcutIconInputStream(context, uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= targetSize * 2) {
            sampleSize *= 2
        }
        val decoded = openShortcutIconInputStream(context, uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } ?: return@runCatching null

        val side = min(decoded.width, decoded.height)
        val cropped = Bitmap.createBitmap(
            decoded,
            (decoded.width - side) / 2,
            (decoded.height - side) / 2,
            side,
            side,
        )
        val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
        if (cropped !== decoded) decoded.recycle()
        if (scaled !== cropped) cropped.recycle()
        scaled
    }.getOrNull()

    private fun openShortcutIconInputStream(context: Context, uri: Uri): java.io.InputStream? {
        val resolverStream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        if (resolverStream != null) return resolverStream
        val path = uri.path?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { File(path).inputStream() }.getOrNull()
    }

    private const val ID_PREFIX = "game_"
    private const val DEFAULT_ICON_SIZE_PX = 192
    private const val MAX_ICON_SIZE_PX = 512
}
