package com.antigravityremote.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var popupContainer: FrameLayout
    private var offlineLayout: View? = null
    private var loadingLayout: View? = null
    private var isOffline = false

    private val TARGET_URL = "https://antigravity.google.com/"

    // For file uploads
    private var uploadMessage: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val results = if (data?.data != null) {
                arrayOf(data.data!!)
            } else {
                null
            }
            uploadMessage?.onReceiveValue(results)
        } else {
            uploadMessage?.onReceiveValue(null)
        }
        uploadMessage = null
    }

    // For auto-reconnect
    private lateinit var connectivityManager: ConnectivityManager
    @Volatile private var isNetworkAvailable = true
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            super.onLost(network)
            isNetworkAvailable = false
            runOnUiThread {
                showOfflineScreen()
            }
        }
        
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                isNetworkAvailable = true
                if (isOffline) {
                    runOnUiThread {
                        val currentUrl = webView.url
                        if (currentUrl.isNullOrEmpty() || currentUrl == "about:blank") {
                            webView.loadUrl(TARGET_URL)
                        } else {
                            webView.reload()
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // We will apply padding to avoid content overlapping with system bars and keyboard
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            val extraPaddingTopPx = (5 * resources.displayMetrics.density).toInt()
            view.setPadding(insets.left, insets.top + extraPaddingTopPx, insets.right, insets.bottom)
            windowInsets
        }

        // Create popup container for OAuth windows
        popupContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Create main WebView programmatically
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        configureWebView(webView)

        // Assemble the view hierarchy
        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(webView)
            addView(popupContainer)
        }
        
        // Add fake splash screen/loading layout
        loadingLayout = layoutInflater.inflate(R.layout.layout_loading, rootLayout, false)
        rootLayout.addView(loadingLayout)
        
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val hasSeenOnboarding = prefs.getBoolean("has_seen_onboarding", false)

        var onboardingLayout: View? = null
        if (!hasSeenOnboarding) {
            onboardingLayout = layoutInflater.inflate(R.layout.activity_onboarding, rootLayout, false)
            
            onboardingLayout.findViewById<android.widget.Button>(R.id.btnContinue).setOnClickListener {
                prefs.edit().putBoolean("has_seen_onboarding", true).apply()
                onboardingLayout?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                    rootLayout.removeView(onboardingLayout)
                }?.start()
            }

            onboardingLayout.findViewById<android.widget.TextView>(R.id.setupLink).setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://antigravity.google/docs/remote-control/")))
            }

            val disclaimerTextView = onboardingLayout.findViewById<android.widget.TextView>(R.id.disclaimerText)
            val fullText = "This app is an unofficial open-source wrapper of antigravity.google.com. Your Google sign-in is handled directly by Google. This app does not store or access your credentials."
            val spannableString = android.text.SpannableString(fullText)
            val linkStart = fullText.indexOf("antigravity.google.com")
            val linkEnd = linkStart + "antigravity.google.com".length

            val clickableSpan = object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://antigravity.google.com")))
                }
            }

            spannableString.setSpan(clickableSpan, linkStart, linkEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannableString.setSpan(android.text.style.ForegroundColorSpan(0xFF007ACC.toInt()), linkStart, linkEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            disclaimerTextView.text = spannableString
            disclaimerTextView.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            onboardingLayout.setOnTouchListener { _, _ -> true }

            rootLayout.addView(onboardingLayout)
        }

        // Add offline layout
        offlineLayout = layoutInflater.inflate(R.layout.layout_offline, rootLayout, false)
        offlineLayout?.visibility = View.GONE
        offlineLayout?.findViewById<android.widget.Button>(R.id.btnRetry)?.setOnClickListener {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val hasInternet = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            
            if (hasInternet) {
                Toast.makeText(this@MainActivity, "Reconnecting...", Toast.LENGTH_SHORT).show()
                val currentUrl = webView.url
                if (currentUrl.isNullOrEmpty() || currentUrl == "about:blank") {
                    webView.loadUrl(TARGET_URL)
                } else {
                    webView.reload()
                }
            } else {
                Toast.makeText(this@MainActivity, "Still no connection...", Toast.LENGTH_SHORT).show()
            }
        }
        rootLayout.addView(offlineLayout)
        
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (onboardingLayout?.parent != null) {
                    finish()
                } else if (popupContainer.childCount > 0) {
                    val topPopup = popupContainer.getChildAt(popupContainer.childCount - 1) as WebView
                    if (topPopup.canGoBack()) {
                        topPopup.goBack()
                    } else {
                        popupContainer.removeView(topPopup)
                        topPopup.destroy()
                    }
                } else {
                    val currentUrl = webView.url ?: ""
                    val uri = try { Uri.parse(currentUrl) } catch (e: Exception) { null }
                    val isHomePage = uri?.host == "antigravity.google.com" && (uri.path.isNullOrEmpty() || uri.path == "/")
                    
                    if (isHomePage) {
                        finish()
                    } else if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        })

        setContentView(rootLayout)

        val loadTask = Runnable {
            if (savedInstanceState != null) {
                webView.restoreState(savedInstanceState)
            } else {
                webView.loadUrl(TARGET_URL)
            }
        }
        
        if (!hasSeenOnboarding && onboardingLayout != null) {
            onboardingLayout.post(loadTask)
        } else {
            loadTask.run()
        }
    }

    private fun showOfflineScreen() {
        isOffline = true
        webView.visibility = View.GONE
        offlineLayout?.visibility = View.VISIBLE
    }

    private fun hideOfflineScreen() {
        isOffline = false
        offlineLayout?.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(targetWebView: WebView) {
        targetWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true

            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true

            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT

            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            useWideViewPort = true
            loadWithOverviewMode = true

            val defaultUA = userAgentString
            userAgentString = defaultUA
                .replace("; wv", "")
                .replace("Version/4.0 ", "")
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(targetWebView.settings, true)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(targetWebView.settings, WebSettingsCompat.FORCE_DARK_ON)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(targetWebView, true)
        }

        targetWebView.webViewClient = object : WebViewClient() {
            private var isErrorLoading = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isErrorLoading = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!isErrorLoading && isOffline && isNetworkAvailable) {
                    hideOfflineScreen()
                }
                loadingLayout?.let { layout ->
                    layout.animate().alpha(0f).setDuration(300).withEndAction {
                        (layout.parent as? ViewGroup)?.removeView(layout)
                        loadingLayout = null
                    }.start()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                val host = url.host ?: return false

                if (host.contains("google.") || host.endsWith("youtube.com")) {
                    return false
                }

                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (e: Exception) {
                    return false
                }
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    isErrorLoading = true
                    showOfflineScreen()
                }
            }
            
            // For older devices or specific error types not caught by the newer method
            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                isErrorLoading = true
                showOfflineScreen()
            }
        }

        targetWebView.webChromeClient = object : WebChromeClient() {
            // Handle file uploads
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                uploadMessage?.onReceiveValue(null)
                uploadMessage = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }

                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: Exception) {
                    uploadMessage = null
                    return false
                }
                return true
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val newWebView = WebView(this@MainActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                
                configureWebView(newWebView)
                popupContainer.addView(newWebView)

                val transport = resultMsg?.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                super.onCloseWindow(window)
                popupContainer.removeView(window)
                window?.destroy()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        CookieManager.getInstance().flush()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }

    override fun onDestroy() {
        webView.destroy()
        for (i in 0 until popupContainer.childCount) {
            (popupContainer.getChildAt(i) as? WebView)?.destroy()
        }
        popupContainer.removeAllViews()
        super.onDestroy()
    }
}
