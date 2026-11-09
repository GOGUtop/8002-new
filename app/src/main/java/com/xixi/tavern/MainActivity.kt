package com.xixi.tavern

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAVERN_URL = "http://aaa.xixisillytavern.top:8002"
        private const val CHANNEL_ID = "tavern_generation_finished"
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        val data = result.data
        val selected = if (result.resultCode == RESULT_OK) {
            when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else null
        callback.onReceiveValue(selected)
        filePathCallback = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "未允许通知权限，生成完成时将无法显示系统通知", Toast.LENGTH_LONG).show()
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "未允许存储权限，旧版 Android 可能无法保存网页导出文件", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
        requestLegacyStoragePermissionIfNeeded()

        webView = WebView(this)
        setContentView(webView)
        configureWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl(TAVERN_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(TavernBridge(this), "TavernNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                val tavernHost = Uri.parse(TAVERN_URL).host
                val tavernPort = Uri.parse(TAVERN_URL).port
                if ((scheme == "http" || scheme == "https") &&
                    uri.host.equals(tavernHost, ignoreCase = true) && uri.port == tavernPort
                ) return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Exception) {
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                installTavernHooks()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    fileChooserLauncher.launch(Intent.createChooser(intent, "选择要导入的文件"))
                    true
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                    setDescription("正在下载酒馆文件")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, contentDisposition, mimeType)
                    )
                }
                val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
                Toast.makeText(this, "已开始下载，完成后可在系统下载目录查看", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "下载失败：${e.localizedMessage ?: "未知错误"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun installTavernHooks() {
        val script = """
            (function () {
              if (window.__xixiNativeHooksInstalled) return;
              window.__xixiNativeHooksInstalled = true;

              function isEditable(node) {
                return !!(node && node.closest && node.closest('input, textarea, select, [contenteditable="true"], [role="textbox"]'));
              }

              // 禁止聊天正文上的双击选词/长按原生菜单；输入框和编辑区保留正常粘贴、输入能力。
              document.addEventListener('dblclick', function (event) {
                if (!isEditable(event.target)) {
                  event.preventDefault();
                  var selection = window.getSelection && window.getSelection();
                  if (selection) selection.removeAllRanges();
                }
              }, true);
              document.addEventListener('selectstart', function (event) {
                if (!isEditable(event.target)) event.preventDefault();
              }, true);
              document.addEventListener('contextmenu', function (event) {
                if (!isEditable(event.target)) event.preventDefault();
              }, true);

              // WebView 的 DownloadListener 不接收 blob/data URL；拦截带 download 属性的导出链接交给原生保存。
              document.addEventListener('click', function (event) {
                var anchor = event.target && event.target.closest ? event.target.closest('a[download]') : null;
                if (!anchor) return;
                var href = anchor.href || '';
                if (!href.startsWith('blob:') && !href.startsWith('data:')) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                fetch(href).then(function (response) { return response.blob(); }).then(function (blob) {
                  var reader = new FileReader();
                  reader.onloadend = function () {
                    var dataUrl = String(reader.result || '');
                    var base64 = dataUrl.indexOf(',') >= 0 ? dataUrl.substring(dataUrl.indexOf(',') + 1) : '';
                    if (window.TavernNative && window.TavernNative.saveBlobFile) {
                      window.TavernNative.saveBlobFile(anchor.download || 'tavern-export.bin', blob.type || 'application/octet-stream', base64);
                    }
                  };
                  reader.readAsDataURL(blob);
                }).catch(function () {});
              }, true);

              var style = document.createElement('style');
              style.textContent = 'body, body *:not(input):not(textarea):not(select):not([contenteditable="true"]):not([role="textbox"]) { -webkit-touch-callout: none; }';
              (document.head || document.documentElement).appendChild(style);

              var initialized = false;
              var wasGenerating = false;
              var lastNoticeAt = 0;
              function sendNotice() {
                var now = Date.now();
                if (now - lastNoticeAt < 2500) return;
                lastNoticeAt = now;
                try {
                  if (window.TavernNative && window.TavernNative.onGenerationFinished) {
                    window.TavernNative.onGenerationFinished();
                  }
                } catch (e) {}
              }
              function visible(element) {
                if (!element) return false;
                var style = window.getComputedStyle(element);
                return style.display !== 'none' && style.visibility !== 'hidden' && element.getClientRects().length > 0;
              }
              function isGenerating() {
                var stopButtons = document.querySelectorAll('#mes_stop, #stop_but, .mes_stop, [data-testid="stop-generation"]');
                for (var i = 0; i < stopButtons.length; i++) if (visible(stopButtons[i])) return true;
                var stopByLabel = document.querySelectorAll('button[aria-label*="Stop"], button[title*="Stop"]');
                for (var j = 0; j < stopByLabel.length; j++) if (visible(stopByLabel[j])) return true;
                return false;
              }
              function checkGenerationState() {
                var generating = isGenerating();
                if (!initialized) {
                  initialized = true;
                  wasGenerating = generating;
                  return;
                }
                if (wasGenerating && !generating) sendNotice();
                wasGenerating = generating;
              }
              try {
                var source = window.eventSource;
                var types = window.event_types;
                if (source && types && types.GENERATION_ENDED && typeof source.on === 'function') {
                  source.on(types.GENERATION_ENDED, sendNotice);
                }
              } catch (e) {}
              try {
                var observer = new MutationObserver(checkGenerationState);
                observer.observe(document.documentElement, {
                  subtree: true, childList: true, attributes: true,
                  attributeFilter: ['class', 'style', 'hidden', 'disabled', 'aria-hidden']
                });
              } catch (e) {}
              window.setInterval(checkGenerationState, 700);
              checkGenerationState();
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "酒馆生成完成提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "角色回复生成完成时提醒"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT in 23..28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun saveBlobExport(fileName: String, mimeType: String, base64: String) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .ifBlank { "tavern-export.bin" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("无法创建下载文件")
                resolver.openOutputStream(item)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("无法写入下载文件")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(item, values, null, null)
            } else {
                val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!directory.exists()) directory.mkdirs()
                FileOutputStream(File(directory, safeName)).use { it.write(bytes) }
            }
            runOnUiThread { Toast.makeText(this, "已保存到 Download 文件夹：$safeName", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "保存导出文件失败：${e.localizedMessage ?: "未知错误"}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun showGenerationNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val openApp = PendingIntent.getActivity(
            this,
            1001,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("酒馆回复完成")
            .setContentText("角色已经回复完毕，快来看看吧。")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(this).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    inner class TavernBridge(private val activity: MainActivity) {
        @JavascriptInterface
        fun onGenerationFinished() {
            activity.runOnUiThread { activity.showGenerationNotification() }
        }

        @JavascriptInterface
        fun saveBlobFile(fileName: String, mimeType: String, base64: String) {
            activity.saveBlobExport(fileName, mimeType, base64)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.removeJavascriptInterface("TavernNative")
            webView.destroy()
        }
        super.onDestroy()
    }
}
