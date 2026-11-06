package com.miya.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class MainActivity extends Activity {
    private WebView webView;
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    // Security State
    private boolean isUnlocked = false;
    private long unlockTime = 0;
    private static final long SESSION_TIMEOUT = 600000; // 10 minutes
    private int failedAttempts = 0;
    private long lockoutUntil = 0;
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION = 30000; // 30 seconds
    
    // Secure Auth (Set this during first setup via encrypted storage)
    private static final String AUTH_SALT = "miya_secure_salt_v1"; 
    // In production, fetch hashed password from EncryptedSharedPreferences
    private String getStoredHash() {
        // TODO: Replace with actual EncryptedSharedPreferences read
        // For now, using a placeholder hash of "VERGY" + salt
        return hashPassword("VERGY", AUTH_SALT); 
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Safe Permission Request
        requestPermissionsSafely();

        // Setup Overlay Permission (Non-blocking)
        if (!Settings.canDrawOverlays(this)) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 200);
            } catch (Exception e) { /* Ignore */ }
        }

        // Setup Secure WebView
        webView = new WebView(this);
        setContentView(webView);
        
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false); // SECURITY FIX
        ws.setAllowContentAccess(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        
        // Add JS Bridge
        webView.addJavascriptInterface(new MiyaBridge(), "Android");
        
        // Block Remote URLs - Only allow local assets
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!url.startsWith("file:///android_asset/")) {
                    Toast.makeText(MainActivity.this, "⚠️ External URLs Blocked", Toast.LENGTH_SHORT).show();
                    return true; // Block it
                }
                return false;
            }
            
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl.contains("index.html")) {
                    view.loadData("<h1>Error: index.html missing</h1>", "text/html", "UTF-8");
                }
            }
        });
        
        // Handle Web Permissions (Mic/Camera for WebView)
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(android.webkit.PermissionRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    request.grant(request.getResources());
                }
            }
        });
        
        // Load Local UI
        webView.loadUrl("file:///android_asset/index.html");
        
        // Start Foreground Service (Android 8+ Fix)
        startMiyaService();
    }

    private void requestPermissionsSafely() {
        List<String> perms = new ArrayList<>();
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.RECORD_AUDIO);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.CAMERA);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 && 
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
            
        if (!perms.isEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    private void startMiyaService() {
        Intent serviceIntent = new Intent(this, MiyaService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    // === SECURE JAVASCRIPT BRIDGE ===
    public class MiyaBridge {
        
        @JavascriptInterface
        public void authenticate(String password) {
            // Brute Force Check
            if (System.currentTimeMillis() < lockoutUntil) {
                showToast("⏳ Locked. Try again in " + ((lockoutUntil - System.currentTimeMillis())/1000) + "s");
                return;
            }

            String inputHash = hashPassword(password, AUTH_SALT);
            String storedHash = getStoredHash();
            
            if (inputHash.equals(storedHash)) {
                isUnlocked = true;
                unlockTime = System.currentTimeMillis();
                failedAttempts = 0;
                showToast("🔓 Unrestricted Mode Active");
            } else {
                failedAttempts++;
                if (failedAttempts >= MAX_ATTEMPTS) {
                    lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION;
                    failedAttempts = 0;
                    showToast("🚫 Too many attempts. Locked for 30s.");
                } else {
                    showToast("⚠️ Access Denied (" + failedAttempts + "/" + MAX_ATTEMPTS + ")");
                }
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
            if (!isSessionActive()) { showToast("🔒 Session Expired"); return; }
            
            // Check WRITE_SETTINGS permission safely
            if (Settings.System.canWrite(MainActivity.this)) {
                try {
                    Settings.System.putInt(getContentResolver(), 
                        Settings.System.SCREEN_BRIGHTNESS, Math.max(0, Math.min(255, level)));
                } catch (Exception e) { 
                    showToast("❌ Brightness failed: " + e.getMessage()); 
                }
            } else {
                showToast("⚠️ Grant 'Write Settings' permission first");
                startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, 
                    Uri.parse("package:" + getPackageName())));
            }
        }

        @JavascriptInterface
        public String getBatteryInfo() {
            if (!isSessionActive()) return "{\"error\":\"Session expired\"}";
            
            try {
                android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
                int level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                boolean charging = bm.isCharging();
                return "{\"level\":" + level + ",\"charging\":" + charging + "}";
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
            }
        }

        @JavascriptInterface
        public void openApp(String packageName) {
            if (!isSessionActive()) { showToast("🔒 Session Expired"); return; }
            
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(packageName);
                if (i != null) startActivity(i);
                else showToast("❌ App not found: " + packageName);
            } catch (Exception e) { 
                showToast("❌ Open failed: " + e.getMessage().replace("\"", "'")); 
            }
        }
        
        @JavascriptInterface
        public String getDeviceInfo() {
            if (!isSessionActive()) return "{\"error\":\"Session expired\"}";
            
            // Escape quotes to prevent JSON injection
            String model = Build.MODEL.replace("\"", "\\\"").replace("'", "\\'");
            return "{\"model\":\"" + model + "\",\"sdk\":" + Build.VERSION.SDK_INT + "}";
        }

        private void showToast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }

    // === SECURITY UTILS ===
    private String hashPassword(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), 65536, 256);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
