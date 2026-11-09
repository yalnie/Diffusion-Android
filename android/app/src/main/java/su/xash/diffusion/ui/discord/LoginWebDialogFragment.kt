package su.xash.diffusion.ui.discord

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import su.xash.diffusion.R
import su.xash.diffusion.discord.DiscordAuthManager

class LoginWebDialogFragment : DialogFragment() {

    interface Callback {
        fun onLoginSuccess(token: String)
    }

    private var callback: Callback? = null
    private var webView: WebView? = null
    private var progressBar: LinearProgressIndicator? = null
    private var errorContainer: View? = null
    private var tokenFound = false

    fun setCallback(cb: Callback) {
        callback = cb
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(null)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_discord_login, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.loginToolbar)
        toolbar.setNavigationOnClickListener { dismiss() }

        progressBar = view.findViewById(R.id.loginProgressBar)
        errorContainer = view.findViewById(R.id.errorContainer)
        val wv = view.findViewById<WebView>(R.id.loginWebView)
        webView = wv

        view.findViewById<Button>(R.id.retryButton)?.setOnClickListener {
            errorContainer?.visibility = View.GONE
            wv.visibility = View.VISIBLE
            wv.loadUrl("https://discord.com/login")
        }

        view.findViewById<Button>(R.id.enterTokenManualButton)?.setOnClickListener {
            dismiss()
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar?.progress = newProgress
                if (newProgress >= 100) {
                    progressBar?.visibility = View.GONE
                } else {
                    progressBar?.visibility = View.VISIBLE
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString()
                checkUrlAndExtractToken(url)
                return false
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                checkUrlAndExtractToken(url)
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkUrlAndExtractToken(url)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    wv.visibility = View.GONE
                    errorContainer?.visibility = View.VISIBLE
                }
            }
        }

        wv.loadUrl("https://discord.com/login")
    }

    private fun checkUrlAndExtractToken(url: String?) {
        if (tokenFound) return
        if (url != null && (url.contains("/app") || url.contains("/channels/@me") || url.contains("/login/phone"))) {
            // First attempt: Extract from LevelDb
            val token = DiscordAuthManager.get(requireContext()).extractTokenFromLevelDb()
            if (!token.isNullOrBlank()) {
                onTokenExtracted(token)
                return
            }

            // Second attempt: Execute JavaScript to extract token
            val script = "(function() {" +
                    "try {" +
                    "  var m = [];" +
                    "  (webpackChunkdiscord_app.push([[''],{},function(e){for(var c in e.c)m.push(e.c[c]);}]));" +
                    "  for (var i = 0; i < m.length; i++) {" +
                    "    if (m[i] && m[i].exports && m[i].exports.default && typeof m[i].exports.default.getToken === 'function') {" +
                    "      var t = m[i].exports.default.getToken();" +
                    "      if (t) return t;" +
                    "    }" +
                    "  }" +
                    "  return '';" +
                    "} catch(e) { return ''; }" +
                    "})();"

            webView?.evaluateJavascript(script) { result ->
                if (!tokenFound && !result.isNullOrBlank() && result != "\"\"" && result != "null") {
                    val cleanToken = result.trim('"')
                    if (cleanToken.length >= 50) {
                        onTokenExtracted(cleanToken)
                    }
                }
            }

            // Delayed check in case LevelDb write was asynchronous
            view?.postDelayed({
                if (!tokenFound && isAdded) {
                    val retryToken = DiscordAuthManager.get(requireContext()).extractTokenFromLevelDb()
                    if (!retryToken.isNullOrBlank()) {
                        onTokenExtracted(retryToken)
                    }
                }
            }, 1500)
        }
    }

    private fun onTokenExtracted(token: String) {
        if (tokenFound) return
        tokenFound = true
        DiscordAuthManager.get(requireContext()).token = token
        Toast.makeText(requireContext(), R.string.discord_token_saved, Toast.LENGTH_SHORT).show()
        callback?.onLoginSuccess(token)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webView?.destroy()
        webView = null
        progressBar = null
        errorContainer = null
    }
}
