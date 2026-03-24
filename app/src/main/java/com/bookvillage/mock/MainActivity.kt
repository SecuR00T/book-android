package com.bookvillage.mock

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    // USB real-device mode:
    // - BookVillage frontend via adb reverse: http://127.0.0.1:8080 -> host:80
    private val homeUrl = "http://book-village-alb-1548050843.ap-northeast-2.elb.amazonaws.com/"

    private val BACKEND_URL = "http://book-village-alb-1548050843.ap-northeast-2.elb.amazonaws.com"

    // API credentials for backend communication
    // 취약점: 크레덴셜 하드코딩 - 디컴파일 시 노출 (apktool / jadx)
    private val API_KEY = "bv-internal-2024-secret"
    private val DB_PASSWORD = "1234"
    private val ADMIN_USERNAME = "admin"
    private val ADMIN_PASSWORD = "admin1234"
    private val JWT_SECRET = "bv-jwt-signing-key-do-not-share"

    companion object {
        private const val TAG = "BookVillage"
    }

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

        // 취약점: FCM 토큰을 JS에 그대로 노출
        @JavascriptInterface
        fun getFcmToken(): String {
            val prefs = getSharedPreferences("bookvillage_prefs", MODE_PRIVATE)
            val token = prefs.getString("fcm_token", "") ?: ""
            Log.d(TAG, "getFcmToken() called from JS, token: $token")
            return token
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
    private lateinit var btnNavBack: ImageButton
    private lateinit var btnNavHome: ImageButton
    private lateinit var btnNavRefresh: ImageButton
    private lateinit var btnAdminNotify: Button

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "Notification permission granted: $granted")
            if (granted) fetchFcmToken()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        webView = findViewById(R.id.webView)
        btnNavBack = findViewById(R.id.btnNavBack)
        btnNavHome = findViewById(R.id.btnNavHome)
        btnNavRefresh = findViewById(R.id.btnNavRefresh)
        btnAdminNotify = findViewById(R.id.btnAdminNotify)

        btnAdminNotify.setOnClickListener {
            startActivity(Intent(this, AdminPanelActivity::class.java))
        }

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
                Log.d(TAG, "Loading URL: $url")
                return false
            }
        }
        webView.webChromeClient = WebChromeClient()

        btnNavBack.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }
        btnNavHome.setOnClickListener {
            webView.loadUrl(homeUrl)
        }
        btnNavRefresh.setOnClickListener {
            webView.reload()
        }

        // FCM: 알림 권한 요청 및 토큰 취득
        requestNotificationPermissionIfNeeded()

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
        // AdminPanelActivity 로그인 성공 후 토큰이 저장된 경우 관리자 버튼 표시
        val adminToken = getSharedPreferences(AdminPanelActivity.PREFS, MODE_PRIVATE)
            .getString(AdminPanelActivity.KEY_ADMIN_TOKEN, null)
        val isAdmin = !adminToken.isNullOrEmpty()
        btnAdminNotify.visibility = if (isAdmin) View.VISIBLE else View.GONE
        Log.d(TAG, "onResume - admin_token present: $isAdmin")

        // [Phase 2] 긴급 공지사항 팝업 체크
        // 공격자가 관리자 권한 탈취 후 공지사항에 악성 링크 삽입 시
        // 앱 실행/복귀 시마다 백엔드에서 최신 긴급 공지를 조회하여 팝업 표시
        fetchAndShowUrgentNotice()
    }

    /**
     * ── [Phase 2] 긴급 공지사항 팝업 ─────────────────────────────────────
     *
     * 공격 시나리오:
     *   1. 공격자가 관리자 권한 탈취 (Phase 1: APK 디컴파일 → 하드코딩 크레덴셜 추출)
     *   2. /admin/api/notices 에 긴급 공지 등록:
     *      { "title": "긴급 보안 업데이트", "content": "...", "linkUrl": "http://attacker-c2.com/fake-store", "urgent": true }
     *   3. 사용자 앱 실행 → 이 메서드 호출 → 팝업 표시
     *   4. 사용자는 '공식 공지사항'이므로 신뢰 → 링크 클릭
     *   5. [Phase 3] WebView가 가짜 구글 플레이 스토어로 이동
     *
     * 취약점:
     *   - 공지사항 API에 URL 검증 없음 → 임의 URL 삽입 가능
     *   - 팝업에서 URL 검증 없이 WebView에 로드 → Open Redirect
     *   - 사용자 신뢰를 악용한 사회공학적 공격
     */
    private fun fetchAndShowUrgentNotice() {
        thread {
            try {
                val conn = URL("$BACKEND_URL/api/notices/latest-urgent")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val noticeId = json.optLong("id", -1L)
                    val title    = json.optString("title", "")
                    val content  = json.optString("content", "")
                    val linkUrl  = json.optString("linkUrl", "")

                    if (noticeId < 0 || title.isEmpty()) return@thread

                    // 이미 표시한 공지는 재표시 하지 않음 (noticeId 추적)
                    val prefs = getSharedPreferences("bookvillage_prefs", MODE_PRIVATE)
                    val lastShownId = prefs.getLong("last_notice_popup_id", -1L)
                    if (noticeId == lastShownId) return@thread

                    prefs.edit().putLong("last_notice_popup_id", noticeId).apply()
                    Log.d(TAG, "[Phase 2] Urgent notice fetched: id=$noticeId title=$title linkUrl=$linkUrl")

                    runOnUiThread { showUrgentNoticeDialog(title, content, linkUrl) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[Phase 2] 공지 fetch 실패: ${e.message}")
            }
        }
    }

    /**
     * ── [Phase 2 → Phase 3] 긴급 공지 팝업 다이얼로그 ───────────────────
     *
     * 취약점:
     *   - linkUrl 검증 없음 → 공격자 서버 URL 삽입 가능
     *   - WebView가 외부 URL을 아무 제한 없이 로드
     *     → 가짜 구글 플레이 스토어(Phase 3)로 유도
     *   - android:usesCleartextTraffic=true → HTTP 피싱 페이지 로드 허용
     *   - 사용자는 '공식 앱 공지사항'을 신뢰 → 경계심 낮음
     *
     * [방어 방법]
     *   - linkUrl을 allowlist 도메인만 허용
     *   - shouldOverrideUrlLoading에서 외부 도메인 차단
     *   - 공지사항 등록 시 서버에서 URL 패턴 검증
     */
    private fun showUrgentNoticeDialog(title: String, content: String, linkUrl: String) {
        val builder = AlertDialog.Builder(this)
            .setTitle("🔔 $title")
            .setMessage(content)
            .setCancelable(false)

        if (linkUrl.isNotEmpty()) {
            builder.setPositiveButton("업데이트 / 자세히 보기") { _, _ ->
                // 취약점: URL 검증 없이 WebView에 로드
                // → 공격자 서버(가짜 플레이 스토어)로 직접 이동
                Log.d(TAG, "[Phase 2→3] Notice link clicked, loading: $linkUrl")
                webView.loadUrl(linkUrl)
            }
        }

        builder.setNegativeButton("나중에") { dialog, _ -> dialog.dismiss() }
        builder.show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            // FCM 알림 클릭으로 전달된 url 처리
            val fcmUrl = it.getStringExtra("fcm_url")
            if (!fcmUrl.isNullOrEmpty()) {
                Log.d(TAG, "FCM notification clicked, loading url: $fcmUrl")
                webView.loadUrl(fcmUrl)
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
                path.isNotEmpty() -> "$homeUrl$path"
                else -> homeUrl
            }
            Log.d(TAG, "Deep link received: $data -> navigating to $targetUrl")
            Log.d(TAG, "Deep link params: ${data.query}")
            webView.loadUrl(targetUrl)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> fetchFcmToken()
                else -> requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            fetchFcmToken()
        }
    }

    private fun fetchFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "FCM token fetch failed", task.exception)
                return@addOnCompleteListener
            }
            val token = task.result
            // 취약점: FCM 토큰을 로그에 노출 (CTF 챌린지 - 로그캣에서 토큰 탈취)
            Log.d(TAG, "FCM Token: $token")
            // SharedPreferences에 저장 (AppBridge.getFcmToken()으로 JS에서 접근 가능)
            getSharedPreferences("bookvillage_prefs", MODE_PRIVATE)
                .edit().putString("fcm_token", token).apply()
        }
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
        btnAdminNotify.visibility = View.VISIBLE
        Log.w(TAG, "Admin access granted: name=$name token=$token")
    }

    private fun loadHome() {
        Log.d(TAG, "Loading home: $homeUrl (DB: root/$DB_PASSWORD, Admin: admin/$ADMIN_PASSWORD)")
        webView.clearCache(true)
        webView.loadUrl(homeUrl)
    }
}
