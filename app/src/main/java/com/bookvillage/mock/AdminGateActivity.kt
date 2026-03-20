package com.bookvillage.mock

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * CTF: Android Disassembly Challenges
 *
 * ── Challenge 1: Magic Number ────────────────────────────────────────
 * apktool로 디컴파일 → AdminGateActivity.smali 분석
 *
 *   computeMagicKey() 메서드 역추적:
 *     const/4 v0, 0x1             ; 1
 *     mul-int/lit16 v0, v0, 0x539 ; × 1337
 *     const/16 v1, 0xbeef         ; 48879
 *     add-int v0, v0, v1
 *     const/4 v1, 0x14            ; 20
 *     sub-int v0, v0, v1          ; → 50196
 *
 *   공략:
 *     adb shell am start \
 *       -n com.bookvillage.mock/.AdminGateActivity \
 *       --ei gate_key 50196
 *
 * ── Challenge 2: Intent Extra 분기 ───────────────────────────────────
 * smali에서 getBooleanExtra 호출 확인 (재패키징 없이 ADB로 직접 전달)
 *
 *   smali에서 발견:
 *     const-string v0, "debug"
 *     const/4 v1, 0x0
 *     invoke-virtual {p1, v0, v1},
 *       Landroid/content/Intent;->getBooleanExtra(Ljava/lang/String;Z)Z
 *
 *   공략 (바이너리 수정 없이 ADB만으로):
 *     adb shell am start \
 *       -n com.bookvillage.mock/.AdminGateActivity \
 *       --ez debug true
 */
class AdminGateActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BookVillage"
        private const val BASE_URL =
            "http://book-village-alb-1548050843.ap-northeast-2.elb.amazonaws.com"

        // 취약점: MainActivity 정적 분석으로 발견한 관리자 크레덴셜
        private const val ADMIN_USER = "admin"
        private const val ADMIN_PASS = "admin1234"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Challenge 2: Intent Extra 분기 ─────────────────────────────
        // smali에서 getBooleanExtra("debug", false) 확인 후
        // --ez debug true 로 전달 → 재패키징 없이 우회
        if (intent.getBooleanExtra("debug", false)) {
            Log.w(TAG, "Challenge 2: debug extra received via ADB")
            thread { authenticate("debug-intent") }
            return
        }

        // ── Challenge 1: Magic Number ───────────────────────────────────
        val gateKey = intent.getIntExtra("gate_key", -1)
        Log.d(TAG, "AdminGate: gate_key=$gateKey, expected=${computeMagicKey()}")

        if (gateKey == computeMagicKey()) {
            Log.d(TAG, "Challenge 1: magic key correct")
            thread { authenticate("magic-key") }
        } else {
            Log.w(TAG, "AdminGate: wrong key ($gateKey)")
            Toast.makeText(this, "접근 거부", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ── smali 역추적 대상 ───────────────────────────────────────────────
    // 결과: 1 × 1337 + 48879 - 20 = 50196
    private fun computeMagicKey(): Int {
        val seed = 0xBEEF                // 48879
        val pkgLen = packageName.length  // "com.bookvillage.mock" = 20
        return 1 * 1337 + seed - pkgLen
    }

    // ── 인증 흐름 ───────────────────────────────────────────────────────
    // 하드코딩 쿠키 없이 실제 백엔드 API 호출로 세션 획득
    private fun authenticate(source: String) {
        // Step 1: /api/auth/login → 실제 SESSION_TOKEN 쿠키 발급
        val sessionToken = fetchSessionToken()

        // Step 2: /admin/api/auth/login → FCM용 관리자 토큰 발급
        val adminToken = fetchAdminToken()

        if (sessionToken == null && adminToken == null) {
            runOnUiThread {
                Toast.makeText(this, "인증 실패: 서버 연결 확인", Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }

        // Step 3: 백엔드에서 받은 SESSION_TOKEN을 WebView에 심기
        // → hardcoded setCookie가 아닌, 실제 서버 발급 토큰
        sessionToken?.let {
            CookieManager.getInstance().apply {
                setCookie(BASE_URL, "SESSION_TOKEN=$it; Path=/")
                flush()
            }
            Log.d(TAG, "SESSION_TOKEN set from real API response")
        }

        // Step 4: FCM용 토큰 저장
        val fcmToken = adminToken ?: "fallback-$source-${System.currentTimeMillis()}"
        getSharedPreferences(AdminPanelActivity.PREFS, MODE_PRIVATE).edit()
            .putString(AdminPanelActivity.KEY_ADMIN_TOKEN, fcmToken)
            .putString(AdminPanelActivity.KEY_ADMIN_NAME, "Admin")
            .apply()

        Log.w(TAG, "Admin session ready. source=$source")

        runOnUiThread {
            startActivity(Intent(this, AdminPanelActivity::class.java))
            finish()
        }
    }

    // /api/auth/login → Set-Cookie: SESSION_TOKEN=... 추출
    private fun fetchSessionToken(): String? {
        return try {
            val payload = JSONObject()
                .put("username", ADMIN_USER)
                .put("password", ADMIN_PASS)
                .toString()

            val conn = URL("$BASE_URL/api/auth/login").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            OutputStreamWriter(conn.outputStream).use { it.write(payload) }

            if (conn.responseCode == 200) {
                // 응답 헤더의 Set-Cookie에서 SESSION_TOKEN 값 파싱
                val setCookie = conn.getHeaderField("Set-Cookie") ?: ""
                val match = Regex("SESSION_TOKEN=([^;]+)").find(setCookie)
                val token = match?.groupValues?.get(1)
                Log.d(TAG, "SESSION_TOKEN acquired: ${token?.take(20)}...")
                token
            } else {
                Log.w(TAG, "User login failed: HTTP ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchSessionToken error", e)
            null
        }
    }

    // /admin/api/auth/login → FCM 발송용 관리자 토큰
    private fun fetchAdminToken(): String? {
        return try {
            val payload = JSONObject()
                .put("username", ADMIN_USER)
                .put("password", ADMIN_PASS)
                .toString()

            val conn = URL("$BASE_URL/admin/api/auth/login").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            OutputStreamWriter(conn.outputStream).use { it.write(payload) }

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val token = JSONObject(body).optString("token")
                Log.d(TAG, "Admin token acquired: ${token.take(30)}...")
                token.ifEmpty { null }
            } else {
                Log.w(TAG, "Admin login failed: HTTP ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAdminToken error", e)
            null
        }
    }
}
