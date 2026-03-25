package com.bookvillage.mock

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    // USB real-device mode:
    // - BookVillage frontend via adb reverse: http://127.0.0.1:8080 -> host:80
    private val homeUrl = "http://book-village-alb-1548050843.ap-northeast-2.elb.amazonaws.com/"

    // API credentials for backend communication
    // 취약점: 크레덴셜 하드코딩 - 디컴파일 시 노출 (apktool / jadx)
    private val API_KEY = "bv-internal-2024-secret"
    private val DB_PASSWORD = "1234"
    private val ADMIN_USERNAME = "admin"
    private val ADMIN_PASSWORD = "admin1234"
    private val JWT_SECRET = "bv-jwt-signing-key-do-not-share"

    companion object {
        private const val TAG = "BookVillage"
        private val DOWNLOADABLE_FILE_PATTERN =
            Regex("\\.(apk|aab|zip|pdf|msi|exe|dmg)(?:$|[?#])", RegexOption.IGNORE_CASE)
    }

    private val baseSiteUrl: String
        get() = homeUrl.trimEnd('/')

    // JS Interface: origin 검증 없이 모든 페이지에서 호출 가능 (취약점)
    inner class AppBridge {
        @JavascriptInterface
        fun getAuthToken(): String {
            Log.d(TAG, "getAuthToken() called from JS")
            return JWT_SECRET
        }

        @JavascriptInterface
        fun getApiKey(): String {
            Log.d(TAG, "getApiKey() called from JS")
            return API_KEY
        }

        @JavascriptInterface
        fun getUserInfo(): String {
            val cookies = CookieManager.getInstance().getCookie(homeUrl) ?: ""
            Log.d(TAG, "getUserInfo() called from JS, cookies: $cookies")
            return """{"adminPassword":"$ADMIN_PASSWORD","cookies":"$cookies"}"""
        }

        /**
         * ── Challenge G: Smali 분기 패치 → 일반 계정 권한 상승 ──────────────
         *
         * 흐름:
         *   1. 일반 계정 로그인 (WebView)
         *   2. onPageFinished → JS fetch('/api/users/me') → App.onLoginSuccess(json) 호출
         *   3. checkIsAdmin("USER") → false → 어드민 버튼 숨김 (정상)
         *
         * 공략 (apktool 재패키징):
         *   apktool d app.apk
         *   → smali/com/bookvillage/mock/MainActivity$AppBridge.smali 에서 checkIsAdmin 찾기
         *
         *   # 원본 smali:
         *   const-string v1, "ADMIN"
         *   invoke-virtual {v0, v1}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
         *   move-result v0
         *   if-eqz v0, :cond_not_admin   ← 이 줄을 패치
         *
         *   # 패치 방법 1 - 분기 반전:
         *   if-eqz v0, :cond_not_admin  →  if-nez v0, :cond_not_admin
         *
         *   # 패치 방법 2 - 무조건 true 반환:
         *   const/4 v0, 0x1
         *   return v0
         *
         *   apktool b decompiled/ -o patched.apk
         *   keytool -genkey -v -keystore ctf.keystore -alias ctf -keyalg RSA -keysize 2048 -validity 10000
         *   jarsigner -verbose -sigalg SHA1withRSA -digestalg SHA1 -keystore ctf.keystore patched.apk ctf
         *   adb install patched.apk
         *
         *   → 일반 계정으로 로그인 → 어드민 버튼 노출 → FCM 발송 가능
         */
        @JavascriptInterface
        fun onLoginSuccess(userJson: String) {
            val role = try { JSONObject(userJson).optString("role", "USER") } catch (e: Exception) { "USER" }
            val name = try { JSONObject(userJson).optString("name", "User") } catch (e: Exception) { "User" }
            Log.d(TAG, "onLoginSuccess: name=$name role=$role")
            // checkIsAdmin()이 smali 패치 대상 메서드
            if (checkIsAdmin(role)) {
                Log.w(TAG, "Challenge G: admin access granted via role=$role (smali-patched?)")
                runOnUiThread { grantAdminAccess(name) }
            }
        }
    }

    private lateinit var webView: WebView
    private lateinit var btnNavLogin: Button
    private lateinit var btnNavLogout: Button
    private lateinit var btnNavBack: ImageButton
    private lateinit var btnNavHome: ImageButton
    private lateinit var btnNavRefresh: ImageButton

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        webView = findViewById(R.id.webView)
        btnNavLogin = findViewById(R.id.btnNavLogin)
        btnNavLogout = findViewById(R.id.btnNavLogout)
        btnNavBack = findViewById(R.id.btnNavBack)
        btnNavHome = findViewById(R.id.btnNavHome)
        btnNavRefresh = findViewById(R.id.btnNavRefresh)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        }
        // 취약점: origin 검증 없이 "App" 인터페이스를 모든 URL에 노출
        // 악의적 서버가 App.getAuthToken() 호출로 토큰 탈취 가능
        webView.addJavascriptInterface(AppBridge(), "App")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val cookies = CookieManager.getInstance().getCookie(url)
                Log.d(TAG, "Page loaded: $url")
                Log.d(TAG, "Cookies: $cookies")
                Log.d(TAG, "Session data available - API_KEY: $API_KEY")

                // Challenge G: 페이지 로드마다 로그인 상태 확인
                // SESSION_TOKEN이 있으면 /api/users/me 호출 → App.onLoginSuccess() 전달
                // smali 패치 후: checkIsAdmin()의 if-eqz → if-nez 플립 → 일반 계정도 어드민 진입
                view?.evaluateJavascript("""
                    (function() {
                        fetch('/api/users/me', { credentials: 'include' })
                            .then(function(r) { return r.ok ? r.json() : null; })
                            .then(function(u) {
                                if (u && u.role && typeof App !== 'undefined') {
                                    App.onLoginSuccess(JSON.stringify(u));
                                }
                            })
                            .catch(function() {});
                    })();
                """, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleUrlNavigation(url, null)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return handleUrlNavigation(
                    request?.url?.toString(),
                    request?.requestHeaders?.get("User-Agent")
                )
            }
        }
        webView.webChromeClient = WebChromeClient()

        btnNavLogin.setOnClickListener {
            webView.loadUrl(buildSiteUrl("login"))
        }
        btnNavLogout.setOnClickListener {
            logoutFromWeb()
        }
        btnNavBack.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }
        btnNavHome.setOnClickListener {
            webView.loadUrl(buildSiteUrl())
        }
        btnNavRefresh.setOnClickListener {
            webView.reload()
        }

        // Handle deep link if launched via intent
        handleDeepLink(intent)

        if (savedInstanceState == null) {
            loadHome()
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            val openUrl = it.getStringExtra("open_url")
            if (!openUrl.isNullOrEmpty()) {
                Log.d(TAG, "open_url received: $openUrl")
                webView.loadUrl(openUrl)
            } else {
                handleDeepLink(it)
            }
        }
    }

    private fun handleDeepLink(intent: Intent) {
        val data: Uri? = intent.data
        if (data != null) {
            val path = data.path ?: ""
            val targetUrl = when {
                path.isNotEmpty() -> buildSiteUrl(path)
                else -> buildSiteUrl()
            }
            Log.d(TAG, "Deep link received: $data -> navigating to $targetUrl")
            Log.d(TAG, "Deep link params: ${data.query}")
            webView.loadUrl(targetUrl)
        }
    }

    private fun handleUrlNavigation(url: String?, userAgent: String?): Boolean {
        if (url.isNullOrBlank()) {
            return false
        }

        Log.d(TAG, "Loading URL: $url")
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()

        if (scheme == "http" || scheme == "https") {
            if (isDownloadableUrl(url)) {
                enqueueDownload(url, userAgent, null, null)
                return true
            }
            return false
        }

        return openExternalUri(uri)
    }

    private fun isDownloadableUrl(url: String): Boolean {
        return DOWNLOADABLE_FILE_PATTERN.containsMatchIn(url)
    }

    private fun enqueueDownload(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (url.isNullOrBlank() || !URLUtil.isNetworkUrl(url)) {
            url?.let { openExternalUri(Uri.parse(it)) }
            return
        }

        runCatching {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val resolvedMimeType = resolveMimeType(url, fileName, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("Downloading file")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                addRequestHeader("User-Agent", userAgent ?: webView.settings.userAgentString)

                CookieManager.getInstance().getCookie(url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { addRequestHeader("Cookie", it) }

                if (!resolvedMimeType.isNullOrBlank()) {
                    setMimeType(resolvedMimeType)
                }
            }

            val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            Toast.makeText(this, "다운로드를 시작했습니다.", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            Log.e(TAG, "Failed to enqueue download: $url", error)
            Toast.makeText(this, "다운로드를 시작하지 못했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveMimeType(url: String, fileName: String, mimeType: String?): String? {
        if (!mimeType.isNullOrBlank() && mimeType != "application/octet-stream") {
            return mimeType
        }

        val extensionFromUrl = MimeTypeMap.getFileExtensionFromUrl(url)?.trim().orEmpty()
        val extension = when {
            extensionFromUrl.isNotEmpty() -> extensionFromUrl
            fileName.contains('.') -> fileName.substringAfterLast('.', "")
            else -> ""
        }.lowercase()

        if (extension.isEmpty()) {
            return mimeType
        }

        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "apk" -> "application/vnd.android.package-archive"
                else -> mimeType
            }
    }

    private fun openExternalUri(uri: Uri): Boolean {
        return runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        }.getOrElse { error ->
            Log.w(TAG, "Failed to open external URI: $uri", error)
            false
        }
    }

    private fun buildSiteUrl(path: String = ""): String {
        val normalizedPath = path.trim().trimStart('/')
        return if (normalizedPath.isEmpty()) {
            "$baseSiteUrl/"
        } else {
            "$baseSiteUrl/$normalizedPath"
        }
    }

    private fun logoutFromWeb() {
        val logoutUrl = buildSiteUrl("api/auth/logout")
        val cookieHeader = CookieManager.getInstance().getCookie(buildSiteUrl())

        Thread {
            try {
                val connection = (URL(logoutUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                    if (!cookieHeader.isNullOrBlank()) {
                        setRequestProperty("Cookie", cookieHeader)
                    }
                }
                connection.outputStream.use { }
                connection.inputStream?.close()
                connection.errorStream?.close()
                connection.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Logout request failed, clearing local web session anyway", e)
            }

            runOnUiThread {
                clearWebSession()
                webView.loadUrl(buildSiteUrl())
            }
        }.start()
    }

    private fun clearWebSession() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setCookie(buildSiteUrl(), "SESSION_TOKEN=; Max-Age=0; Path=/")
        cookieManager.flush()

        webView.evaluateJavascript(
            """
                (function() {
                    try {
                        sessionStorage.removeItem('bookvillage_session_token');
                        sessionStorage.removeItem('bookvillage_user');
                        window.dispatchEvent(new Event('bookvillage-auth-changed'));
                    } catch (e) {}
                })();
            """.trimIndent(),
            null
        )
    }

    /**
     * ── Challenge G: smali 패치 대상 메서드 ─────────────────────────────
     *
     * 이 메서드가 패치 포인트:
     *
     * smali (MainActivity$AppBridge.smali 또는 MainActivity.smali):
     *   .method private checkIsAdmin(Ljava/lang/String;)Z
     *       const-string v1, "ADMIN"
     *       invoke-virtual {p1, v1}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
     *       move-result v0
     *       if-eqz v0, :cond_false     ← 패치: if-eqz → if-nez
     *       const/4 v0, 0x1
     *       return v0
     *       :cond_false
     *       const/4 v0, 0x0
     *       return v0
     *   .end method
     *
     * 패치 후: equalsIgnoreCase("ADMIN") 결과가 false(0)여도 :cond_false로 분기하지 않음
     * → return 0x1 (true) 실행 → 모든 role에서 어드민 권한 획득
     */
    private fun checkIsAdmin(role: String): Boolean {
        return role.equals("ADMIN", ignoreCase = true)
    }

    private fun grantAdminAccess(name: String) {
        val token = "smali-patch-${System.currentTimeMillis()}"
        getSharedPreferences(AdminPanelActivity.PREFS, MODE_PRIVATE).edit()
            .putString(AdminPanelActivity.KEY_ADMIN_TOKEN, token)
            .putString(AdminPanelActivity.KEY_ADMIN_NAME, name)
            .apply()
        Log.w(TAG, "Admin access granted: name=$name token=$token")
    }

    private fun loadHome() {
        Log.d(TAG, "Loading home: $homeUrl (DB: root/$DB_PASSWORD, Admin: admin/$ADMIN_PASSWORD)")
        webView.clearCache(true)
        webView.loadUrl(buildSiteUrl())
    }
}
