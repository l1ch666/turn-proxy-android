@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.freeturn.app.ProxyServiceState
import kotlinx.coroutines.delay
import com.freeturn.app.R

// JS injected after the captcha page renders. It locates the most likely
// captchaNotRobot checkbox/interactive element and reports its center as a
// fraction of the viewport (scale-independent), plus a short summary of the
// top candidates for diagnostics. Native code then dispatches a *real* touch
// (isTrusted=true) — a JS .click() is isTrusted=false and is what VK rejects.
private const val CAPTCHA_FINDER_JS = """
(function(){
  function vis(el){
    if(!el||!el.getBoundingClientRect) return false;
    var r=el.getBoundingClientRect();
    if(r.width<6||r.height<6) return false;
    var s=getComputedStyle(el);
    if(s.display==='none'||s.visibility==='hidden'||parseFloat(s.opacity||'1')===0) return false;
    if(r.bottom<=0||r.right<=0||r.top>=window.innerHeight||r.left>=window.innerWidth) return false;
    return true;
  }
  function rc(el){ return el.getBoundingClientRect(); }
  function report(fx,fy,found,info){ AndroidCaptchaAuto.reportTarget(fx,fy,found,info); }
  try {
    // 1) Locate the "I'm not a robot" text (shortest visible element matching).
    var textEl=null, best=1e9;
    var texts=document.querySelectorAll('label,span,div,p,a,button');
    for(var i=0;i<texts.length;i++){
      var e=texts[i]; if(!vis(e)) continue;
      var t=(e.textContent||'').trim();
      if(t.length>2 && t.length<48 && /not a robot|не\s*робот|robot|робот/i.test(t)){
        var a=rc(e).width*rc(e).height;
        if(a<best){ best=a; textEl=e; }
      }
    }
    if(textEl){
      var tr=rc(textEl); var tcy=tr.top+tr.height/2;
      // 2) Prefer a small near-square box on the same row (the checkbox itself).
      var box=null, bd=1e9; var nodes=document.querySelectorAll('*');
      for(var j=0;j<nodes.length;j++){
        var n=nodes[j]; if(!vis(n)) continue;
        var r=rc(n); var ar=r.width/r.height;
        if(ar<0.6||ar>1.7) continue;
        if(r.width<12||r.width>56) continue;
        var ncy=r.top+r.height/2;
        if(Math.abs(ncy-tcy) > Math.max(30, tr.height)) continue;
        if(r.left > tr.left+6) continue; // box sits left of the label text
        var d=Math.abs(ncy-tcy)+Math.abs(r.left-(tr.left-28));
        if(d<bd){ bd=d; box=n; }
      }
      if(box){
        var br=rc(box);
        report((br.left+br.width/2)/window.innerWidth,(br.top+br.height/2)/window.innerHeight,true,
          'box '+box.tagName+'@'+Math.round(br.left)+','+Math.round(br.top)+'/'+Math.round(br.width)+'x'+Math.round(br.height));
        return;
      }
      // 3) Else tap the nearest clickable ancestor (label/button) center.
      var anc=textEl;
      while(anc && anc!==document.body){
        var tag=anc.tagName;
        if(tag==='LABEL'||tag==='BUTTON'||anc.getAttribute('role')==='checkbox'||anc.onclick){ break; }
        anc=anc.parentElement;
      }
      var ar2=rc(anc&&anc!==document.body?anc:textEl);
      report((ar2.left+ar2.width/2)/window.innerWidth,(ar2.top+ar2.height/2)/window.innerHeight,true,
        'row '+(anc?anc.tagName:'TEXT')+'@'+Math.round(ar2.left)+','+Math.round(tcy)+' "'+(textEl.textContent||'').trim().substring(0,24)+'"');
      return;
    }
    // 4) Fallback: a real checkbox input.
    var inp=document.querySelector('input[type=checkbox],[role=checkbox]');
    if(inp){
      var ir=rc(inp);
      if(ir.width>=8&&ir.height>=8){ report((ir.left+ir.width/2)/window.innerWidth,(ir.top+ir.height/2)/window.innerHeight,true,'input@'+Math.round(ir.left)+','+Math.round(ir.top)); return; }
      var p=inp.parentElement; if(p){ var pr=rc(p); report((pr.left+16)/window.innerWidth,(pr.top+pr.height/2)/window.innerHeight,true,'inputParent'); return; }
    }
    report(0.5,0.6,false,'no-target title="'+document.title+'"');
  } catch(e){ report(0.5,0.6,false,'err:'+e); }
})();
"""

private class CaptchaAutomationBridge(
    private val onTarget: (fx: Float, fy: Float, found: Boolean, info: String) -> Unit
) {
    @JavascriptInterface
    fun reportTarget(fx: Float, fy: Float, found: Boolean, info: String) {
        onTarget(fx, fy, found, info)
    }
}

private fun dispatchRealTap(webView: WebView, x: Float, y: Float, handler: Handler) {
    val downTime = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
    webView.dispatchTouchEvent(down)
    down.recycle()
    // Release ~80ms later for a human-like tap (one gesture, same downTime).
    handler.postDelayed({
        val upTime = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
        webView.dispatchTouchEvent(up)
        up.recycle()
    }, 80)
}

private enum class CaptchaPhase { AUTO, MANUAL }

// Builds the captcha WebView with the auto-solve wiring (JS finder + real-gesture
// tap). Shared by the hidden auto phase and the visible manual phase.
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
private fun buildCaptchaWebView(
    ctx: Context,
    mainHandler: Handler,
    webViewRef: MutableState<WebView?>,
    tapsLeft: MutableState<Int>,
    captchaUrl: String,
    onLoadingChange: (Boolean) -> Unit
): WebView = WebView(ctx).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
    val bridge = CaptchaAutomationBridge { fx, fy, found, info ->
        mainHandler.post {
            val wv = webViewRef.value ?: return@post
            val w = wv.width
            val h = wv.height
            if (w <= 0 || h <= 0) return@post
            val x = fx.coerceIn(0f, 1f) * w
            val y = fy.coerceIn(0f, 1f) * h
            ProxyServiceState.addLog(
                "[Auto-Captcha] target found=$found @${"%.2f".format(fx)},${"%.2f".format(fy)} | $info"
            )
            dispatchRealTap(wv, x, y, mainHandler)
        }
    }
    addJavascriptInterface(bridge, "AndroidCaptchaAuto")
    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            onLoadingChange(true)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            onLoadingChange(false)
            // Give VK's widget JS time to render, then locate + tap the checkbox.
            // A second pass covers late-rendered widgets / a needed second nudge.
            val attempt = Runnable {
                if (tapsLeft.value > 0) {
                    tapsLeft.value -= 1
                    view?.evaluateJavascript(CAPTCHA_FINDER_JS, null)
                }
            }
            mainHandler.postDelayed(attempt, 1600)
            mainHandler.postDelayed(attempt, 5200)
        }
    }
    webViewRef.value = this
    loadUrl(captchaUrl)
}

@Composable
fun CaptchaWebViewDialog(
    captchaUrl: String,
    onDismiss: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var phase by remember { mutableStateOf(CaptchaPhase.AUTO) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    // Limit auto-tap attempts so we never spam VK; manual solving stays available.
    val tapsLeft = remember { mutableStateOf(2) }

    // The auto attempt runs fully hidden (off-screen, no dialog, no spinner) so the
    // captcha never flashes to the user. On success the core clears the captcha
    // session and this whole composable is removed externally. If it is still here
    // after the window, the auto attempt failed (or a slider appeared) — only then
    // do we open the visible page for manual solving.
    LaunchedEffect(captchaUrl) {
        delay(12_000)
        phase = CaptchaPhase.MANUAL
    }

    when (phase) {
        CaptchaPhase.AUTO -> {
            // Zero-size, non-blocking host: the WebView is measured at a realistic
            // size (so VK's JS renders the checkbox and the tap lands) but drawn far
            // off-screen, so nothing is shown and the app stays fully interactive.
            Box(modifier = Modifier.size(0.dp)) {
                AndroidView(
                    factory = { ctx ->
                        buildCaptchaWebView(ctx, mainHandler, webViewRef, tapsLeft, captchaUrl) {
                            isLoading = it
                        }
                    },
                    modifier = Modifier
                        .requiredSize(360.dp, 720.dp)
                        .graphicsLayer { translationX = 10_000f }
                )
            }
        }

        CaptchaPhase.MANUAL -> {
            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                buildCaptchaWebView(ctx, mainHandler, webViewRef, tapsLeft, captchaUrl) {
                                    isLoading = it
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 56.dp)
                        )

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .align(Alignment.TopCenter),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                                Text(
                                    stringResource(R.string.captcha_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.align(Alignment.CenterStart)
                                )
                                TextButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                ) {
                                    Text(stringResource(R.string.captcha_close))
                                }
                            }
                        }

                        if (isLoading) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }
}
