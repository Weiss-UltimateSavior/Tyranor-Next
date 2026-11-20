package com.tyranor.next.core.game.scan

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 从已安装的 MAGES. 宿主读取识别规则（宿主仓库 `engine/impacto/profiles.json`，宿主 0.3.1 起作为 APK 资产
 * [ASSET_PATH] 随包发布）。宿主新增 profile 后，本 App 不用更新就能识别对应的游戏目录。
 *
 * 宿主未安装、旧宿主没有这份资产、内容不合格式时为 [Load.Unavailable]，调用方退回 [ImpactoGameCatalog] 的内置目录表；
 * 读取途中的偶发错误为 [Load.Failed]，调用方保留上一次的规则。只读宿主自己的资产，不执行宿主的任何代码。
 */
object ImpactoHostProfiles {
    /**
     * 与 `ImpactoHostContract.PACKAGE` 同值（JVM 单测核对）。扫描层不直接依赖 external 模块（避免 game-scan →
     * engine-external 的耦合/循环，同 `EngineScanner` 里的模块别名常量），两处如需改名必须同步。
     */
    internal const val HOST_PACKAGE = "com.tyranor.next.impacto"

    /** 宿主 APK 内的资产路径（宿主仓库 `host-apk/app/build.gradle.kts` 的 `stageImpactoAssets`）。 */
    const val ASSET_PATH = "tyn/profiles.json"

    /** 规则文件大小上限：正常不到 10 KB，超出视为异常内容，不读入。 */
    private const val MAX_BYTES = 256 * 1024

    private const val TAG = "ImpactoHostProfiles"

    /** [load] 的结果。 */
    sealed interface Load {
        /** 读到了合格的规则。 */
        class Loaded(val rules: ImpactoGameCatalog.Rules) : Load

        /** 宿主未安装、没有这份资产（旧宿主）或内容不合格式：确定的状态，用内置目录表。 */
        data object Unavailable : Load

        /** 读取途中出错（I/O、系统服务异常）：偶发失败，保留上一次的规则。 */
        data object Failed : Load
    }

    /** 读取已安装宿主的规则（不抛出）。 */
    fun load(context: Context): Load {
        val text = try {
            val resources = context.packageManager.getResourcesForApplication(HOST_PACKAGE)
            resources.assets.open(ASSET_PATH).use { input ->
                val bytes = input.readNBytesCompat(MAX_BYTES + 1)
                if (bytes.size > MAX_BYTES) {
                    Log.w(TAG, "ignoring oversized $ASSET_PATH from the host")
                    return Load.Unavailable
                }
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return Load.Unavailable
        } catch (_: FileNotFoundException) {
            return Load.Unavailable
        } catch (e: IOException) {
            Log.w(TAG, "cannot read $ASSET_PATH from the host", e)
            return Load.Failed
        } catch (e: RuntimeException) {
            // SecurityException、系统服务异常等
            Log.w(TAG, "cannot read $ASSET_PATH from the host", e)
            return Load.Failed
        }
        val rules = parse(text)
        if (rules == null) {
            Log.w(TAG, "ignoring malformed $ASSET_PATH from the host")
            return Load.Unavailable
        }
        return Load.Loaded(rules)
    }

    /**
     * 解析规则文本（纯函数，供 JVM 单测）。格式与宿主仓库 `engine/impacto/profiles.json` 一致：
     * - `profiles[]`：`id`、`name`、`language`（可为 null）、`variantOf`（可缺省）、
     *   `detect`（可为 null）= `{ allOf: [条目…], textArchive?: 条目 }`；`textArchive` 同样必须存在。
     * - `unsupportedLayouts[]`（可缺省）：`{ id, allOf: [条目…], tryProfile }`，`tryProfile` 必须是上面某个 profile。
     *
     * 条目是相对游戏目录的路径，最多一层子目录（第二层必须是文件），不得含空段、`.`、`..`。字符串字段必须真的是
     * 非空字符串（不接受数字、`null` 之类被转成字符串的值）。任何一处不合格式就整份不用（返回 null），不做部分采纳——
     * 规则只在宿主仓库里维护，那边的测试保证格式。没有任何可识别 profile 时同样返回 null。
     */
    fun parse(text: String): ImpactoGameCatalog.Rules? = try {
        val root = JSONObject(text)
        val profiles = root.getJSONArray("profiles")
        val games = ArrayList<ImpactoGameCatalog.Game>()
        val ids = HashSet<String>()
        for (i in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(i)
            val id = profile.requiredString("id")
            require(ids.add(id))
            val name = profile.requiredString("name")
            val detect = when (val value = profile.opt("detect")) {
                null, JSONObject.NULL -> continue
                is JSONObject -> value
                else -> throw IllegalArgumentException("detect")
            }
            val entries = detect.getJSONArray("allOf").strings()
            val textArchive = detect.optionalString("textArchive")
            val required = entries + listOfNotNull(textArchive)
            require(entries.isNotEmpty() && required.all(::isValidEntry))
            games += ImpactoGameCatalog.Game(
                profileId = id,
                title = name,
                requiredEntries = required.toSet(),
                family = profile.optionalString("variantOf"),
                textLanguage = profile.optionalString("language"),
            )
        }
        val layouts = ArrayList<ImpactoGameCatalog.UnsupportedLayout>()
        val unsupported = when (val value = root.opt("unsupportedLayouts")) {
            null -> JSONArray()
            is JSONArray -> value
            else -> throw IllegalArgumentException("unsupportedLayouts")
        }
        for (i in 0 until unsupported.length()) {
            val layout = unsupported.getJSONObject(i)
            val entries = layout.getJSONArray("allOf").strings()
            val tryProfile = layout.requiredString("tryProfile")
            require(entries.isNotEmpty() && entries.all(::isValidEntry) && games.any { it.profileId == tryProfile })
            layouts += ImpactoGameCatalog.UnsupportedLayout(
                id = layout.requiredString("id"),
                requiredEntries = entries.toSet(),
                tryProfile = tryProfile,
            )
        }
        if (games.isEmpty()) null else ImpactoGameCatalog.Rules(games, layouts)
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: StackOverflowError) {
        // org.json 递归解析，极深的嵌套会耗尽栈；规则文件不会是这样，按不合格式处理
        null
    }

    /** 相对路径、最多一层子目录、没有空段与 `.`/`..`（扫描只看根目录与其子目录一层）。 */
    internal fun isValidEntry(entry: String): Boolean {
        val parts = entry.split('/')
        return parts.size in 1..2 && parts.all { it.isNotEmpty() && it != "." && it != ".." }
    }

    /** 全部元素都必须是非空字符串（`getString` 会把数字转成字符串，这里不接受）。 */
    private fun JSONArray.strings(): List<String> = (0 until length()).map { index ->
        (get(index) as? String)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("entry $index")
    }

    private fun JSONObject.requiredString(name: String): String =
        (opt(name) as? String)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException(name)

    /** 缺省或 null 为 null；存在时必须是非空字符串。 */
    private fun JSONObject.optionalString(name: String): String? = when (val value = opt(name)) {
        null, JSONObject.NULL -> null
        is String -> value.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException(name)
        else -> throw IllegalArgumentException(name)
    }

    /** 最多读 [limit] 字节（`InputStream.readNBytes` 需要 API 33，minSdk 为 26）。 */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
