package cn.tracktimer.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.*
import cn.tracktimer.core.Calibration
import cn.tracktimer.core.ClockSync
import cn.tracktimer.core.SyncClient
import cn.tracktimer.core.SyncServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

// This diagnostic release intentionally ships Chinese-only copy.
@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private val ink = Color.rgb(23, 42, 38)
    private val muted = Color.rgb(89, 109, 102)
    private val green = Color.rgb(18, 97, 78)
    private lateinit var store: ReportStore
    private val jobs = Executors.newSingleThreadExecutor()
    private var syncJob: Future<*>? = null
    private var server: SyncServer? = null
    private var previousCalibration: Calibration? = null
    private var cameraProbe: CameraProbe? = null
    private var audioProbe: AudioProbe? = null
    private var syncBusy = false
    private var syncGeneration = 0
    private var exportBusy = false
    private var permissionAction: (() -> Unit)? = null
    private lateinit var deviceStatus: TextView
    private lateinit var hostStatus: TextView
    private lateinit var syncStatus: TextView
    private lateinit var cameraStatus: TextView
    private lateinit var audioStatus: TextView
    private lateinit var reportStatus: TextView
    private lateinit var hostInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var hostButton: Button
    private lateinit var syncButton: Button
    private lateinit var cameraButton: Button
    private lateinit var audioButton: Button
    private lateinit var exportButton: Button
    private lateinit var preview: TextureView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = ReportStore(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        buildScreen()
        updateReportCount()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(244, 247, 245)); isFillViewport = true }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(28)) }
        scroll.addView(page)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        page.addView(text("TRACK TIMER  /  DEVICE LAB", 11, green, true))
        page.addView(text("跑道计时", 32, ink, true), spaced(8))
        page.addView(text("两台安卓 · 60 fps · 设备验证版 0.1", 14, muted), spaced(4))
        val intro = card(page)
        intro.addView(text("先把时间测准", 22, ink, true))
        intro.addView(text("检查相机、录音与双机时差，为训练计时做准备。当前版本只生成诊断报告，尚不生成跑步成绩。", 14, muted), spaced(8))

        val device = card(page)
        section(device, "01", "这台手机", "读取第三方 App 实际能调用的拍摄模式。")
        deviceStatus = text("${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} · 待检测", 15, ink)
        device.addView(deviceStatus, spaced(12))
        val detect = button("检测并保存设备报告")
        device.addView(detect, spaced(12))
        detect.setOnClickListener {
            detect.isEnabled = false
            jobs.execute {
                val result = runCatching { DeviceReport.collect(this).also { store.saveJson("device", it) } }
                ui {
                    detect.isEnabled = true
                    result.onSuccess {
                        deviceStatus.text = "${Build.MANUFACTURER} ${Build.MODEL}\n" +
                            if (it.has("selectedCamera")) "可请求 ${it.optInt("requestedFps")} fps · ${it.optString("selectedSize")}\n" +
                                "相机统一时钟：${if (it.optBoolean("cameraClockRealtime")) "系统声明支持" else "待验证"}\n实际帧率需通过下方录像测量。"
                            else "相机配置待确认：${it.optString("cameraSelectionError")}"
                        updateReportCount()
                    }.onFailure { deviceStatus.text = "检测失败：${it.message}" }
                }
            }
        }

        val sync = card(page)
        section(sync, "02", "两台手机对时", "两台连接同一 Wi-Fi，或一台开启热点、另一台加入。先靠近测试。")
        hostButton = button("作为起点：开启校准服务")
        sync.addView(hostButton, spaced(12)); hostButton.setOnClickListener { toggleHost() }
        hostStatus = text("服务未开启", 13, muted).apply { setTextIsSelectable(true) }
        sync.addView(hostStatus, spaced(8))
        hostInput = input("填写起点手机显示的 IPv4 地址", InputType.TYPE_CLASS_PHONE)
        sync.addView(hostInput, spaced(16))
        codeInput = input("六位配对码", InputType.TYPE_CLASS_NUMBER)
        sync.addView(codeInput, spaced(8))
        syncButton = button("作为终点：执行 48 次校准")
        sync.addView(syncButton, spaced(10)); syncButton.setOnClickListener { measureSync() }
        syncStatus = text("尚未校准。训练前后各测一次，可观察时差变化。", 13, muted).apply { setTextIsSelectable(true) }
        sync.addView(syncStatus, spaced(10))

        val capture = card(page)
        section(capture, "03", "验证采集能力", "手机固定，光线充足。录像与录音测试可以同时进行。")
        // TextureView rejects background drawables; the containing frame owns its background.
        preview = TextureView(this)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(19, 35, 31)) }
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        capture.addView(frame, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(14) })
        cameraStatus = text("预览在录像测试时开启。", 13, muted)
        capture.addView(cameraStatus, spaced(10))
        cameraButton = button("录制 10 秒视频")
        capture.addView(cameraButton, spaced(10))
        cameraButton.setOnClickListener {
            if (cameraProbe != null) { cameraButton.isEnabled = false; cameraProbe?.stop() }
            else withPermission(Manifest.permission.CAMERA) { startCamera() }
        }
        audioStatus = text("录音 5 秒，其间播放短提示音；请保持安静。", 13, muted)
        capture.addView(audioStatus, spaced(18))
        audioButton = button("测试发令音与麦克风")
        capture.addView(audioButton, spaced(10))
        audioButton.setOnClickListener { withPermission(Manifest.permission.RECORD_AUDIO) { startAudio() } }

        val reports = card(page)
        section(reports, "04", "带走测试结果", "导出包含设备信息、校准记录、视频及麦克风录音的 ZIP。文件由你选择保存位置。")
        reportStatus = text("", 13, muted)
        reports.addView(reportStatus, spaced(12))
        exportButton = button("导出诊断报告 ZIP")
        reports.addView(exportButton, spaced(10))
        exportButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "track-timer-${Build.MODEL.replace(' ', '-')}-${System.currentTimeMillis()}.zip")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, 20)
        }
        page.addView(text("校准范围仅描述网络对时的不确定性。\n总计时精度仍需真机和参考设备对照验证。", 12, muted), spaced(22))
        setContentView(scroll)
    }

    private fun toggleHost() {
        if (server != null) { stopHost(); return }
        try {
            val code = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
            val host = SyncServer(code, UUID.randomUUID().toString(), SystemClock::elapsedRealtimeNanos)
            val port = host.start(19119); server = host
            hostStatus.text = "校准服务已开启\n地址：${localAddresses()}\n端口：$port　配对码：$code\n请保持本页在前台。"
            hostButton.text = "关闭起点校准服务"
        } catch (e: Exception) { hostStatus.text = "开启失败：${e.message}" }
    }

    private fun stopHost() {
        server?.close(); server = null
        if (::hostStatus.isInitialized) { hostStatus.text = "服务未开启"; hostButton.text = "作为起点：开启校准服务" }
    }

    private fun measureSync() {
        val host = hostInput.text.toString().trim(); val code = codeInput.text.toString().trim()
        if (host.isEmpty() || !code.matches(Regex("[0-9]{6}"))) {
            syncStatus.text = "请填写起点地址与六位配对码。"; return
        }
        syncBusy = true; syncButton.isEnabled = false; updateButtons()
        val generation = ++syncGeneration
        syncStatus.text = "正在交换时间戳，请保持两台手机在前台……"
        syncJob = jobs.submit {
            try {
                val measurement = SyncClient.measure(host, 19119, code, AppSession.id, SystemClock::elapsedRealtimeNanos)
                val c = measurement.calibration
                val json = JSONObject().put("kind", "calibration").put("localSession", c.localSession)
                    .put("remoteSession", c.remoteSession).put("anchorLocalNs", c.anchorLocalNs)
                    .put("offsetRemoteMinusLocalNs", c.offsetNs).put("networkUncertaintyNs", c.uncertaintyNs)
                    .put("lowerOffsetNs", c.lowerOffsetNs).put("upperOffsetNs", c.upperOffsetNs)
                    .put("bestRoundTripNs", c.bestRoundTripNs).put("selectedCount", c.selectedCount)
                    .put("sampleCount", c.sampleCount).put("precisionVerified", false)
                    .put("samples", JSONArray(measurement.samples.map {
                        JSONObject().put("t1", it.t1).put("t2", it.t2).put("t3", it.t3).put("t4", it.t4)
                    }))
                val previous = previousCalibration
                val drift = previous?.let { runCatching { ClockSync.driftPpm(it, c) }.getOrNull() }
                if (drift != null) json.put("diagnosticDriftPpm", drift)
                store.saveJson("calibration", json)
                syncUi(generation) {
                    previousCalibration = c
                    syncStatus.text = "已完成 ${c.sampleCount} 次交换\n起点时钟－本机：${ms(c.offsetNs)} ms\n" +
                        "网络估计范围：±${ms(c.uncertaintyNs)} ms\n最短净往返：${ms(c.bestRoundTripNs)} ms\n" +
                        (if (c.uncertaintyNs > 5_000_000) "网络波动较大，建议靠近重测。\n" else "已保存校准记录；总精度仍待验证。\n") +
                        (drift?.let { "前后线性漂移估计：${String.format(Locale.US, "%.2f", it)} ppm（含校准误差）" } ?: "")
                }
            } catch (e: Exception) {
                syncUi(generation) { syncStatus.text = "校准未完成：${e.message}\n检查网络、配对码，或回到本页后重试。" }
            } finally {
                syncUi(generation) { syncBusy = false; syncButton.isEnabled = true; updateButtons(); updateReportCount() }
            }
        }
    }

    private fun startCamera() {
        if (cameraProbe != null) return
        cameraButton.text = "停止录像"; cameraStatus.text = "正在配置相机……"
        cameraProbe = CameraProbe(this, store, preview, { cameraStatus.text = it }, { report ->
            if (!isDestroyed) {
                cameraProbe = null; cameraButton.text = "录制 10 秒视频"; cameraButton.isEnabled = true
                cameraStatus.text = if (!report.isNull("error")) "录像测试未完成：${report.optString("error")}"
                else "采集帧率：${number(report, "captureFps")} fps\n编码视频帧率：${number(report, "encodedFps")} fps\n" +
                    "最大采集间隔：${ms(report.optLong("maxFrameGapNs"))} ms\n" +
                    "视频已保存；采集与编码时间映射仍待标定。"
                if (report.has("saveError")) cameraStatus.append("\n报告保存失败：${report.optString("saveError")}")
                updateButtons(); updateReportCount()
            }
        }).also { it.start() }
        updateButtons()
    }

    private fun startAudio() {
        if (audioProbe != null) return
        audioButton.isEnabled = false
        audioProbe = AudioProbe(this, store, { audioStatus.text = it }, { report ->
            if (!isDestroyed) {
                audioProbe = null; audioButton.isEnabled = true
                audioStatus.text = when {
                    report.has("error") -> "录音测试失败：${report.optString("error")}"
                    report.optBoolean("cancelled") -> "录音已中断，已保留采集到的诊断数据。"
                    else -> "硬件时间戳样本：${report.optInt("hardwareTimestampCount")}\n" +
                        (if (report.isNull("candidateSample")) "未找到测试音候选，请检查音量或环境噪声。" else "检测到测试音候选；尚未验证为准确发令时刻。")
                }
                if (report.has("saveError")) audioStatus.append("\n报告保存失败：${report.optString("saveError")}")
                updateButtons(); updateReportCount()
            }
        }).also { it.start() }
        updateButtons()
    }

    private fun withPermission(permission: String, action: () -> Unit) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) action()
        else { permissionAction = action; requestPermissions(arrayOf(permission), 10) }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            val action = permissionAction; permissionAction = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) action?.invoke()
            else Toast.makeText(this, "需要对应权限才能采集；可在系统设置中授权后重试。", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Platform callback retained for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 20 || resultCode != RESULT_OK) return
        exportBusy = true; updateButtons(); reportStatus.text = "正在导出……"
        jobs.execute {
            val result = runCatching { contentResolver.openOutputStream(uri)?.use { store.export(it) } ?: error("无法打开保存位置") }
            ui {
                exportBusy = false; updateButtons()
                reportStatus.text = result.fold({ "导出完成，可分享 ZIP 用于分析。" }, { "导出失败：${it.message}" })
            }
        }
    }

    override fun onPause() {
        super.onPause()
        cameraProbe?.stop("App 离开前台，录像已停止")
        audioProbe?.cancel()
        // A queued Future cancelled before execution never runs its finally block.
        // Also ignore callbacks from an old request after the user starts a new one.
        ++syncGeneration; syncJob?.cancel(true)
        if (syncBusy) {
            syncBusy = false; syncButton.isEnabled = true
            syncStatus.text = "校准已中断，回到前台后可重新开始。"
            updateButtons()
        }
        stopHost()
    }
    override fun onDestroy() { jobs.shutdownNow(); super.onDestroy() }

    private fun updateButtons() {
        if (::exportButton.isInitialized) exportButton.isEnabled = cameraProbe == null && audioProbe == null && !syncBusy && !exportBusy
    }
    private fun updateReportCount() { reportStatus.text = "已保存 ${store.reportCount()} 份诊断记录；所有文件保存在本机。" }
    private fun ui(block: () -> Unit) { runOnUiThread { if (!isDestroyed) block() } }
    private fun syncUi(generation: Int, block: () -> Unit) { ui { if (generation == syncGeneration) block() } }
    private fun ms(ns: Long) = String.format(Locale.US, "%.3f", ns / 1e6)
    private fun number(json: JSONObject, key: String) = if (json.isNull(key)) "未知" else String.format(Locale.US, "%.2f", json.optDouble(key))
    private fun localAddresses(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .joinToString(" / ") { it.hostAddress ?: "" }.ifEmpty { "未找到局域网地址，请连接 Wi-Fi 或开启热点" }
    }.getOrElse { "请在系统网络设置中查看 IP" }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun spaced(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun card(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(225, 234, 228)) }
        parent.addView(this, spaced(18))
    }
    private fun section(parent: LinearLayout, index: String, title: String, detail: String) {
        parent.addView(text("$index  /  $title", 19, ink, true))
        parent.addView(text(detail, 13, muted), spaced(6))
    }
    private fun button(label: String) = Button(this).apply {
        text = label; textSize = 14f; isAllCaps = false; minHeight = dp(48)
        setTextColor(Color.WHITE); setPadding(dp(12), dp(10), dp(12), dp(10))
        backgroundTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(Color.rgb(165, 183, 174), green))
    }
    private fun input(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText; textSize = 14f; setTextColor(ink); setHintTextColor(muted)
        inputType = type; setSingleLine(); minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }
}
