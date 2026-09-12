package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CodexSubscription
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ProviderSourceTypes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies that Codex uses Eta's existing Responses tool-call continuation contract. */
class CodexResponsesRequestTest {
    @Test
    fun codexSourceAlwaysUsesResponsesProvider() {
        val config = AgentModelClient.ModelConfig(
            providerId = "builtin-codex-subscription",
            providerSourceType = ProviderSourceTypes.CODEX,
            baseUrl = CodexSubscription.BASE_URL,
            apiKey = "",
            model = "codex-test-model",
            systemPrompt = "系统提示",
            openAiEndpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
        )

        assertEquals(OpenAiResponsesProvider, ProviderClientFactory.getClient(config))
    }

    @Test
    fun requestPreservesAssistantCallsAndContiguousToolOutputs() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "检查设备"))
            .put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", "")
                    .put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "call_1")
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "device_info")
                                        .put("arguments", "{\"detail\":true}"),
                                ),
                        ),
                    ),
            )
            .put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", "call_1")
                    .put("content", "{\"ok\":true}"),
            )
        val tools = JSONArray().put(
            JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", "device_info")
                        .put("description", "读取设备信息")
                        .put("parameters", JSONObject().put("type", "object")),
                ),
        )

        val request = ResponsesRequestBuilder.build(
            config = AgentModelClient.ModelConfig(
                providerId = "builtin-codex-subscription",
                providerSourceType = ProviderSourceTypes.CODEX,
                baseUrl = CodexSubscription.BASE_URL,
                apiKey = "",
                model = "codex-test-model",
                systemPrompt = "系统提示",
                openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
                hostedWebSearchEnabled = true,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.HIGH),
                    canDisable = true,
                ),
                reasoningEffort = ReasoningEffort.HIGH,
            ),
            messages = messages,
            tools = tools,
        )

        assertTrue(request.getBoolean("stream"))
        assertFalse(request.getBoolean("store"))
        assertEquals(
            "reasoning.encrypted_content",
            request.getJSONArray("include").getString(0),
        )
        val input = request.getJSONArray("input")
        assertEquals("message", input.getJSONObject(0).getString("type"))
        assertEquals("function_call", input.getJSONObject(1).getString("type"))
        assertEquals("call_1", input.getJSONObject(1).getString("call_id"))
        assertEquals("function_call_output", input.getJSONObject(2).getString("type"))
        assertEquals("call_1", input.getJSONObject(2).getString("call_id"))
        assertEquals("function", request.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals(1, request.getJSONArray("tools").length())
    }
}
