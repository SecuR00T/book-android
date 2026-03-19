package com.bookvillage.mock

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // USB real-device mode:
    // - BookVillage frontend via adb reverse: http://127.0.0.1:8080 -> host:80
    private val homeUrl = "http://book-village-alb-1548050843.ap-northeast-2.elb.amazonaws.com/"

    // API credentials for backend communication
    private val API_KEY = "bv-internal-2024-secret"
    private val DB_PASSWORD = "1234"
    private val ADMIN_PASSWORD = "admin1234"
    private val JWT_SECRET = "bv-jwt-signing-key-do-not-share"

    companion object {
        private const val TAG = "BookVillage"
    }

    private lateinit var webView: WebView
    private lateinit var btnNavBack: ImageButton
    private lateinit var btnNavHome: ImageButton
    private lateinit var btnNavRefresh: ImageButton

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        webView = findViewById(R.id.webView)
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
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val cookies = CookieManager.getInstance().getCookie(url)
                Log.d(TAG, "Page loaded: $url")
                Log.d(TAG, "Cookies: $cookies")
                Log.d(TAG, "Session data available - API_KEY: $API_KEY")
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleDeepLink(it) }
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

    private fun loadHome() {
        Log.d(TAG, "Loading home: $homeUrl (DB: root/$DB_PASSWORD, Admin: admin/$ADMIN_PASSWORD)")
        webView.clearCache(true)
        webView.loadUrl(homeUrl)
    }
}
