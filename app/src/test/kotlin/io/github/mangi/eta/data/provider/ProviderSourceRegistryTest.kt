package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.CodexSubscription
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ProviderTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderSourceRegistryTest {
    @Test
    fun officialCodexEndpointResolvesToSubscriptionSource() {
        assertEquals(
            ProviderSourceTypes.CODEX,
            ProviderSourceRegistry.resolve(
                providerId = null,
                sourceType = null,
                baseUrl = "${CodexSubscription.BASE_URL}/",
                providerType = ProviderTypes.OPENAI_COMPATIBLE,
            ),
        )
    }
}
