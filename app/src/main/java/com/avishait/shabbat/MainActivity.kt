package com.avishait.shabbat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val notifRequestCode = 7001

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
            setSupportZoom(false)
            displayZoomControls = false
            builtInZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.addJavascriptInterface(Bridge(), "ShabbatNative")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) { callback.invoke(origin, true, false) }
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

        webView.loadUrl("file:///android_asset/shabbat.html")

        if (ShabbatCore.notifEnabled(this)) NotificationScheduler.rescheduleAll(this)
        WidgetUpdater.updateAll(this)
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
