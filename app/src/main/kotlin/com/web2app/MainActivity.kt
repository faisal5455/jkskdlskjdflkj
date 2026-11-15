package com.web2app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.web2app.data.AppRepository
import com.web2app.handlers.PermissionsHandler
import com.web2app.models.parseAppConfig

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: View
    private lateinit var noInternetView: View

    /** Holder for the configured page loader; null/false means use the default spinner. */
    private var pageLoaderHolder: View? = null
    private var useCustomPageLoader = false

    private lateinit var permissionsHandler: PermissionsHandler
    private var connectivityManager: ConnectivityManager? = null

    /**
     * Serves files packaged in assets/www over https://appassets.androidplatform.net,
     * giving bundled HTML/ZIP sites a real web origin — ES modules, fetch and CORS
     * all work, which they do not under a plain file:// load.
     */
    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        const val EXTRA_APP_ID = "extra_app_id"
        private const val DEFAULT_THEME = 0xFF5B5BD6.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Preview mode: load the app being previewed from the builder's storage.
        // Falls back to the bundled assets/app_settings.json when launched standalone.
        val appId = intent.getStringExtra(EXTRA_APP_ID)
        val isPreview = appId != null
        appConfig = if (isPreview) {
            AppRepository(this).getApp(appId!!) ?: appConfig
        } else {
            parseAppConfig(readJson(this, "app_settings.json"))
        }

        // Setup permissions handler (must be created before setContentView for ActivityResult)
        permissionsHandler = PermissionsHandler(this)

        setContentView(R.layout.activity_main)
        if (appConfig.fullScreen) {
            // No system bars to sit under, so skip the insets/scrim entirely.
            enterFullScreen()
            findViewById<View>(R.id.statusBarScrim).visibility = View.GONE
        } else {
            applySystemBarInsets(R.id.mainRoot, R.id.statusBarScrim)
            applyStatusBarColor()
        }

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        noInternetView = findViewById(R.id.noInternetView)

        // Show the preview close button (faint eye behind a clear X) only when
        // previewing an app from the builder; tapping it dismisses the preview.
        findViewById<View>(R.id.previewControls).apply {
            visibility = if (isPreview) View.VISIBLE else View.GONE
            setOnClickListener { finish() }
        }

        setupPageLoader()
        setupWebView()
        setupConnectivity()
        requestConfiguredPermissions()

        swipeRefresh.isEnabled = appConfig.pullRefresh
        swipeRefresh.setOnRefreshListener { webView.reload() }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            loadCurrentUrl()
        }
    }

    /** Re-hide the bars after they were revealed by a swipe or another window. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && appConfig.fullScreen) enterFullScreen()
    }

    // ─── Page loader ─────────────────────────────────────────────────────────

    /**
     * Builds the configured in-page loader into [R.id.pageLoaderHolder]. When the page
     * loader isn't enabled we fall back to the default centered [progressBar].
     */
    private fun setupPageLoader() {
        val holder = findViewById<FrameLayout>(R.id.pageLoaderHolder)
        pageLoaderHolder = holder
        val pl = appConfig.pageLoader
        val theme = safeParseColor(appConfig.themeColor, DEFAULT_THEME)
        if (!pl.enabled) {
            useCustomPageLoader = false
            // Tint the default spinner with the configured theme colour too.
            (progressBar as? android.widget.ProgressBar)?.indeterminateTintList =
                android.content.res.ColorStateList.valueOf(theme)
            return
        }
        val icon = Base64ImageUtil.base64ToBitmap(appConfig.appIcon)
        val opts = PageLoaderViews.Opts(
            card = pl.card,
            cardColor = safeParseColor(pl.cardColor, Color.WHITE),
            cardOpacity = pl.cardOpacity,
            cardRadius = pl.cardRadius,
            gap = pl.gap,
            loaderWidth = pl.loaderWidth,
            loaderThickness = pl.loaderThickness
        )
        holder.removeAllViews()
        holder.addView(PageLoaderViews.create(this, pl.style, icon, theme, opts))
        useCustomPageLoader = true
    }

    /** Shows/hides whichever loader is in use (configured page loader or the default). */
    private fun showLoading(show: Boolean) {
        val target = if (useCustomPageLoader) pageLoaderHolder else progressBar
        target?.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ─── WebView ───────────────────────────────────────────────────────────────

    @Suppress("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            // Allow mixed content (http resources on https pages) – mirrors reference Webview.kt
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            // Custom user-agent matching the reference
            userAgentString =
                "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        // Enable cookies (including third-party) – mirrors reference Webview.kt
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(webView, true)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                // tel:, mailto:, whatsapp:, intent:, Google Maps, wa.me … belong to another
                // app; the WebView would show ERR_UNKNOWN_URL_SCHEME or a marketing page.
                if (ExternalLinks.isExternal(request.url, Uri.parse(appConfig.websiteURL).host)) {
                    ExternalLinks.open(this@MainActivity, request.url.toString()) { view.loadUrl(it) }
                    return true
                }
                // Returning false lets the WebView load it — calling loadUrl here as well
                // used to load every link twice.
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                showLoading(true)
            }

            override fun onPageFinished(view: WebView, url: String) {
                showLoading(false)
                swipeRefresh.isRefreshing = false
            }
        }

        // WebChromeClient with popup-window support – mirrors reference Webview.kt onCreateWindow
        webView.webChromeClient = object : WebChromeClient() {
            // Camera/mic (getUserMedia) and location requests from the page.
            override fun onPermissionRequest(request: PermissionRequest) =
                permissionsHandler.onWebPermissionRequest(request)

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: GeolocationPermissions.Callback?
            ) = permissionsHandler.onWebGeolocationRequest(origin, callback)

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val ctx = view?.context ?: return false

                val popupWebView = WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        setSupportMultipleWindows(true)
                        javaScriptCanOpenWindowsAutomatically = true
                    }
                    webChromeClient = permissionsHandler.webChromeClient()
                }

                val popupDialog = android.app.Dialog(ctx).apply {
                    setContentView(popupWebView)
                    window?.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                // Non-web links in the popup (Google Maps' intent:// redirect, tel:,
                // whatsapp:, …) go to their app instead of an ERR_UNKNOWN_URL_SCHEME page.
                popupWebView.webViewClient = ExternalLinks.popupClient(this@MainActivity, popupDialog)
                popupDialog.show()

                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popupWebView
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    private fun loadCurrentUrl() {
        webView.loadUrl(appConfig.websiteURL)
    }

    // ─── Network Connectivity (mirrors connectivityState / observeConnectivityAsFlow) ──

    private fun setupConnectivity() {
        // Apps whose content is bundled inside the APK (file:///android_asset/...)
        // work with no connection at all — never show them the offline screen.
        if (isOfflineContent()) {
            showWebContent()
            return
        }

        connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Show correct initial state
        if (isNetworkConnected()) showWebContent() else showNoInternet()

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { showWebContent() }
            }

            override fun onLost(network: Network) {
                runOnUiThread { showNoInternet() }
            }
        }

        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    /** True when the app serves a site packaged into its own assets. */
    private fun isOfflineContent(): Boolean {
        val url = appConfig.websiteURL
        return url.startsWith("file://") || url.startsWith("content://") ||
            url.startsWith("https://appassets.androidplatform.net")
    }

    private fun isNetworkConnected(): Boolean {
        val cm = connectivityManager ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun showWebContent() {
        swipeRefresh.visibility = View.VISIBLE
        noInternetView.visibility = View.GONE
    }

    private fun showNoInternet() {
        swipeRefresh.visibility = View.GONE
        noInternetView.visibility = View.VISIBLE
    }

    // ─── Permissions (mirrors PermissionsWrapper composable in reference) ──────

    private fun requestConfiguredPermissions() {
        val perms = appConfig.permissions
        val needsLocation = perms.contains(0)
        val needsFileCamera = perms.contains(1)
        val needsAudioVideo = perms.contains(2)

        when {
            needsLocation -> permissionsHandler.requestLocationPermissions {
                if (needsFileCamera) {
                    permissionsHandler.requestFileAndCameraPermissions {
                        if (needsAudioVideo) permissionsHandler.requestAudioVideoPermissions()
                    }
                } else if (needsAudioVideo) {
                    permissionsHandler.requestAudioVideoPermissions()
                }
            }
            needsFileCamera -> permissionsHandler.requestFileAndCameraPermissions {
                if (needsAudioVideo) permissionsHandler.requestAudioVideoPermissions()
            }
            needsAudioVideo -> permissionsHandler.requestAudioVideoPermissions()
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun safeParseColor(hex: String, default: Int): Int {
        return try {
            if (hex.isBlank()) default else Color.parseColor(hex)
        } catch (e: Exception) {
            default
        }
    }

    /**
     * Paints the status-bar scrim with the configured theme colour (the layout's
     * hardcoded colour was ignoring the setting), and flips the status-bar icons to
     * dark on a light theme for legibility.
     */
    private fun applyStatusBarColor() {
        val theme = safeParseColor(appConfig.themeColor, DEFAULT_THEME)
        findViewById<View>(R.id.statusBarScrim).setBackgroundColor(theme)
        val isLight = androidx.core.graphics.ColorUtils.calculateLuminance(theme) > 0.5
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = isLight
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
    }
}
