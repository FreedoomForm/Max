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
    data class Error(val message: String) : GoogleVoiceState()
}

class GoogleSearchAiVoiceEngine(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val onChunkFinalized: (String) -> Unit,
    private val onInterimText: (String) -> Unit
) {
    private val tag = "GoogleAiVoiceEngine"
    private val mainHandler = Handler(Looper.getMainLooper())
    var webView: WebView? = null
        private set

    private val _voiceState = MutableStateFlow<GoogleVoiceState>(GoogleVoiceState.Initializing)
    val voiceState: StateFlow<GoogleVoiceState> = _voiceState.asStateFlow()

    private val _engineStatus = MutableStateFlow("Google AI Mode (udm=50) Initializing...")
    val engineStatus: StateFlow<String> = _engineStatus.asStateFlow()

    private val _isCaptchaShowing = MutableStateFlow(false)
    val isCaptchaShowing: StateFlow<Boolean> = _isCaptchaShowing.asStateFlow()

    private var isListeningActive = false
    private var lastExtractedText = ""
    private var isPageReady = false

    // Direct Google Search AI Mode URL with parameters from Python reference
    private val AI_MODE_URL = "https://www.google.com/search?q=&sourceid=chrome&ie=UTF-8&udm=50&aep=48&cud=0"

    init {
        mainHandler.post {
            initStealthEngine()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initStealthEngine() {
        try {
            // Setup Cookie Manager with third-party cookie support
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            webView = WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                // Enhanced stealth WebSettings matching Python reference
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

                    // Modern desktop / mobile realistic Chrome User-Agent
                    userAgentString =
                        "Mozilla/5.0 (Linux; Android 14; Mobile; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                }

                cookieManager.setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(VoiceBridgeInterface(), "AndroidVoiceBridge")

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        // Grant all requested permissions (microphones, audio, notifications)
                        request?.grant(request.resources)
                        _engineStatus.value = "Microphone access granted to Google AI Mode"
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        isPageReady = false
                        _engineStatus.value = "Connecting to google.com/search?udm=50..."
                        // Inject stealth script before page loads
                        injectStealthScript()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        isPageReady = true
                        _engineStatus.value = "Google AI Mode Engine Ready"
                        if (_voiceState.value !is GoogleVoiceState.CaptchaDetected) {
                            _voiceState.value = GoogleVoiceState.Ready
                        }
                        
                        // Inject anti-detection & consent bypass
                        injectStealthScript()
                        injectGoogleVoiceBridge()
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

                loadUrl(AI_MODE_URL)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize WebView", e)
            _engineStatus.value = "Engine error: ${e.message}"
            _voiceState.value = GoogleVoiceState.Error(e.message ?: "Failed to initialize")
        }
    }

    /**
     * Enhanced stealth injection inspired by the Python reference:
     * - Disables webdriver flags
     * - Mocks navigator.plugins & languages
     * - Fixes window.chrome detection
     * - Auto-resolves notification permissions
     */
    private fun injectStealthScript() {
        val stealthJs = """
            (function() {
                try {
                    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                    Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
                    Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en', 'ru', 'uz'] });
                    
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

    private fun injectGoogleVoiceBridge() {
        val script = """
            (function() {
                // 1. Comprehensive Cookie / Consent Auto-Handler (Accept all, I agree, etc.)
                try {
                    var cookieSelectors = [
                        '//button[contains(., "Accept all")]',
                        '//button[contains(., "I agree")]',
                        '//button[@id="L2AGLb"]',
                        '//button[contains(., "Roziman")]',
                        '//button[contains(., "Принять все")]',
                        '//button[contains(., "Согласен")]',
                        '//button[contains(., "Qabul qilish")]'
                    ];

                    for (var s = 0; s < cookieSelectors.length; s++) {
                        var xpathResult = document.evaluate(cookieSelectors[s], document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
                        if (xpathResult && xpathResult.singleNodeValue) {
                            xpathResult.singleNodeValue.click();
                            break;
                        }
                    }
                } catch(e) {}

                // 2. Active monitor of input box for recognized phrases
                function checkInputs() {
                    var inputs = document.querySelectorAll('textarea, input[name="q"], input[type="text"], div[role="combobox"] textarea');
                    inputs.forEach(function(input) {
                        if (input.value && input.value.trim().length > 0) {
                            var val = input.value.trim();
                            if (window.AndroidVoiceBridge) {
                                window.AndroidVoiceBridge.onSpeechResult(val, true);
                            }
                        }
                    });
                }

                // Global mutation observer
                var observer = new MutationObserver(function(mutations) {
                    checkInputs();
                });
                
                observer.observe(document.body, { childList: true, subtree: true, attributes: true });

                if (window.AndroidVoiceBridge) {
                    window.AndroidVoiceBridge.onBridgeReady();
                }
            })();
        """.trimIndent()

        mainHandler.post {
            webView?.evaluateJavascript(script, null)
        }
    }

    /**
     * Captcha & Unusual Traffic Detection
     */
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
                                    html.includes('our systems have detected unusual traffic') ||
                                    html.includes('robot emasligingizni tasdiqlang') ||
                                    html.includes('подтвердите, что вы не робот') ||
                                    document.querySelector('#captcha-form, iframe[src*="recaptcha"], #recaptcha') !== null;
                                    
                    return isCaptcha ? "CAPTCHA_DETECTED" : "NO_CAPTCHA";
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
                    _engineStatus.value = "Security Check / Captcha detected. Please solve on screen."
                } else {
                    _isCaptchaShowing.value = false
                }
            }
        }
    }

    fun startVoiceRecognition() {
        isListeningActive = true
        _voiceState.value = GoogleVoiceState.Listening()
        _engineStatus.value = "Listening via Google AI Mode (udm=50)..."
        checkForCaptcha()
        triggerVoiceMicInPage()
        startWatchdogPolling()
    }

    private fun triggerVoiceMicInPage() {
        if (!isPageReady) return

        val triggerMicJs = """
            (function() {
                try {
                    // Check if already active listening
                    var activeListeningIndicator = document.querySelector('div[aria-label*="listening" i], [aria-label*="tinglamoqda" i], div.spch, #spch');
                    if (activeListeningIndicator && activeListeningIndicator.offsetWidth > 0) {
                        return "ALREADY_LISTENING";
                    }

                    // 1. Locate Google Search AI mic button by SVG mic icon path
                    var svgs = document.querySelectorAll('svg path');
                    for (var i = 0; i < svgs.length; i++) {
                        var d = svgs[i].getAttribute('d') || '';
                        if (d.indexOf('M12 14c') !== -1 || d.indexOf('M12 2a3') !== -1 || d.startsWith('M12')) {
                            var btn = svgs[i].closest('button, div[role="button"], a');
                            if (btn) {
                                btn.click();
                                return "CLICKED_MIC_SVG";
                            }
                        }
                    }

                    // 2. Search by aria-label for Voice search button
                    var voiceBtns = document.querySelectorAll('[aria-label*="voice" i], [aria-label*="Voice" i], [aria-label*="ovoz" i], [aria-label*="Ovoz" i], [aria-label*="Search by voice" i]');
                    for (var j = 0; j < voiceBtns.length; j++) {
                        if (voiceBtns[j].tagName === 'BUTTON' || voiceBtns[j].getAttribute('role') === 'button' || voiceBtns[j].tagName === 'A') {
                            voiceBtns[j].click();
                            return "CLICKED_MIC_ARIA";
                        }
                    }

                    // 3. Fallback input focus
                    var primaryInput = document.querySelector('textarea, input[name="q"]');
                    if (primaryInput) {
                        primaryInput.focus();
                        return "FOCUSED_INPUT";
                    }

                    return "NO_MIC_BUTTON";
                } catch(e) {
                    return "ERROR: " + e.toString();
                }
            })();
        """.trimIndent()

        mainHandler.post {
            webView?.evaluateJavascript(triggerMicJs) { res ->
                Log.d(tag, "Trigger Mic Result: $res")
            }
        }
    }

    private fun startWatchdogPolling() {
        if (!isListeningActive) return

        val watchdogJs = """
            (function() {
                try {
                    var resultText = "";
                    var inputs = document.querySelectorAll('textarea, input[name="q"], input[type="text"]');
                    for (var i = 0; i < inputs.length; i++) {
                        if (inputs[i].value && inputs[i].value.trim().length > 0) {
                            resultText = inputs[i].value.trim();
                            // Clear input after capturing so subsequent speech doesn't duplicate
                            inputs[i].value = '';
                            break;
                        }
                    }

                    // Check if voice search overlay is still active or closed (timeout/silence)
                    var isOverlayActive = false;
                    var voiceOverlay = document.querySelector('div.spch, #spch, div[aria-modal="true"], div[aria-label*="listening" i]');
                    if (voiceOverlay && voiceOverlay.offsetWidth > 0 && voiceOverlay.offsetHeight > 0) {
                        isOverlayActive = true;
                    }

                    // Also check for captcha during run
                    var html = document.documentElement.innerHTML.toLowerCase();
                    var isCaptcha = html.includes('recaptcha') || html.includes('unusual traffic');

                    return JSON.stringify({
                        text: resultText,
                        overlayActive: isOverlayActive,
                        isCaptcha: isCaptcha
                    });
                } catch(e) {
                    return JSON.stringify({ text: "", overlayActive: false, isCaptcha: false, error: e.toString() });
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
            val overlayActive = jsonObj.optBoolean("overlayActive", false)
            val isCaptcha = jsonObj.optBoolean("isCaptcha", false)

            if (isCaptcha) {
                _isCaptchaShowing.value = true
                _voiceState.value = GoogleVoiceState.CaptchaDetected
                _engineStatus.value = "Security Check detected. Please solve on screen."
                return
            } else {
                if (_isCaptchaShowing.value) {
                    _isCaptchaShowing.value = false
                }
            }

            if (text.isNotBlank() && text != lastExtractedText) {
                lastExtractedText = text
                _engineStatus.value = "Transcribed phrase from udm=50"
                onChunkFinalized(text)
            }

            // Auto-recovery / Keep-Alive: If user is actively recording but Google's web mic auto-closed due to silence
            if (isListeningActive && !overlayActive) {
                _voiceState.value = GoogleVoiceState.Reconnecting
                _engineStatus.value = "Google AI mic ready (auto-listening)..."
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

    fun dismissCaptchaSolved() {
        _isCaptchaShowing.value = false
        _voiceState.value = GoogleVoiceState.Ready
        _engineStatus.value = "Captcha solved! Google AI Engine Ready"
        injectStealthScript()
        injectGoogleVoiceBridge()
    }

    fun reloadEngine() {
        _isCaptchaShowing.value = false
        _engineStatus.value = "Reloading Google AI Mode..."
        mainHandler.post {
            webView?.reload()
        }
    }

    fun stopVoiceRecognition() {
        isListeningActive = false
        _voiceState.value = GoogleVoiceState.Ready
        _engineStatus.value = "Google AI Mode Engine Ready"

        val stopJs = """
            (function() {
                try {
                    var stopBtns = document.querySelectorAll('button[aria-label*="stop" i], div[role="button"][aria-label*="stop" i], button[aria-label*="close" i], div.spch-c');
                    if (stopBtns.length > 0) stopBtns[0].click();
                } catch(e) {}
            })();
        """.trimIndent()

        mainHandler.post {
            webView?.evaluateJavascript(stopJs, null)
        }
    }

    private inner class VoiceBridgeInterface {
        @JavascriptInterface
        fun onBridgeReady() {
            coroutineScope.launch(Dispatchers.Main) {
                _engineStatus.value = "Google AI Mode (udm=50) Bridge Connected"
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
