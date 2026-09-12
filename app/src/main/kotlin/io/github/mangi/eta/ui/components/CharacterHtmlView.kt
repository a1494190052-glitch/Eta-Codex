package io.github.mangi.eta.ui.components

import android.annotation.SuppressLint
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

/** 交互宿主：脚本开关、动作回调与变量读取。 */
internal data class CharacterHtmlHost(
    val scriptsEnabled: Boolean,
    val onAction: (CharacterHtmlAction) -> Unit,
    val readVariable: (String) -> String = { "" },
)

/**
 * 沙盒 WebView 渲染卡内 HTML。
 *
 * 安全边界：
 * - 无脚本模式完全禁用 JS；开启脚本时也只暴露 [EtaBridge] 三个白名单方法
 * - 阻断一切网络请求（blockNetworkLoads + shouldInterceptRequest 兜底），仅允许 data:/about:
 * - 无文件/内容访问，无弹窗，无多窗口，禁缓存
 * - 注入 CSP 限制资源来源为内联样式与 data: 图片
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun CharacterHtmlView(
    html: String,
    host: CharacterHtmlHost?,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val scriptsEnabled = host?.scriptsEnabled == true
    val density = LocalDensity.current
    val currentHost by rememberUpdatedState(host)
    var heightPx by remember { mutableIntStateOf(0) }
    val maxHeightPx = with(density) { 1400.dp.toPx().toInt() }
    val minHeightPx = with(density) { 24.dp.toPx().toInt() }

    key(html, scriptsEnabled) {
        val bridge = remember { EtaBridge() }
        bridge.host = host.takeIf { scriptsEnabled }
        val wrapped = remember(html, scriptsEnabled, isDark) {
            wrapHtmlDocument(html, scriptsEnabled, isDark)
        }
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(ComposeColor.Transparent),
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { heightPx.coerceAtLeast(minHeightPx).toDp() }),
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(Color.TRANSPARENT)
                        settings.javaScriptEnabled = scriptsEnabled
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
                        if (scriptsEnabled) {
                            addJavascriptInterface(bridge, "EtaNative")
                        }
                        webViewClient = object : WebViewClient() {
                            private fun reportHeight(view: WebView) {
                                val measured = view.contentHeight
                                if (measured > 0) {
                                    heightPx = measured.coerceAtMost(maxHeightPx)
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                view.postDelayed({ reportHeight(view) }, 50)
                                view.postDelayed({ reportHeight(view) }, 350)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? {
                                val scheme = request.url?.scheme?.lowercase()
                                if (scheme == "data" || scheme == "about") return null
                                return WebResourceResponse(
                                    "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)),
                                )
                            }
                        }
                        loadDataWithBaseURL(null, wrapped, "text/html", "utf-8", null)
                    }
                },
                update = { view ->
                    if (view.url == null) view.loadDataWithBaseURL(null, wrapped, "text/html", "utf-8", null)
                },
                onRelease = { view ->
                    runCatching { view.removeJavascriptInterface("EtaNative") }
                    runCatching { view.stopLoading() }
                    runCatching { view.destroy() }
                },
            )
        }
    }
}

/** JS 白名单桥：字符串动作进、变量值出；主线程派发。 */
private class EtaBridge : Any() {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var host: CharacterHtmlHost? = null

    @JavascriptInterface
    fun action(payload: String) {
        val host = host ?: return
        val action = parseAction(payload) ?: return
        mainHandler.post { host.onAction(action) }
    }

    @JavascriptInterface
    fun getVar(name: String): String {
        val safeName = name.take(128)
        if (safeName.isBlank()) return ""
        return runCatching { host?.readVariable(safeName).orEmpty() }.getOrDefault("")
    }

    @JavascriptInterface
    fun setVar(name: String, value: String) {
        val host = host ?: return
        val safeName = name.take(128)
        if (safeName.isBlank()) return
        mainHandler.post { host.onAction(CharacterHtmlAction.SetVariable(safeName, value.take(4000))) }
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

/**
 * 会话状态桥保持 WebView 独立于 Compose 重组；host 由组合层在每次重组时刷新，
 * 保证回调始终指向最新的动作处理逻辑。
 */

/** 组装文档：CSP、基础样式与（可选）交互 shim。 */
private fun wrapHtmlDocument(html: String, scriptsEnabled: Boolean, isDark: Boolean): String {
    val textColor = if (isDark) "#E6E6E6" else "#1F1F1F"
    val scriptPolicy = if (scriptsEnabled) "'unsafe-inline'" else "'none'"
    val shim = if (scriptsEnabled) INTERACTION_SHIM else ""
    return """
        <!doctype html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; media-src data:; font-src data:; style-src 'unsafe-inline'; script-src $scriptPolicy; connect-src 'none'">
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
