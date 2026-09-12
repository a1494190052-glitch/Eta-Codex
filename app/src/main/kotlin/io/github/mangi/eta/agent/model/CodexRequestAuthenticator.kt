package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CodexSubscription
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.repository.CodexCredentials
import okhttp3.Headers

/*
 * Eta-Codex: request authentication for the official ChatGPT Codex endpoint.
 *
 * The Codex subscription header contract is adapted from Miffan
 * (https://github.com/Ayuilos/Miffan, commit 4fd12969), file
 * OpenAIRequestAuthenticator.kt, Copyright (c) Ayuilos contributors,
 * licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 *
 * This file is distributed under the AGPL-3.0 license. A copy of the license
 * is available at https://www.gnu.org/licenses/agpl-3.0.html
 */

/**
 * Applies the fixed authentication contract required by the official ChatGPT Codex endpoint.
 * Custom provider headers are merged before this object is called, so they cannot override the
 * account identity or bearer credential.
 */
internal object CodexRequestAuthenticator {
    const val MODELS_CLIENT_VERSION = "0.148.0"

    private const val AUTHORIZATION = "Authorization"
    private const val CHATGPT_ACCOUNT_ID = "ChatGPT-Account-Id"
    private const val ORIGINATOR = "originator"
    // Eta identifies its own requests instead of impersonating Miffan or another Codex client.
    private const val ORIGINATOR_VALUE = "eta"

    fun isCodexSubscription(config: AgentModelClient.ModelConfig): Boolean =
        ProviderSourceRegistry.resolve(
            providerId = config.providerId,
            sourceType = config.providerSourceType,
            baseUrl = config.baseUrl,
            providerType = config.providerType,
        ) == ProviderSourceTypes.CODEX

    fun apply(
        headers: Headers.Builder,
        baseUrl: String,
        credentials: CodexCredentials,
    ) {
        require(baseUrl.trim().trimEnd('/') == CodexSubscription.BASE_URL) {
            "ChatGPT 订阅只能用于官方 Codex 地址"
        }
        require(credentials.accessToken.isNotBlank()) {
            "ChatGPT/Codex access token 为空，请重新登录"
        }
        require(credentials.accountId.isNotBlank()) {
            "ChatGPT/Codex account ID 缺失，请重新登录"
        }
        headers.removeAll(AUTHORIZATION)
        headers.removeAll(CHATGPT_ACCOUNT_ID)
        headers.removeAll(ORIGINATOR)
        headers.add(AUTHORIZATION, "Bearer ${credentials.accessToken}")
        headers.add(CHATGPT_ACCOUNT_ID, credentials.accountId)
        headers.add(ORIGINATOR, ORIGINATOR_VALUE)
    }
}
