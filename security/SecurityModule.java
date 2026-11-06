package com.miya.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class SecurityModule {
    private static final String TAG = "MiyaSecurity";
    // ⚠️ APNA SIGNATURE HASH YAHAN DALEIN
    private static final String EXPECTED_SIGNATURE_HASH = "YOUR_APP_SIGNATURE_HASH_HERE"; 
    
    private static final String[] ROOT_PATHS = {
        "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
        "/system/xbin/su", "/data/local/xbin/su"
    };
    
    private static final Set<String> EMULATOR_BRANDS = new HashSet<>(Arrays.asList(
        "generic", "unknown", "google_sdk", "sdk", "vbox86p", "emulator"
    ));

    public static boolean performSecurityCheck(Context context) {
        Log.d(TAG, "Starting security audit...");
        if (isDeviceRooted()) { Log.e(TAG, "❌ Root detected!"); return false; }
        if (isEmulator()) { Log.e(TAG, "❌ Emulator detected!"); return false; }
        if (!verifyAppSignature(context)) { Log.e(TAG, "❌ Tampered APK!"); return false; }
        Log.i(TAG, "✅ All checks passed.");
        return true;
    }

    public static boolean isDeviceRooted() {
        for (String path : ROOT_PATHS) if (new File(path).exists()) return true;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"which", "su"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            if (r.readLine() != null) return true;
        } catch (Exception ignored) {}
        return Build.TAGS != null && Build.TAGS.contains("test-keys");
    }

    public static boolean isEmulator() {
        String brand = Build.BRAND.toLowerCase();
        return EMULATOR_BRANDS.contains(brand) || 
               Build.FINGERPRINT.startsWith("generic") || 
               Build.HARDWARE.contains("goldfish");
    }

    public static boolean verifyAppSignature(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                var info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = info.signingInfo.getApkContentsSigners();
            } else {
                @SuppressWarnings("deprecation") var info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
                sigs = info.signatures;
            }
            if (sigs == null || sigs.length == 0) return false;
            
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sigs[0].toByteArray());
            String hash = Base64.encodeToString(digest, Base64.NO_WRAP);
            return EXPECTED_SIGNATURE_HASH.equals(hash);
        } catch (Exception e) { return false; }
    }

    public static EncryptedSharedPreferences getSecureStorage(Context context) {
        try {
            String key = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return (EncryptedSharedPreferences) EncryptedSharedPreferences.create(
                "miya_secure_prefs", key, context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) { throw new RuntimeException("Secure storage failed", e); }
    }

    public static void triggerSelfDestruct(Context context) {
        Log.e(TAG, "🔴 SELF DESTRUCT INITIATED");
        try { getSecureStorage(context).edit().clear().apply(); } catch(Exception ignored){}
        context.getSharedPreferences("miya_tools", Context.MODE_PRIVATE).edit().clear().apply();
    }
}
