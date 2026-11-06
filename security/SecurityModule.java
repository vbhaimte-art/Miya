package com.miya.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebStorage;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.google.android.play.core.integrity.IntegrityManager;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.IntegrityTokenRequest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Real, working security module.
 *
 * NOTE: Client-side checks sirf speed-bump hain. Asli bharosa ke liye
 * Play Integrity token ko apne SERVER pe verify karo.
 *
 * build.gradle (app) mein add karo:
 *   buildConfigField "String", "EXPECTED_SIG_SHA256", "\"AA:BB:CC:...\""   // keytool -list -v se SHA256
 *   implementation "androidx.security:security-crypto:1.1.0-alpha06"
 *   implementation "com.google.android.play:integrity:1.4.0"
 *
 * AndroidManifest.xml mein (Android 11+ package detection ke liye):
 *   <queries>
 *     <package android:name="com.topjohnwu.magisk"/>
 *     <package android:name="eu.chainfire.supersu"/>
 *     <package android:name="com.koushikdutta.superuser"/>
 *     <package android:name="com.noshufou.android.su"/>
 *   </queries>
 */
public final class SecurityModule {

    private SecurityModule() {}

    public enum Threat { NONE, TAMPERED, DEBUGGER, HOOKING, ROOT, EMULATOR }

    public interface ThreatCallback { void onResult(Threat threat); }

    public interface IntegrityCallback {
        void onToken(String token);
        void onError(Exception e);
    }

    private static final String PREFS_NAME = "miya_secure_prefs";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final List<String> ROOT_PATHS = Arrays.asList(
            "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
            "/sbin/su", "/system/bin/su", "/system/xbin/su", "/system/sd/xbin/su",
            "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
            "/su/bin/su", "/system/bin/failsafe/su", "/magisk", "/sbin/.magisk"
    );

    private static final List<String> ROOT_PACKAGES = Arrays.asList(
            "com.topjohnwu.magisk", "eu.chainfire.supersu",
            "com.koushikdutta.superuser", "com.noshufou.android.su",
            "com.thirdparty.superuser"
    );

    // ------------------------------------------------------------------
    // 1. MAIN ENTRY: checks background thread pe chalte hain, result main pe
    // ------------------------------------------------------------------
    public static void runChecksAsync(final Context context, final ThreatCallback cb) {
        final Context app = context.getApplicationContext();
        EXEC.execute(() -> {
            final Threat t = scan(app);
            MAIN.post(() -> cb.onResult(t));
        });
    }

    /** Blocking version, main thread pe mat chalao. */
    public static Threat scan(Context context) {
        boolean debugBuild = (context.getApplicationInfo().flags
                & ApplicationInfo.FLAG_DEBUGGABLE) != 0;

        if (!verifyAppSignature(context)) return Threat.TAMPERED;

        // Debug build pe dev ko block mat karo
        if (!debugBuild) {
            if (isDebuggerAttached(context)) return Threat.DEBUGGER;
            if (isHooked()) return Threat.HOOKING;
            if (isDeviceRooted(context)) return Threat.ROOT;
            if (isEmulator()) return Threat.EMULATOR;
        }
        return Threat.NONE;
    }

    /** Threat mila to real wipe + app band. Activity se call karo. */
    public static void enforce(Context context, Threat threat) {
        if (threat == Threat.NONE) return;
        selfDestruct(context); // process kill ho jata hai
    }

    // ------------------------------------------------------------------
    // 2. SIGNATURE VERIFY (hash BuildConfig se, SHA-256 hex, constant-time compare)
    // ------------------------------------------------------------------
    @SuppressWarnings("deprecation")
    public static boolean verifyAppSignature(Context context) {
        try {
            String expected = normalizeHex(BuildConfig.EXPECTED_SIG_SHA256);
            if (expected.length() != 64) return false; // placeholder / galat format

            PackageManager pm = context.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = pm.getPackageInfo(context.getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo == null) return false;
                // multiple signers = suspicious; key rotation ke liye history bhi dekho
                sigs = info.signingInfo.hasMultipleSigners()
                        ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
            } else {
                PackageInfo info = pm.getPackageInfo(context.getPackageName(),
                        PackageManager.GET_SIGNATURES);
                sigs = info.signatures;
            }
            if (sigs == null || sigs.length == 0) return false;

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] expectedBytes = expected.getBytes("UTF-8");
            for (Signature s : sigs) {
                String hash = toHex(md.digest(s.toByteArray()));
                if (MessageDigest.isEqual(hash.getBytes("UTF-8"), expectedBytes)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 3. ROOT DETECTION (paths + packages + test-keys + rw /system + su)
    // ------------------------------------------------------------------
    public static boolean isDeviceRooted(Context context) {
        for (String p : ROOT_PATHS) if (new File(p).exists()) return true;

        PackageManager pm = context.getPackageManager();
        for (String pkg : ROOT_PACKAGES) {
            try { pm.getPackageInfo(pkg, 0); return true; }
            catch (PackageManager.NameNotFoundException ignored) {}
        }

        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) return true;
        if (isSystemWritable()) return true;
        return canExecuteSu();
    }

    private static boolean isSystemWritable() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length < 4) continue;
                String mountPoint = parts[1];
                if (mountPoint.equals("/system") || mountPoint.equals("/system_ext")
                        || mountPoint.equals("/vendor")) {
                    for (String opt : parts[3].split(",")) {
                        if (opt.equals("rw")) return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean canExecuteSu() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"which", "su"});
            if (!p.waitFor(2, TimeUnit.SECONDS)) return false;
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            return r.readLine() != null;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    // ------------------------------------------------------------------
    // 4. EMULATOR DETECTION (broad; real phones ko block nahi karta)
    // ------------------------------------------------------------------
    public static boolean isEmulator() {
        String fp = lower(Build.FINGERPRINT), model = lower(Build.MODEL),
                manuf = lower(Build.MANUFACTURER), hw = lower(Build.HARDWARE),
                prod = lower(Build.PRODUCT), board = lower(Build.BOARD),
                brand = lower(Build.BRAND), device = lower(Build.DEVICE);

        int score = 0;
        if (fp.startsWith("generic") || fp.contains("emulator") || fp.contains("sdk_gphone")) score++;
        if (model.contains("emulator") || model.contains("android sdk built for")
                || model.contains("sdk_gphone")) score++;
        if (manuf.contains("genymotion")) score += 2;
        if (hw.contains("goldfish") || hw.contains("ranchu") || hw.contains("vbox")) score++;
        if (prod.contains("sdk") || prod.contains("emulator") || prod.contains("simulator")
                || prod.contains("vbox")) score++;
        if (board.contains("nox") || hw.contains("nox") || prod.contains("bluestacks")
                || model.contains("bluestacks")) score += 2;
        if (brand.startsWith("generic") && device.startsWith("generic")) score++;
        if (new File("/dev/qemu_pipe").exists() || new File("/dev/socket/qemud").exists()
                || new File("/system/lib/libc_malloc_debug_qemu.so").exists()) score += 2;

        return score >= 2; // 1 signal akela kaafi nahi, custom-ROM phones safe rahenge
    }

    // ------------------------------------------------------------------
    // 5. DEBUGGER / HOOKING (Frida, Xposed)
    // ------------------------------------------------------------------
    public static boolean isDebuggerAttached(Context context) {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger();
    }

    public static boolean isHooked() {
        // Xposed / LSPosed
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (ClassNotFoundException ignored) {}

        // Frida default port
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 27042), 200);
            return true;
        } catch (Exception ignored) {}

        // Process memory mein frida / xposed libs
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String l = line.toLowerCase(Locale.ROOT);
                if (l.contains("frida") || l.contains("gum-js-loop")
                        || l.contains("xposed") || l.contains("lsposed")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ------------------------------------------------------------------
    // 6. SECURE STORAGE (crash nahi karta; corrupt keystore pe reset karta hai)
    // ------------------------------------------------------------------
    public static SharedPreferences getSecureStorage(Context context) {
        Context app = context.getApplicationContext();
        try {
            return createPrefs(app);
        } catch (Exception first) {
            // Keystore/prefs corrupt (backup restore, OS update). Reset karke retry.
            try {
                app.deleteSharedPreferences(PREFS_NAME);
                deleteKeystoreEntries();
                return createPrefs(app);
            } catch (Exception second) {
                throw new IllegalStateException("Secure storage unavailable", second);
            }
        }
    }

    private static SharedPreferences createPrefs(Context app) throws Exception {
        MasterKey key = new MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return EncryptedSharedPreferences.create(
                app, PREFS_NAME, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    // ------------------------------------------------------------------
    // 7. REAL SELF-DESTRUCT: WebView, files, DBs, prefs, cache, Keystore keys
    // ------------------------------------------------------------------
    public static void selfDestruct(Context context) {
        final Context app = context.getApplicationContext();

        // WebView data main thread pe hi clear hota hai
        runOnMainAndWait(() -> {
            try {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                WebStorage.getInstance().deleteAllData();
            } catch (Exception ignored) {}
        });

        deleteKeystoreEntries();

        // Pura private data dir (files, databases, shared_prefs, app_webview...)
        deleteContents(app.getDataDir());
        deleteContents(app.getCacheDir());
        deleteContents(app.getCodeCacheDir());
        deleteContents(app.getNoBackupFilesDir());
        File ext = app.getExternalCacheDir();
        if (ext != null) deleteContents(ext);
        for (File dir : app.getExternalFilesDirs(null)) {
            if (dir != null) deleteContents(dir);
        }

        // Final guarantee: system level pe app data wipe + process kill
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.clearApplicationUserData();
        } catch (Exception ignored) {}

        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private static void deleteKeystoreEntries() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            for (String alias : Collections.list(ks.aliases())) {
                ks.deleteEntry(alias); // ye keys sirf is app ki hain
            }
        } catch (Exception ignored) {}
    }

    private static void deleteContents(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) deleteRecursive(f);
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void runOnMainAndWait(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) { r.run(); return; }
        final CountDownLatch latch = new CountDownLatch(1);
        MAIN.post(() -> { try { r.run(); } finally { latch.countDown(); } });
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    // ------------------------------------------------------------------
    // 8. PLAY INTEGRITY (token lo, SERVER pe verify karo)
    // ------------------------------------------------------------------
    /**
     * @param nonce  server se aaya base64 URL-safe random string (replay rokne ke liye)
     * @param cloudProjectNumber Google Cloud project number
     * Token ko apne backend pe bhejo; backend Google ke decodeIntegrityToken API se
     * verdict check kare (appRecognitionVerdict, deviceRecognitionVerdict).
     */
    public static void requestIntegrityToken(Context context, String nonce,
                                             long cloudProjectNumber,
                                             final IntegrityCallback cb) {
        IntegrityManager im = IntegrityManagerFactory.create(context.getApplicationContext());
        im.requestIntegrityToken(
                IntegrityTokenRequest.builder()
                        .setNonce(nonce)
                        .setCloudProjectNumber(cloudProjectNumber)
                        .build())
                .addOnSuccessListener(r -> cb.onToken(r.token()))
                .addOnFailureListener(cb::onError);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------
    private static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }

    private static String normalizeHex(String s) {
        return s == null ? "" : s.replace(":", "").replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
