package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CodexSubscription
import io.github.mangi.eta.data.repository.CodexCredentials
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexRequestAuthenticatorTest {
    @Test
    fun subscriptionAuthReplacesConflictingCredentialAndIdentityHeaders() {
        val headers = Headers.Builder()
            .add("Authorization", "Bearer stale")
            .add("ChatGPT-Account-Id", "wrong-account")
            .add("originator", "another-client")

        CodexRequestAuthenticator.apply(
            headers = headers,
            baseUrl = CodexSubscription.BASE_URL,
            credentials = CodexCredentials(
                accessToken = "fresh-access-token",
                refreshToken = "refresh-token",
                accountId = "chatgpt-account",
            ),
        )

        val requestHeaders = headers.build()
        assertEquals("Bearer fresh-access-token", requestHeaders["Authorization"])
        assertEquals("chatgpt-account", requestHeaders["ChatGPT-Account-Id"])
        assertEquals("eta", requestHeaders["originator"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun subscriptionAuthRejectsNonOfficialEndpoint() {
        CodexRequestAuthenticator.apply(
            headers = Headers.Builder(),
            baseUrl = "https://gateway.example.com/v1",
            credentials = CodexCredentials(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                accountId = "account",
            ),
        )
    }
}
