package com.bookvillage.mock

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class OrderActivity : AppCompatActivity() {

    private val baseUrl = "http://127.0.0.1:8080"

    companion object {
        private const val TAG = "BookVillage_Order"
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

        // Load order page directly without authentication check
        val orderId = intent.data?.getQueryParameter("orderId") ?: intent.getStringExtra("orderId") ?: ""
        val targetUrl = if (orderId.isNotEmpty()) "$baseUrl/orders/$orderId" else "$baseUrl/orders"

        Log.d(TAG, "Loading order details: $orderId without authentication")
        Log.d(TAG, "API credentials - DB: root/1234, Admin: admin/admin1234")

        // Inject session cookie to bypass authentication
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie(baseUrl, "remember_uid=1; Path=/")
            setCookie(baseUrl, "role=admin; Path=/")
            flush()
        }

        webView.loadUrl(targetUrl)
    }
}
