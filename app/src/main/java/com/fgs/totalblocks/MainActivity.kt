package com.fgs.totalblocks

import android.app.Dialog
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

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
    }

    private fun setupMenuWebView() {
        menuWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    syncVibrationState()
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
                fun requestVibrationState() = runOnUiThread {
                    syncVibrationState()
                }
            }, "Android")

            loadUrl("file:///android_asset/menu.html")
        }
    }

    private fun syncVibrationState() {
        val state = gameView.isVibrationEnabled
        menuWebView?.evaluateJavascript("setToggleState($state)", null)
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
