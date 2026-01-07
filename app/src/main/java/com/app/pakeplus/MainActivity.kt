package com.app.pakeplus

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebView.WebViewTransport
import android.webkit.URLUtil
import androidx.activity.enableEdgeToEdge
// import android.view.Menu
// import android.view.WindowInsets
// import com.google.android.material.snackbar.Snackbar
// import com.google.android.material.navigation.NavigationView
// import androidx.navigation.findNavController
// import androidx.navigation.ui.AppBarConfiguration
// import androidx.navigation.ui.navigateUp
// import androidx.navigation.ui.setupActionBarWithNavController
// import androidx.navigation.ui.setupWithNavController
// import androidx.drawerlayout.widget.DrawerLayout
// import com.app.pakeplus.databinding.ActivityMainBinding
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.net.toUri
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

//    private lateinit var appBarConfiguration: AppBarConfiguration
//    private lateinit var binding: ActivityMainBinding

    private lateinit var webView: WebView
    private lateinit var gestureDetector: GestureDetectorCompat

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = packageInfo.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        Log.i(
            "AppStartup",
            "Build versionName=$versionName versionCode=$versionCode ts=${System.currentTimeMillis()}"
        )
        // parseJsonWithNative
        val config = parseJsonWithNative(this, "app.json")
        val fullScreen = config?.get("fullScreen") as? Boolean ?: false
        val debug = config?.get("debug") as? Boolean ?: false
        val userAgent = config?.get("userAgent") as? String ?: ""
        val webUrl = config?.get("webUrl") as? String ?: "https://pakeplus.cn/"
        // enable debug by chrome://inspect
        WebView.setWebContentsDebuggingEnabled(debug)
        // config fullscreen
        if (fullScreen) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            )
            window.setFlags(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
            )
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = lp
            } else {
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        )
            }
        }
        // 可以让内容视图的颜色延伸到屏幕边缘
        enableEdgeToEdge()
        setContentView(R.layout.single_main)
        // set system safe area
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.ConstraintLayout))
        { view, insets ->
            val systemBar = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBar.left, systemBar.top, systemBar.right, 0)
            insets
        }
        webView = findViewById<WebView>(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true       // 启用JS
            domStorageEnabled = true       // 启用DOM存储（Vue 需要）
            allowFileAccess = true         // 允许文件访问
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        webView
        // set user agent
        if (userAgent.isNotEmpty()) {
            webView.settings.userAgentString = userAgent
        }

        webView.settings.loadWithOverviewMode = true
        webView.settings.setSupportZoom(false)

        // clear cache
        webView.clearCache(true)

        // JS bridge for window.App.openBrowser / window.native.openOuter
        val jsBridge = JsBridge(this)
        webView.addJavascriptInterface(jsBridge, "App")
        webView.addJavascriptInterface(jsBridge, "native")

        // open download links in external browser
        webView.setDownloadListener { url, _, _, _, _ ->
            if (url.isNullOrBlank()) return@setDownloadListener
            val safeUrl = if (URLUtil.isNetworkUrl(url)) url else return@setDownloadListener
            Log.i("WebViewClient", "DownloadListener open external: $safeUrl")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
            } catch (e: Exception) {
                Log.e("WebViewClient", "Failed to open download url: $safeUrl", e)
            }
        }

        // inject js
        webView.webViewClient = MyWebViewClient(debug)

        // get web load progress
        webView.webChromeClient = MyChromeClient()

        // Setup gesture detector
        gestureDetector =
            GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false

                    val diffX = e2.x - e1.x
                    val diffY = e2.y - e1.y

                    // Only handle horizontal swipes
                    if (abs(diffX) > abs(diffY)) {
                        if (abs(diffX) > 100 && abs(velocityX) > 100) {
                            if (diffX > 0) {
                                // Swipe right - go back
                                if (webView.canGoBack()) {
                                    webView.goBack()
                                    return true
                                }
                            } else {
                                // Swipe left - go forward
                                if (webView.canGoForward()) {
                                    webView.goForward()
                                    return true
                                }
                            }
                        }
                    }
                    return false
                }
            })

        // Set touch listener for WebView
        webView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        // load webUrl or file:///android_asset/index.html
        webView.loadUrl(webUrl)

//        binding = ActivityMainBinding.inflate(layoutInflater)
//        setContentView(R.layout.single_main)

//        setSupportActionBar(binding.appBarMain.toolbar)

//        binding.appBarMain.fab.setOnClickListener { view ->
//            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
//                .setAction("Action", null)
//                .setAnchorView(R.id.fab).show()
//        }

//        val drawerLayout: DrawerLayout = binding.drawerLayout
//        val navView: NavigationView = binding.navView
//        val navController = findNavController(R.id.nav_host_fragment_content_main)

        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
//        appBarConfiguration = AppBarConfiguration(
//            setOf(
//                R.id.nav_home, R.id.nav_gallery, R.id.nav_slideshow
//            ), drawerLayout
//        )
//        setupActionBarWithNavController(navController, appBarConfiguration)
//        navView.setupWithNavController(navController)
    }


    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    fun parseJsonWithNative(context: Context, jsonFilePath: String): Map<String, Any>? {
        val jsonString = assets.open(jsonFilePath).bufferedReader().use { it.readText() }
        return try {
            val jsonObject = JSONObject(jsonString)
            // 提取字段
            val name = jsonObject.getString("name")
            val webUrl = jsonObject.getString("webUrl")
            val debug = jsonObject.getBoolean("debug")
            val userAgent = jsonObject.getString("userAgent")
            val fullScreen = jsonObject.getBoolean("fullScreen")
            // 返回键值对
            mapOf(
                "name" to name,
                "webUrl" to webUrl,
                "debug" to debug,
                "userAgent" to userAgent,
                "fullScreen" to fullScreen
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

//    override fun onCreateOptionsMenu(menu: Menu): Boolean {
//        // Inflate the menu; this adds items to the action bar if it is present.
//        menuInflater.inflate(R.menu.main, menu)
//        return true
//    }

//    override fun onSupportNavigateUp(): Boolean {
//        val navController = findNavController(R.id.nav_host_fragment_content_main)
//        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
//    }

    inner class MyWebViewClient(val debug: Boolean) : WebViewClient() {

        @Deprecated("Deprecated in Java", ReplaceWith("false"))
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            val url = url.toString()

            // 检查链接是否是 HTTP/HTTPS，如果是，则继续在 WebView 中加载
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return false // 返回 false，让 WebView 自己加载 URL
            }

            // --- 核心逻辑：处理外部应用链接 ---

            // 1. 检查是否是 Intent URI (e.g., intent://...)
            if (url.startsWith("intent://")) {
                try {
                    // 解析 Intent URI
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)

                    // 检查设备上是否有应用可以处理此 Intent
                    if (intent.resolveActivity(view?.context?.packageManager!!) != null) {
                        view.context?.startActivity(intent)
                        return true // 已经处理，阻止 WebView 加载
                    }

                    // 如果找不到能处理的应用，可以尝试打开备用 URL (如果 Intent 中有定义 fallback URL)
                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                    if (!fallbackUrl.isNullOrEmpty()) {
                        view.loadUrl(fallbackUrl)
                        return true // 加载备用 URL
                    }

                } catch (e: URISyntaxException) {
                    // 解析 Intent URI 失败
                    Log.e("WebViewClient", "Bad Intent URI: $url", e)
                } catch (e: ActivityNotFoundException) {
                    // 找不到匹配的 Activity (外部应用未安装)，此情况通常在 `resolveActivity` 后捕获
                    Log.e("WebViewClient", "No activity found to handle Intent: $url", e)
                    // 您也可以在这里加载一个 "未安装应用" 的提示页面
                }
                // 如果是 Intent 但无法处理（例如未安装应用），您可以选择返回 false 让 WebView 尝试加载（通常会失败）
                // 或者继续执行下面的 Scheme 检查
            }

            // 2. 检查是否是其他自定义 Scheme (e.g., weixin://, zhihu://)
            // 注意：Intent URI 是更通用和推荐的方式，但有些应用可能直接使用 Scheme。
            try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                // 必须检查是否有应用可以处理此 Intent，否则会导致崩溃
                if (intent.resolveActivity(view?.context?.packageManager!!) != null) {
                    view.context?.startActivity(intent)
                    return true // 已经处理，阻止 WebView 加载
                } else {
                    // 没有安装相应的应用
                    Log.w("WebViewClient", "External app not installed for: $url")
                    // 可以添加逻辑提示用户下载应用或打开相应的应用商店链接
                }
            } catch (e: Exception) {
                Log.e("WebViewClient", "Error starting external app: $url", e)
            }
            // 如果不是外部应用 Scheme，也不是 HTTP/HTTPS，则返回 false，让 WebView 处理
            return false
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
        }


        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            println("webView onReceivedError: ${error?.description}")
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (debug) {
                // vConsole
                val vConsole = assets.open("vConsole.js").bufferedReader().use { it.readText() }
                val openDebug = """var vConsole = new window.VConsole()"""
                view?.evaluateJavascript(vConsole + openDebug, null)
            }
            // inject js
            val injectJs = assets.open("custom.js").bufferedReader().use { it.readText() }
            view?.evaluateJavascript(injectJs, null)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val url = request?.url?.toString() ?: return false
            // For main-frame HTTP/HTTPS, keep in WebView
            if (url.startsWith("http://") || url.startsWith("https://")) {
                if (request?.isForMainFrame == true && isLikelyDownloadUrl(url)) {
                    Log.i("WebViewClient", "MainFrame download open external: $url")
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        Log.e("WebViewClient", "Failed to open download url: $url", e)
                    }
                    return true
                }
                return false
            }
            // Delegate non-http(s) handling to legacy path for external schemes
            Log.i("WebViewClient", "Override non-http(s): $url")
            return shouldOverrideUrlLoading(view, url)
        }
    }

    inner class MyChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            val url = view?.url
            println("wev view url:$url")
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            super.onShowCustomView(view, callback)
        }

        override fun onHideCustomView() {
            super.onHideCustomView()
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message?
        ): Boolean {
            if (view == null || resultMsg == null) return false
            Log.i("WebViewClient", "onCreateWindow gesture=$isUserGesture dialog=$isDialog")
            val transport = resultMsg.obj as? WebViewTransport ?: return false
            val childWebView = WebView(view.context)
            childWebView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    v: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val rawUrl = request?.url?.toString() ?: return true
                    val resolvedUrl = resolveUrl(view.url, rawUrl)
                    Log.i("WebViewClient", "TargetBlank open external: $resolvedUrl")
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resolvedUrl)))
                    } catch (e: Exception) {
                        Log.e("WebViewClient", "Failed to open target=_blank: $resolvedUrl", e)
                    }
                    return true
                }
            }
            transport.webView = childWebView
            resultMsg.sendToTarget()
            return true
        }
    }

    private fun isLikelyDownloadUrl(url: String): Boolean {
        val normalized = url.lowercase(Locale.US)
        if (normalized.contains("/downfile/") || normalized.contains("/download/")) {
            return true
        }
        return normalized.endsWith(".apk") ||
            normalized.endsWith(".zip") ||
            normalized.endsWith(".rar") ||
            normalized.endsWith(".7z") ||
            normalized.endsWith(".pdf")
    }

    private fun resolveUrl(baseUrl: String?, targetUrl: String): String {
        if (targetUrl.startsWith("http://") || targetUrl.startsWith("https://")) {
            return targetUrl
        }
        return try {
            if (baseUrl.isNullOrBlank()) {
                targetUrl
            } else {
                URI(baseUrl).resolve(targetUrl).toString()
            }
        } catch (e: Exception) {
            targetUrl
        }
    }

    class JsBridge(private val context: Context) {
        @JavascriptInterface
        fun openBrowser(url: String?) {
            openExternal(url, "openBrowser")
        }

        @JavascriptInterface
        fun openOuter(url: String?) {
            openExternal(url, "openOuter")
        }

        private fun openExternal(url: String?, source: String) {
            if (url.isNullOrBlank()) return
            val safeUrl = if (URLUtil.isNetworkUrl(url)) url else return
            Log.i("WebViewClient", "JsBridge $source open external: $safeUrl")
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
            } catch (e: Exception) {
                Log.e("WebViewClient", "JsBridge failed to open: $safeUrl", e)
            }
        }
    }
}
