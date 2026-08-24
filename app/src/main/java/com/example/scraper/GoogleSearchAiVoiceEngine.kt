package com.example.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class GoogleVoiceState {
    object Idle : GoogleVoiceState()
    object Initializing : GoogleVoiceState()
    object Ready : GoogleVoiceState()
    data class Listening(val recognizedText: String = "") : GoogleVoiceState()
    object Reconnecting : GoogleVoiceState()
    object CaptchaDetected : GoogleVoiceState()
    object LoginRequired : GoogleVoiceState()
    data class Error(val message: String) : GoogleVoiceState()
}

/**
 * High-performance Voice to Text Engine leveraging Google Gemini web (gemini.google.com/app)
 * with automatic fallback to Google AI Search (udm=50), stealth anti-detection,
 * and live text stream observation from Gemini's rich input editor.
 */
class GoogleSearchAiVoiceEngine(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val onChunkFinalized: (String) -> Unit,
    private val onInterimText: (String) -> Unit
) {
    private val tag = "GeminiVoiceEngine"
    private val mainHandler = Handler(Looper.getMainLooper())
    var webView: WebView? = null
        private set

    private val _voiceState = MutableStateFlow<GoogleVoiceState>(GoogleVoiceState.Initializing)
    val voiceState: StateFlow<GoogleVoiceState> = _voiceState.asStateFlow()

    private val _engineStatus = MutableStateFlow("Gemini AI (gemini.google.com/app) Initializing...")
    val engineStatus: StateFlow<String> = _engineStatus.asStateFlow()

    private val _isCaptchaShowing = MutableStateFlow(false)
    val isCaptchaShowing: StateFlow<Boolean> = _isCaptchaShowing.asStateFlow()

    private var isListeningActive = false
    private var lastExtractedText = ""
    private var isPageReady = false

    private val GEMINI_APP_URL = "https://gemini.google.com/app"
    private val GOOGLE_SEARCH_AI_URL = "https://www.google.com/search?q=&sourceid=chrome&ie=UTF-8&udm=50&aep=48&cud=0"

    init {
        mainHandler.post {
            initStealthEngine()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initStealthEngine() {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            webView = WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    allowFileAccess = false
                    allowContentAccess = false

                    userAgentString =
                        "Mozilla/5.0 (Linux; Android 14; Mobile; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                }

                cookieManager.setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(VoiceBridgeInterface(), "AndroidVoiceBridge")

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request?.grant(request.resources)
                        _engineStatus.value = "Microphone access granted to Gemini"
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        isPageReady = false
                        val cleanUrl = url ?: ""
                        if (cleanUrl.contains("gemini.google.com")) {
                            _engineStatus.value = "Connecting to gemini.google.com/app..."
                        } else if (cleanUrl.contains("accounts.google.com")) {
                            _engineStatus.value = "Google Login / Verification required"
                            _voiceState.value = GoogleVoiceState.LoginRequired
                        } else {
                            _engineStatus.value = "Connecting to Google AI Engine..."
                        }
                        injectStealthScript()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        isPageReady = true
                        val currentUrl = url ?: ""

                        if (currentUrl.contains("gemini.google.com")) {
                            _engineStatus.value = "Gemini Voice Engine Ready"
                            if (_voiceState.value !is GoogleVoiceState.CaptchaDetected) {
                                _voiceState.value = GoogleVoiceState.Ready
                            }
                        } else if (currentUrl.contains("accounts.google.com")) {
                            _engineStatus.value = "Google Login needed. Tap Security/Login dialog."
                        }

                        injectStealthScript()
                        injectGeminiVoiceBridge()
                        checkForCaptcha()

                        if (isListeningActive) {
                            triggerVoiceMicInPage()
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        val desc = error?.description?.toString() ?: "Network error"
                        _engineStatus.value = "Engine note: $desc"
                    }
                }

                loadUrl(GEMINI_APP_URL)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize WebView", e)
            _engineStatus.value = "Engine error: ${e.message}"
            _voiceState.value = GoogleVoiceState.Error(e.message ?: "Failed to initialize")
        }
    }

    private fun injectStealthScript() {
        val stealthJs = """
            (function() {
                try {
                    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                    Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
                    Object.defineProperty(navigator, 'languages', { get: () => ['uz-UZ', 'en-US', 'ru-RU', 'en'] });

                    window.chrome = {
                        runtime: {},
                        loadTimes: function() {},
                        csi: function() {},
                        app: {}
                    };

                    if (window.navigator.permissions && window.navigator.permissions.query) {
                        const originalQuery = window.navigator.permissions.query;
                        window.navigator.permissions.query = (parameters) => (
                            parameters.name === 'notifications' || parameters.name === 'microphone' ?
                            Promise.resolve({ state: 'granted' }) :
                            originalQuery(parameters)
                        );
                    }
                } catch(e) {}
            })();
        """.trimIndent()

        mainHandler.post {
            webView?.evaluateJavascript(stealthJs, null)
        }
    }

    private fun injectGeminiVoiceBridge() {
        val script = """
            (function() {
                try {
                    var cookieButtons = document.querySelectorAll('button, div[role="button"]');
                    for (var i = 0; i < cookieButtons.length; i++) {
                        var text = (cookieButtons[i].innerText || cookieButtons[i].textContent || '').toLowerCase();
                        if (text.includes('accept') || text.includes('agree') || text.includes('roziman') || text.includes('принять') || text.includes('qabul')) {
                            cookieButtons[i].click();
                            break;
                        }
                    }
                } catch(e) {}

                function extractGeminiInputText() {
                    var richInputs = document.querySelectorAll(
                        'rich-textarea .ql-editor, ' +
                        'div[contenteditable="true"], ' +
                        'div[role="textbox"], ' +
                        'rich-textarea p, ' +
                        'textarea[aria-label*="Gemini" i], ' +
                        'textarea[aria-label*="prompt" i], ' +
                        'textarea, input[name="q"]'
                    );

                    for (var i = 0; i < richInputs.length; i++) {
                        var el = richInputs[i];
                        var text = el.innerText || el.textContent || el.value || '';
                        text = text.trim();
                        if (text.length > 0 &&
                            !text.startsWith('Diktovka uchun') &&
                            !text.startsWith('Ask Gemini') &&
                            !text.startsWith('Savol bering') &&
                            !text.startsWith('Type a prompt')) {
                            return text;
                        }
                    }
                    return '';
                }

                if (!window.__geminiObserverInitialized) {
                    window.__geminiObserverInitialized = true;
                    var observer = new MutationObserver(function(mutations) {
                        var currentText = extractGeminiInputText();
                        if (currentText && window.AndroidVoiceBridge) {
                            window.AndroidVoiceBridge.onSpeechResult(currentText, false);
                        }
                    });
                    observer.observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true });
                }

                if (window.AndroidVoiceBridge) {
                    window.AndroidVoiceBridge.onBridgeReady();
                }
            })();
        """.trimIndent()

        mainHandler.post {
            webView?.evaluateJavascript(script, null)
        }
    }

    private fun triggerVoiceMicInPage() {
        if (!isPageReady) return

        val triggerMicJs = """
            (function() {
                try {
                    var activeListeningBtn = document.querySelector(
                        'button[aria-label*="Stop" i], ' +
                        'button[aria-label*="To\'xtatish" i], ' +
                        'button[aria-label*="Остановить" i], ' +
                        'div.pulsing-mic, ' +
                        '.is-listening'
                    );
                    if (activeListeningBtn && activeListeningBtn.offsetWidth > 0) {
                        return "ALREADY_LISTENING";
                    }

                    var micSelectors = [
                        'button[aria-label*="microphone" i]',
                        'button[aria-label*="Mikrofon" i]',
                        'button[aria-label*="микрофон" i]',
                        'button[aria-label*="Use microphone" i]',
                        'button[aria-label*="Dictate" i]',
                        'button[aria-label*="Diktovka" i]',
                        'button[data-test-id="mic-button"]',
                        'button.mic-button',
                        'button[jsname="j1wRcf"]'
                    ];

                    for (var s = 0; s < micSelectors.length; s++) {
                        var btn = document.querySelector(micSelectors[s]);
                        if (btn && btn.offsetWidth > 0) {
                            btn.click();
                            return "CLICKED_GEMINI_MIC_ARIA: " + micSelectors[s];
                        }
                    }

                    var matIcons = document.querySelectorAll('mat-icon');
                    for (var m = 0; m < matIcons.length; m++) {
                        var iconName = (matIcons[m].getAttribute('fonticon') || matIcons[m].innerText || matIcons[m].textContent || '').toLowerCase();
                        if (iconName.includes('mic')) {
                            var parentBtn = matIcons[m].closest('button, div[role="button"]');
                            if (parentBtn) {
                                parentBtn.click();
                                return "CLICKED_MAT_ICON_MIC";
                            }
                        }
                    }

                    var svgs = document.querySelectorAll('svg path');
                    for (var i = 0; i < svgs.length; i++) {
                        var d = svgs[i].getAttribute('d') || '';
                        if (d.indexOf('M12 14c') !== -1 || d.indexOf('M12 2a3') !== -1 || (d.startsWith('M12') && d.length > 20)) {
                            var svgBtn = svgs[i].closest('button, div[role="button"]');
                            if (svgBtn && svgBtn.offsetWidth > 0) {
                                svgBtn.click();
                                return "CLICKED_SVG_MIC";
                            }
                        }
                    }

                    var richInput = document.querySelector('rich-textarea .ql-editor, div[contenteditable="true"], textarea');
                    if (richInput) {
                        richInput.focus();
                        return "FOCUSED_INPUT";
                    }

                    return "NO_MIC_FOUND";
                } catch(e) {
                    return "ERROR: " + e.toString();
                }
            })();
        """.trimIndent()

        mainHandler.post {
            webView?.evaluateJavascript(triggerMicJs) { res ->
                Log.d(tag, "Trigger Gemini Mic Result: $res")
            }
        }
    }

    private fun startWatchdogPolling() {
        if (!isListeningActive) return

        val watchdogJs = """
            (function() {
                try {
                    var resultText = "";
                    var richInputs = document.querySelectorAll(
                        'rich-textarea .ql-editor, ' +
                        'div[contenteditable="true"], ' +
                        'div[role="textbox"], ' +
                        'rich-textarea p, ' +
                        'textarea[aria-label*="Gemini" i], ' +
                        'textarea, input[name="q"]'
                    );

                    for (var i = 0; i < richInputs.length; i++) {
                        var el = richInputs[i];
                        var text = el.innerText || el.textContent || el.value || '';
                        text = text.trim();
                        if (text.length > 0 &&
                            !text.startsWith('Diktovka uchun') &&
                            !text.startsWith('Ask Gemini') &&
                            !text.startsWith('Savol bering') &&
                            !text.startsWith('Type a prompt')) {
                            resultText = text;
                            break;
                        }
                    }

                    var isListening = false;
                    var activeMic = document.querySelector(
                        'button[aria-label*="Stop" i], ' +
                        'button[aria-label*="To\'xtatish" i], ' +
                        'button[aria-label*="Остановить" i], ' +
                        'div.pulsing-mic, ' +
                        '.is-listening, ' +
                        'div[aria-modal="true"]'
                    );
                    if (activeMic && activeMic.offsetWidth > 0 && activeMic.offsetHeight > 0) {
                        isListening = true;
                    }

                    var html = document.documentElement.innerHTML.toLowerCase();
                    var url = window.location.href.toLowerCase();
                    var isCaptcha = url.includes('/sorry/') ||
                                    url.includes('captcha') ||
                                    html.includes('recaptcha') ||
                                    html.includes('unusual traffic');
                    var isLogin = url.includes('accounts.google.com') || html.includes('sign in to gemini');

                    return JSON.stringify({
                        text: resultText,
                        isListening: isListening,
                        isCaptcha: isCaptcha,
                        isLogin: isLogin
                    });
                } catch(e) {
                    return JSON.stringify({ text: "", isListening: false, isCaptcha: false, isLogin: false, error: e.toString() });
                }
            })();
        """.trimIndent()

        mainHandler.postDelayed({
            if (isListeningActive) {
                webView?.evaluateJavascript(watchdogJs) { rawJson ->
                    handleWatchdogResponse(rawJson)
                    if (isListeningActive) {
                        startWatchdogPolling()
                    }
                }
            }
        }, 350)
    }

    private fun handleWatchdogResponse(rawJson: String?) {
        if (rawJson == null || rawJson == "null") return
        try {
            val unquoted = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                rawJson.substring(1, rawJson.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            } else {
                rawJson
            }

            val jsonObj = org.json.JSONObject(unquoted)
            val text = jsonObj.optString("text", "").trim()
            val isListening = jsonObj.optBoolean("isListening", false)
            val isCaptcha = jsonObj.optBoolean("isCaptcha", false)
            val isLogin = jsonObj.optBoolean("isLogin", false)

            if (isCaptcha) {
                _isCaptchaShowing.value = true
                _voiceState.value = GoogleVoiceState.CaptchaDetected
                _engineStatus.value = "Security Check detected. Please solve on screen."
                return
            } else if (isLogin) {
                _voiceState.value = GoogleVoiceState.LoginRequired
                _engineStatus.value = "Gemini sign-in recommended. Tap Security/Login dialog."
            } else {
                if (_isCaptchaShowing.value) {
                    _isCaptchaShowing.value = false
                }
            }

            if (text.isNotBlank() && text != lastExtractedText) {
                lastExtractedText = text
                _engineStatus.value = "Transcribing live from Gemini..."
                onChunkFinalized(text)
            }

            if (isListeningActive && !isListening) {
                _voiceState.value = GoogleVoiceState.Reconnecting
                _engineStatus.value = "Gemini Mic ready (continuous listening)..."
                mainHandler.postDelayed({
                    if (isListeningActive) {
                        triggerVoiceMicInPage()
                        _voiceState.value = GoogleVoiceState.Listening()
                    }
                }, 300)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error parsing watchdog response", e)
        }
    }

    fun checkForCaptcha() {
        val captchaDetectionJs = """
            (function() {
                try {
                    var html = document.documentElement.innerHTML.toLowerCase();
                    var url = window.location.href.toLowerCase();

                    var isCaptcha = url.includes('/sorry/') ||
                                    url.includes('captcha') ||
                                    html.includes('recaptcha') ||
                                    html.includes('unusual traffic') ||
                                    html.includes('robot emasligingizni tasdiqlang') ||
                                    html.includes('подтвердите, что вы не робот');

                    var isLogin = url.includes('accounts.google.com');

                    if (isCaptcha) return "CAPTCHA_DETECTED";
                    if (isLogin) return "LOGIN_REQUIRED";
                    return "OK";
                } catch(e) {
                    return "ERROR";
                }
            })();
        """.trimIndent()

        mainHandler.post {
            webView?.evaluateJavascript(captchaDetectionJs) { res ->
                if (res?.contains("CAPTCHA_DETECTED") == true) {
                    _isCaptchaShowing.value = true
                    _voiceState.value = GoogleVoiceState.CaptchaDetected
                    _engineStatus.value = "Security Check detected. Please solve on screen."
                } else if (res?.contains("LOGIN_REQUIRED") == true) {
                    _voiceState.value = GoogleVoiceState.LoginRequired
                    _engineStatus.value = "Google Login needed. Tap Security/Login dialog."
                } else {
                    _isCaptchaShowing.value = false
                }
            }
        }
    }

    fun startVoiceRecognition(languageCode: String = "en-US") {
        isListeningActive = true
        _voiceState.value = GoogleVoiceState.Listening()
        _engineStatus.value = "Listening via Gemini (gemini.google.com/app)..."
        checkForCaptcha()
        triggerVoiceMicInPage()
        startWatchdogPolling()
    }

    fun stopVoiceRecognition() {
        isListeningActive = false
        _voiceState.value = GoogleVoiceState.Ready
        _engineStatus.value = "Gemini Voice Engine Ready"

        val stopJs = """
            (function() {
                try {
                    var stopBtns = document.querySelectorAll(
                        'button[aria-label*="Stop" i], ' +
                        'button[aria-label*="To\'xtatish" i], ' +
                        'button[aria-label*="Остановить" i]'
                    );
                    if (stopBtns.length > 0) stopBtns[0].click();
                } catch(e) {}
            })();
        """.trimIndent()

        mainHandler.post {
            webView?.evaluateJavascript(stopJs, null)
        }
    }

    fun setLanguage(languageCode: String) {
        // Gemini adapts language automatically based on spoken input
    }

    fun dismissCaptchaSolved() {
        _isCaptchaShowing.value = false
        _voiceState.value = GoogleVoiceState.Ready
        _engineStatus.value = "Gemini Engine Ready"
        injectStealthScript()
        injectGeminiVoiceBridge()
    }

    fun reloadEngine() {
        _isCaptchaShowing.value = false
        _engineStatus.value = "Reloading Gemini..."
        mainHandler.post {
            webView?.loadUrl(GEMINI_APP_URL)
        }
    }

    fun openGoogleSearchFallback() {
        _engineStatus.value = "Switching to Google Search AI Mode..."
        mainHandler.post {
            webView?.loadUrl(GOOGLE_SEARCH_AI_URL)
        }
    }

    private inner class VoiceBridgeInterface {
        @JavascriptInterface
        fun onBridgeReady() {
            coroutineScope.launch(Dispatchers.Main) {
                _engineStatus.value = "Gemini Voice Bridge Connected"
            }
        }

        @JavascriptInterface
        fun onSpeechResult(text: String?, isFinal: Boolean) {
            val result = text?.trim() ?: return
            if (result.isBlank()) return
            coroutineScope.launch(Dispatchers.Main) {
                if (result != lastExtractedText) {
                    lastExtractedText = result
                    onChunkFinalized(result)
                }
            }
        }
    }
}
