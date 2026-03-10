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

    private val baseUrl = "http://127.0.0.1:8080"

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
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        // Load profile page directly without authentication check
        val userId = intent.data?.getQueryParameter("userId") ?: intent.getStringExtra("userId") ?: "1"
        val targetUrl = "$baseUrl/mypage"

        Log.d(TAG, "Loading profile for userId: $userId without authentication")
        Log.d(TAG, "Target URL: $targetUrl")

        // Inject session cookie to bypass authentication
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie(baseUrl, "remember_uid=$userId; Path=/")
            setCookie(baseUrl, "role=admin; Path=/")
            flush()
        }

        webView.loadUrl(targetUrl)
    }
}
