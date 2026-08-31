package com.tyranor.next.core.game.launch

import android.util.Log
import com.tyranor.next.core.settings.EngineSettingsStore
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.min

/**
 * Lightweight, read-only Artemis game fingerprint detector.
 *
 * The detector intentionally avoids extracting assets. It only reads directory entries,
 * small ini previews and PFS file tables, then returns an ordered launch candidate list.
 */
object ArtemisEngineFingerprintDetector {
    private const val TAG = "ArtemisAuto"
    private const val MAX_TEXT_BYTES = 64 * 1024
    private const val MAX_SYSTEM_ENTRY_BYTES = 512 * 1024
    private const val MAX_LOOSE_ENTRIES = 3_000
    private const val MAX_LOOSE_DEPTH = 5
    private const val MAX_PFS_ARCHIVES = 8
    private const val MAX_PFS_ENTRIES = 2_000
    private const val MAX_PFS_NAME_BYTES = 4_096
    private const val MIN_ENCRYPTED_LEN = 8

    private val ROOT_PFS_PATCH_RE = Regex("""^root\.pfs\.\d{3}$""")
    private val ANY_PFS_PATCH_RE = Regex("""^[^.]+\.pfs\.\d{3}$""")
    private val OBB_NAME_RE = Regex("""^(main|patch)\.\d+\..+\.obb$""")

    private val EMOTE_PATH_HINTS = listOf(
        "d3demote",
        "iemote",
        "/emote/",
        "\\emote\\",
        "emote/",
        "emote\\",
    )
    private val EMOTE_EXT_HINTS = listOf(".mtn", ".psb", ".emt", ".emote")
    private val EMOTE_TEXT_HINTS = listOf("D3DEMOTE", "IEMOTE", "EMOTECREATE", "CEMOTEPLAYER")

    private val EMOTE_CHAIN = listOf(
        EngineSettingsStore.ART_ENGINE_V3,
        EngineSettingsStore.ART_ENGINE_V1,
        EngineSettingsStore.ART_ENGINE_V2,
        EngineSettingsStore.ART_ENGINE_V4,
        EngineSettingsStore.ART_ENGINE_V5,
    )
    private val COMMERCIAL_ANDROID_CHAIN = listOf(
        EngineSettingsStore.ART_ENGINE_V5,
        EngineSettingsStore.ART_ENGINE_V4,
        EngineSettingsStore.ART_ENGINE_V2,
        EngineSettingsStore.ART_ENGINE_V3,
        EngineSettingsStore.ART_ENGINE_V1,
    )
    private val ANDROID_ASSETS_CHAIN = listOf(
        EngineSettingsStore.ART_ENGINE_V4,
        EngineSettingsStore.ART_ENGINE_V5,
        EngineSettingsStore.ART_ENGINE_V2,
        EngineSettingsStore.ART_ENGINE_V3,
        EngineSettingsStore.ART_ENGINE_V1,
    )
    private val LEGACY_CHAIN = listOf(
        EngineSettingsStore.ART_ENGINE_V2,
        EngineSettingsStore.ART_ENGINE_V1,
        EngineSettingsStore.ART_ENGINE_V3,
        EngineSettingsStore.ART_ENGINE_V4,
        EngineSettingsStore.ART_ENGINE_V5,
    )

    data class ArtemisGameFingerprint(
        val hasLooseBootIni: Boolean = false,
        val hasLooseSystemIni: Boolean = false,
        val hasLooseAndroidSection: Boolean = false,
        val hasLooseWindowsSection: Boolean = false,
        val hasLooseBootKey: Boolean = false,
        val hasLooseFirstIet: Boolean = false,
        val hasLooseEmoteSignal: Boolean = false,
        val hasRootPfs: Boolean = false,
        val hasPatchPfs: Boolean = false,
        val hasAnyPfs: Boolean = false,
        val pfsEntryScanned: Boolean = false,
        val pfsHasSystemIni: Boolean = false,
        val pfsHasAndroidSection: Boolean = false,
        val pfsHasFirstIet: Boolean = false,
        val pfsHasLua: Boolean = false,
        val pfsHasIet: Boolean = false,
        val pfsHasMp4: Boolean = false,
        val pfsHasEmoteAsset: Boolean = false,
        val bootHasResourceSection: Boolean = false,
        val bootUsesApkExpansion: Boolean = false,
        val bootUsesPlayAssetDelivery: Boolean = false,
        val bootUsesDownload: Boolean = false,
        val bootUsesLegacyId: Boolean = false,
        val bootMentionsRootPfs: Boolean = false,
        val hasObbLikeFile: Boolean = false,
        val hasPf8LikeArchive: Boolean = false,
        val scannedLooseEntries: Int = 0,
        val scannedPfsEntries: Int = 0,
        val confidence: Int = 0,
        val reasons: List<String> = emptyList(),
    ) {
        val hasEmoteSignal: Boolean
            get() = hasLooseEmoteSignal || pfsHasEmoteAsset

        val hasAndroidSection: Boolean
            get() = hasLooseAndroidSection || pfsHasAndroidSection

        val isCommercialAndroidShell: Boolean
            get() = hasObbLikeFile || hasPf8LikeArchive ||
                bootUsesApkExpansion || bootUsesPlayAssetDelivery || bootUsesDownload

        val isAndroidAssetsShell: Boolean
            get() = hasLooseBootIni && (hasRootPfs || hasPatchPfs || bootMentionsRootPfs || hasAndroidSection)

        val isLegacyPcLike: Boolean
            get() = (hasLooseSystemIni && hasLooseFirstIet) ||
                (hasLooseWindowsSection && !hasAndroidSection) ||
                (pfsHasSystemIni && pfsHasFirstIet && !hasAndroidSection)

        fun shortSummary(): String = buildString {
            append("boot=").append(hasLooseBootIni)
            append(", android=").append(hasAndroidSection)
            append(", windows=").append(hasLooseWindowsSection)
            append(", emote=").append(hasEmoteSignal)
            append(", rootPfs=").append(hasRootPfs)
            append(", patchPfs=").append(hasPatchPfs)
            append(", pfsScanned=").append(pfsEntryScanned)
            append(", pfsEntries=").append(scannedPfsEntries)
            append(", obb=").append(hasObbLikeFile)
            append(", pf8=").append(hasPf8LikeArchive)
            append(", pad=").append(bootUsesPlayAssetDelivery)
            append(", expansion=").append(bootUsesApkExpansion)
            append(", download=").append(bootUsesDownload)
            append(", confidence=").append(confidence)
        }
    }

    data class ArtemisEnginePlan(
        val initialVersion: String,
        val fallbackVersions: List<String>,
        val reason: String,
        val fingerprint: ArtemisGameFingerprint,
    )

    private data class PfsSummary(
        val scanned: Boolean = false,
        val entryCount: Int = 0,
        val hasSystemIni: Boolean = false,
        val systemIniPreview: String? = null,
        val hasFirstIet: Boolean = false,
        val hasLua: Boolean = false,
        val hasIet: Boolean = false,
        val hasMp4: Boolean = false,
        val hasEmoteAsset: Boolean = false,
        val hasPf8LikeArchive: Boolean = false,
        val archiveNames: List<String> = emptyList(),
    )

    fun buildAutoPlan(rootPath: String): ArtemisEnginePlan {
        val fingerprint = detect(rootPath)
        val chain = when {
            fingerprint.hasEmoteSignal -> EMOTE_CHAIN
            fingerprint.isCommercialAndroidShell -> COMMERCIAL_ANDROID_CHAIN
            fingerprint.isAndroidAssetsShell -> ANDROID_ASSETS_CHAIN
            fingerprint.isLegacyPcLike -> LEGACY_CHAIN
            else -> LEGACY_CHAIN
        }.distinct()
        val reason = when {
            fingerprint.hasEmoteSignal -> "emote resource detected"
            fingerprint.isCommercialAndroidShell -> "android commercial shell/obb/pad/download fingerprint"
            fingerprint.isAndroidAssetsShell -> "android boot.ini/root.pfs fingerprint"
            fingerprint.isLegacyPcLike -> "legacy pc-like artemis layout"
            else -> "generic pfs/system.ini fallback"
        }
        Log.i(TAG, "plan path=$rootPath chain=${chain.joinToString(",")} reason=$reason fp=${fingerprint.shortSummary()} reasons=${fingerprint.reasons.joinToString("|")}")
        return ArtemisEnginePlan(
            initialVersion = chain.first(),
            fallbackVersions = chain,
            reason = reason,
            fingerprint = fingerprint,
        )
    }

    fun detect(rootPath: String): ArtemisGameFingerprint {
        val reasons = mutableListOf<String>()
        if (rootPath.isBlank() || rootPath.startsWith("content://")) {
            return ArtemisGameFingerprint(reasons = listOf("path unavailable for local fingerprint"))
        }
        val root = File(rootPath)
        if (!root.isDirectory) {
            return ArtemisGameFingerprint(reasons = listOf("path is not a directory"))
        }

        val loose = scanLooseFiles(root, reasons)
        val bootInfo = readBootInfo(findChildIgnoreCase(root, "boot.ini"))
        val systemInfo = readSystemInfo(findChildIgnoreCase(root, "system.ini"))
        val pfsSummary = inspectPfsArchives(root, loose.pfsFiles)

        if (bootInfo.hasResourceSection) reasons += "boot.ini resource section"
        if (bootInfo.usesApkExpansion) reasons += "boot.ini apk expansion"
        if (bootInfo.usesPlayAssetDelivery) reasons += "boot.ini play asset delivery"
        if (bootInfo.usesDownload) reasons += "boot.ini download url"
        if (bootInfo.usesLegacyId) reasons += "boot.ini legacy id"
        if (systemInfo.hasAndroidSection) reasons += "loose system.ini [ANDROID]"
        if (systemInfo.hasWindowsSection && !systemInfo.hasAndroidSection) reasons += "loose system.ini [WINDOWS]"
        if (loose.hasFirstIet) reasons += "loose system/first.iet"
        if (loose.hasEmoteSignal || systemInfo.hasEmoteText) reasons += "loose emote signal"
        if (loose.hasObbLikeFile) reasons += "obb-like file"
        if (pfsSummary.hasPf8LikeArchive) reasons += "pf8 archive"
        if (pfsSummary.hasSystemIni) reasons += "pfs system.ini"
        if (pfsSummary.hasFirstIet) reasons += "pfs system/first.iet"
        if (pfsSummary.hasEmoteAsset) reasons += "pfs emote signal"

        val pfsHasAndroidSection = pfsSummary.systemIniPreview?.let { hasSection(it, "ANDROID") } == true
        val confidence = computeConfidence(
            loose = loose,
            boot = bootInfo,
            system = systemInfo,
            pfs = pfsSummary,
            pfsHasAndroidSection = pfsHasAndroidSection,
        )

        return ArtemisGameFingerprint(
            hasLooseBootIni = bootInfo.exists,
            hasLooseSystemIni = systemInfo.exists,
            hasLooseAndroidSection = systemInfo.hasAndroidSection,
            hasLooseWindowsSection = systemInfo.hasWindowsSection,
            hasLooseBootKey = systemInfo.hasBootKey,
            hasLooseFirstIet = loose.hasFirstIet,
            hasLooseEmoteSignal = loose.hasEmoteSignal || systemInfo.hasEmoteText,
            hasRootPfs = loose.hasRootPfs,
            hasPatchPfs = loose.hasPatchPfs,
            hasAnyPfs = loose.hasAnyPfs,
            pfsEntryScanned = pfsSummary.scanned,
            pfsHasSystemIni = pfsSummary.hasSystemIni,
            pfsHasAndroidSection = pfsHasAndroidSection,
            pfsHasFirstIet = pfsSummary.hasFirstIet,
            pfsHasLua = pfsSummary.hasLua,
            pfsHasIet = pfsSummary.hasIet,
            pfsHasMp4 = pfsSummary.hasMp4,
            pfsHasEmoteAsset = pfsSummary.hasEmoteAsset,
            bootHasResourceSection = bootInfo.hasResourceSection,
            bootUsesApkExpansion = bootInfo.usesApkExpansion,
            bootUsesPlayAssetDelivery = bootInfo.usesPlayAssetDelivery,
            bootUsesDownload = bootInfo.usesDownload,
            bootUsesLegacyId = bootInfo.usesLegacyId,
            bootMentionsRootPfs = bootInfo.mentionsRootPfs,
            hasObbLikeFile = loose.hasObbLikeFile,
            hasPf8LikeArchive = loose.hasPf8LikeArchive || pfsSummary.hasPf8LikeArchive,
            scannedLooseEntries = loose.scannedEntries,
            scannedPfsEntries = pfsSummary.entryCount,
            confidence = confidence,
            reasons = reasons.distinct(),
        )
    }

    private fun computeConfidence(
        loose: LooseSummary,
        boot: BootInfo,
        system: SystemInfo,
        pfs: PfsSummary,
        pfsHasAndroidSection: Boolean,
    ): Int {
        var score = 0
        if (loose.hasEmoteSignal || system.hasEmoteText || pfs.hasEmoteAsset) score += 100
        if (system.hasAndroidSection || pfsHasAndroidSection) score += 40
        if (boot.usesApkExpansion || boot.usesPlayAssetDelivery || boot.usesDownload) score += 60
        if (loose.hasObbLikeFile) score += 60
        if (loose.hasPf8LikeArchive || pfs.hasPf8LikeArchive) score += 50
        if (boot.exists && (loose.hasRootPfs || loose.hasPatchPfs || boot.mentionsRootPfs)) score += 40
        if (system.exists && loose.hasFirstIet) score += 50
        if (loose.hasAnyPfs && !system.hasAndroidSection && !pfsHasAndroidSection) score += 20
        if (system.hasWindowsSection && !system.hasAndroidSection && !pfsHasAndroidSection) score += 30
        return score
    }

    private data class LooseSummary(
        val hasFirstIet: Boolean = false,
        val hasEmoteSignal: Boolean = false,
        val hasRootPfs: Boolean = false,
        val hasPatchPfs: Boolean = false,
        val hasAnyPfs: Boolean = false,
        val hasObbLikeFile: Boolean = false,
        val hasPf8LikeArchive: Boolean = false,
        val pfsFiles: List<File> = emptyList(),
        val scannedEntries: Int = 0,
    )

    private fun scanLooseFiles(root: File, reasons: MutableList<String>): LooseSummary {
        var hasFirstIet = false
        var hasEmoteSignal = false
        var hasRootPfs = false
        var hasPatchPfs = false
        var hasAnyPfs = false
        var hasObbLikeFile = false
        var hasPf8LikeArchive = false
        var scanned = 0
        val pfsFiles = mutableListOf<File>()
        val queue = ArrayDeque<Pair<File, String>>()
        queue.add(root to "")
        while (!queue.isEmpty() && scanned < MAX_LOOSE_ENTRIES) {
            val (dir, rel) = queue.removeFirst()
            val depth = if (rel.isEmpty()) 0 else rel.count { it == '/' } + 1
            val children = runCatching { dir.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
            for (child in children) {
                if (scanned >= MAX_LOOSE_ENTRIES) break
                scanned++
                val lowerName = child.name.lowercase(Locale.ROOT)
                val childRel = if (rel.isEmpty()) lowerName else "$rel/$lowerName"
                if (looksLikeEmotePath(childRel)) hasEmoteSignal = true
                if (child.isDirectory) {
                    if (depth < MAX_LOOSE_DEPTH && !lowerName.startsWith(".")) {
                        queue.add(child to childRel)
                    }
                    continue
                }
                if (childRel == "system/first.iet" || childRel.endsWith("/system/first.iet")) hasFirstIet = true
                if (lowerName == "root.pfs") hasRootPfs = true
                if (ROOT_PFS_PATCH_RE.matches(lowerName)) hasPatchPfs = true
                if (isPfsName(lowerName)) {
                    hasAnyPfs = true
                    if (pfsFiles.size < MAX_PFS_ARCHIVES) pfsFiles += child
                    if (hasPf8Magic(child)) hasPf8LikeArchive = true
                }
                if (lowerName.endsWith(".obb") || OBB_NAME_RE.matches(lowerName)) {
                    hasObbLikeFile = true
                    if (hasPf8Magic(child)) hasPf8LikeArchive = true
                }
            }
        }
        if (scanned >= MAX_LOOSE_ENTRIES) reasons += "loose scan entry limit reached"
        return LooseSummary(
            hasFirstIet = hasFirstIet,
            hasEmoteSignal = hasEmoteSignal,
            hasRootPfs = hasRootPfs,
            hasPatchPfs = hasPatchPfs,
            hasAnyPfs = hasAnyPfs,
            hasObbLikeFile = hasObbLikeFile,
            hasPf8LikeArchive = hasPf8LikeArchive,
            pfsFiles = pfsFiles.sortedBy { it.name.lowercase(Locale.ROOT) },
            scannedEntries = scanned,
        )
    }

    private data class BootInfo(
        val exists: Boolean = false,
        val hasResourceSection: Boolean = false,
        val usesApkExpansion: Boolean = false,
        val usesPlayAssetDelivery: Boolean = false,
        val usesDownload: Boolean = false,
        val usesLegacyId: Boolean = false,
        val mentionsRootPfs: Boolean = false,
    )

    private fun readBootInfo(file: File?): BootInfo {
        if (file?.isFile != true) return BootInfo()
        val text = readSmallText(file, MAX_TEXT_BYTES).uppercase(Locale.ROOT)
        val active = activeIniLines(text)
        return BootInfo(
            exists = true,
            hasResourceSection = hasSection(active, "RESOURCE"),
            usesApkExpansion = active.any {
                (it.startsWith("APK_EXPANSION_FILES_MAIN") ||
                    it.startsWith("APK_EXPANSION_FILES_PATCH") ||
                    it.startsWith("APK_EXPANSION_FILES_KEY")) && valuePart(it).isNotBlank()
            },
            usesPlayAssetDelivery = active.any {
                it.startsWith("PLAY_ASSET_DELIVERY_NAMES") && valuePart(it).isNotBlank()
            },
            usesDownload = hasSection(active, "DOWNLOAD") && active.any {
                it.startsWith("URL") && valuePart(it).contains("ROOT.PFS")
            },
            usesLegacyId = hasSection(active, "ID") && active.any {
                it.startsWith("NAME") && valuePart(it).isNotBlank()
            },
            mentionsRootPfs = active.any { it.contains("ROOT.PFS") },
        )
    }

    private data class SystemInfo(
        val exists: Boolean = false,
        val hasAndroidSection: Boolean = false,
        val hasWindowsSection: Boolean = false,
        val hasBootKey: Boolean = false,
        val hasEmoteText: Boolean = false,
    )

    private fun readSystemInfo(file: File?): SystemInfo {
        if (file?.isFile != true) return SystemInfo()
        val text = readSmallText(file, MAX_TEXT_BYTES)
        val upper = text.uppercase(Locale.ROOT)
        val active = activeIniLines(upper)
        return SystemInfo(
            exists = true,
            hasAndroidSection = hasSection(active, "ANDROID"),
            hasWindowsSection = hasSection(active, "WINDOWS"),
            hasBootKey = active.any { it.startsWith("BOOT") && valuePart(it).isNotBlank() },
            hasEmoteText = EMOTE_TEXT_HINTS.any { upper.contains(it) },
        )
    }

    private fun inspectPfsArchives(root: File, loosePfsFiles: List<File>): PfsSummary {
        val files = if (loosePfsFiles.isNotEmpty()) {
            loosePfsFiles
        } else {
            runCatching {
                root.listFiles()?.filter { it.isFile && isPfsName(it.name.lowercase(Locale.ROOT)) }.orEmpty()
            }.getOrDefault(emptyList())
        }.sortedBy { it.name.lowercase(Locale.ROOT) }.take(MAX_PFS_ARCHIVES)

        var scanned = false
        var entryCount = 0
        var hasSystemIni = false
        var systemIniPreview: String? = null
        var hasFirstIet = false
        var hasLua = false
        var hasIet = false
        var hasMp4 = false
        var hasEmoteAsset = false
        var hasPf8LikeArchive = false
        val archiveNames = mutableListOf<String>()

        for (file in files) {
            val summary = inspectPfsArchive(file)
            if (summary.scanned) scanned = true
            entryCount += summary.entryCount
            hasSystemIni = hasSystemIni || summary.hasSystemIni
            if (systemIniPreview == null) systemIniPreview = summary.systemIniPreview
            hasFirstIet = hasFirstIet || summary.hasFirstIet
            hasLua = hasLua || summary.hasLua
            hasIet = hasIet || summary.hasIet
            hasMp4 = hasMp4 || summary.hasMp4
            hasEmoteAsset = hasEmoteAsset || summary.hasEmoteAsset
            hasPf8LikeArchive = hasPf8LikeArchive || summary.hasPf8LikeArchive
            archiveNames += summary.archiveNames
        }
        return PfsSummary(
            scanned = scanned,
            entryCount = entryCount,
            hasSystemIni = hasSystemIni,
            systemIniPreview = systemIniPreview,
            hasFirstIet = hasFirstIet,
            hasLua = hasLua,
            hasIet = hasIet,
            hasMp4 = hasMp4,
            hasEmoteAsset = hasEmoteAsset,
            hasPf8LikeArchive = hasPf8LikeArchive,
            archiveNames = archiveNames,
        )
    }

    private fun inspectPfsArchive(file: File): PfsSummary {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 11) return PfsSummary(archiveNames = listOf(file.name))
                val b0 = raf.read()
                val b1 = raf.read()
                val b2 = raf.read()
                if (b0 != 0x70 || b1 != 0x66) {
                    return PfsSummary(archiveNames = listOf(file.name))
                }
                val pf8 = b2 == 0x38
                val tableLenOrDataStart = readM(raf)
                val tableStart = raf.filePointer
                val tableKey = readTableKey(raf, tableStart, tableLenOrDataStart, file.length())
                raf.seek(tableStart)
                val declaredEntries = readM(raf).coerceAtMost(MAX_PFS_ENTRIES)
                var parsed = 0
                var hasSystemIni = false
                var systemIniPreview: String? = null
                var hasFirstIet = false
                var hasLua = false
                var hasIet = false
                var hasMp4 = false
                var hasEmoteAsset = false
                val fileLength = raf.length()
                while (parsed < declaredEntries && raf.filePointer < fileLength) {
                    val nameLen = runCatching { readM(raf) }.getOrNull() ?: break
                    if (nameLen <= 0 || nameLen > MAX_PFS_NAME_BYTES) break
                    val nameBytes = ByteArray(nameLen)
                    raf.readFully(nameBytes)
                    val rawName = String(nameBytes, Charsets.UTF_8)
                    val lower = rawName.replace('\\', '/').lowercase(Locale.ROOT)
                    val offsetAndLength = runCatching {
                        readM(raf)
                        readM(raf).toLong() to readM(raf).toLong()
                    }.getOrNull() ?: break
                    val (offset, dataLen) = offsetAndLength
                    parsed++
                    if (lower == "system.ini") {
                        hasSystemIni = true
                        if (systemIniPreview == null && dataLen in 1..MAX_SYSTEM_ENTRY_BYTES && offset >= 0 && offset + dataLen <= fileLength) {
                            systemIniPreview = readEntryPreview(raf, offset, dataLen.toInt(), tableKey)
                        }
                    }
                    if (lower == "system/first.iet" || lower.endsWith("/system/first.iet")) hasFirstIet = true
                    if (lower.endsWith(".lua")) hasLua = true
                    if (lower.endsWith(".iet")) hasIet = true
                    if (lower.endsWith(".mp4")) hasMp4 = true
                    if (looksLikeEmotePath(lower)) hasEmoteAsset = true
                }
                PfsSummary(
                    scanned = parsed > 0,
                    entryCount = parsed,
                    hasSystemIni = hasSystemIni,
                    systemIniPreview = systemIniPreview,
                    hasFirstIet = hasFirstIet,
                    hasLua = hasLua,
                    hasIet = hasIet,
                    hasMp4 = hasMp4,
                    hasEmoteAsset = hasEmoteAsset,
                    hasPf8LikeArchive = pf8,
                    archiveNames = listOf(file.name),
                )
            }
        } catch (error: Throwable) {
            Log.w(TAG, "pfs inspect failed file=${file.path}", error)
            PfsSummary(archiveNames = listOf(file.name), hasPf8LikeArchive = hasPf8Magic(file))
        }
    }

    private fun readEntryPreview(raf: RandomAccessFile, offset: Long, dataLen: Int, key: ByteArray?): String? {
        val saved = raf.filePointer
        return try {
            raf.seek(offset)
            val data = ByteArray(min(dataLen, MAX_TEXT_BYTES))
            raf.readFully(data)
            val raw = String(data, Charsets.ISO_8859_1)
            if (raw.contains("[", ignoreCase = false) && raw.contains("]")) {
                raw
            } else if (key != null && data.size >= MIN_ENCRYPTED_LEN) {
                for (i in data.indices) {
                    data[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
                }
                String(data, Charsets.ISO_8859_1)
            } else {
                raw
            }
        } catch (_: Throwable) {
            null
        } finally {
            raf.seek(saved)
        }
    }

    private fun readTableKey(raf: RandomAccessFile, tableStart: Long, tableLen: Int, fileLength: Long): ByteArray? {
        if (tableLen <= 0 || tableStart + tableLen > fileLength || tableLen > 50 * 1024 * 1024) return null
        val saved = raf.filePointer
        return try {
            raf.seek(tableStart)
            val table = ByteArray(tableLen)
            raf.readFully(table)
            MessageDigest.getInstance("SHA-1").digest(table)
        } catch (_: Throwable) {
            null
        } finally {
            raf.seek(saved)
        }
    }

    private fun hasPf8Magic(file: File): Boolean {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.length() >= 3 && raf.read() == 0x70 && raf.read() == 0x66 && raf.read() == 0x38
            }
        }.getOrDefault(false)
    }

    private fun isPfsName(lowerName: String): Boolean =
        lowerName.endsWith(".pfs") || ANY_PFS_PATCH_RE.matches(lowerName)

    private fun looksLikeEmotePath(lowerPath: String): Boolean =
        EMOTE_PATH_HINTS.any { lowerPath.contains(it) } ||
            EMOTE_EXT_HINTS.any { lowerPath.endsWith(it) }

    private fun findChildIgnoreCase(dir: File, name: String): File? =
        runCatching {
            dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }.getOrNull()

    private fun readSmallText(file: File, maxBytes: Int): String {
        return runCatching {
            file.inputStream().use { input ->
                val len = file.length().coerceAtMost(maxBytes.toLong()).toInt()
                val bytes = ByteArray(len)
                val read = input.read(bytes)
                if (read <= 0) "" else String(bytes, 0, read, Charsets.ISO_8859_1)
            }
        }.getOrDefault("")
    }

    private fun activeIniLines(text: String): List<String> =
        text.lineSequence()
            .map { it.substringBefore(';').substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toList()

    private fun hasSection(text: String, section: String): Boolean =
        hasSection(activeIniLines(text.uppercase(Locale.ROOT)), section)

    private fun hasSection(lines: List<String>, section: String): Boolean =
        lines.any { it == "[$section]" }

    private fun valuePart(line: String): String {
        val index = line.indexOf('=')
        return if (index < 0) "" else line.substring(index + 1).trim().trim('"')
    }

    private fun readM(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) throw EOFException("pfs truncated")
        return ((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0) and 0x7fffffff
    }
}
