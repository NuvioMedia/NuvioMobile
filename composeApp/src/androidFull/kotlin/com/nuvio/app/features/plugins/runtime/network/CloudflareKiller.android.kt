package com.nuvio.app.features.plugins.runtime.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal fun platformWebViewSolverImpl(): WebViewSolver = AndroidWebViewSolver

internal object AndroidWebViewSolver : WebViewSolver {
    private val log = Logger.withTag("AndroidWebViewSolver")

    private const val PAGE_STATE_JS = """
        (function() {
            try {
                if (document.readyState !== 'interactive' && document.readyState !== 'complete') return 'wait';
                var title = (document.title || '').toLowerCase();
                if (title.indexOf('attention required') !== -1 || title.indexOf('access denied') !== -1) return 'blocked';
                if (title.indexOf('just a moment') !== -1) return 'wait';
                if (document.querySelector('#challenge-running, #challenge-stage, #cf-challenge-running, .cf-browser-verification, #turnstile-wrapper, #cf-please-wait, script[src*="challenge-platform"]')) return 'wait';
                if (!document.documentElement || !document.body) return 'wait';
                var contentType = (document.contentType || '').toLowerCase();
                if ((contentType.indexOf('json') !== -1 || contentType.indexOf('text/plain') !== -1) && (document.body.innerText || '').length > 0) return 'ok';
                var html = document.documentElement.outerHTML || '';
                if (html.length < 64) return 'wait';
                return 'ok';
            } catch (e) {
                return 'wait';
            }
        })()
    """

    private const val PAGE_BODY_JS = """
        (function() {
            try {
                var contentType = (document.contentType || '').toLowerCase();
                if (contentType.indexOf('json') !== -1 || contentType.indexOf('text/plain') !== -1) {
                    return document.body ? (document.body.innerText || '') : '';
                }
                return document.documentElement ? (document.documentElement.outerHTML || '') : '';
            } catch (e) {
                return '';
            }
        })()
    """

    private const val PAGE_CONTENT_TYPE_JS = """
        (function() {
            try {
                return document.contentType || 'text/html';
            } catch (e) {
                return 'text/html';
            }
        })()
    """

    @Volatile
    private var appContext: Context? = null

    private val blockedPathSuffixes = listOf(
        ".jpg",
        ".jpeg",
        ".png",
        ".webp",
        ".mpg",
        ".mpeg",
        ".mp4",
        ".webm",
        ".gifv",
        ".flv",
        ".asf",
        ".mov",
        ".mng",
        ".mkv",
        ".ogg",
        ".avi",
        ".mp3",
        ".wav",
        ".woff2",
        ".woff",
        ".ttf",
        ".css",
        ".vtt",
        ".srt",
        ".ts",
        ".gif",
    )

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun solve(
        url: String,
        headers: Map<String, String>,
        forceFresh: Boolean,
        timeoutMs: Long,
    ): CfSolveResult? {
        val context = appContext ?: run {
            log.e { "AndroidWebViewSolver is not initialized" }
            return null
        }

        val host = extractHost(url)
        val cookieManager = CookieManager.getInstance()
        withContext(Dispatchers.Main) {
            cookieManager.setAcceptCookie(true)
        }

        if (!forceFresh) {
            val existing = withContext(Dispatchers.Main) {
                runCatching { cookieManager.getCookie(url) }.getOrNull()
            }
            if (existing?.contains("cf_clearance") == true) {
                return CfSolveResult(
                    cookies = parseCookieString(existing),
                    userAgent = captureUserAgent(context),
                )
            }
        }

        runCatching { clearCookiesForUrl(url) }

        var webViewRef: WebView? = null
        var webViewUserAgent = ""
        val finalUrlRef = java.util.concurrent.atomic.AtomicReference(url)

        try {
            withContext(Dispatchers.Main) {
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewUserAgent = settings.userAgentString

                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?,
                            pageUrl: String?,
                            favicon: android.graphics.Bitmap?,
                        ) {
                            if (pageUrl != null && !pageUrl.contains("challenges.cloudflare.com")) {
                                finalUrlRef.set(pageUrl)
                            }
                            super.onPageStarted(view, pageUrl, favicon)
                        }

                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            if (pageUrl != null && !pageUrl.contains("challenges.cloudflare.com")) {
                                finalUrlRef.set(pageUrl)
                            }
                            super.onPageFinished(view, pageUrl)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val requestUrl = request.url.toString()

                            if (requestUrl.contains("recaptcha") || requestUrl.contains("/cdn-cgi/")) {
                                return super.shouldInterceptRequest(view, request)
                            }

                            if (requestUrl.endsWith("/favicon.ico") || requestUrl.startsWith("wss://")) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            val path = runCatching { Uri.parse(requestUrl).path.orEmpty() }.getOrDefault("")
                            if (blockedPathSuffixes.any { path.contains(it, ignoreCase = true) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }

                            return super.shouldInterceptRequest(view, request)
                        }

                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?,
                        ) {
                            handler?.proceed()
                        }
                    }
                }
                webViewRef = webView
                webView.loadUrl(url, headers)
            }

            val result = withTimeoutOrNull(timeoutMs) {
                var solved: CfSolveResult? = null
                while (solved == null) {
                    delay(100L)
                    val currentUrl = finalUrlRef.get()
                    val raw = runCatching {
                        cookieManager.getCookie(currentUrl) ?: cookieManager.getCookie(url)
                    }.getOrNull()

                    if (raw?.contains("cf_clearance") == true) {
                        delay(500L)
                        val finalUrl = finalUrlRef.get()
                        val finalRaw = runCatching {
                            cookieManager.getCookie(finalUrl) ?: cookieManager.getCookie(url)
                        }.getOrNull() ?: raw
                        solved = CfSolveResult(
                            cookies = parseCookieString(finalRaw),
                            userAgent = webViewUserAgent,
                            redirectUrl = finalUrl.takeIf { it != url },
                        )
                    }
                }
                solved
            }

            if (result == null) {
                log.w { "CF solve timed out after ${timeoutMs}ms for $host" }
            }
            return result
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
                webViewRef = null
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun fetchRenderedPage(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): WebViewFetchResult? {
        val context = appContext ?: run {
            log.e { "AndroidWebViewSolver is not initialized" }
            return null
        }

        var webViewRef: WebView? = null
        val finalUrlRef = java.util.concurrent.atomic.AtomicReference(url)
        val statusRef = java.util.concurrent.atomic.AtomicInteger(200)
        val statusTextRef = java.util.concurrent.atomic.AtomicReference("OK")
        val responseHeadersRef = java.util.concurrent.atomic.AtomicReference<Map<String, String>>(emptyMap())

        try {
            withContext(Dispatchers.Main) {
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?,
                            pageUrl: String?,
                            favicon: android.graphics.Bitmap?,
                        ) {
                            pageUrl?.let(finalUrlRef::set)
                            super.onPageStarted(view, pageUrl, favicon)
                        }

                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            pageUrl?.let(finalUrlRef::set)
                            super.onPageFinished(view, pageUrl)
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?,
                        ) {
                            if (request?.isForMainFrame == true && errorResponse != null) {
                                statusRef.set(errorResponse.statusCode)
                                statusTextRef.set(errorResponse.reasonPhrase ?: "")
                                responseHeadersRef.set(errorResponse.responseHeaders.orEmpty())
                            }
                            super.onReceivedHttpError(view, request, errorResponse)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val requestUrl = request.url.toString()
                            if (requestUrl.contains("recaptcha") || requestUrl.contains("/cdn-cgi/")) {
                                return super.shouldInterceptRequest(view, request)
                            }
                            if (requestUrl.endsWith("/favicon.ico") || requestUrl.startsWith("wss://")) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?,
                        ) {
                            handler?.proceed()
                        }
                    }
                }
                webViewRef = webView
                webView.loadUrl(url, headers)
            }

            val result = withTimeoutOrNull(timeoutMs) {
                var rendered: WebViewFetchResult? = null
                while (rendered == null) {
                    delay(250L)
                    val state = withContext(Dispatchers.Main) {
                        webViewRef?.evaluateJavascriptString(PAGE_STATE_JS)
                    } ?: "wait"

                    if (state == "blocked") {
                        return@withTimeoutOrNull null
                    }

                    if (state == "ok") {
                        val body = withContext(Dispatchers.Main) {
                            webViewRef?.evaluateJavascriptString(PAGE_BODY_JS)
                        }.orEmpty()
                        if (body.isNotBlank()) {
                            val contentType = withContext(Dispatchers.Main) {
                                webViewRef?.evaluateJavascriptString(PAGE_CONTENT_TYPE_JS)
                            }.orEmpty().ifBlank { "text/html" }
                            val headersWithContentType = responseHeadersRef.get().toMutableMap()
                            if (headersWithContentType.keys.none { it.equals("content-type", ignoreCase = true) }) {
                                headersWithContentType["content-type"] = contentType
                            }
                            rendered = WebViewFetchResult(
                                status = statusRef.get(),
                                statusText = statusTextRef.get(),
                                url = finalUrlRef.get(),
                                body = body,
                                headers = headersWithContentType,
                            )
                        }
                    }
                }
                rendered
            }

            if (result == null) {
                log.w { "CF WebView fetch fallback timed out after ${timeoutMs}ms for $url" }
            }
            return result
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
                webViewRef = null
            }
        }
    }

    private suspend fun clearCookiesForUrl(url: String) = withContext(Dispatchers.Main) {
        val cookieString = CookieManager.getInstance().getCookie(url).orEmpty()
        if (cookieString.isBlank()) return@withContext

        val host = Uri.parse(url).host ?: return@withContext
        val rootDomain = host.split(".").takeIf { it.size > 2 }
            ?.takeLast(2)
            ?.joinToString(separator = ".", prefix = ".")
        val cookieNames = parseCookieString(cookieString).keys

        cookieNames.forEach { name ->
            expireCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=$host")
            if (rootDomain != null) {
                expireCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=$rootDomain")
            }
        }
        CookieManager.getInstance().flush()
    }

    private suspend fun expireCookie(url: String, value: String) = suspendCoroutine<Unit> { cont ->
        CookieManager.getInstance().setCookie(url, value) {
            cont.resume(Unit)
        }
    }

    private suspend fun captureUserAgent(context: Context): String = withContext(Dispatchers.Main) {
        val webView = WebView(context)
        val userAgent = webView.settings.userAgentString
        webView.destroy()
        userAgent
    }

    private suspend fun WebView.evaluateJavascriptString(script: String): String =
        suspendCoroutine { cont ->
            evaluateJavascript(script) { raw ->
                cont.resume(decodeJavascriptString(raw))
            }
        }

    private fun decodeJavascriptString(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return runCatching {
            org.json.JSONArray("[$raw]").getString(0)
        }.getOrElse {
            raw.removeSurrounding("\"")
        }
    }
}
