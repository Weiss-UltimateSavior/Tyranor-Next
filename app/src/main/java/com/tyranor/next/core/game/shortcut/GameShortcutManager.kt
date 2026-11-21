package com.tyranor.next.core.game.shortcut

import android.app.PendingIntent
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
import com.tyranor.next.core.settings.AppSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/** Creates user-confirmed launcher shortcuts for imported games. */
object GameShortcutManager {
    enum class RequestResult { REQUESTED, UPDATED, UNSUPPORTED, FAILED }

    /** Creates a new pinned shortcut or updates the existing shortcut for this game. */
    suspend fun requestPinShortcut(
        context: Context,
        game: ScanGame,
        launchIntent: Intent,
        customIconUri: Uri? = null,
        pinResultIntent: Intent? = null,
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
            // 没有封面时退回应用图标：跟随应用设置选中的启动器图标（经典 / 新版），与桌面上的应用图标一致
            ?: Icon.createWithResource(
                appContext,
                if (AppSettingsStore.isClassicIconEnabled(appContext)) R.mipmap.ic_launcher_classic else R.mipmap.ic_launcher,
            )
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
                val callback = pinResultIntent?.let {
                    PendingIntent.getBroadcast(
                        appContext,
                        0,
                        it,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                    )
                }
                var accepted = false
                try {
                    accepted = shortcutManager.requestPinShortcut(shortcut, callback?.intentSender)
                    if (accepted) RequestResult.REQUESTED else RequestResult.FAILED
                } finally {
                    if (!accepted) callback?.cancel()
                }
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                RequestResult.FAILED
            }
        }
    }

    /** Returns the stable, non-sensitive launcher shortcut ID for a game URI. */
    internal fun shortcutId(gameUri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(gameUri.toByteArray(Charsets.UTF_8))
        return ID_PREFIX + digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** Decodes and center-crops a fallback icon source to the launcher target size. */
    @androidx.annotation.VisibleForTesting
    internal fun decodeSquareIcon(context: Context, uriText: String, targetSize: Int): Bitmap? {
        var decoded: Bitmap? = null
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        var returned = false
        return try {
            val uri = Uri.parse(uriText)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openShortcutIconInputStream(context, uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= targetSize * 2) {
                sampleSize *= 2
            }
            decoded = openShortcutIconInputStream(context, uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                )
            } ?: return null

            val side = min(decoded.width, decoded.height)
            cropped = Bitmap.createBitmap(
                decoded,
                (decoded.width - side) / 2,
                (decoded.height - side) / 2,
                side,
                side,
            )
            scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
            returned = true
            scaled
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            if (!returned && scaled != null && !scaled.isRecycled) scaled.recycle()
            if (cropped != null && cropped !== scaled && !cropped.isRecycled) cropped.recycle()
            if (decoded != null && decoded !== cropped && decoded !== scaled && !decoded.isRecycled) decoded.recycle()
        }
    }

    /** Opens a shortcut icon URI through the resolver, with a local file fallback for file/empty schemes. */
    private fun openShortcutIconInputStream(context: Context, uri: Uri): InputStream? {
        val resolverStream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        if (resolverStream != null) return resolverStream
        val scheme = uri.scheme
        if (!scheme.equals("file", ignoreCase = true) && !scheme.isNullOrBlank()) return null
        val path = uri.path?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { File(path).inputStream() }.getOrNull()
    }

    private const val ID_PREFIX = "game_"
    private const val DEFAULT_ICON_SIZE_PX = 192
    private const val MAX_ICON_SIZE_PX = 512
}
