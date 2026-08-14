package com.usememos.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import java.io.IOException

class MainActivity : AppCompatActivity() {

    companion object {
        const val SERVER_PORT = 8081
        const val SERVER_URL = "http://127.0.0.1:$SERVER_PORT"
        private const val BACKUP_FILENAME = "memos-backup.zip"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: LinearProgressIndicator
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null

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
            val zipFile = backupZipFile()
            if (uri != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = runCatching {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            zipFile.inputStream().use { it.copyTo(out) }
                        } ?: return@runCatching false
                        true
                    }.getOrDefault(false)
                    withContext(Dispatchers.Main) {
                        toast(if (ok) "备份已保存" else "备份保存失败")
                    }
                }
            }
            zipFile.delete()
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

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        setSupportActionBar(findViewById(R.id.toolbar))

        setupWebView()
        requestNotificationPermissionIfNeeded()
        startForegroundService(android.content.Intent(this, MemosService::class.java))
        startServerAndLoad()
    }

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
            val error = Mobile.StartServer(filesDir.absolutePath, SERVER_PORT)
            withContext(Dispatchers.Main) {
                if (error.isNotEmpty()) {
                    toast("服务启动失败：$error")
                    progressBar.isVisible = false
                } else {
                    webView.loadUrl(SERVER_URL)
                    showLanHint()
                }
            }
        }
    }

    private fun showLanHint() {
        val ip = NetworkUtils.lanIpAddress()
        val message = if (ip != null) {
            "局域网访问：http://$ip:$SERVER_PORT"
        } else {
            "未检测到 WiFi 连接，局域网设备暂时无法访问"
        }
        toast(message)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
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
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun backupData() {
        lifecycleScope.launch {
            progressBar.isVisible = true
            val failure = withContext(Dispatchers.IO) {
                try {
                    Mobile.StopServer()
                    val zipFile = backupZipFile()
                    zipFile.delete()
                    ZipUtils.zipDirectory(filesDir, zipFile)
                    null
                } catch (e: Exception) {
                    e.message
                } finally {
                    // Server must be restarted no matter what happened above.
                    Mobile.StartServer(filesDir.absolutePath, SERVER_PORT)
                }
            }
            progressBar.isVisible = false
            if (failure == null) {
                createBackupLauncher.launch(BACKUP_FILENAME)
            } else {
                toast("备份失败：$failure")
            }
        }
    }

    private fun restoreFromUri(uri: Uri) {
        lifecycleScope.launch {
            progressBar.isVisible = true
            val error = withContext(Dispatchers.IO) {
                var message: String? = null
                try {
                    Mobile.StopServer()
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
                Mobile.StartServer(filesDir.absolutePath, SERVER_PORT)
                message
            }
            progressBar.isVisible = false
            if (error == null) {
                toast("恢复完成")
                webView.loadUrl(SERVER_URL)
            } else {
                toast(error)
            }
        }
    }

    private fun stopSharing() {
        stopService(android.content.Intent(this, MemosService::class.java))
        Mobile.StopServer()
        toast("已停止共享")
        finishAffinity()
    }

    private fun backupZipFile(): File = File(cacheDir, BACKUP_FILENAME)

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