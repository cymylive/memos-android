package com.usememos.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mobile.Mobile
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    companion object {
        const val DEFAULT_SERVER_PORT = 8081
        const val PREF_NAME = "owndiary_prefs"
        const val PREF_SERVER_PORT = "server_port"
        const val PREF_UI_FONT = "ui_font"
        const val FONT_SYSTEM = "system"
        const val MAX_FONT_SIZE = 50L * 1024 * 1024
        @Volatile
        var serverPort: Int = DEFAULT_SERVER_PORT
        private const val BACKUP_FILENAME = "memos-backup.zip"
        private val IMPORT_FONT_MIMES = arrayOf(
            "font/ttf", "font/otf", "font/woff", "font/woff2",
            "application/x-font-ttf", "application/x-font-otf",
            "application/font-sfnt", "application/font-woff", "application/octet-stream"
        )
        val PRESET_FONTS = listOf(
            PresetFont("霞鹜文楷（清新手写风）", "/fonts/lxgw-wenkai-regular.woff2", "woff2"),
            PresetFont("霞鹜文楷 Light（轻盈）", "/fonts/lxgw-wenkai-light.woff2", "woff2")
        )
    }

    data class PresetFont(val label: String, val src: String, val format: String)

    private lateinit var webView: WebView
    private lateinit var progressBar: LinearProgressIndicator
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private val fontDir: File get() = File(filesDir, "fonts")
    private var isBusy = false

    private val importFontLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFont(uri)
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileChooser
            pendingFileChooser = null
            if (callback == null) return@registerForActivityResult
            val uris = if (result.resultCode == RESULT_OK && result.data != null) {
                result.data!!.clipData?.let { clip ->
                    (0 until clip.itemCount).map { clip.getItemAt(it).uri }.toTypedArray()
                } ?: result.data!!.data?.let { arrayOf(it) }
            } else {
                null
            }
            callback.onReceiveValue(uris)
        }

    private val createBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) {
                isBusy = false
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                try {
                    progressBar.isVisible = true
                    val error: String? = withContext(Dispatchers.IO) {
                        try {
                            Mobile.stopServer()
                            val out = contentResolver.openOutputStream(uri)
                            if (out == null) {
                                "无法打开保存位置"
                            } else {
                                out.use { stream -> ZipUtils.zipDirectory(filesDir, stream) }
                                null
                            }
                        } catch (e: Exception) {
                            e.message ?: "未知错误"
                        } finally {
                            runCatching { Mobile.startServer(filesDir.absolutePath, serverPort.toLong()) }
                        }
                    }
                    progressBar.isVisible = false
                    webView.reload()
                    if (error == null) {
                        toast("备份已保存")
                    } else {
                        showErrorDialog("备份失败", error)
                    }
                } finally {
                    isBusy = false
                }
            }
        }

    private val openRestoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) restoreFromUri(uri)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverPort = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(PREF_SERVER_PORT, DEFAULT_SERVER_PORT)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        setSupportActionBar(findViewById(R.id.toolbar))

        setupWebView()
        requestNotificationPermissionIfNeeded()
        startForegroundService(android.content.Intent(this, MemosService::class.java))
        startServerAndLoad()
    }

    private fun serverUrl() = "http://127.0.0.1:$serverPort"

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true && error != null) {
                    toast("无法连接本地服务（${error.description}），请重新打开应用")
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val path = request?.url?.path ?: return null
                if (!path.startsWith("/fonts/")) return null
                val fileName = path.removePrefix("/fonts/")
                if (fileName.isBlank() || fileName.contains('/') || fileName.contains("..")) return null
                val fontFile = File(fontDir, fileName)
                if (!fontFile.isFile()) return null
                return try {
                    WebResourceResponse(fontMime(fileName), null, FileInputStream(fontFile))
                } catch (e: Exception) {
                    null
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                injectFontStyle()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                pendingFileChooser = filePathCallback
                val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                    putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "audio/*", "text/*", "application/*"))
                }
                fileChooserLauncher.launch(android.content.Intent.createChooser(intent, "选择附件"))
                return true
            }
        }
    }

    private fun startServerAndLoad() {
        lifecycleScope.launch(Dispatchers.IO) {
            val error = Mobile.startServer(filesDir.absolutePath, serverPort.toLong())
            withContext(Dispatchers.Main) {
                if (error.isNotEmpty()) {
                    toast("服务启动失败：$error")
                    progressBar.isVisible = false
                } else {
                    webView.loadUrl(serverUrl())
                    showLanHint()
                }
            }
        }
    }

    private fun showLanHint() {
        val ip = NetworkUtils.lanIpAddress()
        val message = if (ip != null) {
            "局域网访问：http://$ip:$serverPort"
        } else {
            "未检测到 WiFi 连接，局域网设备暂时无法访问"
        }
        toast(message)
    }

    private fun showPortDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(serverPort.toString())
        }
        AlertDialog.Builder(this)
            .setTitle("设置端口")
            .setMessage("当前端口 $serverPort，修改后服务将重启，局域网地址随之更新")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newPort = input.text.toString().trim().toIntOrNull()
                if (newPort == null || newPort !in 1..65535) {
                    toast("端口必须是 1-65535 的数字")
                    return@setPositiveButton
                }
                if (newPort == serverPort) {
                    return@setPositiveButton
                }
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putInt(PREF_SERVER_PORT, newPort).apply()
                serverPort = newPort
                stopService(android.content.Intent(this, MemosService::class.java))
                Mobile.stopServer()
                startForegroundService(android.content.Intent(this, MemosService::class.java))
                webView.loadUrl(serverUrl())
                showLanHint()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    private fun currentFont(): String =
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString(PREF_UI_FONT, FONT_SYSTEM) ?: FONT_SYSTEM

    private fun showFontDialog() {
        val current = currentFont()
        val options = ArrayList<Pair<String, String>>()
        options.add(FONT_SYSTEM to "系统默认")
        PRESET_FONTS.forEachIndexed { index, preset ->
            options.add("preset:$index" to preset.label)
        }
        val customFiles = customFontFiles()
        if (customFiles.isNotEmpty()) {
            options.add("" to "—— 自定义字体 ——")
            customFiles.forEach { file -> options.add("custom:${file.name}" to file.name) }
        }
        val checkedIndex = options.indexOfFirst { it.first == current }.let { if (it >= 0) it else 0 }
        AlertDialog.Builder(this)
            .setTitle("界面字体")
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), checkedIndex) { dialog, which ->
                val id = options[which].first
                if (id.isNotEmpty() && id != current) {
                    applyFontSetting(id)
                }
                dialog.dismiss()
            }
            .setPositiveButton("导入字体") { _, _ -> importFontLauncher.launch(IMPORT_FONT_MIMES) }
            .setNeutralButton("删除自定义", null)
            .setNegativeButton("取消", null)
            .show()
            .getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
            .setOnClickListener { showDeleteCustomFontDialog() }
    }

    private fun showDeleteCustomFontDialog() {
        val files = customFontFiles()
        if (files.isEmpty()) {
            toast("没有自定义字体")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除自定义字体")
            .setItems(files.map { it.name }.toTypedArray()) { _, which ->
                deleteCustomFont(files[which].name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteCustomFont(fileName: String) {
        val file = File(fontDir, fileName)
        if (file.delete()) {
            if (currentFont() == "custom:$fileName") {
                applyFontSetting(FONT_SYSTEM)
                toast("已删除，恢复系统默认字体")
            } else {
                toast("已删除")
            }
        } else {
            toast("删除失败")
        }
    }

    private fun importFont(uri: Uri) {
        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryDisplayName(uri) ?: "custom-font"
                    val safeName = sanitizeFontFileName(name)
                    if (fontFormat(safeName).isEmpty()) {
                        return@runCatching null to "仅支持 TTF/OTF/WOFF/WOFF2 字体文件"
                    }
                    fontDir.mkdirs()
                    val target = File(fontDir, safeName)
                    val input = contentResolver.openInputStream(uri)
                    if (input == null) {
                        return@runCatching null to "无法读取字体文件"
                    }
                    input.use { stream ->
                        target.outputStream().use { out -> stream.copyTo(out) }
                    }
                    if (target.length() > MAX_FONT_SIZE) {
                        target.delete()
                        return@runCatching null to "字体文件过大（超过 50MB）"
                    }
                    safeName to null
                }.getOrElse { e -> null to (e.message ?: "导入失败") }
            }
            val safeName = imported.first
            val error = imported.second
            if (error != null) {
                toast(error)
            } else if (safeName != null) {
                applyFontSetting("custom:$safeName")
                toast("字体已导入并应用")
            }
        }
    }

    private fun applyFontSetting(id: String) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(PREF_UI_FONT, id).apply()
        injectFontStyle()
        toast("字体已应用")
    }

    private fun injectFontStyle() {
        val css = fontCssFor(currentFont())
        val escaped = org.json.JSONObject.quote(css)
        val script = "javascript:(function(){var el=document.getElementById('owndiary-font');" +
            "if(!el){el=document.createElement('style');el.id='owndiary-font';document.head.appendChild(el);}" +
            "el.textContent=$escaped;})()"
        webView.evaluateJavascript(script, null)
    }

    private fun fontCssFor(id: String): String {
        return when {
            id == FONT_SYSTEM -> ""
            id.startsWith("preset:") -> {
                val index = id.removePrefix("preset:").toIntOrNull() ?: return ""
                val preset = PRESET_FONTS.getOrNull(index) ?: return ""
                buildFontCss(preset.src, preset.format)
            }
            id.startsWith("custom:") -> {
                val fileName = id.removePrefix("custom:")
                if (fileName.isBlank() || fileName.contains('/') || fileName.contains("..")) return ""
                val src = "/fonts/" + Uri.encode(fileName)
                buildFontCss(src, fontFormat(fileName))
            }
            else -> ""
        }
    }

    private fun buildFontCss(src: String, format: String): String {
        val family = "OwndiaryUiFont"
        return "@font-face{font-family:'$family';src:url('$src') format('$format');font-display:swap;}" +
            "html,body{font-family:'$family',-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC'," +
            "'Hiragino Sans GB','Microsoft YaHei','Noto Sans SC',sans-serif !important;}"
    }

    private fun customFontFiles(): List<File> =
        fontDir.listFiles()
            ?.filter { it.isFile && fontFormat(it.name).isNotEmpty() }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }

    private fun sanitizeFontFileName(name: String): String {
        var safe = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        safe = safe.replace("..", "_")
        if (safe.isBlank()) safe = "custom-font"
        return safe
    }

    private fun fontFormat(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "woff2" -> "woff2"
        "woff" -> "woff"
        "ttf" -> "truetype"
        "otf" -> "opentype"
        else -> ""
    }

    private fun fontMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        else -> "application/octet-stream"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_backup -> {
                backupData()
                true
            }
            R.id.action_restore -> {
                openRestoreLauncher.launch(arrayOf("application/zip"))
                true
            }
            R.id.action_stop -> {
                stopSharing()
                true
            }
            R.id.action_set_port -> {
                showPortDialog()
                true
            }
            R.id.action_set_font -> {
                showFontDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun backupData() {
        if (isBusy) return
        isBusy = true
        createBackupLauncher.launch(BACKUP_FILENAME)
    }

    private fun restoreFromUri(uri: Uri) {
        if (isBusy) return
        isBusy = true
        lifecycleScope.launch {
            try {
                progressBar.isVisible = true
                val error = withContext(Dispatchers.IO) {
                    var message: String? = null
                    try {
                        Mobile.stopServer()
                        val input = contentResolver.openInputStream(uri)
                        if (input == null) {
                            message = "无法读取备份文件"
                        } else {
                            input.use { stream ->
                                // Extract into a temp dir first so a corrupt backup
                                // never destroys the live data, then swap.
                                val parent = filesDir.parentFile!!
                                val tmpDir = File(parent, "restore_tmp")
                                val oldDir = File(parent, "restore_old")
                                tmpDir.deleteRecursively()
                                oldDir.deleteRecursively()
                                ZipUtils.unzipTo(stream, tmpDir)
                                // Database file is named memos_<mode>.db; the embedded
                                // server always runs in prod mode (see memos profile).
                                val dbFile = File(tmpDir, "memos_prod.db")
                                if (!dbFile.isFile || dbFile.length() <= 0) {
                                    throw IOException("备份文件缺少数据库文件，不是有效的备份")
                                }
                                filesDir.renameTo(oldDir)
                                if (!tmpDir.renameTo(filesDir)) {
                                    oldDir.renameTo(filesDir)
                                    throw IOException("替换数据目录失败")
                                }
                                oldDir.deleteRecursively()
                            }
                        }
                    } catch (e: Exception) {
                        message = "恢复失败：${e.message}"
                    }
                    runCatching { Mobile.startServer(filesDir.absolutePath, serverPort.toLong()) }
                    message
                }
                progressBar.isVisible = false
                if (error == null) {
                    toast("恢复完成")
                    webView.loadUrl(serverUrl())
                } else {
                    showErrorDialog("恢复失败", error)
                }
            } finally {
                isBusy = false
            }
        }
    }

    private fun stopSharing() {
        stopService(android.content.Intent(this, MemosService::class.java))
        Mobile.stopServer()
        toast("已停止共享")
        finishAffinity()
    }

    private fun showErrorDialog(title: String, message: String) {
        runCatching {
            File(cacheDir, "backup-error.log").appendText("[$title] $message\n")
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}