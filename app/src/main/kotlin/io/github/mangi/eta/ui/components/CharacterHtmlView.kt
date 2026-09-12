package io.github.mangi.eta.ui.components

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.ByteArrayInputStream
import org.json.JSONObject

/**
 * 角色卡"人机交互界面"（前端卡/状态栏卡）的渲染动作。
 * 所有动作为白名单能力，不存在任意代码/网络/文件通道。
 */
internal sealed interface CharacterHtmlAction {
    data class SendText(val text: String) : CharacterHtmlAction
    data object Regenerate : CharacterHtmlAction
    data class SetVariable(val name: String, val value: String) : CharacterHtmlAction
}

private fun CharacterHtmlAction.actionLogName(): String = when (this) {
    is CharacterHtmlAction.SendText -> "send"
    is CharacterHtmlAction.SetVariable -> "setvar"
    CharacterHtmlAction.Regenerate -> "regenerate"
}

/**
 * 交互宿主。
 *
 * - [scriptsEnabled]：是否注入原生桥（发送消息/变量/重生成）。默认关闭。
 * - [displayScripts]：显示侧正则（placement=2 AI 输出 / placement=1 用户消息，markdownOnly 等）。
 * - [onAction]/[readVariable]：桥动作与变量读取。
 */
internal data class CharacterHtmlHost(
    val scriptsEnabled: Boolean,
    val onAction: (CharacterHtmlAction) -> Unit,
    val readVariable: (String) -> String = { "" },
    val displayScripts: List<io.github.mangi.eta.agent.roleplay.CharacterRegexScripts.Script> = emptyList(),
) {
    fun transformAssistant(text: String): String =
        applyDisplay(text, io.github.mangi.eta.agent.roleplay.CharacterRegexScripts.Placement.AI_OUTPUT)

    fun transformUser(text: String): String =
        applyDisplay(text, io.github.mangi.eta.agent.roleplay.CharacterRegexScripts.Placement.USER_INPUT)

    private fun applyDisplay(text: String, placement: Int): String {
        if (text.isEmpty() || displayScripts.isEmpty()) return text
        return io.github.mangi.eta.agent.roleplay.CharacterRegexScripts.apply(
            text = text,
            scripts = displayScripts,
            placement = placement,
            promptSide = false,
            macroExpand = { it },
        )
    }
}

/**
 * 沙盒 WebView 渲染卡内 HTML。
 *
 * 安全边界：
 * - 无网络：blockNetworkLoads + shouldInterceptRequest 全量拦截；仅 data:/about: 放行，
 *   常见的 jquery/lodash/font CDN 请求返回 App 内置副本或空资源
 * - 无文件/内容访问、无弹窗、无多窗口、禁缓存；所有页面导航被拒绝
 * - CSP 限制资源来源；内联脚本仅能操作本面板 DOM，除白名单桥外无任何原生能力
 *
 * 显示节奏：面板在首次稳定测高之前保持隐藏，测稳后一次性出现；
 * 避免加载期间"低占位 → 全高度 → 多次微调"的可见跳动（闪烁）。
 */
@Composable
internal fun CharacterHtmlView(
    html: String,
    host: CharacterHtmlHost?,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val bridgeEnabled = host?.scriptsEnabled == true
    val density = LocalDensity.current
    val minHeightPx = with(density) { 24.dp.toPx().toInt() }
    val maxHeightPx = with(density) { 1400.dp.toPx().toInt() }
    val fallbackHeightPx = with(density) { 160.dp.toPx().toInt() }

    key(html, bridgeEnabled) {
        val bridge = remember { EtaBridge() }
        bridge.host = host.takeIf { bridgeEnabled }
        val wrapped = remember(html, isDark) {
            wrapHtmlDocument(html, isDark)
        }
        // 0 表示"尚未测稳"：此时面板不可见，仅保留最小占位，高度确定后再显示。
        var heightPx by remember { mutableIntStateOf(0) }
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(ComposeColor.Transparent),
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(
                        with(density) {
                            val effective = if (heightPx > 0) heightPx else minHeightPx
                            effective.coerceIn(minHeightPx, maxHeightPx).toDp()
                        },
                    ),
                factory = { context ->
                    WebView(context).apply {
                        val panelView = this
                        setBackgroundColor(Color.TRANSPARENT)
                        alpha = 0f
                        // 面板渲染依赖内联脚本（状态栏/面板由卡脚本生成 DOM）。
                        // 交互桥（原生能力）仍由"允许卡内脚本交互"开关严格控制。
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.setSupportMultipleWindows(false)
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.blockNetworkLoads = true
                        settings.textZoom = 100
                        isVerticalScrollBarEnabled = true
                        overScrollMode = WebView.OVER_SCROLL_NEVER
                        if (bridgeEnabled) {
                            addJavascriptInterface(bridge, "EtaNative")
                        }
                        val heightTracker = PanelHeightTracker(
                            view = panelView,
                            minHeightPx = minHeightPx,
                            maxHeightPx = maxHeightPx,
                            fallbackHeightPx = fallbackHeightPx,
                            onHeight = { target ->
                                heightPx = target
                                if (panelView.alpha < 1f) {
                                    runCatching {
                                        panelView.animate()
                                            .alpha(1f)
                                            .setDuration(HEIGHT_FADE_IN_MS)
                                            .start()
                                    }
                                }
                            },
                        )
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                heightTracker.restart()
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = true

                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? {
                                val url = request.url?.toString().orEmpty()
                                val scheme = request.url?.scheme?.lowercase()
                                if (scheme == "data" || scheme == "about") return null
                                return routedResource(context, url)
                            }
                        }
                        loadDataWithBaseURL(null, wrapped, "text/html", "utf-8", null)
                        // onPageFinished 未触发或始终测不到高度时的兜底显示，保证面板最终可见。
                        postDelayed({ heightTracker.hardFallback() }, HEIGHT_HARD_FALLBACK_MS)
                    }
                },
                update = { },
                onRelease = { view ->
                    runCatching { view.removeJavascriptInterface("EtaNative") }
                    runCatching { view.stopLoading() }
                    runCatching { view.destroy() }
                },
            )
        }
    }
}

/**
 * 面板高度收敛控制器：采样直到连续稳定，之后一次性发布；
 * 发布后仅复核两次明显变化（字体/图片晚到），避免持续重排。
 */
private class PanelHeightTracker(
    private val view: WebView,
    private val minHeightPx: Int,
    private val maxHeightPx: Int,
    private val fallbackHeightPx: Int,
    private val onHeight: (Int) -> Unit,
) {
    private var lastSample = -1
    private var stableSamples = 0
    private var probes = 0
    private var publishedHeight = -1

    fun restart() {
        lastSample = -1
        stableSamples = 0
        probes = 0
        view.postDelayed({ sample() }, HEIGHT_FIRST_PROBE_DELAY_MS)
    }

    fun hardFallback() {
        if (publishedHeight >= 0) return
        val measured = runCatching { view.contentHeight }.getOrDefault(0)
        publish(if (measured > 0) measured else fallbackHeightPx)
    }

    private fun sample() {
        runCatching {
            probes++
            val measured = view.contentHeight
            if (measured > 0) {
                val stable = lastSample >= 0 &&
                    kotlin.math.abs(measured - lastSample) <= HEIGHT_STABLE_DELTA_PX
                stableSamples = if (stable) stableSamples + 1 else 0
                lastSample = measured
            } else {
                stableSamples = 0
            }
            val settled = measured > 0 && stableSamples >= HEIGHT_STABLE_SAMPLES
            if (settled || probes >= MAX_HEIGHT_PROBES) {
                publish(if (measured > 0) measured else fallbackHeightPx)
            } else {
                view.postDelayed({ sample() }, HEIGHT_PROBE_INTERVAL_MS)
            }
        }
    }

    private fun publish(target: Int) {
        val clamped = target.coerceIn(minHeightPx, maxHeightPx)
        publishedHeight = clamped
        onHeight(clamped)
        view.postDelayed({ recheck() }, HEIGHT_RECHECK_FIRST_DELAY_MS)
        view.postDelayed({ recheck() }, HEIGHT_RECHECK_SECOND_DELAY_MS)
    }

    private fun recheck() {
        runCatching {
            val measured = view.contentHeight
            if (measured > 0) {
                val clamped = measured.coerceIn(minHeightPx, maxHeightPx)
                if (publishedHeight < 0 || kotlin.math.abs(clamped - publishedHeight) > HEIGHT_RECHECK_DELTA_PX) {
                    publishedHeight = clamped
                    onHeight(clamped)
                }
            }
        }
    }
}

/**
 * 外链路由：常用前端库从 App 内置资源返回，其余外链一律空响应；
 * 结合 blockNetworkLoads，面板运行环境始终不触网。
 */
private fun routedResource(context: Context, url: String): WebResourceResponse {
    val lower = url.lowercase()
    return when {
        lower.contains("jquery") && lower.contains(".js") ->
            assetResponse(context, "roleplay-web/jquery.min.js", "application/javascript")
        lower.contains("lodash") && lower.contains(".js") ->
            assetResponse(context, "roleplay-web/lodash.min.js", "application/javascript")
        lower.contains("fonts.googleapis.com") -> emptyResponse("text/css")
        lower.contains("fonts.gstatic.com") -> emptyResponse("font/woff2")
        lower.contains("font-awesome") || lower.contains("fontawesome") -> emptyResponse("text/css")
        else -> emptyResponse("application/javascript")
    }
}

private fun assetResponse(context: Context, path: String, mime: String): WebResourceResponse =
    runCatching {
        WebResourceResponse(mime, "utf-8", context.assets.open(path))
    }.getOrElse { emptyResponse(mime) }

private fun emptyResponse(mime: String): WebResourceResponse =
    WebResourceResponse(mime, "utf-8", ByteArrayInputStream(ByteArray(0)))

/** JS 白名单桥：字符串动作进、变量值出；主线程派发。 */
private class EtaBridge : Any() {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var host: CharacterHtmlHost? = null

    @JavascriptInterface
    fun action(payload: String) {
        if (host == null) return
        val action = parseAction(payload) ?: return
        mainHandler.post {
            val activeHost = host ?: return@post
            AndroidAgentLogger.info("character html action: ${action.actionLogName()}")
            // 最终兜底：卡内动作（含"重新生成"这类会绕过上层防护的转发路径）异常时
            // 只记录并丢弃本次动作，绝不允许未捕获异常从 JS 桥抛到主线程闪退整界面。
            runCatching { activeHost.onAction(action) }.onFailure { throwable ->
                AndroidAgentLogger.error(
                    "character html action failed: ${action.actionLogName()} type=${throwable.safeLogType()}",
                )
            }
        }
    }

    @JavascriptInterface
    fun getVar(name: String): String {
        val safeName = name.take(128)
        if (safeName.isBlank()) return ""
        return runCatching { host?.readVariable(safeName).orEmpty() }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("character_html_get_var_failed") {
                    "character html getVar failed: type=${throwable.safeLogType()}"
                }
            }
            .getOrDefault("")
    }

    @JavascriptInterface
    fun setVar(name: String, value: String) {
        if (host == null) return
        val safeName = name.take(128)
        if (safeName.isBlank()) return
        val action = CharacterHtmlAction.SetVariable(safeName, value.take(4000))
        mainHandler.post {
            val activeHost = host ?: return@post
            AndroidAgentLogger.info("character html action: setvar")
            runCatching { activeHost.onAction(action) }.onFailure { throwable ->
                AndroidAgentLogger.error(
                    "character html action failed: setvar type=${throwable.safeLogType()}",
                )
            }
        }
    }

    private fun parseAction(payload: String): CharacterHtmlAction? = runCatching {
        val json = JSONObject(payload)
        when (json.optString("type")) {
            "send" -> CharacterHtmlAction.SendText(json.optString("text").take(4000))
                .takeIf { it.text.isNotBlank() }
            "regenerate" -> CharacterHtmlAction.Regenerate
            "setvar" -> CharacterHtmlAction.SetVariable(
                json.optString("name").take(128),
                json.optString("value").take(4000),
            ).takeIf { it.name.isNotBlank() }
            else -> null
        }
    }.getOrNull()
}

/** 组装文档：CSP、基础样式与交互 shim（无桥时调用被安全忽略）。 */
private fun wrapHtmlDocument(html: String, isDark: Boolean): String {
    val textColor = if (isDark) "#E6E6E6" else "#1F1F1F"
    val shim = INTERACTION_SHIM
    return """
        <!doctype html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: https:; media-src data:; font-src data: https://fonts.gstatic.com https://cdnjs.cloudflare.com https://cdn.jsdelivr.net; style-src 'unsafe-inline' https://fonts.googleapis.com https://cdnjs.cloudflare.com https://cdn.jsdelivr.net; script-src 'unsafe-inline' https://cdnjs.cloudflare.com https://cdn.jsdelivr.net https://unpkg.com; connect-src 'none'">
        <style>
        html, body { margin:0; padding:0; background:transparent; color:$textColor;
          font-size:15px; line-height:1.5; -webkit-text-size-adjust:100%; word-break:break-word; }
        *, *::before, *::after { box-sizing:border-box; }
        img { max-width:100%; }
        table { border-collapse:collapse; max-width:100%; }
        </style>
        </head>
        <body>
        $html
        $shim
        </body>
        </html>
    """.trimIndent()
}

/** 交互兼容层：Eta / triggerSlash / SillyTavern / TavernHelper 最小白名单子集。 */
private const val INTERACTION_SHIM = """
<script>
(function () {
  function invoke(action) {
    try { window.EtaNative.action(JSON.stringify(action)); } catch (e) {}
  }
  window.Eta = {
    send: function (text) { invoke({ type: 'send', text: String(text == null ? '' : text) }); },
    regenerate: function () { invoke({ type: 'regenerate' }); },
    getVar: function (name) {
      try { return window.EtaNative.getVar(String(name == null ? '' : name)); } catch (e) { return ''; }
    },
    setVar: function (name, value) {
      try { window.EtaNative.setVar(String(name == null ? '' : name), String(value == null ? '' : value)); } catch (e) {}
    }
  };
  window.triggerSlash = function (command) {
    var cmd = String(command == null ? '' : command).trim();
    var m;
    if ((m = cmd.match(/^\/send\s+([\s\S]*)$/i))) { window.Eta.send(m[1]); return ''; }
    if ((m = cmd.match(/^\/setvar\s+(\S+)\s+([\s\S]*)$/i))) { window.Eta.setVar(m[1], m[2]); return ''; }
    if ((m = cmd.match(/^\/getvar\s+(\S+)\s*$/i))) { return window.Eta.getVar(m[1]); }
    if ((m = cmd.match(/^\/regen(erate)?\s*$/i))) { window.Eta.regenerate(); return ''; }
    return '';
  };
  window.SillyTavern = window.SillyTavern || {};
  window.SillyTavern.runSlashCommand = window.triggerSlash;
  window.SillyTavern.substituteParams = function (value) { return String(value == null ? '' : value); };
  window.TavernHelper = window.TavernHelper || {};
  window.TavernHelper.triggerSlash = window.triggerSlash;
  document.addEventListener('click', function (event) {
    var target = event.target;
    var element = target && target.closest ? target.closest('[data-eta-send]') : null;
    if (element) {
      window.Eta.send(element.getAttribute('data-eta-send'));
    }
  }, true);
})();
</script>
"""

// 首屏高度采样：连续两次稳定即发布；最多 14 次探测，之后放弃等待并使用当前值。
private const val HEIGHT_FIRST_PROBE_DELAY_MS = 50L
private const val HEIGHT_PROBE_INTERVAL_MS = 140L
private const val HEIGHT_STABLE_DELTA_PX = 8
private const val HEIGHT_STABLE_SAMPLES = 2
private const val MAX_HEIGHT_PROBES = 14

// 发布后仅复核两次明显变化，避免持续重排造成新的闪烁。
private const val HEIGHT_RECHECK_FIRST_DELAY_MS = 1_300L
private const val HEIGHT_RECHECK_SECOND_DELAY_MS = 2_800L
private const val HEIGHT_RECHECK_DELTA_PX = 24

// 页面回调缺失时的兜底显示时间与淡入时长。
private const val HEIGHT_HARD_FALLBACK_MS = 2_400L
private const val HEIGHT_FADE_IN_MS = 140L
