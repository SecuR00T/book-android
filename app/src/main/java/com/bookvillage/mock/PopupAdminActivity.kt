package com.bookvillage.mock

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class PopupAdminActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOKEN = "bypass_token"
        private const val BASE_URL =
            "http://book-village-alb-1548050843.ap-northeast-2.elb.amazonaws.com"
    }

    private lateinit var bypassToken: String
    private lateinit var listContainer: LinearLayout
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        bypassToken = intent.getStringExtra(EXTRA_TOKEN) ?: ""

        /* ── 루트 레이아웃 ── */
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0F0F0"))
        }

        /* ── 헤더 ── */
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#2F355F"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "북촌 관리자 — 팝업 관리"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(this).apply {
            text = "+ 새 팝업"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { showForm(null) }
        })

        /* ── 목록 스크롤 ── */
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        scroll.addView(listContainer)

        root.addView(header)
        root.addView(scroll)
        setContentView(root)

        loadPopups()
    }

    /* ────────────────────────────── 목록 로드 ────────────────────────────── */

    private fun loadPopups() {
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { apiGet("/admin/api/popups") }
                listContainer.removeAllViews()
                val arr = JSONArray(json)
                if (arr.length() == 0) {
                    listContainer.addView(TextView(this@PopupAdminActivity).apply {
                        text = "등록된 팝업이 없습니다."
                        gravity = Gravity.CENTER
                        setPadding(0, dp(40), 0, 0)
                        setTextColor(Color.GRAY)
                    })
                } else {
                    for (i in 0 until arr.length()) addPopupCard(arr.getJSONObject(i))
                }
            } catch (e: Exception) {
                toast("로드 실패: ${e.message}")
            }
        }
    }

    /* ────────────────────────────── 카드 뷰 ────────────────────────────── */

    private fun addPopupCard(popup: JSONObject) {
        val id      = popup.optLong("id")
        val title   = popup.optString("title")
        val active  = popup.optBoolean("isActive")
        val linkUrl = popup.optString("linkUrl", "-")
        val device  = popup.optString("deviceType", "all")

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(8))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
            layoutParams = lp
        }

        /* 제목 + 상태 배지 */
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row1.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row1.addView(TextView(this).apply {
            text = if (active) "활성" else "비활성"
            setTextColor(Color.WHITE)
            setBackgroundColor(if (active) Color.parseColor("#4CAF50") else Color.parseColor("#9E9E9E"))
            setPadding(dp(7), dp(2), dp(7), dp(2))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        /* 링크 + 디바이스 */
        val infoTv = TextView(this).apply {
            text = "링크: $linkUrl\n대상: $device"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, dp(4), 0, dp(4))
        }

        /* 버튼 행 */
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row2.addView(Button(this).apply {
            text = "수정"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { showForm(popup) }
        })
        row2.addView(Button(this).apply {
            text = "삭제"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#F44336"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.leftMargin = dp(8) }
            setOnClickListener {
                AlertDialog.Builder(this@PopupAdminActivity)
                    .setTitle("팝업 삭제")
                    .setMessage("'$title' 팝업을 삭제하시겠습니까?")
                    .setPositiveButton("삭제") { _, _ -> deletePopup(id) }
                    .setNegativeButton("취소", null)
                    .show()
            }
        })

        card.addView(row1)
        card.addView(infoTv)
        card.addView(row2)
        listContainer.addView(card)
    }

    /* ────────────────────────────── 등록/수정 폼 ────────────────────────────── */

    private fun showForm(existing: JSONObject?) {
        val isEdit = existing != null
        val today     = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weekLater = LocalDate.now().plusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        fun et(hint: String, value: String = ""): EditText = EditText(this).apply {
            this.hint = hint
            setText(value)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(6) }
        }

        val etTitle   = et("제목 *", existing?.optString("title") ?: "")
        val etContent = et("내용", existing?.optString("content") ?: "")
        val etLink    = et("링크 URL (APK 경로 등)", existing?.optString("linkUrl") ?: "")
        val etStart   = et("시작일 (YYYY-MM-DD) *", existing?.optString("startDate") ?: today)
        val etEnd     = et("종료일 (YYYY-MM-DD) *", existing?.optString("endDate") ?: weekLater)

        val cbActive = CheckBox(this).apply {
            text = "활성화"
            isChecked = existing?.optBoolean("isActive") ?: true
        }

        val deviceKeys   = arrayOf("all", "mobile", "desktop")
        val deviceLabels = arrayOf("전체", "모바일", "PC")
        var selectedDevice = deviceKeys.indexOfFirst { it == (existing?.optString("deviceType") ?: "mobile") }.let { if (it < 0) 1 else it }
        val spDevice = Spinner(this).apply {
            adapter = ArrayAdapter(this@PopupAdminActivity, android.R.layout.simple_spinner_dropdown_item, deviceLabels)
            setSelection(selectedDevice)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selectedDevice = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        layout.addView(etTitle)
        layout.addView(etContent)
        layout.addView(etLink)
        layout.addView(etStart)
        layout.addView(etEnd)
        layout.addView(cbActive)
        layout.addView(spDevice)

        AlertDialog.Builder(this)
            .setTitle(if (isEdit) "팝업 수정" else "새 팝업 등록")
            .setView(layout)
            .setPositiveButton(if (isEdit) "수정" else "등록") { _, _ ->
                val body = JSONObject().apply {
                    put("title",      etTitle.text.toString())
                    put("content",    etContent.text.toString())
                    put("linkUrl",    etLink.text.toString())
                    put("startDate",  etStart.text.toString())
                    put("endDate",    etEnd.text.toString())
                    put("isActive",   cbActive.isChecked)
                    put("deviceType", deviceKeys[selectedDevice])
                }
                if (isEdit) updatePopup(existing!!.getLong("id"), body)
                else createPopup(body)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /* ────────────────────────────── CRUD ────────────────────────────── */

    private fun createPopup(body: JSONObject) = scope.launch {
        try {
            withContext(Dispatchers.IO) { apiPost("/admin/api/popups", body.toString()) }
            toast("팝업이 등록되었습니다.")
            loadPopups()
        } catch (e: Exception) { toast("등록 실패: ${e.message}") }
    }

    private fun updatePopup(id: Long, body: JSONObject) = scope.launch {
        try {
            withContext(Dispatchers.IO) { apiPut("/admin/api/popups/$id", body.toString()) }
            toast("팝업이 수정되었습니다.")
            loadPopups()
        } catch (e: Exception) { toast("수정 실패: ${e.message}") }
    }

    private fun deletePopup(id: Long) = scope.launch {
        try {
            withContext(Dispatchers.IO) { apiDelete("/admin/api/popups/$id") }
            toast("팝업이 삭제되었습니다.")
            loadPopups()
        } catch (e: Exception) { toast("삭제 실패: ${e.message}") }
    }

    /* ────────────────────────────── HTTP ────────────────────────────── */

    private fun apiGet(path: String): String {
        val conn = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $bypassToken")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        return conn.inputStream.bufferedReader().readText()
    }

    private fun apiPost(path: String, json: String): String {
        val conn = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $bypassToken")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(json) }
        return conn.inputStream.bufferedReader().readText()
    }

    private fun apiPut(path: String, json: String): String {
        val conn = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.setRequestProperty("Authorization", "Bearer $bypassToken")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(json) }
        return conn.inputStream.bufferedReader().readText()
    }

    private fun apiDelete(path: String) {
        val conn = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.setRequestProperty("Authorization", "Bearer $bypassToken")
        conn.responseCode
    }

    /* ────────────────────────────── 유틸 ────────────────────────────── */

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
