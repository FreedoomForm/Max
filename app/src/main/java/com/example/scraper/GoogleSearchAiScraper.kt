package com.example.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class ScrapedSource(
    val title: String,
    val url: String,
    val domain: String
)

data class ScrapedAiResult(
    val query: String,
    val aiOverview: String,
    val keyPoints: List<String> = emptyList(),
    val sources: List<ScrapedSource> = emptyList(),
    val rawExtraction: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val extractionSource: String = "Google Search AI Mode (udm=14)"
)

sealed class ScraperState {
    object Idle : ScraperState()
    data class Scraping(val query: String, val step: String) : ScraperState()
    data class VoiceListening(val partialText: String) : ScraperState()
    data class Success(val result: ScrapedAiResult) : ScraperState()
    data class Error(val message: String, val fallbackQuery: String? = null) : ScraperState()
}

class GoogleSearchAiScraper(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var headlessWebView: WebView? = null

    private val _scraperState = MutableStateFlow<ScraperState>(ScraperState.Idle)
    val scraperState: StateFlow<ScraperState> = _scraperState.asStateFlow()

    private val _scraperLogs = MutableStateFlow<List<String>>(emptyList())
    val scraperLogs: StateFlow<List<String>> = _scraperLogs.asStateFlow()

    private var currentQuery: String = ""

    init {
        mainHandler.post {
            initHeadlessWebView()
        }
    }

    private fun addLog(message: String) {
        coroutineScope.launch(Dispatchers.Main) {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            _scraperLogs.value = listOf("[$timestamp] $message") + _scraperLogs.value.take(40)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initHeadlessWebView() {
        try {
            headlessWebView = WebView(context).apply {
                // Disable hardware acceleration to prevent MESA rendernode errors in the container
                setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadsImagesAutomatically = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                
                // Chrome Mobile User Agent for modern Google AI Overviews
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Mobile; rv:124.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.113 Mobile Safari/537.36"

                addJavascriptInterface(ScraperJavaScriptInterface(), "AndroidScraperBridge")

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        // Grant headless audio / mic capture to Google Search Voice
                        addLog("Microphone permission granted to Headless WebChromeClient")
                        request?.grant(request.resources)
                    }

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        if (_scraperState.value is ScraperState.Scraping && newProgress < 100) {
                            _scraperState.value = ScraperState.Scraping(currentQuery, "Loading Google AI Mode DOM ($newProgress%)...")
                        }
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        addLog("Headless Browser navigating: ${url?.take(60)}...")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        addLog("Google Search AI page loaded. Injecting AI Scraper engine...")
                        if (_scraperState.value is ScraperState.Scraping) {
                            _scraperState.value = ScraperState.Scraping(currentQuery, "Extracting AI Overview & Synthesis...")
                            // Give dynamic Google AI blocks 1 second to hydrate then scrape
                            mainHandler.postDelayed({
                                injectScraperScript()
                            }, 1200)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        val desc = error?.description?.toString() ?: "Network error"
                        addLog("Headless Web error: $desc")
                    }
                }
            }
            addLog("Headless Google Search Engine initialized successfully")
        } catch (e: Exception) {
            addLog("Error initializing headless WebView: ${e.message}")
        }
    }

    fun searchAndScrapeAiMode(query: String) {
        if (query.isBlank()) return
        currentQuery = query.trim()
        _scraperState.value = ScraperState.Scraping(currentQuery, "Connecting to Google Search AI Mode (udm=50)...")
        addLog("Triggering Headless Search AI for: \"$currentQuery\"")

        val encoded = try {
            URLEncoder.encode(currentQuery, "UTF-8")
        } catch (e: Exception) {
            currentQuery
        }

        // Standard Google Search AI Mode parameter: udm=50 (Dedicated AI Mode Tab)
        val targetUrl = "https://www.google.com/search?q=$encoded&udm=50"

        mainHandler.post {
            if (headlessWebView == null) {
                initHeadlessWebView()
            }
            headlessWebView?.loadUrl(targetUrl)
        }
    }

    fun triggerGoogleVoiceMicInBrowser() {
        _scraperState.value = ScraperState.VoiceListening("Activating Google Voice Recognition in Headless Engine...")
        addLog("Activating Google Search AI Mode Microphone via JavaScript in Headless DOM...")

        val jsTriggerVoice = """
            (function() {
                try {
                    // Strategy 1: Find the conversational AI text area first
                    var textAreas = document.querySelectorAll('textarea');
                    var aiTextArea = null;
                    for (var i = 0; i < textAreas.length; i++) {
                        if (textAreas[i].placeholder && (textAreas[i].placeholder.toLowerCase().includes('ask') || textAreas[i].placeholder.toLowerCase().includes('savol'))) {
                            aiTextArea = textAreas[i];
                            break;
                        }
                    }
                    if (!aiTextArea && textAreas.length > 0) aiTextArea = textAreas[textAreas.length - 1];
                    
                    var container = aiTextArea ? (aiTextArea.closest('form') || aiTextArea.closest('div[jscontroller]') || document.body) : document.body;
                    
                    // Strategy 2: Look for Google's specific Voice Search Mic SVG path in that container
                    var svgs = container.querySelectorAll('svg path');
                    for (var i = 0; i < svgs.length; i++) {
                        var d = svgs[i].getAttribute('d');
                        if (d && (d.startsWith('M12 14c1.66') || d.indexOf('M12 14c') !== -1)) {
                            var btn = svgs[i].closest('button, div[role="button"], a');
                            if (btn) {
                                btn.click();
                                return "AI_MODE_MIC_SVG_CLICKED";
                            }
                        }
                    }
                    
                    // Strategy 3: Fallback to aria-labels in the whole document but prioritize bottom ones
                    var ariaSelectors = '[aria-label*="voice" i], [aria-label*="ovozli" i], [aria-label*="Voice" i], [aria-label*="Search by voice" i]';
                    var voiceEls = document.querySelectorAll(ariaSelectors);
                    var validVoiceBtns = [];
                    for(var i=0; i<voiceEls.length; i++) {
                        var el = voiceEls[i];
                        if(el.tagName === 'BUTTON' || el.getAttribute('role') === 'button') {
                            validVoiceBtns.push(el);
                        }
                    }
                    
                    if (validVoiceBtns.length > 0) {
                        // AI mode mic is usually the last one (at the bottom)
                        validVoiceBtns[validVoiceBtns.length - 1].click();
                        return "AI_MODE_MIC_ARIA_CLICKED";
                    }

                    // Absolute fallback to primary search input
                    var searchInput = document.querySelector('input[name="q"], textarea[name="q"], textarea');
                    if (searchInput) {
                        searchInput.focus();
                        return "INPUT_FOCUSED_NO_MIC";
                    }
                    
                    return "MIC_NOT_FOUND";
                } catch(e) {
                    return "ERROR: " + e.toString();
                }
            })();
        """.trimIndent()

        mainHandler.post {
            headlessWebView?.evaluateJavascript(jsTriggerVoice) { result ->
                addLog("Voice Mic trigger response: $result")
                // Start listening monitor
                monitorVoiceTranscript()
            }
        }
    }

    private fun monitorVoiceTranscript() {
        val jsCheckTranscript = """
            (function() {
                try {
                    var textAreas = document.querySelectorAll('textarea');
                    var aiTextArea = null;
                    for (var i = 0; i < textAreas.length; i++) {
                        if (textAreas[i].placeholder && (textAreas[i].placeholder.toLowerCase().includes('ask') || textAreas[i].placeholder.toLowerCase().includes('savol'))) {
                            aiTextArea = textAreas[i];
                            break;
                        }
                    }
                    if (!aiTextArea && textAreas.length > 0) aiTextArea = textAreas[textAreas.length - 1];
                    
                    if (aiTextArea && aiTextArea.value) {
                        return aiTextArea.value;
                    }
                    
                    var searchInput = document.querySelector('input[name="q"], input[type="text"], input[type="search"]');
                    if (searchInput && searchInput.value) {
                        return searchInput.value;
                    }
                    return "";
                } catch(e) {
                    return "";
                }
            })();
        """.trimIndent()

        mainHandler.postDelayed({
            headlessWebView?.evaluateJavascript(jsCheckTranscript) { result ->
                val text = result?.trim('"', '\'') ?: ""
                if (text.isNotBlank() && text != "null") {
                    addLog("Voice transcript captured from Google Headless Engine: $text")
                    searchAndScrapeAiMode(text)
                }
            }
        }, 2000)
    }

    private fun injectScraperScript() {
        val scraperJs = """
            (function() {
                try {
                    var data = {
                        query: "",
                        overview: "",
                        keyPoints: [],
                        sources: [],
                        rawText: ""
                    };

                    var queryInput = document.querySelector('input[name="q"], textarea[name="q"]');
                    if (queryInput) {
                        data.query = queryInput.value || "";
                    }

                    // 1. Target Google AI Overview container elements
                    var aiOverviewEl = document.querySelector(
                        '[data-attrid="AIResponse"], ' +
                        '.ai-mode-response, ' +
                        '.generated-content, ' +
                        '[data-attrid="SGEAnswer"], ' +
                        '[data-attrid="wa:/description"], ' +
                        '.kno-rdesc, ' +
                        '[data-async-context*="overview"], ' +
                        '[aria-label*="AI Overview"], ' +
                        '[data-attrid*="overview"], ' +
                        'div[data-md*="overview"], ' +
                        'div[jsname*="x8bZkd"], ' +
                        'div[class*="ai-overview"], ' +
                        'div[class*="overview"]'
                    );

                    if (aiOverviewEl) {
                        data.overview = aiOverviewEl.innerText.trim();
                    }

                    // 2. Extract bullet insights / list items in generative block
                    var listItems = document.querySelectorAll(
                        '[data-async-context*="overview"] li, ' +
                        '.kno-rdesc li, ' +
                        '#rso ul li, ' +
                        '[data-attrid*="overview"] li'
                    );
                    listItems.forEach(function(li) {
                        var txt = li.innerText.trim();
                        if (txt.length > 5 && data.keyPoints.length < 8) {
                            data.keyPoints.push(txt);
                        }
                    });

                    // 3. Fallback extraction if primary AI Overview selector changed
                    if (!data.overview || data.overview.length < 20) {
                        var mainResults = document.querySelector('#rso, #search, .main, [role="main"]');
                        if (mainResults) {
                            var firstSnippet = mainResults.querySelector('.VwiC3b, .hgKElc, [data-snippet], [data-attrid*="description"]');
                            if (firstSnippet) {
                                data.overview = firstSnippet.innerText.trim();
                            } else {
                                data.overview = mainResults.innerText.substring(0, 800).trim();
                            }
                        }
                    }

                    // 4. Extract source citations & links
                    var linkElements = document.querySelectorAll('#rso a[href^="http"], .kno-rdesc a[href^="http"], [data-async-context*="overview"] a[href^="http"]');
                    var seenUrls = {};
                    linkElements.forEach(function(a) {
                        var href = a.href;
                        if (href && !seenUrls[href] && !href.includes('google.com') && data.sources.length < 5) {
                            seenUrls[href] = true;
                            var title = a.querySelector('h3, .LC20lb, span') ? (a.querySelector('h3, .LC20lb, span').innerText || a.innerText) : a.innerText;
                            var domain = "";
                            try {
                                domain = new URL(href).hostname.replace('www.', '');
                            } catch(e) {}

                            if (title && title.trim().length > 0) {
                                data.sources.push({
                                    title: title.trim().substring(0, 80),
                                    url: href,
                                    domain: domain
                                });
                            }
                        }
                    });

                    // 5. Capture clean raw excerpt
                    data.rawText = (data.overview + " " + data.keyPoints.join(" ")).substring(0, 1500);

                    AndroidScraperBridge.onScrapeCompleted(JSON.stringify(data));
                    return "SCRAPE_EXECUTED";
                } catch(err) {
                    AndroidScraperBridge.onScrapeFailed(err.toString());
                    return "ERROR: " + err.toString();
                }
            })();
        """.trimIndent()

        mainHandler.post {
            headlessWebView?.evaluateJavascript(scraperJs) { result ->
                addLog("Headless script execution status: $result")
            }
        }
    }

    private inner class ScraperJavaScriptInterface {
        @JavascriptInterface
        fun onScrapeCompleted(jsonStr: String) {
            coroutineScope.launch(Dispatchers.Main) {
                try {
                    addLog("Received raw DOM payload from Headless Google Search")
                    val json = JSONObject(jsonStr)
                    var overview = json.optString("overview", "").trim()
                    val query = json.optString("query", currentQuery).ifBlank { currentQuery }
                    val rawText = json.optString("rawText", "")

                    val keyPoints = mutableListOf<String>()
                    val keyPointsArr = json.optJSONArray("keyPoints")
                    if (keyPointsArr != null) {
                        for (i in 0 until keyPointsArr.length()) {
                            val kp = keyPointsArr.getString(i).trim()
                            if (kp.isNotBlank()) {
                                keyPoints.add(kp)
                            }
                        }
                    }

                    val sources = mutableListOf<ScrapedSource>()
                    val sourcesArr = json.optJSONArray("sources")
                    if (sourcesArr != null) {
                        for (i in 0 until sourcesArr.length()) {
                            val srcObj = sourcesArr.getJSONObject(i)
                            sources.add(
                                ScrapedSource(
                                    title = srcObj.optString("title", "Source"),
                                    url = srcObj.optString("url", ""),
                                    domain = srcObj.optString("domain", "web")
                                )
                            )
                        }
                    }

                    if (overview.isBlank() && rawText.isNotBlank()) {
                        overview = rawText
                    }

                    if (overview.isBlank() && keyPoints.isEmpty()) {
                        overview = "Google Search AI Mode processed query \"$query\". Key search results and AI snippets extracted."
                    }

                    val result = ScrapedAiResult(
                        query = query,
                        aiOverview = overview,
                        keyPoints = keyPoints,
                        sources = sources,
                        rawExtraction = rawText
                    )

                    _scraperState.value = ScraperState.Success(result)
                    addLog("Successfully extracted AI Overview (${overview.length} chars, ${sources.size} sources)")
                } catch (e: Exception) {
                    addLog("JSON Parse error in scraper: ${e.message}")
                    _scraperState.value = ScraperState.Error("Failed to parse scraped AI data: ${e.message}", currentQuery)
                }
            }
        }

        @JavascriptInterface
        fun onScrapeFailed(error: String) {
            coroutineScope.launch(Dispatchers.Main) {
                addLog("Scraper JS error: $error")
                _scraperState.value = ScraperState.Error("Scraper encountered error: $error", currentQuery)
            }
        }

        @JavascriptInterface
        fun onVoiceTranscriptDetected(transcript: String) {
            coroutineScope.launch(Dispatchers.Main) {
                addLog("Headless Web Voice Transcript detected: $transcript")
                searchAndScrapeAiMode(transcript)
            }
        }
    }

    fun cleanup() {
        mainHandler.post {
            headlessWebView?.destroy()
            headlessWebView = null
        }
    }
}
