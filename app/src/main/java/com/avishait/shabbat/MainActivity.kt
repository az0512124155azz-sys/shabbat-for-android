package com.avishait.shabbat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.avishait.shabbat.widgets.WidgetUpdater
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val notifRequestCode = 7001
    private val locationRequestCode = 7002
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AlarmReceiver.ensureChannel(this)

        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowUniversalAccessFromFileURLs = true
            allowFileAccessFromFileURLs = true
            setSupportZoom(false)
            displayZoomControls = false
            builtInZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // Lock text scale to the page's own design regardless of the device's
            // system font-size setting. Without this, WebView multiplies every
            // font-size in shabbat.html by the OS accessibility font scale, which
            // blows past the fixed-width time boxes and breaks the layout.
            textZoom = 100
        }

        webView.addJavascriptInterface(Bridge(), "ShabbatNative")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                if (locationPermitted()) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        locationRequestCode
                    )
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("mailto:")) {
                    val intent = Intent(Intent.ACTION_SENDTO)
                    intent.data = Uri.parse(url)
                    startActivity(intent)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                injectState()
            }
        }

        loadContent()

        if (ShabbatCore.notifEnabled(this)) NotificationScheduler.rescheduleAll(this)
        WidgetUpdater.updateAll(this)
    }

    /**
     * Over-the-air content updates: load the cached copy of shabbat.html if a
     * newer one was downloaded, otherwise the bundled asset. Then quietly
     * fetch the latest version from GitHub for next launch, so small changes
     * (design tweaks, new cities) reach users without an app-store update.
     */
    private fun loadContent() {
        val cached = File(filesDir, "shabbat.html")
        if (cached.exists() && cached.length() > 10000) {
            webView.loadUrl("file://" + cached.absolutePath)
        } else {
            webView.loadUrl("file:///android_asset/shabbat.html")
        }
        Thread {
            try {
                val url = URL("https://raw.githubusercontent.com/az0512124155azz-sys/shabbat-for-android/main/app/src/main/assets/shabbat.html")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    val valid = body.length > 10000 &&
                            body.contains("hdr-title") &&
                            body.trimEnd().endsWith("</html>")
                    if (valid && (!cached.exists() || cached.readText(Charsets.UTF_8) != body)) {
                        val tmp = File(filesDir, "shabbat.html.tmp")
                        tmp.writeText(body, Charsets.UTF_8)
                        tmp.renameTo(cached)
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                // offline or fetch failed — keep current content
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) injectState()
    }

    /** Push native-side state (notification flag + tefillin map) into the page. */
    private fun injectState() {
        val notifOk = ShabbatCore.notifEnabled(this) && notificationsPermitted()
        val st = "{\"notif\":$notifOk,\"tef\":${ShabbatCore.tefillinMap(this)}}"
        webView.evaluateJavascript("window.nativeInit&&nativeInit($st)", null)
    }

    private fun notificationsPermitted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun locationPermitted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestNotifFlow() {
        if (!notificationsPermitted()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), notifRequestCode
            )
        } else grantNotif()
    }

    private fun grantNotif() {
        ShabbatCore.setNotifEnabled(this, true)
        NotificationScheduler.rescheduleAll(this)
        webView.evaluateJavascript("window.nativeNotifResult&&nativeNotifResult(true)", null)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notifRequestCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                grantNotif()
            } else {
                webView.evaluateJavascript("window.nativeNotifResult&&nativeNotifResult(false)", null)
            }
        } else if (requestCode == locationRequestCode) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoOrigin = null
            pendingGeoCallback = null
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun onCityChanged(json: String) {
            ShabbatCore.saveCity(this@MainActivity, json)
            NotificationScheduler.rescheduleAll(this@MainActivity)
            WidgetUpdater.updateAll(this@MainActivity)
        }

        @JavascriptInterface
        fun setTefillin(key: String, v: Boolean) {
            ShabbatCore.setTefillin(this@MainActivity, key, v)
            WidgetUpdater.updateAll(this@MainActivity)
        }

        @JavascriptInterface
        fun enableNotifications() {
            runOnUiThread { requestNotifFlow() }
        }

        @JavascriptInterface
        fun disableNotifications() {
            ShabbatCore.setNotifEnabled(this@MainActivity, false)
            NotificationScheduler.cancelAll(this@MainActivity)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
