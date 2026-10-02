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
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.*
import java.io.File
import java.util.zip.ZipInputStream
import androidx.core.net.toUri
import androidx.core.view.isEmpty

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

    /** 画面容器，负责比例适配与全屏切换 */
    private lateinit var aspectContainer: AspectRatioFrameLayout

    private val prefs by lazy { getSharedPreferences("app_data", MODE_PRIVATE) }

    /**
     * gp-next 数据目录，对应 Tauri 的 AppData 根。
     * 页面侧把 plugin:path|resolve_directory 的返回值（本移植中为页面 origin）拼上 `/gp-next`，
     * 因此数据实际位于 filesDir/gp-next，与 GameDocumentsProvider 的 gpnext 根指向同一目录。
     */
    private val gpNextDir: File by lazy { File(filesDir, GP_NEXT_DIR_NAME).apply { mkdirs() } }

    // 导出流程：解出的内容等待用户选定目标后写入，文件名优先由游戏给出
    private var pendingExport: ByteArray? = null
    private var pendingExportName: String? = null

    private companion object {
        const val PREF_FULLSCREEN = "webview_fullscreen"
        const val EXTENSION_LOCATION = "resource://android/assets/messaging_extension/"
        const val EXTENSION_ID = "gamefix@fct.com"
        const val TAG = "GameActivity"
        const val GP_NEXT_DIR_NAME = "gp-next"

        /** 与 bridgePatch.js 约定的回传标题前缀 */
        const val GD_TITLE_PREFIX = "[GD] "

        /**
         * 本地服务器端口。
         *
         * 该端口属于对外接口的一部分，不可随意变更：localStorage 按 origin 隔离，
         * 更换端口会切换 origin，导致 gp-next 设置与游戏存档全部读不到。
         */
        const val SERVER_PORT = 23337
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setupFullScreen()

        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionCode

        if (prefs.getInt("extracted_version", 0) != currentVersion) {
            checkAndExtractAssets(currentVersion)
        } else {
            startServerAndLaunchGame()
        }
    }

    /** 启动本地静态服务器，随后回主线程初始化 GeckoView */
    private fun startServerAndLaunchGame() {
        val gameDir = File(filesDir, "pvzge_web-master/docs")

        CoroutineScope(Dispatchers.IO).launch {
            val serverInstance = embeddedServer(Netty, port = SERVER_PORT) {
                routing {
                    // 游戏资源。default 把未命中的路径回落到 index.html。
                    staticFiles("/", gameDir) {
                        default("index.html")
                    }

                    // gp-next 数据目录。页面侧通过 fetch 该虚拟路径读取原始字节。
                    //
                    // 此处不可配置 default(...)：Ktor 在文件不存在时不作响应，而是继续走 defaultPath，
                    // 配置后会返回 index.html 而非 404，游戏会把 HTML 当作 JSON 解析。
                    staticFiles("/gp-next", gpNextDir)

                    // 元数据与写操作。GeckoView 没有可用的 JS 桥，页面侧只能经 HTTP 调用；
                    // 路径统一放在 path 查询参数中，写操作的请求体留给文件内容。
                    route("/__gdnext/fs") {
                        post("/readdir") {
                            call.respondText(
                                gpNextReadDirJson(call.gpNextQueryPath()),
                                ContentType.Application.Json
                            )
                        }
                        post("/stat") {
                            val json = gpNextStatJson(call.gpNextQueryPath())
                            if (json == null) call.respond(HttpStatusCode.NotFound)
                            else call.respondText(json, ContentType.Application.Json)
                        }
                        post("/exists") { call.respondBool(gpNextExists(call.gpNextQueryPath())) }
                        post("/mkdir") { call.respondBool(gpNextMkdir(call.gpNextQueryPath())) }
                        post("/remove") { call.respondBool(gpNextRemove(call.gpNextQueryPath())) }
                        post("/rename") {
                            val params = call.request.queryParameters
                            call.respondBool(
                                gpNextRename(params["from"].orEmpty(), params["to"].orEmpty())
                            )
                        }
                        post("/write") {
                            val bytes = call.receiveStream().use { it.readBytes() }
                            call.respondBool(gpNextWriteBytes(call.gpNextQueryPath(), bytes))
                        }
                    }
                }
            }.start(wait = false)

            // 保存引擎实例供退出时停止。resolvedConnectors 为挂起函数，必须在协程中调用。
            server = serverInstance.engine
            serverPort = serverInstance.engine.resolvedConnectors().firstOrNull()?.port ?: SERVER_PORT

            withContext(Dispatchers.Main) {
                initGeckoView()
            }
        }
    }

    /** 文件选择的结果，待 onActivityResult 完成后回填 */
    private var fileCallback: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null

    /** 装配 GeckoView、WebExtension 与 Session 回调，并加载游戏入口页 */
    private fun initGeckoView() {
        geckoView = MouseGameWebView(this)

        // 黑底容器，负责 16:10 ~ 17:9 的比例适配与全屏切换
        aspectContainer = AspectRatioFrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            // 沿用上次退出时的全屏状态，避免先小后大的尺寸跳变
            fullscreen = prefs.getBoolean(PREF_FULLSCREEN, false)
            addView(
                geckoView,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = android.view.Gravity.CENTER }
            )
        }

        setContentView(aspectContainer)

        // 需要远程调试时取消下一行注释
        // geckoRuntime.settings.setRemoteDebuggingEnabled(true)

        // 注入 WebExtension：GeckoView 没有 evaluateJavascript，页面钩子只能由扩展注入
        geckoRuntime.webExtensionController
            .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
            .accept(
                { Log.d(TAG, "Extension injected: id=${it?.id} version=${it?.metaData?.version}") },
                { e -> Log.e(TAG, "Extension failed", e) }
            )

        // 配置 Session 的各代理与回调
        geckoSession.apply {
            geckoSession.permissionDelegate = object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    perm: GeckoSession.PermissionDelegate.ContentPermission
                ): GeckoResult<Int>? {
                    // 自动播放权限（有声或无声）直接放行
                    val isAutoplay = perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                            perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE

                    if (isAutoplay) {
                        return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                    }
                    // 其余权限（如地理位置、摄像头）按默认逻辑处理
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
                        // 主类型放宽为通配符，使选择器能显示更多文件
                        type = "*/*"
                        // 显式补充类型：各系统对 .json5 的识别结果不一致
                        val mimeTypes = arrayOf(
                            "application/json",
                            "application/octet-stream", // 部分系统识别为二进制
                            "text/plain"                // 部分系统识别为纯文本
                        )
                        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                    }
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE)

                    // 返回一个未完成的 Result
                    return fileCallback
                }
            }
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    canGoBackState = canGoBack
                }

                /** 本地服务器资源留在 GeckoView 内，外部链接交给系统浏览器 */
                override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                    val url = request.uri

                    // data: URI 不在此处拦截：导出由 contentDelegate.onExternalResponse 处理

                    if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
                        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                    }

                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        openInSystemBrowser(url)
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }

                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }

                /** window.open 或 target="_blank"：交给系统浏览器，不创建新 Session */
                override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                    openInSystemBrowser(uri)
                    // 返回 null 表示已由原生接管
                    return GeckoResult.fromValue(null)
                }
            }

            contentDelegate = object : GeckoSession.ContentDelegate {
                /** data: URI 形式的下载（游戏导出存档）在此接管 */
                override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                    if (response.uri.startsWith("data:")) {
                        exportDataUri(response.uri, response.headers["content-type"])
                    }
                }

                /**
                 * 页面进入或退出全屏。bridgePatch.js 会把游戏的 Tauri 全屏请求翻译成
                 * 标准 Fullscreen API，从而触发此回调。
                 */
                override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                    setWebviewFullscreen(fullScreen)
                }

                // 回传通道：bridgePatch.js 把页面世界的意图写入标题。
                // 不使用 runtime.sendNativeMessage：实测其 Promise 静默 reject，不触发 MessageDelegate；
                // 而 onTitleChange 稳定可达。
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    val payload = title?.removePrefix(GD_TITLE_PREFIX) ?: return
                    if (payload == title) return // 不是本移植的消息
                    when (payload.substringBefore(' ')) {
                        "fullscreen" -> setWebviewFullscreen(payload.substringAfter(' ').toBoolean())
                        "exportName" -> pendingExportName = payload.substringAfter(' ')
                        "openDataFolder" -> openGpNextFolder()
                    }
                }
            }

            open(geckoRuntime)
        }

        geckoView.setSession(geckoSession)
        geckoSession.loadUri("http://127.0.0.1:$serverPort/index.html")

        setupBackNavigation()
    }

    private fun exportDataUri(dataUri: String, contentType: String?) {
        val parts = dataUri.split(",")
        if (parts.size < 2) return
        pendingExport = Uri.decode(parts.subList(1, parts.size).joinToString(",")).toByteArray()

        // 交由系统保存对话框决定位置与文件名
        val fallbackType = contentType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val name = pendingExportName ?: suggestFileName(fallbackType)
        pendingExportName = null
        // 文件名通常已自带扩展名（由游戏给出），mime 必须与之匹配，
        // 否则 SAF 会按 mime 再追加一个后缀（例如 .json 变成 .json.txt）
        val type = mimeFromExtension(name) ?: fallbackType

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            this.type = type
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, EXPORT_SAVE_RESULT_CODE)
    }

    private fun saveExportTo(uri: Uri, bytes: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw java.io.IOException("openOutputStream returned null")
            }.isSuccess
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@GameActivity,
                    if (ok) R.string.export_done else R.string.export_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** 兜底文件名：游戏未给出名字时按类型和时间生成 */
    private fun suggestFileName(mimeType: String): String {
        val time = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return "gardendless_$time.${mimeToExtension(mimeType)}"
    }

    private fun mimeToExtension(mimeType: String): String =
        when (val type = mimeType.substringBefore(';').trim()) {
            "application/json" -> "json"
            "text/plain" -> "txt"
            "application/octet-stream" -> "bin"
            else -> type.substringAfter('/').takeIf { it.all(Char::isLetterOrDigit) } ?: "bin"
        }

    private fun mimeFromExtension(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it != fileName } ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
    }

    /**
     * 切换画面满屏 / 比例适配，并持久化该状态供下次启动沿用。
     */
    private fun setWebviewFullscreen(enabled: Boolean) {
        if (!::aspectContainer.isInitialized) return
        aspectContainer.fullscreen = enabled
        prefs.edit().putBoolean(PREF_FULLSCREEN, enabled).apply()
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

    /** 首次安装或版本号变化时，把 assets 中的游戏包解压到 filesDir */
    private fun checkAndExtractAssets(currentVersion: Int) {
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
                // 记录已解压版本，后续启动可直接跳过解压
                prefs.edit().putInt("extracted_version", currentVersion).apply()
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

    /** 沉浸式全屏：隐藏状态栏与导航栏、允许刘海区域，并保持屏幕常亮 */
    private fun setupFullScreen() {
        // 兜底隐藏 ActionBar
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
    private val EXPORT_SAVE_RESULT_CODE = 102

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            EXPORT_SAVE_RESULT_CODE -> {
                val bytes = pendingExport
                pendingExport = null
                // 用户取消保存时 data 为 null，直接丢弃暂存内容
                val uri = data?.data
                if (bytes != null && uri != null) saveExportTo(uri, bytes)
            }
            FILE_CHOOSER_RESULT_CODE -> handleFileChooserResult(resultCode, data)
        }
    }

    private fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        if (fileCallback == null) return
        val prompt = currentFilePrompt ?: return
        val originalUri = if (resultCode == RESULT_OK) data?.data else null

        if (originalUri != null) {
            // GeckoView 只接受 file: URI，content: 需先复制到私有目录
            val fileUri = if ("file".equals(originalUri.scheme, ignoreCase = true)) {
                originalUri
            } else {
                toFileUri(this, originalUri)
            }

            if (fileUri != null) {
                fileCallback?.complete(prompt.confirm(this, fileUri))
            } else {
                fileCallback?.complete(prompt.dismiss())
            }
        } else {
            fileCallback?.complete(prompt.dismiss())
        }

        fileCallback = null
        currentFilePrompt = null
    }

    /** 把 content: URI 的内容复制到私有目录，返回对应的 file: URI */
    private fun toFileUri(context: android.content.Context, uri: Uri): Uri? {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            // 临时文件落在 cacheDir，由系统按缓存回收
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
    /** 调用系统浏览器打开外部链接 */
    private fun openInSystemBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("GeckoView", "无法打开系统浏览器: ${e.message}")
        }
    }

    // ── gp-next 数据目录 I/O ──
    // 调用方是 bridgePatch.js 注入到页面世界的钩子：读取走 /gp-next 虚拟路径，
    // 元数据与写操作走 /__gdnext/fs/* 端点。传入路径为相对 AppData 的路径，
    // 例如 gp-next/packs/Foo/pack.json。

    /** 从查询参数 path 取出 gp-next 相对路径 */
    private fun ApplicationCall.gpNextQueryPath(): String =
        request.queryParameters["path"].orEmpty()

    /** 以 JSON 布尔值应答，供页面侧直接 res.json() 解析 */
    private suspend fun ApplicationCall.respondBool(value: Boolean) =
        respondText(value.toString(), ContentType.Application.Json)

    /**
     * 把 gp-next 相对路径解析为 filesDir 下的真实文件。
     * 越界或非法输入一律收敛到数据根目录，避免逃逸到 filesDir 的其他位置。
     */
    private fun gpNextFile(rawPath: String): File {
        val root = gpNextDir.canonicalFile
        val rootPrefix = root.path + File.separator
        // 页面侧应传相对路径；若误传带 origin 的绝对 URL，此处取其路径部分
        val withoutScheme = rawPath.substringAfter("://", rawPath)
        val relative = if (withoutScheme == rawPath) {
            withoutScheme
        } else {
            withoutScheme.substringAfter('/', "")
        }
        val normalized = relative.replace('\\', '/').trimStart('/')
        val candidate = File(filesDir, normalized).canonicalFile
        return if (candidate.path == root.path || candidate.path.startsWith(rootPrefix)) candidate else root
    }

    /** 返回 [{name, isFile, isDirectory, isSymlink}] 形式的 JSON；目录不存在时返回空数组 */
    private fun gpNextReadDirJson(rawPath: String): String {
        val entries = JSONArray()
        gpNextFile(rawPath).listFiles()?.forEach { file ->
            entries.put(JSONObject().apply {
                put("name", file.name)
                put("isFile", file.isFile)
                put("isDirectory", file.isDirectory)
                // 内部存储不涉及符号链接，固定为 false；需要真实判断时见 gpNextStatJson
                put("isSymlink", false)
            })
        }
        return entries.toString()
    }

    private fun gpNextExists(rawPath: String): Boolean = gpNextFile(rawPath).exists()

    /** mkdirs() 在目录已存在时返回 false，因此以 isDirectory 判断最终结果 */
    private fun gpNextMkdir(rawPath: String): Boolean {
        val dir = gpNextFile(rawPath)
        dir.mkdirs()
        return dir.isDirectory
    }

    private fun gpNextRemove(rawPath: String): Boolean {
        val file = gpNextFile(rawPath)
        // 仅用于清理 __gpn_edits，禁止删除数据根
        if (file.canonicalFile == gpNextDir.canonicalFile) return false
        return file.deleteRecursively()
    }

    /** 重命名；源文件不存在，或任一参数指向数据根时返回 false */
    private fun gpNextRename(oldRawPath: String, newRawPath: String): Boolean {
        val src = gpNextFile(oldRawPath)
        if (!src.exists()) return false
        val dst = gpNextFile(newRawPath)
        if (src.canonicalFile == gpNextDir.canonicalFile || dst.canonicalFile == gpNextDir.canonicalFile) {
            return false
        }
        dst.parentFile?.mkdirs()
        return src.renameTo(dst)
    }

    /** 写入二进制内容，父目录不存在时自动创建 */
    private fun gpNextWriteBytes(rawPath: String, bytes: ByteArray): Boolean = runCatching {
        val file = gpNextFile(rawPath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }.isSuccess

    /**
     * 生成 stat / lstat 的结果，字段与 @tauri-apps/plugin-fs 的 FileInfo 一致。
     * dist-js 会逐字段读取，其中 mtime/atime 必须是毫秒时间戳或 null，且字段不可缺失。
     * 使用 Os.lstat 而非 File，以便识别符号链接——新版包快照会拒绝含符号链接的包。
     * 路径不存在时返回 null，由调用方转换为 404，与 Tauri 的 stat 失败语义一致。
     */
    private fun gpNextStatJson(rawPath: String): String? = runCatching {
        val file = gpNextFile(rawPath)
        val st = Os.lstat(file.absolutePath)
        val type = st.st_mode and OsConstants.S_IFMT
        JSONObject().apply {
            put("isFile", type == OsConstants.S_IFREG)
            put("isDirectory", type == OsConstants.S_IFDIR)
            put("isSymlink", type == OsConstants.S_IFLNK)
            put("size", st.st_size)
            // Os.lstat 返回秒，而 FileInfo 使用毫秒
            put("mtime", st.st_mtime * 1000L)
            put("atime", st.st_atime * 1000L)
            // Android 无法获取 birthtime，此处以 ctime 近似
            put("birthtime", st.st_ctime * 1000L)
            put("readonly", !file.canWrite())
            put("fileAttributes", 0)
            put("dev", st.st_dev)
            put("ino", st.st_ino)
            put("mode", st.st_mode)
            put("nlink", st.st_nlink)
            put("uid", st.st_uid)
            put("gid", st.st_gid)
            put("rdev", st.st_rdev)
            put("blksize", st.st_blksize)
            put("blocks", st.st_blocks)
        }.toString()
    }.getOrNull()

    /**
     * 以系统文件管理器打开 gp-next 数据目录（即 DocumentsProvider 的 gpnext 根）。
     * 对应游戏 patcher 页的「打开补丁文件夹」。
     */
    private fun openGpNextFolder() {
        val authority = "$packageName.documents"
        val rootUri = DocumentsContract.buildRootUri(authority, GameDocumentsProvider.GP_NEXT_ROOT_ID)
        val initialUri =
            DocumentsContract.buildDocumentUri(authority, "${GameDocumentsProvider.GP_NEXT_ROOT_ID}:")
        val intent = Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setDataAndType(rootUri, DocumentsContract.Root.MIME_TYPE_ITEM)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "无法打开 gp-next 数据目录", e)
            Toast.makeText(this, R.string.documents_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
}