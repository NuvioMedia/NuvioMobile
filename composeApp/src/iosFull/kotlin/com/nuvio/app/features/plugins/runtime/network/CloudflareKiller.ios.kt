package com.nuvio.app.features.plugins.runtime.network

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import platform.Foundation.*
import platform.WebKit.*
import platform.darwin.NSObject
import platform.Security.SecTrustRef


internal fun platformWebViewSolverImpl(): WebViewSolver = IosWebViewSolver

@OptIn(ExperimentalForeignApi::class)
internal object IosWebViewSolver : WebViewSolver {
    private val log = Logger.withTag("IosWebViewSolver")
    private var cachedUserAgent: String? = null

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

    override suspend fun solve(
        url: String,
        headers: Map<String, String>,
        forceFresh: Boolean,
        timeoutMs: Long,
    ): CfSolveResult? {
        val host = extractHost(url)
        val nsUrl = NSURL.URLWithString(url) ?: run {
            log.e { "CF solve: invalid URL: $url" }
            return null
        }

        if (!forceFresh) {
            val existing = getWkCookies(host)
            if (existing.containsKey("cf_clearance")) {
                return CfSolveResult(cookies = existing, userAgent = getOrCaptureUserAgent())
            }
        }

        withContext(Dispatchers.Main) {
            clearWkCookiesForHost(host)
        }

        var webViewRef: WKWebView? = null
        val webViewUserAgent = getOrCaptureUserAgent()

        try {
            val delegate = withContext(Dispatchers.Main) {
                val d = CloudflareNavigationDelegate()
                val config = WKWebViewConfiguration()
                val webView = WKWebView(
                    frame = platform.CoreGraphics.CGRectMake(0.0, 0.0, 375.0, 812.0),
                    configuration = config,
                )
                webView.customUserAgent = webViewUserAgent
                webView.navigationDelegate = d
                webViewRef = webView
                webView.loadRequest(NSURLRequest.requestWithURL(nsUrl))
                d
            }

            val result = withTimeoutOrNull(timeoutMs) {
                var solved: CfSolveResult? = null
                var isFailed = false
                while (solved == null && !isFailed) {
                    delay(100L)
                    withContext(Dispatchers.Main) {
                        val error = delegate.error
                        if (error != null) {
                            log.e { "CF solve failed due to network/navigation error: ${error.localizedDescription}" }
                            isFailed = true
                            return@withContext
                        }
                        val cookies = getWkCookies(host)
                        if (cookies.containsKey("cf_clearance")) {
                            val finalUrl = webViewRef?.URL?.absoluteString ?: url
                            solved = CfSolveResult(
                                cookies = cookies,
                                userAgent = webViewUserAgent,
                                redirectUrl = finalUrl.takeIf { it != url },
                            )
                        }
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
                webViewRef?.navigationDelegate = null
                webViewRef?.stopLoading()
                webViewRef = null
            }
        }
    }

    override suspend fun fetchRenderedPage(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): WebViewFetchResult? {
        val nsUrl = NSURL.URLWithString(url) ?: run {
            log.e { "CF fetch fallback: invalid URL: $url" }
            return null
        }

        var webViewRef: WKWebView? = null
        val webViewUserAgent = getOrCaptureUserAgent()

        try {
            val delegate = withContext(Dispatchers.Main) {
                val d = CloudflareNavigationDelegate()
                val config = WKWebViewConfiguration()
                val webView = WKWebView(
                    frame = platform.CoreGraphics.CGRectMake(0.0, 0.0, 375.0, 812.0),
                    configuration = config,
                )
                webView.customUserAgent = webViewUserAgent
                webView.navigationDelegate = d
                webViewRef = webView
                webView.loadRequest(NSURLRequest.requestWithURL(nsUrl))
                d
            }

            val result = withTimeoutOrNull(timeoutMs) {
                var rendered: WebViewFetchResult? = null
                var isFailed = false
                while (rendered == null && !isFailed) {
                    delay(250L)
                    withContext(Dispatchers.Main) {
                        val error = delegate.error
                        if (error != null) {
                            log.e { "CF fetch fallback failed due to network/navigation error: ${error.localizedDescription}" }
                            isFailed = true
                            return@withContext
                        }
                        val state = webViewRef?.evaluateJavascriptString(PAGE_STATE_JS).orEmpty()
                        if (state == "blocked") {
                            isFailed = true
                            return@withContext
                        }

                        if (state == "ok") {
                            val body = webViewRef?.evaluateJavascriptString(PAGE_BODY_JS).orEmpty()
                            if (body.isNotBlank()) {
                                val contentType = webViewRef?.evaluateJavascriptString(PAGE_CONTENT_TYPE_JS)
                                    .orEmpty().ifBlank { "text/html" }
                                val headersWithContentType = delegate.headers.toMutableMap()
                                if (headersWithContentType.keys.none { it.equals("content-type", ignoreCase = true) }) {
                                    headersWithContentType["content-type"] = contentType
                                }
                                rendered = WebViewFetchResult(
                                    status = delegate.status,
                                    statusText = delegate.statusText,
                                    url = webViewRef?.URL?.absoluteString ?: url,
                                    body = body,
                                    headers = headersWithContentType,
                                )
                            }
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
                webViewRef?.navigationDelegate = null
                webViewRef?.stopLoading()
                webViewRef = null
            }
        }
    }

    private suspend fun WKWebView.evaluateJavascriptString(script: String): String =
        withContext(Dispatchers.Main) {
            suspendCoroutine { cont ->
                evaluateJavaScript(script) { result, _ ->
                    cont.resume(result as? String ?: "")
                }
            }
        }

    private suspend fun getOrCaptureUserAgent(): String = withContext(Dispatchers.Main) {
        cachedUserAgent?.let { return@withContext it }
        val webView = WKWebView(
            frame = platform.CoreGraphics.CGRectMake(0.0, 0.0, 375.0, 812.0),
            configuration = WKWebViewConfiguration(),
        )
        val deferred = CompletableDeferred<String>()
        webView.evaluateJavaScript("navigator.userAgent") { result, _ ->
            deferred.complete(
                result as? String
                    ?: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148",
            )
        }
        val userAgent = deferred.await()
        // Keep the WebView reference alive during suspension by referencing it here
        log.d { "Captured User-Agent using WebView. WebView description: ${webView.description}" }
        userAgent.also { cachedUserAgent = it }
    }

    private suspend fun getWkCookies(
        host: String,
    ): Map<String, String> = withContext(Dispatchers.Main) {
        val cookieStore = WKWebsiteDataStore.defaultDataStore().httpCookieStore
        suspendCoroutine { cont ->
            cookieStore.getAllCookies { rawList ->
                @Suppress("UNCHECKED_CAST")
                val cookies = (rawList as? List<NSHTTPCookie>)
                    ?.filter { cookieMatchesHost(it.domain, host) }
                    ?.associate { it.name to it.value }
                    ?.filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
                    .orEmpty()
                cont.resume(cookies)
            }
        }
    }

    private suspend fun clearWkCookiesForHost(host: String) = withContext(Dispatchers.Main) {
        suspendCoroutine<Unit> { cont ->
            val store = WKWebsiteDataStore.defaultDataStore().httpCookieStore
            store.getAllCookies { rawList ->
                @Suppress("UNCHECKED_CAST")
                val cookies = (rawList as? List<NSHTTPCookie>)
                    ?.filter { cookieMatchesHost(it.domain, host) }
                    .orEmpty()

                if (cookies.isEmpty()) {
                    cont.resume(Unit)
                    return@getAllCookies
                }

                var remaining = cookies.size
                cookies.forEach { cookie ->
                    store.deleteCookie(cookie) {
                        remaining -= 1
                        if (remaining == 0) {
                            cont.resume(Unit)
                        }
                    }
                }
            }
        }
    }

    private fun cookieMatchesHost(domain: String, host: String): Boolean {
        val normalizedDomain = domain.trimStart('.').lowercase()
        val normalizedHost = host.lowercase()
        return normalizedHost == normalizedDomain || normalizedHost.endsWith(".$normalizedDomain")
    }
}

@OptIn(ExperimentalForeignApi::class)
private class CloudflareNavigationDelegate : NSObject(), WKNavigationDelegateProtocol {
    var status: Int = 200
    var statusText: String = "OK"
    val headers = mutableMapOf<String, String>()
    var error: NSError? = null

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationResponse: WKNavigationResponse,
        decisionHandler: (WKNavigationResponsePolicy) -> Unit
    ) {
        val httpResponse = decidePolicyForNavigationResponse.response as? NSHTTPURLResponse
        if (httpResponse != null) {
            status = httpResponse.statusCode.toInt()
            statusText = NSHTTPURLResponse.localizedStringForStatusCode(httpResponse.statusCode)
            headers.clear()
            httpResponse.allHeaderFields.forEach { (key, value) ->
                headers[key.toString()] = value.toString()
            }
        }
        decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow)
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailNavigation: WKNavigation?,
        withError: NSError
    ) {
        error = withError
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError
    ) {
        error = withError
    }

    override fun webView(
        webView: WKWebView,
        didReceiveAuthenticationChallenge: platform.Foundation.NSURLAuthenticationChallenge,
        completionHandler: (platform.Foundation.NSURLSessionAuthChallengeDisposition, platform.Foundation.NSURLCredential?) -> Unit
    ) {
        val trust = didReceiveAuthenticationChallenge.protectionSpace?.serverTrust
        if (trust != null) {
            completionHandler(
                platform.Foundation.NSURLSessionAuthChallengeUseCredential,
                NSURLCredential.credentialForTrust(trust)
            )
        } else {
            completionHandler(platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling, null)
        }
    }
}
