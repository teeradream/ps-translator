package com.pstranslator.tv

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TvMainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("ps_translator_tv", Context.MODE_PRIVATE) }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var discoveryJob: Job? = null

    private lateinit var tvHdmiView: TvView
    private lateinit var overlayWebView: WebView
    private lateinit var controlPanel: LinearLayout
    private lateinit var statusDot: View
    private lateinit var tvStatusText: TextView
    private lateinit var btnFloatingOverlay: Button
    private lateinit var btnSelectHdmi: Button
    private lateinit var btnManualIp: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnCheckUpdate: Button

    private var connectedPhone: PhoneServerInfo? = null
    private var hdmiInputs: List<TvInputInfo> = emptyList()
    private var selectedHdmiIndex: Int = -1

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_tv_main)

        tvHdmiView = findViewById(R.id.tvHdmiView)
        overlayWebView = findViewById(R.id.overlayWebView)
        controlPanel = findViewById(R.id.controlPanel)
        statusDot = findViewById(R.id.statusDot)
        tvStatusText = findViewById(R.id.tvStatusText)
        btnFloatingOverlay = findViewById(R.id.btnFloatingOverlay)
        btnSelectHdmi = findViewById(R.id.btnSelectHdmi)
        btnManualIp = findViewById(R.id.btnManualIp)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)

        setupWebView()
        loadHdmiInputs()
        setupButtons()
        updateFloatingButtonUI()

        btnFloatingOverlay.requestFocus()
        startAutoDiscoveryLoop()
        checkForTvUpdate(silentIfUpToDate = true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        overlayWebView.setBackgroundColor(Color.TRANSPARENT)
        overlayWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        overlayWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        overlayWebView.webViewClient = WebViewClient()
    }

    private fun loadHdmiInputs() {
        try {
            val tim = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            val allInputs = tim?.tvInputList ?: emptyList()
            hdmiInputs = allInputs.filter {
                it.type == TvInputInfo.TYPE_HDMI || it.isPassthroughInput
            }
            if (hdmiInputs.isEmpty()) {
                btnSelectHdmi.text = "📺 ช่อง HDMI: ใช้โหมดลอยทับ"
            } else {
                val savedId = prefs.getString("hdmi_input_id", null)
                val idx = hdmiInputs.indexOfFirst { it.id == savedId }
                if (idx >= 0) {
                    tuneHdmi(idx)
                } else {
                    btnSelectHdmi.text = "📺 เลือกช่อง HDMI (${hdmiInputs.size})"
                }
            }
        } catch (_: Exception) {
            hdmiInputs = emptyList()
            btnSelectHdmi.text = "📺 ช่อง HDMI: ใช้โหมดลอยทับ"
        }
    }

    private fun tuneHdmi(index: Int) {
        if (index !in hdmiInputs.indices) return
        val info = hdmiInputs[index]
        selectedHdmiIndex = index
        prefs.edit().putString("hdmi_input_id", info.id).apply()
        val label = info.loadLabel(this)?.toString()?.takeIf { it.isNotBlank() } ?: "HDMI ${index + 1}"
        btnSelectHdmi.text = "📺 $label"
        try {
            val uri = TvContract.buildChannelUriForPassthroughInput(info.id)
            tvHdmiView.visibility = View.VISIBLE
            tvHdmiView.tune(info.id, uri)
        } catch (e: Exception) {
            Toast.makeText(this, "ทีวีรุ่นนี้ไม่รองรับเปิด HDMI ในแอป — แนะนำใช้ปุ่ม 'ลอยทับ HDMI' แทน", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupButtons() {
        btnFloatingOverlay.setOnClickListener {
            toggleFloatingOverlay()
        }

        btnSelectHdmi.setOnClickListener {
            if (hdmiInputs.isEmpty()) {
                Toast.makeText(
                    this,
                    "ไม่พบพอร์ต HDMI ในระบบ (เช่น Android TV Box) — กด '✨ ลอยทับ HDMI' แล้วสลับช่องรีโมตได้เลย",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            val labels = hdmiInputs.mapIndexed { idx, info ->
                info.loadLabel(this)?.toString()?.takeIf { it.isNotBlank() } ?: "HDMI ${idx + 1}"
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("เลือกช่อง HDMI ของ PS5")
                .setItems(labels) { _, which ->
                    tuneHdmi(which)
                }
                .setNegativeButton("ยกเลิก", null)
                .show()
        }

        btnManualIp.setOnClickListener {
            showManualIpDialog()
        }

        btnDisconnect.setOnClickListener {
            disconnectPhone()
        }

        btnCheckUpdate.setOnClickListener {
            checkForTvUpdate(silentIfUpToDate = false)
        }
    }

    private fun checkForTvUpdate(silentIfUpToDate: Boolean) {
        uiScope.launch {
            if (!silentIfUpToDate) {
                Toast.makeText(this@TvMainActivity, "กำลังตรวจสอบเวอร์ชันล่าสุด...", Toast.LENGTH_SHORT).show()
            }
            val info = TvAppUpdater.fetchLatestTvReleaseInfo()
            if (isFinishing || isDestroyed) return@launch
            if (info == null) {
                if (!silentIfUpToDate) {
                    Toast.makeText(this@TvMainActivity, "ตรวจสอบอัปเดตไม่สำเร็จ กรุณาเช็กอินเทอร์เน็ต", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            if (TvAppUpdater.isRemoteVersionNewer(info.tagName, BuildConfig.VERSION_NAME)) {
                AlertDialog.Builder(this@TvMainActivity)
                    .setTitle("พบอัปเดต PS Translator TV (${info.tagName})")
                    .setMessage("เวอร์ชันปัจจุบัน: v${BuildConfig.VERSION_NAME}\nเวอร์ชันใหม่: ${info.tagName}\n\nต้องการดาวน์โหลดและติดตั้งอัปเดตบนทีวีทันทีหรือไม่?")
                    .setPositiveButton("อัปเดตทันที") { _, _ ->
                        startTvApkUpdate(info.apkUrl)
                    }
                    .setNegativeButton("ไว้ทีหลัง", null)
                    .show()
            } else if (!silentIfUpToDate) {
                Toast.makeText(this@TvMainActivity, "แอปทีวีเป็นเวอร์ชันล่าสุดแล้ว (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startTvApkUpdate(apkUrl: String) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("กำลังดาวน์โหลดอัปเดต...")
            .setMessage("กำลังดาวน์โหลด 0%")
            .setCancelable(false)
            .show()
        uiScope.launch {
            val ok = TvAppUpdater.downloadAndInstallTvApk(this@TvMainActivity, apkUrl) { pct ->
                if (progressDialog.isShowing) {
                    progressDialog.setMessage("กำลังดาวน์โหลด $pct% ...")
                }
            }
            if (progressDialog.isShowing) {
                try { progressDialog.dismiss() } catch (_: Exception) {}
            }
            if (!ok && !isFinishing && !isDestroyed) {
                Toast.makeText(this@TvMainActivity, "ดาวน์โหลดอัปเดตไม่สำเร็จ กรุณาลองใหม่", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleFloatingOverlay() {
        if (TvOverlayService.isRunning) {
            stopService(Intent(this, TvOverlayService::class.java))
            updateFloatingButtonUI(false)
            Toast.makeText(this, "ปิดโหมดซับลอยทับ HDMI แล้ว", Toast.LENGTH_SHORT).show()
            return
        }

        val phone = connectedPhone
        if (phone == null) {
            Toast.makeText(this, "กรุณารอเชื่อมต่อกับมือถือ PS Translator ก่อนครับ", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "กรุณาอนุญาต 'แสดงทับแอปอื่น' ให้ PS Translator TV ก่อนครับ", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Exception) {}
            }
            return
        }

        val url = "http://${phone.ip}:${TvPhoneDiscovery.PORT}/tv?t=${phone.token}"
        val svcIntent = Intent(this, TvOverlayService::class.java).putExtra(TvOverlayService.EXTRA_URL, url)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }
        updateFloatingButtonUI(true)
        Toast.makeText(this, "เปิดซับลอยทับ HDMI แล้ว! กดสลับช่องรีโมตไป PS5 ได้เลย", Toast.LENGTH_LONG).show()
    }

    private fun updateFloatingButtonUI(running: Boolean = TvOverlayService.isRunning) {
        btnFloatingOverlay.text = if (running) "✨ ลอยทับ HDMI: เปิดอยู่" else "✨ ลอยทับ HDMI: ปิด"
    }

    private fun showManualIpDialog() {
        val input = EditText(this).apply {
            hint = "เช่น 192.168.1.35"
            setText(prefs.getString("phone_ip", "") ?: "")
            inputType = InputType.TYPE_CLASS_PHONE
            setPadding(36, 24, 36, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("กรอกเลข IP ของมือถือ (พอร์ต 8791)")
            .setView(input)
            .setPositiveButton("เชื่อมต่อ") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    uiScope.launch {
                        tvStatusText.text = "กำลังเชื่อมต่อกับ $ip..."
                        val hit = TvPhoneDiscovery.tryOfferHost(ip, timeoutMs = 2000)
                        if (hit != null) {
                            onPhoneConnected(hit)
                        } else {
                            tvStatusText.text = "เชื่อมต่อ $ip ไม่สำเร็จ (ตรวจสอบว่าเปิดแอป PS Translator บนมือถืออยู่)"
                        }
                    }
                }
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun startAutoDiscoveryLoop() {
        discoveryJob?.cancel()
        discoveryJob = uiScope.launch {
            while (isActive && connectedPhone == null) {
                val savedIp = prefs.getString("phone_ip", null)
                val myIp = TvPhoneDiscovery.getLocalIpv4Address() ?: "Wi-Fi"
                tvStatusText.text = "กำลังค้นหามือถือ PS Translator ในวงแลน ($myIp)..."
                val hit = TvPhoneDiscovery.discoverOnLocalSubnet(savedIp)
                if (hit != null) {
                    onPhoneConnected(hit)
                    break
                }
                delay(2500)
            }
        }
    }

    private fun onPhoneConnected(info: PhoneServerInfo) {
        connectedPhone = info
        prefs.edit().putString("phone_ip", info.ip).apply()
        tvStatusText.text = "เชื่อมต่อกับ ${info.name} แล้ว (IP: ${info.ip})"
        val url = "http://${info.ip}:${TvPhoneDiscovery.PORT}/tv?t=${info.token}"
        overlayWebView.loadUrl(url)

        // If floating overlay is already active, update its URL too
        if (TvOverlayService.isRunning) {
            val svcIntent = Intent(this, TvOverlayService::class.java).putExtra(TvOverlayService.EXTRA_URL, url)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
        }
    }

    private fun disconnectPhone() {
        val old = connectedPhone
        connectedPhone = null
        prefs.edit().remove("phone_ip").apply()
        overlayWebView.loadUrl("about:blank")
        if (TvOverlayService.isRunning) {
            stopService(Intent(this, TvOverlayService::class.java))
            updateFloatingButtonUI(false)
        }
        if (old != null) {
            uiScope.launch { TvPhoneDiscovery.sendForget(old.ip) }
        }
        startAutoDiscoveryLoop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (controlPanel.visibility == View.VISIBLE && connectedPhone != null) {
                controlPanel.visibility = View.GONE
                return true
            }
        } else if (keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER
        ) {
            if (controlPanel.visibility != View.VISIBLE) {
                controlPanel.visibility = View.VISIBLE
                btnFloatingOverlay.requestFocus()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        discoveryJob?.cancel()
        uiScope.cancel()
        try { tvHdmiView.reset() } catch (_: Exception) {}
        super.onDestroy()
    }
}
