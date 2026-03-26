package com.bookvillage.mock

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

        /* ── 웹 관리자 페이지 컬러 팔레트 ── */
        private const val NAVY         = "#2F355F"
        private const val NAVY_DARK    = "#23284B"
        private const val TBL_HDR_BG   = "#F8F7F4"
        private const val TBL_HDR_TEXT  = "#5C4A32"
        private const val ROW_HOVER    = "#FAF9F7"
        private const val BADGE_GRAY   = "#E0E0E0"
        private const val TEXT_MUTED   = "#9E9E9E"
        private const val TEXT_PRIMARY  = "#212121"
        private const val TEXT_SUB     = "#757575"
        private const val BORDER_COLOR = "#E5E5E5"
        private const val BG_PAGE      = "#F9FAFB"
        private const val RED          = "#EF4444"
    }

    private lateinit var bypassToken: String
    private lateinit var tableBody: LinearLayout
    private lateinit var filterKeyword: EditText
    private lateinit var filterActiveSpinner: Spinner
    private lateinit var filterDeviceSpinner: Spinner
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var allPopups: JSONArray = JSONArray()

    private val deviceKeys   = arrayOf("all", "mobile", "pc")
    private val deviceLabels = arrayOf("전체", "모바일", "PC")
    private val activeKeys   = arrayOf("", "true", "false")
    private val activeLabels = arrayOf("전체", "사용", "미사용")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        bypassToken = intent.getStringExtra(EXTRA_TOKEN) ?: ""

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(BG_PAGE))
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        /* ── 페이지 헤더 (PageHeader 스타일) ── */
        content.addView(buildPageHeader())

        /* ── 필터 카드 ── */
        content.addView(buildFilterCard())

        /* ── 테이블 카드 ── */
        content.addView(buildTableCard())

        root.addView(content)
        setContentView(root)

        loadPopups()
    }

    /* ══════════════════════════════════════════════════════════════
       페이지 헤더
       ══════════════════════════════════════════════════════════════ */
    private fun buildPageHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))

            addView(TextView(this@PopupAdminActivity).apply {
                text = "팝업 관리"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(TEXT_PRIMARY))
            })
            addView(TextView(this@PopupAdminActivity).apply {
                text = "홈페이지 메인화면에 띄울 팝업을 관리하세요."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.parseColor(TEXT_SUB))
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    /* ══════════════════════════════════════════════════════════════
       필터 카드
       ══════════════════════════════════════════════════════════════ */
    private fun buildFilterCard(): LinearLayout {
        val card = makeCard()
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(12) }
        card.layoutParams = lp

        /* 검색어 */
        val searchBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(10) }
        }
        searchBlock.addView(makeLabel("검색어"))
        filterKeyword = EditText(this).apply {
            hint = "팝업 제목 검색"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = InputType.TYPE_CLASS_TEXT
            background = makeInputBg()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP)
        }
        searchBlock.addView(filterKeyword)
        card.addView(searchBlock)

        /* 사용여부 + Device 행 */
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
            background = makeInputBg()
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        activeBlock.addView(filterActiveSpinner)

        val deviceBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }
        deviceBlock.addView(makeLabel("Device"))
        filterDeviceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PopupAdminActivity, android.R.layout.simple_spinner_dropdown_item, deviceLabels)
            background = makeInputBg()
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        deviceBlock.addView(filterDeviceSpinner)

        filterRow.addView(activeBlock)
        filterRow.addView(deviceBlock)
        card.addView(filterRow)

        /* 버튼 행: 검색 + 새 팝업 등록 */
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP)
        }

        btnRow.addView(makeNavyButton("검색").apply {
            setOnClickListener { applyFilter() }
            layoutParams = LinearLayout.LayoutParams(LP_WRAP, dp(38)).also { it.rightMargin = dp(8) }
        })
        btnRow.addView(makeNavyButton("+ 새 팝업 등록").apply {
            setOnClickListener { showForm(null) }
            layoutParams = LinearLayout.LayoutParams(LP_WRAP, dp(38))
        })

        card.addView(btnRow)
        return card
    }

    /* ══════════════════════════════════════════════════════════════
       테이블 카드
       ══════════════════════════════════════════════════════════════ */
    private fun buildTableCard(): LinearLayout {
        val card = makeCard()
        card.setPadding(0, 0, 0, 0)
        card.layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP)

        val hScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP)
            isHorizontalScrollBarEnabled = true
        }

        val tableWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP)
            minimumWidth = dp(780)
        }

        /* 테이블 헤더 */
        tableWrapper.addView(buildTableHeader())

        /* 테이블 바디 */
        tableBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        tableWrapper.addView(tableBody)

        hScroll.addView(tableWrapper)
        card.addView(hScroll)
        return card
    }

    private fun buildTableHeader(): LinearLayout {
        val headers = listOf("순번", "종류", "팝업제목", "시작일", "종료일", "사용여부", "Device", "등록일", "관리")
        val weights = floatArrayOf(0.5f, 0.9f, 1.8f, 1.1f, 1.1f, 0.9f, 0.9f, 1.1f, 1.5f)

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor(TBL_HDR_BG))
            setPadding(0, dp(2), 0, dp(2))

            headers.forEachIndexed { i, h ->
                addView(TextView(this@PopupAdminActivity).apply {
                    text = h
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(TBL_HDR_TEXT))
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, weights[i])
                })
            }
        }
    }

    /* ══════════════════════════════════════════════════════════════
       데이터 로드 & 필터
       ══════════════════════════════════════════════════════════════ */
    private fun loadPopups() {
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { apiGet("/admin/api/popups") }
                allPopups = JSONArray(json)
                applyFilter()
            } catch (e: Exception) {
                toast("로드 실패: ${e.message}")
            }
        }
    }

    private fun applyFilter() {
        val keyword = filterKeyword.text.toString().trim()
        val activeVal = activeKeys[filterActiveSpinner.selectedItemPosition]
        val deviceVal = deviceKeys[filterDeviceSpinner.selectedItemPosition]

        tableBody.removeAllViews()

        val filtered = mutableListOf<JSONObject>()
        for (i in 0 until allPopups.length()) {
            val p = allPopups.getJSONObject(i)
            if (keyword.isNotEmpty() && !p.optString("title").contains(keyword, ignoreCase = true)) continue
            if (activeVal.isNotEmpty() && p.optBoolean("isActive").toString() != activeVal) continue
            if (deviceVal != "all" && p.optString("deviceType", "all") != deviceVal) continue
            filtered.add(p)
        }

        if (filtered.isEmpty()) {
            tableBody.addView(TextView(this).apply {
                text = "등록된 팝업이 없습니다."
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(TEXT_MUTED))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(16), dp(40), dp(16), dp(40))
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP)
            })
        } else {
            filtered.forEachIndexed { idx, popup ->
                addTableRow(popup, filtered.size - idx)
            }
        }
    }

    /* ══════════════════════════════════════════════════════════════
       테이블 행
       ══════════════════════════════════════════════════════════════ */
    private fun addTableRow(popup: JSONObject, rowNum: Int) {
        val id        = popup.optLong("id")
        val title     = popup.optString("title")
        val content   = popup.optString("content", "")
        val startDate = popup.optString("startDate", "-")
        val endDate   = popup.optString("endDate", "-")
        val active    = popup.optBoolean("isActive")
        val device    = popup.optString("deviceType", "all")
        val createdAt = popup.optString("createdAt", "-").let { if (it.length >= 10) it.substring(0, 10) else "-" }
        val isAd      = content.startsWith("[AD]")

        val weights = floatArrayOf(0.5f, 0.9f, 1.8f, 1.1f, 1.1f, 0.9f, 0.9f, 1.1f, 1.5f)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(0, dp(2), 0, dp(2))

            /* 하단 보더 */
            val border = View(this@PopupAdminActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(1))
                setBackgroundColor(Color.parseColor(BORDER_COLOR))
            }

            // 나중에 보더 추가
        }

        /* 순번 */
        row.addView(makeCell(rowNum.toString(), weights[0]).apply {
            setTextColor(Color.parseColor(TEXT_MUTED))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        /* 종류 배지 */
        val typeContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, weights[1])
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        typeContainer.addView(makeBadge(
            text = if (isAd) "광고" else "업데이트",
            bgColor = if (isAd) "#F59E0B" else "#3B82F6",
            textColor = "#FFFFFF"
        ))
        row.addView(typeContainer)

        /* 팝업제목 */
        row.addView(makeCell(title, weights[2]).apply {
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            maxLines = 1
        })

        /* 시작일 */
        row.addView(makeCell(startDate, weights[3]))

        /* 종료일 */
        row.addView(makeCell(endDate, weights[4]))

        /* 사용여부 배지 */
        val badgeContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, weights[5])
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        badgeContainer.addView(makeBadge(
            text = if (active) "사용" else "미사용",
            bgColor = if (active) NAVY else BADGE_GRAY,
            textColor = if (active) "#FFFFFF" else TEXT_SUB
        ))
        row.addView(badgeContainer)

        /* Device 배지 */
        val deviceContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, weights[6])
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        deviceContainer.addView(makeBorderedBadge(device))
        row.addView(deviceContainer)

        /* 등록일 */
        row.addView(makeCell(createdAt, weights[7]).apply {
            setTextColor(Color.parseColor(TEXT_MUTED))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        /* 관리 버튼 */
        val actionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, weights[8])
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

        actionContainer.addView(makeOutlineButton("수정").apply {
            setOnClickListener { showForm(popup) }
            layoutParams = LinearLayout.LayoutParams(LP_WRAP, dp(28)).also { it.rightMargin = dp(4) }
        })
        actionContainer.addView(makeRedButton("삭제").apply {
            setOnClickListener {
                AlertDialog.Builder(this@PopupAdminActivity)
                    .setTitle("팝업 삭제")
                    .setMessage("\"$title\" 팝업을 삭제하시겠습니까?")
                    .setPositiveButton("삭제") { _, _ -> deletePopup(id) }
                    .setNegativeButton("취소", null)
                    .show()
            }
            layoutParams = LinearLayout.LayoutParams(LP_WRAP, dp(28))
        })
        row.addView(actionContainer)

        /* 행 + 보더 래퍼 */
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(View(this@PopupAdminActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, 1)
                setBackgroundColor(Color.parseColor(BORDER_COLOR))
            })
        }

        tableBody.addView(wrapper)
    }

    /* ══════════════════════════════════════════════════════════════
       등록/수정 모달 (웹 디자인 매칭)
       ══════════════════════════════════════════════════════════════ */
    private fun showForm(existing: JSONObject?) {
        val isEdit = existing != null
        val today     = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val monthLater = LocalDate.now().plusMonths(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

        /* 다이얼로그 컨텐츠 */
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        fun labeledInput(label: String, required: Boolean, hint: String, value: String, multiline: Boolean = false): Pair<View, EditText> {
            val block = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(12) }
            }
            block.addView(TextView(this).apply {
                text = if (required) "$label *" else label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(TEXT_PRIMARY))
                setPadding(0, 0, 0, dp(4))
            })
            val et = EditText(this).apply {
                this.hint = hint
                setText(value)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                background = makeInputBg()
                setPadding(dp(12), dp(10), dp(12), dp(10))
                if (multiline) {
                    minLines = 3
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    gravity = Gravity.TOP
                }
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP)
            }
            block.addView(et)
            return block to et
        }

        /* 팝업 종류 스피너 */
        val existingContent = existing?.optString("content", "") ?: ""
        val isAdType = existingContent.startsWith("[AD]")
        val typeLabels = arrayOf("업데이트", "광고")
        var selectedType = if (isAdType) 1 else 0

        val typeBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(12) }
        }
        typeBlock.addView(TextView(this).apply {
            text = "팝업 종류 *"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            setPadding(0, 0, 0, dp(4))
        })
        val spType = Spinner(this).apply {
            adapter = ArrayAdapter(this@PopupAdminActivity, android.R.layout.simple_spinner_dropdown_item, typeLabels)
            setSelection(selectedType)
            background = makeInputBg()
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        typeBlock.addView(spType)
        layout.addView(typeBlock)

        val displayContent = if (isAdType) existingContent.removePrefix("[AD]") else existingContent
        val (titleBlock, etTitle) = labeledInput("제목", true, "팝업 제목을 입력하세요", existing?.optString("title") ?: "")
        val (contentBlock, etContent) = labeledInput("내용", false,
            if (isAdType) "광고 내용을 입력하세요" else "업데이트 설명 또는 앱이름|설명 형식",
            displayContent, multiline = true)
        val (linkBlock, etLink) = labeledInput("링크 URL", false, "https://example.com 또는 APK 다운로드 경로", existing?.optString("linkUrl") ?: "")

        spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedType = pos
                etContent.hint = if (pos == 1) "광고 내용을 입력하세요" else "업데이트 설명 또는 앱이름|설명 형식"
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        layout.addView(titleBlock)
        layout.addView(contentBlock)
        layout.addView(linkBlock)

        /* 시작일 / 종료일 행 */
        val dateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(12) }
        }

        val startBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).also { it.rightMargin = dp(8) }
        }
        startBlock.addView(TextView(this).apply {
            text = "시작일 *"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            setPadding(0, 0, 0, dp(4))
        })
        val etStart = EditText(this).apply {
            hint = "YYYY-MM-DD"
            setText(existing?.optString("startDate") ?: today)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = makeInputBg()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP)
        }
        startBlock.addView(etStart)

        val endBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }
        endBlock.addView(TextView(this).apply {
            text = "종료일 *"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            setPadding(0, 0, 0, dp(4))
        })
        val etEnd = EditText(this).apply {
            hint = "YYYY-MM-DD"
            setText(existing?.optString("endDate") ?: monthLater)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = makeInputBg()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP)
        }
        endBlock.addView(etEnd)

        dateRow.addView(startBlock)
        dateRow.addView(endBlock)
        layout.addView(dateRow)

        /* Device 타입 + 사용여부 행 */
        val optRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).also { it.bottomMargin = dp(12) }
        }

        val devBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).also { it.rightMargin = dp(8) }
        }
        devBlock.addView(TextView(this).apply {
            text = "Device 타입"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            setPadding(0, 0, 0, dp(4))
        })
        val formDeviceLabels = arrayOf("전체", "모바일", "PC")
        val formDeviceKeys   = arrayOf("all", "mobile", "pc")
        var selectedDevice = formDeviceKeys.indexOfFirst { it == (existing?.optString("deviceType") ?: "all") }.let { if (it < 0) 0 else it }
        val spDevice = Spinner(this).apply {
            adapter = ArrayAdapter(this@PopupAdminActivity, android.R.layout.simple_spinner_dropdown_item, formDeviceLabels)
            setSelection(selectedDevice)
            background = makeInputBg()
            setPadding(dp(10), dp(6), dp(10), dp(6))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selectedDevice = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        devBlock.addView(spDevice)

        val activeBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }
        activeBlock.addView(TextView(this).apply {
            text = "사용여부"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            setPadding(0, 0, 0, dp(4))
        })
        val cbActive = CheckBox(this).apply {
            text = if (existing?.optBoolean("isActive") != false) "사용" else "미사용"
            isChecked = existing?.optBoolean("isActive") ?: true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (isChecked) Color.parseColor(NAVY) else Color.parseColor(TEXT_MUTED))
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(NAVY))
            setOnCheckedChangeListener { _, checked ->
                text = if (checked) "사용" else "미사용"
                setTextColor(if (checked) Color.parseColor(NAVY) else Color.parseColor(TEXT_MUTED))
            }
        }
        activeBlock.addView(cbActive)

        optRow.addView(devBlock)
        optRow.addView(activeBlock)
        layout.addView(optRow)

        scroll.addView(layout)

        /* 다이얼로그 빌드 */
        val dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .create()

        /* 커스텀 헤더 + 하단 버튼 래퍼 */
        val dialogRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        /* 모달 헤더 */
        val modalHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        modalHeader.addView(TextView(this).apply {
            text = if (isEdit) "팝업 수정" else "새 팝업 등록"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(NAVY))
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })
        modalHeader.addView(TextView(this).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.parseColor(TEXT_MUTED))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { dialog.dismiss() }
        })

        /* 헤더 하단 보더 */
        val headerBorder = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, 1)
            setBackgroundColor(Color.parseColor(BORDER_COLOR))
        }

        /* 하단 버튼 행 */
        val footerBorder = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, 1)
            setBackgroundColor(Color.parseColor(BORDER_COLOR))
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        footer.addView(makeOutlineButton("취소").apply {
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(LP_WRAP, dp(36)).also { it.rightMargin = dp(8) }
        })
        footer.addView(makeNavyButton(if (isEdit) "수정 완료" else "등록").apply {
            setOnClickListener {
                val titleText = etTitle.text.toString().trim()
                if (titleText.isEmpty()) {
                    toast("제목을 입력해 주세요.")
                    return@setOnClickListener
                }
                val rawContent = etContent.text.toString()
                val finalContent = if (selectedType == 1) {
                    if (rawContent.startsWith("[AD]")) rawContent else "[AD]$rawContent"
                } else {
                    rawContent.removePrefix("[AD]")
                }
                val body = JSONObject().apply {
                    put("title",      titleText)
                    put("content",    finalContent)
                    put("linkUrl",    etLink.text.toString())
                    put("startDate",  etStart.text.toString())
                    put("endDate",    etEnd.text.toString())
                    put("isActive",   cbActive.isChecked)
                    put("deviceType", formDeviceKeys[selectedDevice])
                }
                if (isEdit) updatePopup(existing!!.getLong("id"), body)
                else createPopup(body)
                dialog.dismiss()
            }
            layoutParams = LinearLayout.LayoutParams(LP_WRAP, dp(36))
        })

        dialogRoot.addView(modalHeader)
        dialogRoot.addView(headerBorder)
        dialogRoot.addView(scroll)
        dialogRoot.addView(footerBorder)
        dialogRoot.addView(footer)

        dialog.setView(dialogRoot)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    /* ══════════════════════════════════════════════════════════════
       CRUD
       ══════════════════════════════════════════════════════════════ */
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

    /* ══════════════════════════════════════════════════════════════
       HTTP
       ══════════════════════════════════════════════════════════════ */
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

    /* ══════════════════════════════════════════════════════════════
       UI 유틸
       ══════════════════════════════════════════════════════════════ */
    private val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val LP_WRAP  = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** 카드 (흰 배경 + 라운드 + 그림자) */
    private fun makeCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(1, Color.parseColor(BORDER_COLOR))
            }
            elevation = dp(2).toFloat()
        }
    }

    /** 라벨 텍스트 */
    private fun makeLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor(TEXT_MUTED))
            setPadding(0, 0, 0, dp(3))
        }
    }

    /** 인풋 배경 (보더 라운드) */
    private fun makeInputBg(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(1, Color.parseColor(BORDER_COLOR))
            cornerRadius = dp(6).toFloat()
        }
    }

    /** 네이비 버튼 */
    private fun makeNavyButton(text: String): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.parseColor(NAVY))
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(14), dp(6), dp(14), dp(6))
            stateListAnimator = null
            elevation = 0f
        }
    }

    /** 아웃라인 버튼 */
    private fun makeOutlineButton(text: String): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(1, Color.parseColor(BORDER_COLOR))
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(8), dp(2), dp(8), dp(2))
            stateListAnimator = null
            elevation = 0f
        }
    }

    /** 빨간 버튼 (삭제) */
    private fun makeRedButton(text: String): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.parseColor(RED))
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(8), dp(2), dp(8), dp(2))
            stateListAnimator = null
            elevation = 0f
        }
    }

    /** 테이블 셀 텍스트 */
    private fun makeCell(text: String, weight: Float): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, weight)
        }
    }

    /** 채워진 배지 (사용/미사용) */
    private fun makeBadge(text: String, bgColor: String, textColor: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(textColor))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
    }

    /** 보더 배지 (Device) */
    private fun makeBorderedBadge(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.parseColor(TEXT_MUTED))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(1, Color.parseColor(BORDER_COLOR))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
