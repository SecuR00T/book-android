package com.bookvillage.mock

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class ProfileActivity : AppCompatActivity() {

    private val baseUrl = "http://book-village-alb-1548050843.ap-northeast-2.elb.amazonaws.com/"

    companion object {
        private const val TAG = "BookVillage_Profile"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this)
        setContentView(webView)
        supportActionBar?.hide()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        // Hardcoded bypass token (취약점: 소스코드 내 운영 토큰 하드코딩)
        val bypassToken = "BV-ANDROID-DEEPLINK-2024"
        val userId = intent.data?.getQueryParameter("userId") ?: intent.getStringExtra("userId") ?: "1"
        val targetUrl = "$baseUrl/mypage/orders"

        Log.d(TAG, "Loading profile for userId: $userId without authentication")
        Log.d(TAG, "Target URL: $targetUrl")
        Log.d(TAG, "Bypass token: $bypassToken")

        // Inject session cookie to bypass authentication
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie(baseUrl, "remember_uid=$userId; Path=/")
            setCookie(baseUrl, "role=admin; Path=/")
            flush()
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                // sessionStorage에 토큰 주입하여 React 앱 인증 우회
                view?.evaluateJavascript("""
                    (function() {
                        sessionStorage.setItem('bookvillage_session_token', '$bypassToken');
                        if (window.location.pathname !== '/mypage/orders') {
                            window.location.href = '$targetUrl';
                        }
                    })();
                """.trimIndent(), null)
            }
        }

        webView.loadUrl(targetUrl)
    }
}
