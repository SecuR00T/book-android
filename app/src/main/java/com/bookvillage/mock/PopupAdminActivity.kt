package com.bookvillage.mock

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
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

        private const val NAVY        = "#2F355F"
        private const val AMBER       = "#F59E0B"
        private const val AMBER_BG    = "#FEF3C7"
        private const val AMBER_TEXT  = "#B45309"
        private const val BLUE_BG     = "#DBEAFE"
        private const val BLUE_TEXT   = "#1D4ED8"
        private const val RED         = "#EF4444"
        private const val BORDER      = "#E5E5E5"
        private const val BG_PAGE     = "#F5F5F7"
        private const val TEXT_PRIMARY = "#1A1A2E"
        private const val TEXT_SUB    = "#6B7280"
        private const val TEXT_MUTED  = "#9CA3AF"
        private const val BADGE_GRAY  = "#E5E7EB"
    }

    private lateinit var bypassToken: String
    private lateinit var listContainer: LinearLayout
    private lateinit var formOverlay: FrameLayout
    private lateinit var filterKeyword: EditText
    private lateinit var filterActiveSpinner: Spinner
    private lateinit var filterDeviceSpinner: Spinner
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var allPopups: JSONArray = JSONArray()

    private val deviceKeys   = arrayOf("all", "mobile", "pc")
    private val deviceLabels = arrayOf("전체", "모바일", "PC")
    private val activeKeys   = arrayOf("", "true", "false")
    private val activeLabels = arrayOf("전체", "사용", "미사용")

    /* ══════════════════ onCreate ══════════════════ */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        bypassToken = intent.getStringExtra(EXTRA_TOKEN) ?: ""

        /* root: FrameLayout (리스트 + 폼 오버레이 겹침) */
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor(BG_PAGE))
        }

        /* ── 리스트 레이어 ── */
        val listScroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(LP_MATCH, LP_MATCH)
            isFillViewport = true
        }
        val listContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(80))
        }
        listContent.addView(buildPageHeader())
        listContent.addView(buildFilterCard())
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listContent.addView(listContainer)
        listScroll.addView(listContent)
        root.addView(listScroll)

        /* ── 폼 오버레이 레이어 (초기 GONE) ── */
        formOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(LP_MATCH, LP_MATCH)
            setBackgroundColor(Color.parseColor("#B3000000"))
            visibility = View.GONE
        }
        root.addView(formOverlay)

        setContentView(root)
        loadPopups()
    }

    /* ══════════════════ 페이지 헤더 ══════════════════ */
    private fun buildPageHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(14))
        addView(TextView(this@PopupAdminActivity).apply {
            text = "팝업 관리"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(TEXT_PRIMARY))
        })
        addView(TextView(this@PopupAdminActivity).apply {
            text = "홈페이지 메인화면에 띄울 팝업을 관리하세요."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Color.parseColor(TEXT_SUB))
            setPadding(0, dp(4), 0, 0)
        })
    }

    /* ══════════════════ 필터 카드 ══════════════════ */
    private fun buildFilterCard(): LinearLayout {
        val card = makeCard()
        card.setPadding(dp(14), dp(14), dp(14), dp(14))
        card.layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(14) }

        val searchBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(10) }
        }
        searchBlock.addView(makeLabel("검색어"))
        filterKeyword = makeEditText("팝업 제목 검색")
        searchBlock.addView(filterKeyword)
        card.addView(searchBlock)

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(10) }
        }
        val activeBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).also { it.rightMargin = dp(8) }
        }
        activeBlock.addView(makeLabel("사용여부"))
        filterActiveSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PopupAdminActivity, android.R.layout.simple_spinner_dropdown_item, activeLabels)
            background = makeInputBg(); setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        activeBlock.addView(filterActiveSpinner)

        val deviceBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }
        deviceBlock.addView(makeLabel("Device"))
        filterDeviceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PopupAdminActivity, android.R.layout.simple_spinner_dropdown_item, deviceLabels)
            background = makeInputBg(); setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        deviceBlock.addView(filterDeviceSpinner)
        filterRow.addView(activeBlock); filterRow.addView(deviceBlock)
        card.addView(filterRow)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
        }
        btnRow.addView(makeNavyButton("검색").apply {
            setOnClickListener { applyFilter() }
            layoutParams = LinearLayout.LayoutParams(LP_WRAP, dp(40)).also { it.rightMargin = dp(8) }
        })
        btnRow.addView(makeNavyButton("+ 새 팝업 등록").apply {
            setOnClickListener { showForm(null) }
            layoutParams = LinearLayout.LayoutParams(LP_WRAP, dp(40))
        })
        card.addView(btnRow)
        return card
    }

    /* ══════════════════ 팝업 카드 리스트 ══════════════════ */
    private fun applyFilter() {
        val keyword   = filterKeyword.text.toString().trim()
        val activeVal = activeKeys[filterActiveSpinner.selectedItemPosition]
        val deviceVal = deviceKeys[filterDeviceSpinner.selectedItemPosition]
        listContainer.removeAllViews()

        val filtered = mutableListOf<JSONObject>()
        for (i in 0 until allPopups.length()) {
            val p = allPopups.getJSONObject(i)
            if (keyword.isNotEmpty() && !p.optString("title").contains(keyword, ignoreCase = true)) continue
            if (activeVal.isNotEmpty() && p.optBoolean("isActive").toString() != activeVal) continue
            if (deviceVal != "all" && p.optString("deviceType", "all") != deviceVal) continue
            filtered.add(p)
        }

        if (filtered.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "등록된 팝업이 없습니다."; gravity = Gravity.CENTER
                setTextColor(Color.parseColor(TEXT_MUTED)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(16), dp(48), dp(16), dp(48))
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP)
            })
        } else {
            filtered.forEach { addPopupCard(it) }
        }
    }

    private fun addPopupCard(popup: JSONObject) {
        val id        = popup.optLong("id")
        val title     = popup.optString("title", "-")
        val startDate = popup.optString("startDate", "-")
        val endDate   = popup.optString("endDate", "-")
        val active    = popup.optBoolean("isActive")
        val device    = popup.optString("deviceType", "all")
        val popupType = popup.optString("popupType", "update")
        val isAd      = popupType == "ad"
        val createdAt = popup.optString("createdAt", "-").let { if (it.length >= 10) it.substring(0, 10) else "-" }

        val card = makeCard().apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(10) }
        }

        /* ── 상단: 타입 배지 + 제목 + 사용여부 ── */
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(8) }
        }
        topRow.addView(makeBadge(
            if (isAd) "광고" else "업데이트",
            if (isAd) AMBER_BG else BLUE_BG,
            if (isAd) AMBER_TEXT else BLUE_TEXT
        ).apply { layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP).also { it.rightMargin = dp(8) } })
        topRow.addView(TextView(this).apply {
            text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(TEXT_PRIMARY))
            maxLines = 1; layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })
        topRow.addView(makeBadge(
            if (active) "사용" else "미사용",
            if (active) NAVY else BADGE_GRAY,
            if (active) "#FFFFFF" else TEXT_SUB
        ).apply { layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP).also { it.leftMargin = dp(8) } })
        card.addView(topRow)

        /* ── 중단: 날짜 + 디바이스 ── */
        val midRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(6) }
        }
        midRow.addView(TextView(this).apply {
            text = "📅 $startDate ~ $endDate"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(Color.parseColor(TEXT_SUB))
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })
        midRow.addView(makeBorderedBadge(device))
        card.addView(midRow)

        /* ── 하단: 등록일 + 버튼 ── */
        val botRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        botRow.addView(TextView(this).apply {
            text = "등록: $createdAt"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(Color.parseColor(TEXT_MUTED))
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })
        botRow.addView(makeOutlineButton("수정").apply {
            setOnClickListener { showForm(popup) }
            layoutParams = LinearLayout.LayoutParams(LP_WRAP, dp(34)).also { it.rightMargin = dp(6) }
        })
        botRow.addView(makeRedButton("삭제").apply {
            setOnClickListener {
                AlertDialog.Builder(this@PopupAdminActivity)
                    .setTitle("팝업 삭제").setMessage("\"$title\" 팝업을 삭제하시겠습니까?")
                    .setPositiveButton("삭제") { _, _ -> deletePopup(id) }
                    .setNegativeButton("취소", null).show()
            }
            layoutParams = LinearLayout.LayoutParams(LP_WRAP, dp(34))
        })
        card.addView(botRow)
        listContainer.addView(card)
    }

    /* ══════════════════ 폼 오버레이 ══════════════════ */
    private fun showForm(existing: JSONObject?) {
        val isEdit       = existing != null
        val today        = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val monthLater   = LocalDate.now().plusMonths(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val existingType = existing?.optString("popupType", "update") ?: "update"
        var selectedTypeKey = existingType

        formOverlay.removeAllViews()
        formOverlay.visibility = View.VISIBLE

        /* ── 폼 컨테이너 (하단 시트 스타일) ── */
        val formPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
            }
            layoutParams = FrameLayout.LayoutParams(LP_MATCH, LP_MATCH).also {
                it.topMargin = dp(48)
            }
        }
        formOverlay.addView(formPanel)

        /* ── 헤더 ── */
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(16), dp(16))
        }
        header.addView(TextView(this).apply {
            text = if (isEdit) "팝업 수정" else "새 팝업 등록"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(NAVY))
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })
        val closeBtn = TextView(this).apply {
            text = "✕"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.parseColor(TEXT_MUTED)); setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        header.addView(closeBtn)
        formPanel.addView(header)
        formPanel.addView(makeDivider())

        /* ── 스크롤 가능 폼 내용 ── */
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, 0, 1f)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        /* ─ 섹션1: 기본 정보 ─ */
        layout.addView(makeSectionHeader("기본 정보"))

        /* 팝업 타입 토글 */
        layout.addView(makeFormLabel("팝업 타입 *"))
        val btnUpdate = makeToggleButton("📦 업데이트 공지")
        val btnAd     = makeToggleButton("📢 광고 배너")
        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(14) }
        }
        btnUpdate.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).also { it.rightMargin = dp(8) }
        btnAd.layoutParams     = LinearLayout.LayoutParams(0, dp(48), 1f)
        toggleRow.addView(btnUpdate); toggleRow.addView(btnAd)
        layout.addView(toggleRow)

        /* 제목 */
        layout.addView(makeFormLabel("제목 *"))
        val etTitle = makeFormInput("팝업 제목을 입력하세요", existing?.optString("title") ?: "")
        layout.addView(etTitle)

        /* 내용 */
        layout.addView(makeFormLabel("내용"))
        val etContent = makeFormInput(
            if (existingType == "ad") "광고 내용을 입력하세요"
            else "앱이름|업데이트 설명  예: 북촌|보안 업데이트",
            existing?.optString("content") ?: "", multiline = true
        )
        layout.addView(etContent)

        val hintTv = TextView(this).apply {
            text = "앱이름|설명 형식으로 입력하면 앱카드에 이름이 표시됩니다."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(Color.parseColor(BLUE_TEXT))
            background = GradientDrawable().apply { setColor(Color.parseColor(BLUE_BG)); cornerRadius = dp(8).toFloat() }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(14) }
            visibility = if (existingType == "update") View.VISIBLE else View.GONE
        }
        layout.addView(hintTv)

        /* 타입 토글 스타일 적용 */
        fun applyToggle() {
            if (selectedTypeKey == "update") {
                btnUpdate.setTextColor(Color.WHITE)
                btnUpdate.background = GradientDrawable().apply { setColor(Color.parseColor(NAVY)); cornerRadius = dp(12).toFloat() }
                btnAd.setTextColor(Color.parseColor(TEXT_SUB))
                btnAd.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat(); setStroke(1, Color.parseColor(BORDER)) }
                etContent.hint = "앱이름|업데이트 설명  예: 북촌|보안 업데이트"
                hintTv.visibility = View.VISIBLE
            } else {
                btnAd.setTextColor(Color.WHITE)
                btnAd.background = GradientDrawable().apply { setColor(Color.parseColor(AMBER)); cornerRadius = dp(12).toFloat() }
                btnUpdate.setTextColor(Color.parseColor(TEXT_SUB))
                btnUpdate.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat(); setStroke(1, Color.parseColor(BORDER)) }
                etContent.hint = "광고 내용을 입력하세요"
                hintTv.visibility = View.GONE
            }
        }
        btnUpdate.setOnClickListener { selectedTypeKey = "update"; applyToggle() }
        btnAd.setOnClickListener { selectedTypeKey = "ad"; applyToggle() }
        applyToggle()

        /* ─ 섹션2: 미디어 & 링크 ─ */
        layout.addView(makeSectionHeader("미디어 & 링크"))

        layout.addView(makeFormLabel("이미지 URL"))
        val etImage = makeFormInput("/uploads/popups/image.jpg 또는 https://...", existing?.optString("imageUrl") ?: "")
        layout.addView(etImage)

        layout.addView(makeFormLabel("링크 URL"))
        val etLink = makeFormInput(
            if (existingType == "update") "APK 다운로드 경로" else "https://example.com",
            existing?.optString("linkUrl") ?: ""
        )
        layout.addView(etLink)

        /* ─ 섹션3: 일정 & 설정 ─ */
        layout.addView(makeSectionHeader("일정 & 설정"))

        val dateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(4) }
        }
        val startCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).also { it.rightMargin = dp(8) }
        }
        startCol.addView(makeFormLabel("시작일 *"))
        val etStart = makeFormInput("YYYY-MM-DD", existing?.optString("startDate") ?: today)
        startCol.addView(etStart)

        val endCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }
        endCol.addView(makeFormLabel("종료일 *"))
        val etEnd = makeFormInput("YYYY-MM-DD", existing?.optString("endDate") ?: monthLater)
        endCol.addView(etEnd)
        dateRow.addView(startCol); dateRow.addView(endCol)
        layout.addView(dateRow)

        val optRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(8) }
        }
        val devCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).also { it.rightMargin = dp(8) }
        }
        devCol.addView(makeFormLabel("Device 타입"))
        var selectedDevice = deviceKeys.indexOfFirst { it == (existing?.optString("deviceType") ?: "all") }.let { if (it < 0) 0 else it }
        val spDevice = Spinner(this).apply {
            adapter = ArrayAdapter(this@PopupAdminActivity, android.R.layout.simple_spinner_dropdown_item, deviceLabels)
            setSelection(selectedDevice); background = makeInputBg()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(48))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selectedDevice = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        devCol.addView(spDevice)

        val activeCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }
        activeCol.addView(makeFormLabel("사용여부"))
        val cbActive = CheckBox(this).apply {
            text = if (existing?.optBoolean("isActive") != false) "사용" else "미사용"
            isChecked = existing?.optBoolean("isActive") ?: true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(if (isChecked) Color.parseColor(NAVY) else Color.parseColor(TEXT_MUTED))
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(NAVY))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(48))
            background = makeInputBg()
            setOnCheckedChangeListener { _, checked ->
                text = if (checked) "사용" else "미사용"
                setTextColor(if (checked) Color.parseColor(NAVY) else Color.parseColor(TEXT_MUTED))
            }
        }
        activeCol.addView(cbActive)
        optRow.addView(devCol); optRow.addView(activeCol)
        layout.addView(optRow)

        scroll.addView(layout)
        formPanel.addView(scroll)

        /* ── 하단 버튼 (sticky) ── */
        formPanel.addView(makeDivider())
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
            setBackgroundColor(Color.WHITE)
        }
        val btnCancel = makeOutlineButton("취소").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).also { it.rightMargin = dp(8) }
        }
        val btnSave = makeNavyButton(if (isEdit) "수정 완료" else "등록").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 2f)
        }
        footer.addView(btnCancel); footer.addView(btnSave)
        formPanel.addView(footer)

        /* ── 닫기 액션 ── */
        fun closeForm() {
            formOverlay.visibility = View.GONE
            formOverlay.removeAllViews()
        }
        closeBtn.setOnClickListener { closeForm() }
        btnCancel.setOnClickListener { closeForm() }
        formOverlay.setOnClickListener { /* 배경 탭 무시 */ }

        /* ── 저장 ── */
        btnSave.setOnClickListener {
            val titleText = etTitle.text.toString().trim()
            if (titleText.isEmpty()) { toast("제목을 입력해 주세요."); return@setOnClickListener }
            val startText = etStart.text.toString().trim()
            val endText   = etEnd.text.toString().trim()
            if (startText.isEmpty() || endText.isEmpty()) { toast("날짜를 입력해 주세요."); return@setOnClickListener }
            val body = JSONObject().apply {
                put("title",      titleText)
                put("content",    etContent.text.toString())
                put("linkUrl",    etLink.text.toString())
                put("startDate",  startText)
                put("endDate",    endText)
                put("isActive",   cbActive.isChecked)
                put("deviceType", deviceKeys[selectedDevice])
                put("popupType",  selectedTypeKey)
                put("imageUrl",   etImage.text.toString())
            }
            if (isEdit) updatePopup(existing!!.getLong("id"), body)
            else        createPopup(body)
            closeForm()
        }
    }

    /* ══════════════════ CRUD ══════════════════ */
    private fun loadPopups() {
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { apiGet("/admin/api/popups") }
                allPopups = JSONArray(json)
                applyFilter()
            } catch (e: Exception) { toast("로드 실패: ${e.message}") }
        }
    }
    private fun createPopup(body: JSONObject) = scope.launch {
        try { withContext(Dispatchers.IO) { apiPost("/admin/api/popups", body.toString()) }; toast("팝업이 등록되었습니다."); loadPopups() }
        catch (e: Exception) { toast("등록 실패: ${e.message}") }
    }
    private fun updatePopup(id: Long, body: JSONObject) = scope.launch {
        try { withContext(Dispatchers.IO) { apiPut("/admin/api/popups/$id", body.toString()) }; toast("팝업이 수정되었습니다."); loadPopups() }
        catch (e: Exception) { toast("수정 실패: ${e.message}") }
    }
    private fun deletePopup(id: Long) = scope.launch {
        try { withContext(Dispatchers.IO) { apiDelete("/admin/api/popups/$id") }; toast("팝업이 삭제되었습니다."); loadPopups() }
        catch (e: Exception) { toast("삭제 실패: ${e.message}") }
    }

    /* ══════════════════ HTTP ══════════════════ */
    private fun apiGet(path: String): String {
        val conn = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $bypassToken")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        return conn.inputStream.bufferedReader().readText()
    }
    private fun apiPost(path: String, json: String): String {
        val conn = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $bypassToken")
        conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(json) }
        return conn.inputStream.bufferedReader().readText()
    }
    private fun apiPut(path: String, json: String): String {
        val conn = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.setRequestProperty("Authorization", "Bearer $bypassToken")
        conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(json) }
        return conn.inputStream.bufferedReader().readText()
    }
    private fun apiDelete(path: String) {
        val conn = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.setRequestProperty("Authorization", "Bearer $bypassToken")
        conn.responseCode
    }

    /* ══════════════════ UI 유틸 ══════════════════ */
    private val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val LP_WRAP  = LinearLayout.LayoutParams.WRAP_CONTENT
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun makeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(14).toFloat()
            setStroke(1, Color.parseColor(BORDER))
        }
        elevation = dp(2).toFloat()
    }
    private fun makeLabel(text: String): TextView = TextView(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(Color.parseColor(TEXT_MUTED)); setPadding(0, 0, 0, dp(4))
    }
    private fun makeInputBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.WHITE); setStroke(1, Color.parseColor(BORDER)); cornerRadius = dp(10).toFloat()
    }
    private fun makeDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LP_MATCH, 1); setBackgroundColor(Color.parseColor(BORDER))
    }
    private fun makeFormLabel(text: String): TextView = TextView(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(TEXT_PRIMARY))
        setPadding(0, dp(4), 0, dp(6))
    }
    private fun makeFormInput(hint: String, value: String = "", multiline: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint; setText(value)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); background = makeInputBg()
        setPadding(dp(14), dp(14), dp(14), dp(14))
        if (multiline) { minLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; gravity = Gravity.TOP }
        layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(12) }
    }
    private fun makeEditText(hint: String): EditText = EditText(this).apply {
        this.hint = hint; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        inputType = InputType.TYPE_CLASS_TEXT; background = makeInputBg()
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP)
    }
    private fun makeSectionHeader(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(12))
        layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.topMargin = dp(4) }
        addView(View(this@PopupAdminActivity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f); setBackgroundColor(Color.parseColor(BORDER))
        })
        addView(TextView(this@PopupAdminActivity).apply {
            text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(TEXT_MUTED))
            setPadding(dp(10), 0, dp(10), 0)
        })
        addView(View(this@PopupAdminActivity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f); setBackgroundColor(Color.parseColor(BORDER))
        })
    }
    private fun makeToggleButton(text: String): Button = Button(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(null, Typeface.BOLD); isAllCaps = false
        stateListAnimator = null; elevation = 0f
    }
    private fun makeNavyButton(text: String): Button = Button(this).apply {
        this.text = text; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(null, Typeface.BOLD); isAllCaps = false
        background = GradientDrawable().apply { setColor(Color.parseColor(NAVY)); cornerRadius = dp(10).toFloat() }
        setPadding(dp(16), dp(8), dp(16), dp(8)); stateListAnimator = null; elevation = 0f
    }
    private fun makeOutlineButton(text: String): Button = Button(this).apply {
        this.text = text; setTextColor(Color.parseColor(TEXT_PRIMARY)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        isAllCaps = false
        background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(1, Color.parseColor(BORDER)); cornerRadius = dp(10).toFloat() }
        setPadding(dp(12), dp(8), dp(12), dp(8)); stateListAnimator = null; elevation = 0f
    }
    private fun makeRedButton(text: String): Button = Button(this).apply {
        this.text = text; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); isAllCaps = false
        background = GradientDrawable().apply { setColor(Color.parseColor(RED)); cornerRadius = dp(8).toFloat() }
        setPadding(dp(12), dp(4), dp(12), dp(4)); stateListAnimator = null; elevation = 0f
    }
    private fun makeBadge(text: String, bgColor: String, textColor: String): TextView = TextView(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(textColor))
        background = GradientDrawable().apply { setColor(Color.parseColor(bgColor)); cornerRadius = dp(12).toFloat() }
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }
    private fun makeBorderedBadge(text: String): TextView = TextView(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(Color.parseColor(TEXT_MUTED))
        background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(1, Color.parseColor(BORDER)); cornerRadius = dp(12).toFloat() }
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    override fun onBackPressed() {
        if (formOverlay.visibility == View.VISIBLE) {
            formOverlay.visibility = View.GONE
            formOverlay.removeAllViews()
        } else {
            super.onBackPressed()
        }
    }
    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
