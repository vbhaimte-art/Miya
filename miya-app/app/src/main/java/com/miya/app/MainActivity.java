package com.miya.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class MainActivity extends FragmentActivity {

    private static final String TAG = "MiyaMain";
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";
    private static final int REQ_PERMS = 100;
    private static final int REQ_WEB = 101;
    private static final long SESSION_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final Pattern PKG_PATTERN = Pattern.compile("^[a-zA-Z0-9_.]{1,200}$");
    private static final int AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private boolean started = false;
    private boolean voiceStarted = false;
    private PermissionRequest pendingWebReq;

    // Session state (elapsedRealtime: user phone ki clock badal ke bypass nahi kar sakta)
    private volatile boolean unlocked = false;
    private volatile long unlockedAt = 0;

    // ------------------------------------------------------------------
    // LIFECYCLE
    // ------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Screenshots / recent-apps preview band
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);

        setupWebView(); // load NAHI hota; pehle security checks

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) webView.goBack(); else finish();
            }
        });

        // Security gate: local checks -> server (Play Integrity) -> UI load
        SecurityModule.runChecksAsync(this, threat -> {
            if (threat != SecurityModule.Threat.NONE) {
                SecurityModule.enforce(this, threat);
            } else {
                verifyServerThenStart();
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        unlocked = false; // app background mein gayi -> dobara auth
    }

    @Override
    protected void onDestroy() {
        pendingWebReq = null;
        if (webView != null) {
            // FIX: destroy se pehle parent se hatao, warna leak/crash ho sakta hai
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.removeJavascriptInterface("Android");
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // SECURITY GATE
    // ------------------------------------------------------------------
    private void verifyServerThenStart() {
        String url = BuildConfig.INTEGRITY_BASE_URL;
        if (BuildConfig.DEBUG || url == null || url.isEmpty()) {
            startApp(); // debug build ya server configure nahi
            return;
        }
        SecurityModule.verifyWithServer(this, url, BuildConfig.CLOUD_PROJECT_NUMBER,
                (ok, reason) -> {
                    if (ok) {
                        startApp();
                    } else {
                        Log.w(TAG, "Server verify failed: " + reason);
                        SecurityModule.showBlockedAndExit(this);
                    }
                });
    }

    private void startApp() {
        // FIX: callback activity destroy hone ke baad aaye to webView null hota hai (NPE)
        if (started || isFinishing() || isDestroyed() || webView == null) return;
        started = true;

        requestRuntimePermissions();

        try {
            getAssets().open("index.html").close();
            webView.loadUrl(START_URL);
        } catch (Exception e) {
            Toast.makeText(this, "Error: assets/index.html missing!", Toast.LENGTH_LONG).show();
        }

        // FIX: mic-type foreground service tabhi, jab RECORD_AUDIO mil chuki ho.
        // Android 14+ pe bina permission ke service SecurityException se crash hoti hai.
        if (has(Manifest.permission.RECORD_AUDIO)) startVoiceService();
    }

    private void startVoiceService() {
        if (voiceStarted || isFinishing() || isDestroyed()) return;
        if (!has(Manifest.permission.RECORD_AUDIO)) return;
        voiceStarted = true;
        try {
            startService(new Intent(this, MiyaService.class));
        } catch (Exception e) {
            voiceStarted = false;
            Log.w(TAG, "Service start failed", e);
        }
    }

    // ------------------------------------------------------------------
    // WEBVIEW (sirf apni local assets; remote content block)
    // ------------------------------------------------------------------
    private void setupWebView() {
        webView = new WebView(this);
        setContentView(webView);

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.addJavascriptInterface(new MiyaBridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (isOwnOrigin(u)) {
                    WebResourceResponse resp = assetLoader.shouldInterceptRequest(u);
                    if (resp != null) return resp;
                }
                return blocked();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !isOwnOrigin(r.getUrl()); // bahar ke links block
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    if (!isOwnOrigin(req.getOrigin())) { req.deny(); return; }
                    handleWebPermission(req, true);
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest req) {
                if (pendingWebReq == req) pendingWebReq = null;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback cb) {
                boolean allow = origin != null && isOwnOrigin(Uri.parse(origin))
                        && has(Manifest.permission.ACCESS_FINE_LOCATION);
                cb.invoke(origin, allow, false);
            }
        });
    }

    /**
     * FIX: pehle permission na hone par request seedha deny ho jati thi.
     * Ab Android permission maangte hain aur milne ke baad WebView ko grant karte hain.
     */
    private void handleWebPermission(PermissionRequest req, boolean mayAsk) {
        List<String> ok = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String r : req.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                if (has(Manifest.permission.RECORD_AUDIO)) ok.add(r);
                else missing.add(Manifest.permission.RECORD_AUDIO);
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                if (has(Manifest.permission.CAMERA)) ok.add(r);
                else missing.add(Manifest.permission.CAMERA);
            }
        }
        if (!missing.isEmpty() && mayAsk && pendingWebReq == null) {
            pendingWebReq = req;
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_WEB);
            return;
        }
        if (ok.isEmpty()) req.deny(); else req.grant(ok.toArray(new String[0]));
    }

    private static boolean isOwnOrigin(Uri u) {
        return u != null && "https".equals(u.getScheme()) && HOST.equals(u.getHost());
    }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked",
                Collections.<String, String>emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    // ------------------------------------------------------------------
    // PERMISSIONS (ek baar, sirf missing wali, API level ke hisaab se)
    // ------------------------------------------------------------------
    private boolean has(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRuntimePermissions() {
        List<String> need = new ArrayList<>();
        String[] base = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
        for (String p : base) if (!has(p)) need.add(p);
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);

        if (code == REQ_WEB) {
            PermissionRequest r = pendingWebReq;
            pendingWebReq = null;
            if (r != null) handleWebPermission(r, false);
            return;
        }

        if (code != REQ_PERMS) return;
        for (int r : results) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Kuch permissions deny hui, kuch features kaam nahi karenge",
                        Toast.LENGTH_LONG).show();
                break;
            }
        }
        // Mic permission ab mili hai -> voice service chalao
        startVoiceService();
    }

    // ------------------------------------------------------------------
    // AUTH (hardcoded password ki jagah fingerprint / PIN / pattern)
    // ------------------------------------------------------------------
    private void promptAuth() {
        BiometricManager bm = BiometricManager.from(this);
        if (bm.canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            toast("Phone mein screen lock (PIN/fingerprint) set karo");
            notifyJs(false);
            return;
        }

        BiometricPrompt prompt = new BiometricPrompt(this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        unlocked = true;
                        unlockedAt = SystemClock.elapsedRealtime();
                        toast("🔓 Unlocked");
                        notifyJs(true);
                    }

                    @Override
                    public void onAuthenticationError(int code, @NonNull CharSequence msg) {
                        notifyJs(false);
                    }
                });

        prompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Miya unlock")
                .setSubtitle("Apni identity confirm karo")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build());
    }

    private void notifyJs(boolean ok) {
        if (webView == null) return;
        webView.evaluateJavascript(
                "window.onMiyaAuth && window.onMiyaAuth(" + ok + ")", null);
    }

    private boolean sessionActive() {
        if (!unlocked) return false;
        if (SystemClock.elapsedRealtime() - unlockedAt > SESSION_TIMEOUT_MS) {
            unlocked = false;
            return false;
        }
        return true;
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    // ------------------------------------------------------------------
    // JAVASCRIPT BRIDGE (sensitive methods session-gated)
    // JS side: Android.authenticate() ; window.onMiyaAuth = ok => {...}
    // ------------------------------------------------------------------
    public class MiyaBridge {

        @JavascriptInterface
        public void authenticate() {
            runOnUiThread(MainActivity.this::promptAuth);
        }

        @JavascriptInterface
        public boolean isSessionActive() {
            return sessionActive();
        }

        @JavascriptInterface
        public void lock() {
            unlocked = false;
        }

        /** Sirf is app ki screen ki brightness (WRITE_SETTINGS ki zaroorat nahi). */
        @JavascriptInterface
        public void setBrightness(int level) {
            if (!sessionActive()) { toast("⚠️ Pehle unlock karo"); return; }
            final float b = Math.max(0.02f, Math.min(255, Math.max(0, level)) / 255f);
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = b;
                getWindow().setAttributes(lp);
            });
        }

        @JavascriptInterface
        public void openApp(final String packageName) {
            if (!sessionActive()) { toast("⚠️ Pehle unlock karo"); return; }
            if (packageName == null || !PKG_PATTERN.matcher(packageName).matches()) return;
            runOnUiThread(() -> {
                try {
                    // NOTE: Android 11+ pe manifest mein <queries><package .../> zaroori hai
                    Intent i = getPackageManager().getLaunchIntentForPackage(packageName);
                    if (i != null) startActivity(i);
                    else toast("App nahi mili");
                } catch (Exception e) {
                    Log.w(TAG, "openApp failed", e);
                }
            });
        }

        @JavascriptInterface
        public void requestOverlayPermission() {
            if (!sessionActive()) { toast("⚠️ Pehle unlock karo"); return; }
            runOnUiThread(() -> {
                try {
                    if (!Settings.canDrawOverlays(MainActivity.this)) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "overlay intent failed", e);
                }
            });
        }

        @JavascriptInterface
        public String getBatteryInfo() {
            try {
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                return new JSONObject()
                        .put("level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                        .put("charging", bm.isCharging())
                        .toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String getDeviceInfo() {
            try {
                return new JSONObject()
                        .put("model", Build.MODEL)
                        .put("sdk", Build.VERSION.SDK_INT)
                        .toString();
            } catch (Exception e) {
                return "{}";
            }
        }
    }
}
