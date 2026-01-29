// gardendless-gecko

// Copyright (C) 2026  Caten Hu

// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

package com.fct.gardendless

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import org.mozilla.geckoview.*
import java.io.File
import java.util.zip.ZipInputStream
import androidx.core.net.toUri

object GeckoManager {
    private var runtime: GeckoRuntime? = null

    fun getRuntime(context: android.content.Context): GeckoRuntime {
        if (runtime == null) {
            // 确保使用 ApplicationContext，防止内存泄漏
            runtime = GeckoRuntime.create(context.applicationContext)
        }
        return runtime!!
    }
}

class GameActivity : AppCompatActivity() {

    // GeckoView 核心
    private lateinit var geckoView: GeckoView
    private val geckoSession = GeckoSession()
    private val geckoRuntime: GeckoRuntime get() = GeckoManager.getRuntime(this)

    // 状态维护
    private var canGoBackState: Boolean = false
    private var server: ApplicationEngine? = null
    private var serverPort: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setupFullScreen()

        val sp = getSharedPreferences("app_data", MODE_PRIVATE)
        val savedVersion = sp.getInt("extracted_version", 0)
        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionCode

        if (savedVersion != currentVersion) {
            checkAndExtractAssets(currentVersion, sp)
        } else {
            startServerAndLaunchGame()
        }
    }

    private fun startServerAndLaunchGame() {
        val gameDir = File(filesDir, "pvzge_web-master/docs")

        CoroutineScope(Dispatchers.IO).launch {
// 1. 创建并启动服务器
            val serverInstance = embeddedServer(Netty, port = 23337) {
                routing {
                    staticFiles("/", gameDir) {
                        default("index.html")
                    }
                }
            }.start(wait = false)

            // 2. 关键修复：赋值给 server 变量时，访问 .engine 属性
            // serverInstance.engine 的类型正是 ApplicationEngine
            server = serverInstance.engine

            // 3. 获取端口
            serverPort = serverInstance.engine.resolvedConnectors().firstOrNull()?.port ?: 23337

            withContext(Dispatchers.Main) {
                initGeckoView()
            }
        }
    }

    // 定义一个全局变量来持有结果处理器
    private var fileCallback: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null

    private fun initGeckoView() {
        geckoView = MouseGameWebView(this)
        setContentView(geckoView)

        // geckoRuntime.settings.setRemoteDebuggingEnabled(true)

        // 2. 加载 WebExtension (替代 evaluateJavascript)
        geckoRuntime.webExtensionController
            .ensureBuiltIn("resource://android/assets/messaging_extension/", "gamefix@fct.com")
            .accept(
                { Log.d("GeckoView", "Extension injected successfully") },
                { e -> Log.e("GeckoView", "Extension failed", e) }
            )

        // 3. 配置 Session 代理
        geckoSession.apply {
            // 配置 Session 权限代理
            geckoSession.permissionDelegate = object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    perm: GeckoSession.PermissionDelegate.ContentPermission
                ): GeckoResult<Int>? {
                    // 检查是否是自动播放权限请求（有声或无声）
                    val isAutoplay = perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                            perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE

                    if (isAutoplay) {
                        // 强制返回“允许”状态
                        return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                    }
                    // 其他权限（如地理位置、摄像头）按默认逻辑处理
                    return null
                }
            }
            promptDelegate = object : GeckoSession.PromptDelegate {
                override fun onFilePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    // 保存 prompt 对象以便在 onActivityResult 中使用
                    this@GameActivity.currentFilePrompt = prompt

                    fileCallback = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()

                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*" // 游戏需要的 json/json5 等
                        val mimeTypes = arrayOf(
                            "application/json",
                            "application/octet-stream", // 很多系统把 json5 识别为 bin
                            "text/plain"               // 有些系统把 json5 识别为纯文本
                        )
                        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                    }
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE)

                    // 返回一个未完成的 Result
                    return fileCallback
                }
            }
            // 异步监听返回状态
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    canGoBackState = canGoBack
                }// 关键：允许并处理页面跳转
                // 场景 1：在当前窗口直接点击跳转
                override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                    val url = request.uri

                    // 1. 如果是 Data URI (游戏导出存档)
                    // if (url.startsWith("data:")) {
                        // 交给你的导出逻辑
                        // exportDataUri(url)
                        // 拒绝加载这个 URL，因为我们已经手动处理了文件下载
                        // return GeckoResult.fromValue(AllowOrDeny.DENY)
                    // }

                    // 2. 如果是本地服务器资源 (游戏运行所需)
                    if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
                        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                    }

                    // 3. 如果是外部链接 (http/https)，调用系统浏览器
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        openInSystemBrowser(url)
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }

                    // 默认允许
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }

                // 场景 2：网页通过 window.open 或 target="_blank" 打开新窗口
                override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                    // 直接调用系统浏览器
                    openInSystemBrowser(uri)

                    // 返回 null 并告知 GeckoView 我们已接管，不需要创建新 Session
                    return GeckoResult.fromValue(null)
                }
            }

            // 处理 Data URI 下载/导出
            contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                    if (response.uri.startsWith("data:")) {
                        exportDataUri(response.uri)
                    }
                }
            }

            open(geckoRuntime)
        }

        geckoView.setSession(geckoSession)
        geckoSession.loadUri("http://127.0.0.1:$serverPort/index.html")

        setupBackNavigation()
    }

    private fun exportDataUri(dataUri: String) {
        try {
            val parts = dataUri.split(",")
            if (parts.size < 2) return
            val content = Uri.decode(parts.subList(1, parts.size).joinToString(","))
            val cacheFile = File(cacheDir, "pp.json")
            cacheFile.writeText(content)

            val contentUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", cacheFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.export)))
        } catch (e: Exception) {
            Log.e("Export", "Failed to export data", e)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 使用 NavigationDelegate 维护的异步状态
                if (canGoBackState) {
                    geckoSession.goBack()
                } else {
                    showExitDialog()
                }
            }
        })
    }

    private fun checkAndExtractAssets(currentVersion: Int, sp: android.content.SharedPreferences) {
        val progressBar = ProgressBar(this).apply { isIndeterminate = true; setPadding(50, 50, 50, 50) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unzipping)
            .setMessage(R.string.description)
            .setView(progressBar)
            .setCancelable(false)
            .show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                assets.open("pvzge_web-master.zip").use { input ->
                    ZipInputStream(input).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val file = File(filesDir, entry.name)
                            if (entry.isDirectory) file.mkdirs()
                            else {
                                file.parentFile?.mkdirs()
                                file.outputStream().use { zis.copyTo(it) }
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
                sp.edit().putInt("extracted_version", currentVersion).apply()
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    startServerAndLaunchGame()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { dialog.dismiss() }
            }
        }
    }

    private fun setupFullScreen() {
        supportActionBar?.hide()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showExitDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hint)
            .setMessage(R.string.exit_confirm)
            .setPositiveButton(R.string.yes) { _, _ -> finish() }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop(500, 1000)
        geckoSession.close()
    }
    private var currentFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private val FILE_CHOOSER_RESULT_CODE = 101

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FILE_CHOOSER_RESULT_CODE && fileCallback != null) {
            val originalUri = if (resultCode == RESULT_OK) data?.data else null
            val prompt = currentFilePrompt ?: return

            if (originalUri != null) {
                // 关键补丁：转换 content:// 到 file://
                val fileUri = if ("file".equals(originalUri.scheme, ignoreCase = true)) {
                    originalUri
                } else {
                    toFileUri(this, originalUri)
                }

                if (fileUri != null) {
                    // 传回拷贝后的 File Uri
                    fileCallback?.complete(prompt.confirm(this, fileUri))
                } else {
                    fileCallback?.complete(prompt.dismiss())
                }
            } else {
                fileCallback?.complete(prompt.dismiss())
            }

            // 释放引用
            fileCallback = null
            currentFilePrompt = null
        }
    }
    // 辅助函数：将 Content Uri 转换为私有目录下的 File Uri
    private fun toFileUri(context: android.content.Context, uri: Uri): Uri? {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            // 建立临时文件，建议保留原文件扩展名
            val tempFile = File(context.cacheDir, "upload_temp_${System.currentTimeMillis()}")
            tempFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            // 返回 File 协议的 Uri
            return Uri.fromFile(tempFile)
        } catch (e: Exception) {
            Log.e("GeckoView", "Failed to copy file", e)
            return null
        }
    }
    // 辅助函数：调用系统浏览器
    private fun openInSystemBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("GeckoView", "无法打开系统浏览器: ${e.message}")
        }
    }
}