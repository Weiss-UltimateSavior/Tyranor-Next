package com.tyranor.next.ui.cover

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tyranor.next.core.auth.BangumiAuthService
import com.tyranor.next.core.auth.BangumiAuthStore
import com.tyranor.next.core.auth.BangumiTokenVerification
import com.tyranor.next.core.auth.SecureAuthStore
import com.tyranor.next.core.status.PlayStatusService
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 粘贴凭据清洗（BK-20）：首尾 trim + 一行 filterNot 去内部全部空白——
 * 含换行/空格的粘贴 token 不再原样通过校验入库；confirm 读点为调用时点的
 * state 最新值（竞态修复在 Composable 内，静态自查，此处钉住清洗口径）。
 */
class CredentialTokenCleanupTest {

    @Test
    fun plainTokenPassesThroughUnchanged() {
        assertEquals("abc123def", cleanCredentialToken("abc123def"))
    }

    @Test
    fun leadingAndTrailingWhitespaceIsTrimmed() {
        assertEquals("token123", cleanCredentialToken("  token123\t\n"))
        assertEquals("token123", cleanCredentialToken("\ntoken123 "))
    }

    @Test
    fun internalWhitespaceIsRemoved() {
        // 粘贴时常见的空格分段与跨行复制
        assertEquals("abc123", cleanCredentialToken("abc 123"))
        assertEquals("abcd", cleanCredentialToken("ab\ncd"))
        assertEquals("abcd", cleanCredentialToken("a\tb\rcd"))
        assertEquals("abcd", cleanCredentialToken("a b  c\nd "))
    }

    @Test
    fun whitespaceOnlyInputBecomesEmptyAndTriggersMissingMessage() {
        assertEquals("", cleanCredentialToken("   "))
        assertEquals("", cleanCredentialToken("\n\t "))
    }

    @Test
    fun cancelledVndbVerificationNeverOverwritesNewerSave() = runBlocking {
        val context: Context = ContextWrapper(null)
        val firstVerifyStarted = CompletableDeferred<Unit>()
        val allowFirstVerifyFinish = CompletableDeferred<Unit>()
        val savedTokens = CopyOnWriteArrayList<String>()

        var firstCancelled = false
        val firstJob = launch {
            try {
                verifyAndSaveVndbToken(
                    context = context,
                    token = "vndb_stale",
                    verify = {
                        firstVerifyStarted.complete(Unit)
                        allowFirstVerifyFinish.await()
                        PlayStatusService.VndbAuthInfo("u1", "alice", listOf("listread"))
                    },
                    saveToken = { _, tok, _ ->
                        savedTokens.add(tok)
                        true
                    },
                )
            } catch (_: CancellationException) {
                firstCancelled = true
            }
        }

        firstVerifyStarted.await()
        // 新提交先完成保存，随后取消旧协程并放行其校验返回，确保确保活性检查拦住旧写回
        val secondOutcome = verifyAndSaveVndbToken(
            context = context,
            token = "vndb_fresh",
            verify = { PlayStatusService.VndbAuthInfo("u2", "bob", listOf("listwrite")) },
            saveToken = { _, tok, _ ->
                savedTokens.add(tok)
                true
            },
        )
        assertEquals(CredentialDialogOutcome.Saved, secondOutcome)

        firstJob.cancel()
        allowFirstVerifyFinish.complete(Unit)
        firstJob.join()

        assertTrue(firstCancelled)
        assertEquals(listOf("vndb_fresh"), savedTokens.toList())
    }

    @Test
    fun cancelledBangumiVerificationNeverOverwritesNewerSave() = runBlocking {
        val context: Context = ContextWrapper(null)
        val firstVerifyStarted = CompletableDeferred<Unit>()
        val allowFirstVerifyFinish = CompletableDeferred<Unit>()
        val savedTokens = CopyOnWriteArrayList<String>()

        var firstCancelled = false
        val firstJob = launch {
            try {
                verifyAndSaveBangumiToken(
                    context = context,
                    token = "bgm_stale",
                    verify = {
                        firstVerifyStarted.complete(Unit)
                        allowFirstVerifyFinish.await()
                        BangumiTokenVerification.Success(BangumiAuthService.BangumiTokenIdentity(1L, "alice"))
                    },
                    readGeneration = { 0L },
                    saveToken = { _, tok, _, _ ->
                        savedTokens.add(tok)
                        true
                    },
                )
            } catch (_: CancellationException) {
                firstCancelled = true
            }
        }

        firstVerifyStarted.await()
        val secondOutcome = verifyAndSaveBangumiToken(
            context = context,
            token = "bgm_fresh",
            verify = {
                BangumiTokenVerification.Success(BangumiAuthService.BangumiTokenIdentity(2L, "bob"))
            },
            readGeneration = { 0L },
            saveToken = { _, tok, _, _ ->
                savedTokens.add(tok)
                true
            },
        )
        assertEquals(CredentialDialogOutcome.Saved, secondOutcome)

        firstJob.cancel()
        allowFirstVerifyFinish.complete(Unit)
        firstJob.join()

        assertTrue(firstCancelled)
        assertEquals(listOf("bgm_fresh"), savedTokens.toList())
    }

    @After
    fun tearDownSecureStore() {
        SecureAuthStore.testCipherDelegate = null
    }

    @Test
    fun vndbVerifyReturningSuccessAfterCancellationNeverSavesToken() = runBlocking {
        val context: Context = ContextWrapper(null)
        val savedTokens = CopyOnWriteArrayList<String>()
        var cancelled = false
        val job = launch {
            try {
                verifyAndSaveVndbToken(
                    context = context,
                    token = "vndb_stale",
                    verify = {
                        currentCoroutineContext()[Job]?.cancel()
                        PlayStatusService.VndbAuthInfo("u1", "alice", listOf("listread"))
                    },
                    saveToken = { _, tok, _ ->
                        savedTokens.add(tok)
                        true
                    },
                )
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        job.join()
        assertTrue(cancelled)
        assertTrue(savedTokens.isEmpty())
    }

    @Test
    fun bangumiVerifyReturningSuccessAfterCancellationNeverSavesToken() = runBlocking {
        val context: Context = ContextWrapper(null)
        val savedTokens = CopyOnWriteArrayList<String>()
        var cancelled = false
        val job = launch {
            try {
                verifyAndSaveBangumiToken(
                    context = context,
                    token = "bgm_stale",
                    verify = {
                        currentCoroutineContext()[Job]?.cancel()
                        BangumiTokenVerification.Success(BangumiAuthService.BangumiTokenIdentity(1L, "alice"))
                    },
                    readGeneration = { 0L },
                    saveToken = { _, tok, _, _ ->
                        savedTokens.add(tok)
                        true
                    },
                )
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        job.join()
        assertTrue(cancelled)
        assertTrue(savedTokens.isEmpty())
    }

    @Test
    fun activeBangumiPersonalVerifyDoesNotOverwriteNewerOAuthAuthorization() = runBlocking {
        val storage = mutableMapOf<String, MutableMap<String, Any>>()
        val memoryTokens = mutableMapOf<String, String>()
        SecureAuthStore.testCipherDelegate = { field, plain ->
            if (plain != null) {
                if (plain.isEmpty()) memoryTokens.remove(field) else memoryTokens[field] = plain
                null
            } else {
                memoryTokens[field]
            }
        }
        val context: Context = createMemoryContext(storage)
        val verifyStarted = CompletableDeferred<Unit>()
        val allowVerifyFinish = CompletableDeferred<Unit>()

        var personalOutcome: CredentialDialogOutcome? = null
        val personalJob = launch {
            personalOutcome = verifyAndSaveBangumiToken(
                context = context,
                token = "bgm_personal_old",
                verify = {
                    verifyStarted.complete(Unit)
                    allowVerifyFinish.await()
                    BangumiTokenVerification.Success(BangumiAuthService.BangumiTokenIdentity(1L, "alice"))
                },
                formatString = { _, _ -> "superseded" },
            )
        }

        verifyStarted.await()
        // 协程仍保持活跃期间，另一条 OAuth 流程先完成新账号保存（credential_generation 递增）
        assertTrue(
            BangumiAuthStore.saveTokens(
                context = context,
                accessToken = "oauth_access_new",
                refreshToken = "oauth_refresh_new",
                expiresAtMillis = 999_999L,
            ),
        )
        allowVerifyFinish.complete(Unit)
        personalJob.join()

        // 旧个人令牌校验不得覆盖新 OAuth 凭据，且不得返回 Saved
        assertEquals(CredentialDialogOutcome.Failed("superseded"), personalOutcome)
        assertEquals("oauth_access_new", BangumiAuthStore.getAccessToken(context))
        assertEquals("oauth_refresh_new", BangumiAuthStore.getRefreshToken(context))
    }

    private fun createMemoryContext(storage: MutableMap<String, MutableMap<String, Any>>): Context =
        object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val backing = storage.getOrPut(name) { mutableMapOf() }
                return Proxy.newProxyInstance(
                    SharedPreferences::class.java.classLoader,
                    arrayOf(SharedPreferences::class.java),
                ) { _, method, args ->
                    synchronized(storage) {
                        when (method.name) {
                            "contains" -> backing.containsKey(args[0])
                            "getString" -> backing[args[0]] as? String ?: args.getOrNull(1) as? String
                            "getLong" -> backing[args[0]] as? Long ?: args.getOrNull(1) as? Long ?: 0L
                            "getInt" -> backing[args[0]] as? Int ?: args.getOrNull(1) as? Int ?: 0
                            "getBoolean" -> backing[args[0]] as? Boolean ?: args.getOrNull(1) as? Boolean ?: false
                            "getAll" -> backing.toMap()
                            "edit" -> createMemoryEditor(storage, backing)
                            else -> null
                        }
                    }
                } as SharedPreferences
            }
        }

    private fun createMemoryEditor(
        lock: Any,
        backing: MutableMap<String, Any>,
    ): SharedPreferences.Editor {
        val pending = mutableMapOf<String, Any?>()
        var clear = false
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString" -> { pending[args[0] as String] = args[1]; editor }
                "putLong" -> { pending[args[0] as String] = args[1]; editor }
                "putInt" -> { pending[args[0] as String] = args[1]; editor }
                "putBoolean" -> { pending[args[0] as String] = args[1]; editor }
                "remove" -> { pending[args[0] as String] = null; editor }
                "clear" -> { clear = true; pending.clear(); editor }
                "apply", "commit" -> {
                    synchronized(lock) {
                        if (clear) backing.clear()
                        for ((k, v) in pending) {
                            if (v == null) backing.remove(k) else backing[k] = v
                        }
                    }
                    if (method.name == "commit") true else null
                }
                else -> null
            }
        } as SharedPreferences.Editor
        return editor
    }
}
