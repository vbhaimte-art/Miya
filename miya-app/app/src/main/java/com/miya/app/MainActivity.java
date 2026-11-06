package com.miya.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
    private WebView webView;
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    // Security State
    private boolean isUnlocked = false;
    private long unlockTime = 0;
    private static final long SESSION_TIMEOUT = 600000; // 10 minutes

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Request Critical Permissions Safely
        requestPermissions();

        // Setup Overlay Permission for Background Service
        checkOverlayPermission();

        // Setup WebView
        webView = new WebView(this);
        setContentView(webView);
        
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        
        // Add JS Bridge
        webView.addJavascriptInterface(new MiyaBridge(), "Android");
        
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        
        // Load UI - Make sure index.html exists in assets!
        try {
            webView.loadUrl("file:///android_asset/index.html");
        } catch (Exception e) {
            Toast.makeText(this, "Error: index.html missing!", Toast.LENGTH_LONG).show();
        }
        
        // Start Background Service
        startService(new Intent(this, MiyaService.class));
    }

    private void requestPermissions() {
        String[] perms = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        };
        
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, perms, PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void checkOverlayPermission() {
        try {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 200);
            }
        } catch (Exception e) { /* Ignore on some devices */ }
    }

    // === JAVASCRIPT BRIDGE ===
    public class MiyaBridge {
        
        @JavascriptInterface
        public void authenticate(String password) {
            if ("VERGY".equalsIgnoreCase(password)) {
                isUnlocked = true;
                unlockTime = System.currentTimeMillis();
                showToast("🔓 Unrestricted Mode Active");
            } else {
                showToast("⚠️ Access Denied");
            }
        }

        @JavascriptInterface
        public boolean isSessionActive() {
            if (!isUnlocked) return false;
            if ((System.currentTimeMillis() - unlockTime) > SESSION_TIMEOUT) {
                isUnlocked = false;
                return false;
            }
            return true;
        }

        @JavascriptInterface
        public void setBrightness(int level) {
            try {
                Settings.System.putInt(getContentResolver(), 
                    Settings.System.SCREEN_BRIGHTNESS, level);
            } catch (Exception e) { e.printStackTrace(); }
        }

        @JavascriptInterface
        public String getBatteryInfo() {
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            int level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            boolean charging = bm.isCharging();
            return "{\"level\":" + level + ",\"charging\":" + charging + "}";
        }

        @JavascriptInterface
        public void openApp(String packageName) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(packageName);
                if (i != null) startActivity(i);
            } catch (Exception e) { e.printStackTrace(); }
        }
        
        @JavascriptInterface
        public String getDeviceInfo() {
            return "{\"model\":\"" + Build.MODEL + "\",\"sdk\":" + Build.VERSION.SDK_INT + "}";
        }

        private void showToast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }
              }
