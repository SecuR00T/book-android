package com.bookvillage.mock

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * ═══════════════════════════════════════════════════════════════════
 *  CTF: 관리자 패널 - 공략 경로 6가지
 * ═══════════════════════════════════════════════════════════════════
 *
 *  [진입]
 *  apktool/jadx 디컴파일 → AndroidManifest에서 딥링크 발견
 *    adb shell am start -W \
 *      -a android.intent.action.VIEW \
 *      -d "bookvillage://admin-panel" \
 *      com.bookvillage.mock
 *
 *  ── 서버 인증 우회 ──────────────────────────────────────────────
 *  Path A │ SQL Injection
 *         │ /admin/api/auth/login 의 username 필드에 주입
 *         │ 예) username: "admin' --"  password: (임의)
 *         │ → 비밀번호 검사 WHERE 절 주석처리 → 관리자 토큰 발급
 *
 *  Path B │ JWT 시크릿 추출 → 토큰 위조
 *         │ jadx로 이 파일 디컴파일 →
 *         │   JWT_SECRET = "bv-jwt-signing-key-do-not-share" 발견
 *         │ 위조 토큰 생성 (아래 forgeAdminToken() 참고)
 *         │   payload = base64('{"role":"ADMIN","id":1}')
 *         │   sig     = sha1(payload + JWT_SECRET)
 *         │   token   = payload + "." + sig
 *
 *  Path C │ XOR 하드코딩 bypass 토큰
 *         │ jadx로 이 파일 디컴파일 → decodeBypass() 발견
 *         │ ENCODED_BYPASS XOR XOR_KEY → "BV-BYPASS-KEY-2024"
 *         │ 딥링크: bookvillage://admin-panel?token=BV-BYPASS-KEY-2024
 *
 *  ── 앱 수준 인증 우회 (재패키징 없이) ──────────────────────────
 *  Bypass D │ Intent Extra 주입 (취약한 토큰 형식 검증)
 *           │ jadx로 isWeakTokenValid() 발견 → split("-")[3]=="admin" 확인
 *           │ adb shell am start \
 *           │   -n com.bookvillage.mock/.AdminPanelActivity \
 *           │   --es admin_token "x-x-x-admin-x-x"
 *
 *  Bypass E │ allowBackup=true → SharedPreferences 변조
 *           │ 1. adb backup -noapk com.bookvillage.mock -f backup.ab
 *           │ 2. backup.ab 압축 해제 → bookvillage_prefs.xml 수정
 *           │    <boolean name="is_admin" value="true" />
 *           │ 3. adb restore backup_patched.ab
 *           │ → 앱 재시작 시 is_admin=true 감지 → 관리자 패널 진입
 *
 *  Bypass F │ HTTP 평문 통신 → Burp Suite 응답 변조
 *           │ 1. Android 프록시: Burp Suite 127.0.0.1:8080
 *           │ 2. GET /api/auth/verify-role 요청 인터셉트
 *           │ 3. 응답 {"role":"USER"} → {"role":"ADMIN"} 변조
 *           │ → 서버 무결성 검증 없음 → 관리자 패널 진입
 * ═══════════════════════════════════════════════════════════════════
 */
class AdminPanelActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BookVillage"
        private const val BACKEND =
            "http://book-village-alb-1548050843.ap-northeast-2.elb.amazonaws.com"
        const val PREFS = "bookvillage_prefs"
        const val KEY_ADMIN_TOKEN = "admin_token"
        const val KEY_ADMIN_NAME  = "admin_name"

        // 취약점 Path B: APK의 JWT_SECRET과 공유 (디컴파일로 발견 가능)
        private const val JWT_SECRET = "bv-jwt-signing-key-do-not-share"

        // ── Challenge 3: XOR 인코딩된 bypass 토큰 ────────────────────
        // smali에서 추출해야 할 두 가지:
        //
        //   1) ENCODED_BYPASS 배열 (iget-object로 접근)
        //   2) XOR_KEY 배열 ('C'=0x43, 'T'=0x54, 'F'=0x46)
        //
        // smali의 decodeBypass() 메서드에서 XOR 연산 확인:
        //   xor-int/2addr v_byte, v_key
        //
        // 수동 디코딩: ENCODED_BYPASS[i] XOR XOR_KEY[i % 3]
        // 결과: "BV-BYPASS-KEY-2024"
        //
        // 취약점: XOR는 가역 연산 → 키만 알면 즉시 복호화 가능
        private val ENCODED_BYPASS = byteArrayOf(
            0x01, 0x02, 0x6B, 0x01, 0x0D, 0x16, 0x02, 0x07, 0x15,
            0x6E, 0x1F, 0x03, 0x1A, 0x79, 0x74, 0x73, 0x66, 0x72
        )
        private val XOR_KEY = byteArrayOf(
            'C'.code.toByte(), 'T'.code.toByte(), 'F'.code.toByte()
        )
    }

    // 로그인 뷰
    private lateinit var layoutLogin: LinearLayout
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvLoginHint: TextView
    private lateinit var tvLoginStatus: TextView

    // 알림 발송 뷰
    private lateinit var layoutNotify: LinearLayout
    private lateinit var tvAdminWelcome: TextView
    private lateinit var etNotifyTitle: EditText
    private lateinit var etNotifyBody: EditText
    private lateinit var etNotifyUrl: EditText
    private lateinit var btnSend: Button
    private lateinit var btnOpenWebAdmin: Button
    private lateinit var btnLogout: Button
    private lateinit var tvSendResult: TextView
    private lateinit var progressSend: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "BookVillage 관리자"
        buildLayout()

        // ── Path C: XOR 디코딩 bypass 토큰 비교 ────────────────────────
        // smali에서 decodeBypass() 역추적 후 딥링크에 토큰 전달:
        //   bookvillage://admin-panel?token=BV-BYPASS-KEY-2024
        //
        // 취약점: equals() 로 단순 문자열 비교 → 복호화 값 알면 바로 통과
        val deepLinkToken = intent.data?.getQueryParameter("token") ?: ""
        if (deepLinkToken.isNotEmpty()) {
            if (deepLinkToken == decodeBypass()) {
                Log.w(TAG, "Path C solved: XOR bypass token matched")
                val serverToken = "debug-${decodeBypass()}"
                saveAdminSession(serverToken, "XOR Admin")
                showNotifyPanel("XOR Admin", serverToken)
                return
            } else {
                Log.d(TAG, "Bypass token mismatch: provided=$deepLinkToken")
            }
        }

        // ── Bypass D: Intent Extra 주입 (취약한 형식 검증) ─────────────
        // jadx로 isWeakTokenValid() 메서드 발견:
        //   token.split("-")[3] == "admin" 이면 통과 (길이 검사만)
        //
        // 공격 (재패키징 없이):
        //   adb shell am start \
        //     -n com.bookvillage.mock/.AdminPanelActivity \
        //     --es admin_token "x-x-x-admin-x-x"
        val intentToken = intent.getStringExtra("admin_token") ?: ""
        if (intentToken.isNotEmpty()) {
            if (isWeakTokenValid(intentToken)) {
                Log.w(TAG, "Bypass D: intent extra token accepted ($intentToken)")
                saveAdminSession(intentToken, "Intent Admin")
                showNotifyPanel("Intent Admin", intentToken)
                return
            } else {
                Log.d(TAG, "Bypass D: intent token format invalid ($intentToken)")
            }
        }

        // ── Bypass E: allowBackup=true → SharedPreferences 변조 ────────
        // 공격 흐름:
        //   1. adb backup -noapk com.bookvillage.mock -f backup.ab
        //   2. dd if=backup.ab bs=24 skip=1 | python3 -c \
        //        "import zlib,sys; open('b.tar','wb').write(zlib.decompress(sys.stdin.buffer.read()))"
        //   3. tar xf b.tar → apps/com.bookvillage.mock/sp/bookvillage_prefs.xml 편집
        //      <boolean name="is_admin" value="true" />
        //   4. tar cf b_patched.tar apps/ && \
        //      python3 -c "import zlib,sys; open('patched.ab','wb').write(b'\x41\x4e\x44\x52\x4f\x49\x44\x42\x41\x43\x4b\x55\x50\x4b\x52\x49\x4d\x04\x00\x00\x00\x01\x00\x00'+zlib.compress(open('b_patched.tar','rb').read()))"
        //   5. adb restore patched.ab → 앱 재시작
        //
        // 취약점: SharedPreferences 무결성 검증 없음 (파일 변조 가능)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean("is_admin", false)) {
            Log.w(TAG, "Bypass E: is_admin=true in SharedPreferences (backup restore attack)")
            val name  = prefs.getString(KEY_ADMIN_NAME, "Backup Admin") ?: "Backup Admin"
            val token = prefs.getString(KEY_ADMIN_TOKEN, "backup-bypass-${System.currentTimeMillis()}")
                ?: "backup-bypass-${System.currentTimeMillis()}"
            showNotifyPanel(name, token)
            return
        }

        // ── 저장된 세션 복원 ──
        val savedToken = prefs.getString(KEY_ADMIN_TOKEN, null)
        if (!savedToken.isNullOrEmpty()) {
            val name = prefs.getString(KEY_ADMIN_NAME, "관리자") ?: "관리자"
            showNotifyPanel(name, savedToken)
            return
        }

        // ── Bypass F: HTTP 평문 통신 → Burp Suite 응답 변조 ────────────
        // 앱이 /api/auth/verify-role 응답을 신뢰 → 중간자 변조 가능
        // (로그인 패널을 먼저 보여주고, 응답 오면 전환)
        showLoginPanel()
        thread { checkRoleFromServer() }
    }

    // ── 로그인 패널 ────────────────────────────────────────────────

    private fun showLoginPanel() {
        layoutLogin.visibility = View.VISIBLE
        layoutNotify.visibility = View.GONE

        btnLogin.setOnClickListener {
            val username = etUsername.text.toString()   // trim() 제거 → SQL Injection 입력 보존
            val password = etPassword.text.toString()
            if (username.isEmpty() || password.isEmpty()) {
                tvLoginStatus.text = "아이디와 비밀번호를 입력하세요."
                return@setOnClickListener
            }
            btnLogin.isEnabled = false
            tvLoginStatus.text = "로그인 중..."
            thread { doLogin(username, password) }
        }
    }

    private fun doLogin(username: String, password: String) {
        try {
            val payload = JSONObject().apply {
                put("username", username)
                put("password", password)
            }.toString()

            val conn = URL("$BACKEND/admin/api/auth/login").openConnection()
                    as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            OutputStreamWriter(conn.outputStream).use { it.write(payload) }

            val code = conn.responseCode
            val body = if (code == 200) conn.inputStream.bufferedReader().readText()
                       else conn.errorStream?.bufferedReader()?.readText() ?: ""

            Log.d(TAG, "Admin login ($code): $body")

            runOnUiThread {
                btnLogin.isEnabled = true
                if (code == 200) {
                    val json   = JSONObject(body)
                    val token  = json.optString("token")
                    val name   = json.optJSONObject("user")?.optString("name") ?: username
                    // 취약점: 관리자 토큰 로그 노출
                    Log.d(TAG, "Admin token acquired: $token")
                    saveAdminSession(token, name)
                    showNotifyPanel(name, token)
                } else {
                    val msg = try { JSONObject(body).optString("message", "로그인 실패") }
                              catch (e: Exception) { "로그인 실패 (HTTP $code)" }
                    tvLoginStatus.text = msg
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            runOnUiThread { btnLogin.isEnabled = true; tvLoginStatus.text = "오류: ${e.message}" }
        }
    }

    // ── 알림 발송 패널 ──────────────────────────────────────────────

    private fun showNotifyPanel(name: String, token: String) {
        layoutLogin.visibility = View.GONE
        layoutNotify.visibility = View.VISIBLE
        tvAdminWelcome.text = "환영합니다, $name 님 (관리자)"

        btnSend.setOnClickListener {
            val title = etNotifyTitle.text.toString().trim()
            val body  = etNotifyBody.text.toString().trim()
            val url   = etNotifyUrl.text.toString().trim()
            if (body.isEmpty()) { tvSendResult.text = "알림 내용을 입력하세요."; return@setOnClickListener }
            btnSend.isEnabled = false
            progressSend.visibility = View.VISIBLE
            tvSendResult.text = ""
            thread { doSendNotification(token, title, body, url) }
        }

        // AdminGateActivity에서 받아온 실제 SESSION_TOKEN으로 WebView 이동
        // 쿠키는 이미 CookieManager에 심겨 있으므로 URL 이동만 하면 됨
        btnOpenWebAdmin.setOnClickListener {
            val webAdminIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("fcm_url", "$BACKEND/admin")
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(webAdminIntent)
        }

        btnLogout.setOnClickListener {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove(KEY_ADMIN_TOKEN).remove(KEY_ADMIN_NAME).apply()
            showLoginPanel()
        }
    }

    private fun doSendNotification(token: String, title: String, body: String, url: String) {
        try {
            val payload = JSONObject().apply {
                put("title", title); put("body", body)
                if (url.isNotEmpty()) put("url", url)
            }.toString()

            val conn = URL("$BACKEND/admin/api/notifications/send").openConnection()
                    as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Admin-Token", token)
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            OutputStreamWriter(conn.outputStream).use { it.write(payload) }

            val code     = conn.responseCode
            val response = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                           else conn.errorStream?.bufferedReader()?.readText() ?: ""
            Log.d(TAG, "FCM send ($code): $response")
            val json   = JSONObject(response)
            val result = if (code in 200..299 && json.optBoolean("success"))
                             "발송 완료: ${json.optString("message")}"
                         else "실패 ($code): ${json.optString("message", response)}"

            runOnUiThread {
                btnSend.isEnabled = true
                progressSend.visibility = View.GONE
                tvSendResult.text = result
            }
        } catch (e: Exception) {
            Log.e(TAG, "FCM send error", e)
            runOnUiThread {
                btnSend.isEnabled = true
                progressSend.visibility = View.GONE
                tvSendResult.text = "오류: ${e.message}"
            }
        }
    }

    // ── Bypass D: 취약한 토큰 형식 검증 ─────────────────────────────
    // jadx 정적 분석으로 발견:
    //   parts[3] == "admin" 이면 통과 → "x-x-x-admin-x-x" 로 우회
    //
    // smali:
    //   invoke-virtual {v_token}, Ljava/lang/String;->split(Ljava/lang/String;)[Ljava/lang/String;
    //   const/16 v1, 0x3                 ; index 3
    //   aget-object v2, v_parts, v1
    //   const-string v3, "admin"
    //   invoke-virtual {v2, v3}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    private fun isWeakTokenValid(token: String): Boolean {
        val parts = token.split("-")
        return parts.size >= 6 && parts[3] == "admin"
    }

    // ── Bypass F: HTTP 평문 통신 → Burp Suite 응답 변조 ──────────────
    // 취약점:
    //   1. android:usesCleartextTraffic="true" → HTTP 평문 통신
    //   2. 응답 JSON에 서명 없음 → MITM으로 role 값 변조 가능
    //
    // 공격:
    //   Android 프록시 → Burp Suite 127.0.0.1:8080
    //   GET /api/auth/verify-role 인터셉트
    //   응답 {"role":"USER"} → {"role":"ADMIN"} 수정 → Forward
    private fun checkRoleFromServer() {
        try {
            val conn = URL("$BACKEND/api/auth/verify-role").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val role = JSONObject(body).optString("role", "USER")
                Log.d(TAG, "verify-role response: role=$role")
                // 취약점: 서버 응답을 그대로 신뢰 → Burp로 변조 시 관리자 진입
                if ("ADMIN".equals(role, ignoreCase = true)) {
                    Log.w(TAG, "Bypass F: role=ADMIN from verify-role (Burp manipulation?)")
                    runOnUiThread {
                        saveAdminSession("verify-role-bypass-${System.currentTimeMillis()}", "Burp Admin")
                        showNotifyPanel("Burp Admin", "verify-role-bypass")
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "verify-role skipped: ${e.message}")
        }
    }

    // ── Path C: XOR 디코딩 ──────────────────────────────────────────
    // smali에서 이 메서드를 찾으면 인코딩 방식이 노출됨:
    //   new-array v0, v2, [B          ; ByteArray 생성
    //   aget-byte v3, v_encoded, v_i  ; ENCODED_BYPASS[i]
    //   aget-byte v4, v_key, v_mod    ; XOR_KEY[i % 3]
    //   xor-int/2addr v3, v4          ; XOR 연산
    //   aput-byte v3, v0, v_i         ; 결과 저장
    private fun decodeBypass(): String {
        return String(ByteArray(ENCODED_BYPASS.size) { i ->
            (ENCODED_BYPASS[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        })
    }

    // ── Path B: 토큰 위조 헬퍼 (디컴파일 시 노출 - CTF 힌트) ─────────
    // jadx로 이 메서드 발견 시 위조 방법 파악 가능
    @Suppress("unused")
    private fun forgeAdminToken(userId: Int = 1): String {
        val payloadJson = """{"role":"ADMIN","id":$userId}"""
        val payloadB64  = Base64.encodeToString(payloadJson.toByteArray(), Base64.NO_WRAP)
        val sig         = sha1(payloadB64 + JWT_SECRET)
        return "$payloadB64.$sig"
    }

    private fun sha1(input: String): String {
        val md   = MessageDigest.getInstance("SHA-1")
        val hash = md.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun saveAdminSession(token: String, name: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_ADMIN_TOKEN, token)
            .putString(KEY_ADMIN_NAME, name)
            .apply()
    }


    // ── 레이아웃 ──────────────────────────────────────────────────

    private fun buildLayout() {
        val root      = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 64, 48, 48)
        }
        root.addView(container)

        // 로그인 패널
        layoutLogin = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tvLoginTitle = TextView(this).apply { text = "관리자 로그인"; textSize = 22f; setPadding(0,0,0,8) }
        // 취약점 힌트: 디컴파일로 발견 가능 (주석은 jadx 결과에도 남음)
        tvLoginHint = TextView(this).apply {
            text = "※ SQL Injection 또는 JWT 토큰 위조로 우회 가능"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 24)
        }
        etUsername = EditText(this).apply { hint = "아이디 (예: admin' --)" }
        etPassword = EditText(this).apply {
            hint = "비밀번호"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        btnLogin = Button(this).apply {
            text = "로그인"; setBackgroundColor(0xFF1565C0.toInt()); setTextColor(0xFFFFFFFF.toInt())
        }
        tvLoginStatus = TextView(this).apply { setTextColor(0xFFC62828.toInt()) }
        layoutLogin.apply {
            addView(tvLoginTitle); addView(tvLoginHint)
            addView(etUsername); addView(etPassword)
            addView(btnLogin); addView(tvLoginStatus)
        }

        // 알림 발송 패널
        layoutNotify = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvAdminWelcome = TextView(this).apply { textSize = 16f; setPadding(0,0,0,24) }
        val tvSendTitle = TextView(this).apply { text = "전체 사용자 FCM 알림 발송"; textSize = 18f; setPadding(0,0,0,16) }
        etNotifyTitle = EditText(this).apply { hint = "알림 제목"; setText("BookVillage 공지") }
        etNotifyBody  = EditText(this).apply { hint = "알림 내용"; minLines = 3 }
        etNotifyUrl   = EditText(this).apply { hint = "클릭 시 이동할 URL (선택)" }
        btnSend = Button(this).apply {
            text = "전체 사용자에게 FCM 발송"
            setBackgroundColor(0xFFC62828.toInt()); setTextColor(0xFFFFFFFF.toInt())
        }
        progressSend = ProgressBar(this).apply { visibility = View.GONE }
        tvSendResult = TextView(this).apply { setPadding(0, 8, 0, 0) }
        btnOpenWebAdmin = Button(this).apply {
            text = "웹 관리자 패널 열기"
            setBackgroundColor(0xFF2E7D32.toInt()); setTextColor(0xFFFFFFFF.toInt())
        }
        btnLogout = Button(this).apply {
            text = "로그아웃"; setBackgroundColor(0xFF757575.toInt()); setTextColor(0xFFFFFFFF.toInt())
        }
        layoutNotify.apply {
            addView(tvAdminWelcome); addView(tvSendTitle)
            addView(etNotifyTitle); addView(etNotifyBody); addView(etNotifyUrl)
            addView(btnSend); addView(progressSend); addView(tvSendResult)
            addView(btnOpenWebAdmin); addView(btnLogout)
        }

        container.addView(layoutLogin)
        container.addView(layoutNotify)
        setContentView(root)
    }
}
