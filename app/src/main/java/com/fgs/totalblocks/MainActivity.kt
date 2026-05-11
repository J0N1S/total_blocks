package com.fgs.totalblocks

import android.Manifest
import android.app.AlarmManager
import android.app.Dialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private var menuDialog: Dialog? = null
    private var menuWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        gameView = findViewById(R.id.gameView)

        gameView.onMenuClicked = {
            showMenu()
        }

        // Initialize WebView once
        setupMenuWebView()

        checkNotificationPermission()
        scheduleNotification()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun scheduleNotification() {
        val intent = Intent(this, NotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val interval = 60 * 1000L // 1 minute
        val triggerAtMillis = System.currentTimeMillis() + interval

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            interval,
            pendingIntent
        )
    }

    private fun setupMenuWebView() {
        menuWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    syncVibrationState()
                    syncSoundState()
                }
            }
            webChromeClient = WebChromeClient()

            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun closeMenu() = runOnUiThread {
                    menuDialog?.dismiss()
                }

                @android.webkit.JavascriptInterface
                fun newGame() = runOnUiThread {
                    menuDialog?.dismiss()
                    gameView.startNewGame()
                }

                @android.webkit.JavascriptInterface
                fun setVibrationEnabled(enabled: Boolean) = runOnUiThread {
                    gameView.isVibrationEnabled = enabled
                }

                @android.webkit.JavascriptInterface
                fun setSoundEnabled(enabled: Boolean) = runOnUiThread {
                    gameView.isSoundEnabled = enabled
                }

                @android.webkit.JavascriptInterface
                fun requestVibrationState() = runOnUiThread {
                    syncVibrationState()
                }

                @android.webkit.JavascriptInterface
                fun requestSoundState() = runOnUiThread {
                    syncSoundState()
                }
            }, "Android")

            loadUrl("file:///android_asset/menu.html")
        }
    }

    private fun syncVibrationState() {
        val state = gameView.isVibrationEnabled
        menuWebView?.evaluateJavascript("setToggleState('vibration', $state)", null)
    }

    private fun syncSoundState() {
        val state = gameView.isSoundEnabled
        menuWebView?.evaluateJavascript("setToggleState('sound', $state)", null)
    }

    private fun showMenu() {
        if (menuDialog == null) {
            menuDialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            menuWebView?.let {
                // Remove from previous parent if necessary
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                menuDialog?.setContentView(it)
            }
        }
        
        syncVibrationState()
        syncSoundState()
        menuDialog?.show()
        // Also trigger the menu open event in JS
        menuWebView?.evaluateJavascript("onMenuOpen()", null)
    }

    override fun onDestroy() {
        super.onDestroy()
        menuDialog?.dismiss()
        menuWebView?.destroy()
    }
}
