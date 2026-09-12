package io.github.mangi.eta.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentHttpClient
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64 as JavaBase64

/*
 * Eta-Codex: ChatGPT/Codex subscription authentication.
 *
 * The OpenAI Codex OAuth device-flow behavior in this file is adapted from
 * Miffan (https://github.com/Ayuilos/Miffan, commit 4fd12969), file
 * OpenAICodexAuthService.kt, Copyright (c) Ayuilos contributors,
 * licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 *
 * This file is distributed under the AGPL-3.0 license. A copy of the license
 * is available at https://www.gnu.org/licenses/agpl-3.0.html
 */

/**
 * ChatGPT/Codex subscription credentials.
 *
 * Credentials deliberately never enter [io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig],
 * RemotePreferences, Room, backups, agent prompts, or tool transcripts. They live only in this
 * Android-Keystore-protected store in Eta's own process.
 */
@Serializable
internal data class CodexCredentials(
    val accessToken: String,
    val refreshToken: String,
    val accountId: String,
    val expiresAt: Long = 0L,
    val email: String? = null,
    val planType: String? = null,
)

internal data class CodexDeviceCode(
    val userCode: String,
    val verificationUrl: String = CodexAuthRepository.DEVICE_VERIFICATION_URL,
)

internal data class CodexAccountSummary(
    val email: String?,
    val planType: String?,
)

/**
 * OAuth device authorization and refresh support for the official Codex endpoint.
 *
 * The device-code protocol and request contract are adapted from Miffan's AGPL source for
 * personal, non-commercial integration. Keep the accompanying notice when distributing a build.
 */
internal object CodexAuthRepository {
    internal const val DEVICE_VERIFICATION_URL = "https://auth.openai.com/codex/device"

    private const val AUTH_BASE_URL = "https://auth.openai.com"
    private const val DEVICE_AUTH_BASE_URL = "$AUTH_BASE_URL/api/accounts/deviceauth"
    private const val TOKEN_URL = "$AUTH_BASE_URL/oauth/token"
    private const val DEVICE_CALLBACK_URL = "$AUTH_BASE_URL/deviceauth/callback"
    // Public client identifier used by the open-source Codex device flow; it is not a secret.
    private const val CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val DEFAULT_POLL_INTERVAL_SECONDS = 5L
    private const val DEVICE_AUTH_TIMEOUT_MS = 15 * 60 * 1000L
    private const val TOKEN_REFRESH_LEEWAY_MS = 5 * 60 * 1000L
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Volatile
    private var credentialStore: CodexCredentialStore? = null
    private val credentialCache = ConcurrentHashMap<String, CodexCredentials>()
    private val refreshLocks = ConcurrentHashMap<String, Any>()

    fun init(context: Context) {
        if (credentialStore == null) {
            synchronized(this) {
                if (credentialStore == null) {
                    credentialStore = CodexCredentialStore(context.applicationContext, json)
                }
            }
        }
    }

    fun isSignedIn(providerId: String): Boolean = loadCredentials(providerId) != null

    fun accountSummary(providerId: String): CodexAccountSummary? =
        loadCredentials(providerId)?.let { credentials ->
            CodexAccountSummary(credentials.email, credentials.planType)
        }

    suspend fun signIn(
        providerId: String,
        onDeviceCodeReady: (CodexDeviceCode) -> Unit,
    ): CodexCredentials = withContext(Dispatchers.IO) {
        val deviceCode = requestDeviceCode()
        withContext(Dispatchers.Main.immediate) {
            onDeviceCodeReady(CodexDeviceCode(deviceCode.userCode))
        }
        val authorization = awaitDeviceAuthorization(deviceCode)
        val credentials = exchangeAuthorizationCode(authorization).toCredentials(previous = null)
        persist(providerId, credentials)
        credentials
    }

    fun signOut(providerId: String) {
        credentialCache.remove(providerId)
        refreshLocks.remove(providerId)
        credentialStore?.clear(providerId)
    }

    /**
     * Returns a valid credential for a model request. This function is intentionally blocking:
     * Eta's provider contract is synchronous and already executes network I/O outside the UI.
     */
    fun credentials(
        providerId: String,
        forceRefresh: Boolean = false,
    ): CodexCredentials {
        val initial = loadCredentials(providerId)
            ?: error("尚未登录 ChatGPT/Codex，请先在 Provider 设置中完成登录")
        if (!forceRefresh && !initial.needsRefresh()) return initial

        val lock = refreshLocks.computeIfAbsent(providerId) { Any() }
        synchronized(lock) {
            val current = loadCredentials(providerId) ?: initial
            if (!forceRefresh && !current.needsRefresh()) return current
            val refreshed = refresh(current).toCredentials(previous = current)
            persist(providerId, refreshed)
            return refreshed
        }
    }

    private fun requestDeviceCode(): DeviceCodeResponse {
        val requestBody = json.encodeToString(
            buildJsonObject { put("client_id", CODEX_CLIENT_ID) },
        ).toRequestBody(JSON_MEDIA_TYPE)
        val response = execute(
            Request.Builder()
                .url("$DEVICE_AUTH_BASE_URL/usercode")
                .header("Accept", "application/json")
                .post(requestBody)
                .build(),
        )
        if (response.code !in 200..299) {
            error(httpError("无法开始 ChatGPT/Codex 登录", response))
        }
        return json.decodeFromString(DeviceCodeResponse.serializer(), response.body)
    }

    private suspend fun awaitDeviceAuthorization(
        deviceCode: DeviceCodeResponse,
    ): DeviceAuthorizationResponse {
        val deadline = System.currentTimeMillis() + DEVICE_AUTH_TIMEOUT_MS
        val intervalMillis = deviceCode.intervalSeconds.coerceAtLeast(1L) * 1000L
        while (System.currentTimeMillis() < deadline) {
            val body = json.encodeToString(
                buildJsonObject {
                    put("device_auth_id", deviceCode.deviceAuthId)
                    put("user_code", deviceCode.userCode)
                },
            ).toRequestBody(JSON_MEDIA_TYPE)
            val response = execute(
                Request.Builder()
                    .url("$DEVICE_AUTH_BASE_URL/token")
                    .header("Accept", "application/json")
                    .post(body)
                    .build(),
            )
            if (response.code in 200..299) {
                return json.decodeFromString(DeviceAuthorizationResponse.serializer(), response.body)
            }
            if (!response.isAuthorizationPending()) {
                error(httpError("ChatGPT/Codex 登录被拒绝", response))
            }
            delay(intervalMillis)
        }
        error("ChatGPT/Codex 登录超时，请重新开始登录")
    }

    private fun exchangeAuthorizationCode(
        authorization: DeviceAuthorizationResponse,
    ): TokenResponse = executeTokenRequest(
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", authorization.authorizationCode)
            .add("redirect_uri", DEVICE_CALLBACK_URL)
            .add("client_id", CODEX_CLIENT_ID)
            .add("code_verifier", authorization.codeVerifier)
            .build(),
        "无法完成 ChatGPT/Codex 登录",
    )

    private fun refresh(previous: CodexCredentials): TokenResponse = executeTokenRequest(
        FormBody.Builder()
            .add("client_id", CODEX_CLIENT_ID)
            .add("grant_type", "refresh_token")
            .add("refresh_token", previous.refreshToken)
            .build(),
        "无法刷新 ChatGPT/Codex 登录",
    )

    private fun executeTokenRequest(form: FormBody, errorPrefix: String): TokenResponse {
        val response = execute(
            Request.Builder()
                .url(TOKEN_URL)
                .header("Accept", "application/json")
                .post(form)
                .build(),
        )
        if (response.code !in 200..299) error(httpError(errorPrefix, response))
        return json.decodeFromString(TokenResponse.serializer(), response.body)
    }

    private fun execute(request: Request): HttpResult =
        AgentHttpClient.client.newCall(request).execute().use { response ->
            HttpResult(response.code, response.body.string())
        }

    private fun TokenResponse.toCredentials(previous: CodexCredentials?): CodexCredentials {
        val accessClaims = decodeJwtPayload(accessToken)
        val idClaims = idToken?.let(::decodeJwtPayload)
        val authClaims = idClaims?.get("https://api.openai.com/auth")?.jsonObject
            ?: accessClaims?.get("https://api.openai.com/auth")?.jsonObject
        val resolvedAccountId = accountId
            ?: authClaims?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull
            ?: idClaims?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull
            ?: accessClaims?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull
            ?: previous?.accountId
            ?: error("ChatGPT/Codex 登录没有返回账号 ID")
        val expiresAt = expiresIn?.takeIf { it > 0 }?.let { duration ->
            System.currentTimeMillis() + duration * 1000L
        } ?: accessClaims?.get("exp")?.jsonPrimitive?.longOrNull?.times(1000L)
            ?: previous?.expiresAt
            ?: 0L

        return CodexCredentials(
            accessToken = accessToken,
            refreshToken = refreshToken ?: previous?.refreshToken
                ?: error("ChatGPT/Codex 登录没有返回刷新令牌"),
            accountId = resolvedAccountId,
            expiresAt = expiresAt,
            email = idClaims?.get("email")?.jsonPrimitive?.contentOrNull
                ?: accessClaims?.get("email")?.jsonPrimitive?.contentOrNull
                ?: previous?.email,
            planType = authClaims?.get("chatgpt_plan_type")?.jsonPrimitive?.contentOrNull
                ?: previous?.planType,
        )
    }

    private fun decodeJwtPayload(token: String): JsonObject? {
        val payload = token.split('.').getOrNull(1) ?: return null
        return runCatching {
            val decoded = JavaBase64.getUrlDecoder().decode(payload).decodeToString()
            json.parseToJsonElement(decoded).jsonObject
        }.getOrNull()
    }

    private fun CodexCredentials.needsRefresh(): Boolean =
        expiresAt > 0L && System.currentTimeMillis() >= expiresAt - TOKEN_REFRESH_LEEWAY_MS

    private fun persist(providerId: String, credentials: CodexCredentials) {
        store().set(providerId, credentials)
        credentialCache[providerId] = credentials
    }

    private fun loadCredentials(providerId: String): CodexCredentials? =
        credentialCache[providerId] ?: credentialStore?.get(providerId)?.also {
            credentialCache[providerId] = it
        }

    private fun store(): CodexCredentialStore = checkNotNull(credentialStore) {
        "CodexAuthRepository.init(context) 必须在使用前调用"
    }

    private fun HttpResult.isAuthorizationPending(): Boolean {
        if (code !in setOf(400, 403, 404, 409, 429)) return false
        val message = errorMessage().lowercase()
        return message.isBlank() ||
            message.contains("pending") ||
            message.contains("authorization") ||
            message.contains("not found") ||
            message.contains("slow_down")
    }

    private fun httpError(prefix: String, response: HttpResult): String = buildString {
        append(prefix)
        append(" (HTTP ${response.code})")
        response.errorMessage().takeIf { it.isNotBlank() }?.let { detail ->
            append(": ")
            append(detail)
        }
    }

    private fun HttpResult.errorMessage(): String = runCatching {
        val normalized = body.trim()
        if (normalized.isBlank()) return@runCatching ""
        val root = json.parseToJsonElement(normalized) as? JsonObject
        val message = root?.firstString("error_description", "message", "detail")
            ?: when (val error = root?.get("error")) {
                is JsonPrimitive -> error.contentOrNull
                is JsonObject -> error.firstString("message", "error_description", "code", "type")
                else -> null
            }
        (message ?: normalized.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(500)
    }.getOrDefault("")

    private fun JsonObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        }

    private data class HttpResult(val code: Int, val body: String)

    @Serializable
    private data class DeviceCodeResponse(
        @SerialName("device_auth_id") val deviceAuthId: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("interval") private val rawInterval: JsonPrimitive? = null,
    ) {
        val intervalSeconds: Long
            get() = rawInterval?.longOrNull ?: DEFAULT_POLL_INTERVAL_SECONDS
    }

    @Serializable
    private data class DeviceAuthorizationResponse(
        @SerialName("authorization_code") val authorizationCode: String,
        @SerialName("code_verifier") val codeVerifier: String,
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("id_token") val idToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("account_id") val accountId: String? = null,
    )
}

/** AES-GCM storage for Codex credentials; no plaintext token is persisted in Room or backups. */
private class CodexCredentialStore(
    context: Context,
    private val json: Json,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun get(providerId: String): CodexCredentials? {
        val encoded = preferences.getString(tokenKey(providerId), null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_BYTES),
            )
            val plain = cipher.doFinal(payload, GCM_IV_BYTES, payload.size - GCM_IV_BYTES)
                .toString(Charsets.UTF_8)
            json.decodeFromString(CodexCredentials.serializer(), plain)
        }.getOrNull()?.takeIf { credentials ->
            credentials.accessToken.isNotBlank() &&
                credentials.refreshToken.isNotBlank() &&
                credentials.accountId.isNotBlank()
        }
    }

    @Synchronized
    fun set(providerId: String, credentials: CodexCredentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(
            json.encodeToString(CodexCredentials.serializer(), credentials).toByteArray(Charsets.UTF_8),
        )
        val payload = cipher.iv + encrypted
        check(
            preferences.edit()
                .putString(tokenKey(providerId), Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit(),
        ) { "Codex 凭据保存失败" }
    }

    @Synchronized
    fun clear(providerId: String) {
        check(preferences.edit().remove(tokenKey(providerId)).commit()) {
            "Codex 凭据删除失败"
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun tokenKey(providerId: String): String = "credentials_$providerId"

    private companion object {
        const val PREFERENCES_NAME = "eta_codex_credentials"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "eta_codex_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
