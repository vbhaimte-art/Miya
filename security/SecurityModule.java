package com.miya.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
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
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Security module (fixed).
 *
 * NOTE: Client-side checks sirf speed-bump hain. Asli bharosa ke liye
 * Play Integrity token ko apne SERVER pe verify karo.
 *
 * build.gradle (app):
 *   buildConfigField "String", "EXPECTED_SIG_SHA256", "\"AA:BB:CC:...\""
 *   implementation "androidx.security:security-crypto:1.1.0-alpha06"
 *   implementation "com.google.android.play:integrity:1.4.0"
 *
 * AndroidManifest.xml:
 *   <queries>
 *     <package android:name="com.topjohnwu.magisk"/>
 *     <package android:name="eu.chainfire.supersu"/>
 *     <package android:name="com.koushikdutta.superuser"/>
 *     <package android:name="com.noshufou.android.su"/>
 *   </queries>
 */
public final class SecurityModule {

    private SecurityModule() {}

    public enum Threat { NONE, TAMPERED, DEBUGGER, HOOKING, CLONED, ROOT, EMULATOR }

    public interface ThreatCallback { void onResult(Threat threat); }

    public interface IntegrityCallback {
        void onToken(String token);
        void onError(Exception e);
    }

    private static final String PREFS_NAME = "miya_secure_prefs";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** In threats pe poora data wipe hoga. ROOT/EMULATOR pe sirf app band (false-positive safety). */
    private static final Set<Threat> WIPE_ON =
            EnumSet.of(Threat.TAMPERED, Threat.HOOKING, Threat.DEBUGGER);

    private static final List<String> ROOT_PATHS = Arrays.asList(
            "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
            "/sbin/su", "/system/bin/su", "/system/xbin/su", "/system/sd/xbin/su",
            "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
            "/su/bin/su", "/system/bin/failsafe/su", "/magisk", "/sbin/.magisk",
            "/sbin/.core", "/system/xbin/daemonsu", "/system/bin/.ext/.su",
            "/system/usr/we-need-root/su-backup", "/data/adb/magisk", "/data/adb/ksu",
            "/data/adb/ap", "/data/adb/modules", "/debug_ramdisk", "/system/xbin/busybox"
    );

    private static final List<String> ROOT_PACKAGES = Arrays.asList(
            "com.topjohnwu.magisk", "eu.chainfire.supersu",
            "com.koushikdutta.superuser", "com.noshufou.android.su",
            "com.thirdparty.superuser",
            // KernelSU / APatch / Magisk forks / hide tools / hooking managers
            "me.weishu.kernelsu", "me.bmax.apatch", "io.github.vvb2060.magisk",
            "io.github.huskydg.magisk", "com.devadvance.rootcloak", "com.devadvance.rootcloakplus",
            "com.amphoras.hidemyroot", "com.formyhm.hideroot", "com.zachspong.temprootremovejb",
            "de.robv.android.xposed.installer", "org.lsposed.manager", "io.github.lsposed.manager",
            "com.saurik.substrate", "com.ramdroid.appquarantine", "com.chelpus.lackypatch"
    );

    private static SharedPreferences cachedPrefs;

    // ------------------------------------------------------------------
    // 1. MAIN ENTRY
    // ------------------------------------------------------------------
    public static void runChecksAsync(final Context context, final ThreatCallback cb) {
        final Context app = context.getApplicationContext();
        EXEC.execute(() -> {
            Threat t;
            try {
                t = scan(app);
            } catch (Throwable e) {
                // FIX: exception pe bhi callback zaroor aaye
                t = Threat.NONE;
            }
            final Threat result = t;
            MAIN.post(() -> cb.onResult(result));
        });
    }

    /** Blocking version, main thread pe mat chalao. */
    public static Threat scan(Context context) {
        // FIX: manifest flag attacker badal sakta hai; BuildConfig.DEBUG compile-time hai
        if (BuildConfig.DEBUG) return Threat.NONE;

        if (!verifyAppSignature(context)) return Threat.TAMPERED;
        if (isDebuggerAttached(context)) return Threat.DEBUGGER;
        if (isHooked()) return Threat.HOOKING;
        if (isCloned(context)) return Threat.CLONED;
        if (isDeviceRooted(context)) return Threat.ROOT;
        if (isEmulator()) return Threat.EMULATOR;
        return Threat.NONE;
    }

    /** Threat mila to action lo. File IO background thread pe hota hai. */
    public static void enforce(final Context context, final Threat threat) {
        if (threat == Threat.NONE) return;
        final Context app = context.getApplicationContext();
        EXEC.execute(() -> {
            if (WIPE_ON.contains(threat)) {
                selfDestruct(app); // process kill ho jata hai
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
    }

    // ------------------------------------------------------------------
    // 2. SIGNATURE VERIFY
    // ------------------------------------------------------------------
    @SuppressWarnings("deprecation")
    public static boolean verifyAppSignature(Context context) {
        try {
            String expected = normalizeHex(BuildConfig.EXPECTED_SIG_SHA256);
            if (expected.length() != 64) return false;

            PackageManager pm = context.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = pm.getPackageInfo(context.getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo == null) return false;
                // FIX: multiple signers = suspicious -> reject
                if (info.signingInfo.hasMultipleSigners()) return false;
                // key rotation ke liye history (lineage attacker forge nahi kar sakta)
                sigs = info.signingInfo.getSigningCertificateHistory();
            } else {
                PackageInfo info = pm.getPackageInfo(context.getPackageName(),
                        PackageManager.GET_SIGNATURES);
                sigs = info.signatures;
            }
            if (sigs == null || sigs.length == 0) return false;

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
            for (Signature s : sigs) {
                String hash = toHex(md.digest(s.toByteArray()));
                if (MessageDigest.isEqual(hash.getBytes(StandardCharsets.UTF_8), expectedBytes)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 3. ROOT DETECTION
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
        if (hasRootMounts()) return true;          // Magisk/KernelSU/APatch overlays (hidden package bhi pakda jata hai)
        if (mapsContains("magisk", "zygisk", "riru", "kernelsu")) return true;
        if (hasRootProps()) return true;
        if (isSelinuxPermissive()) return true;
        return canExecuteSu();
    }

    /** Magisk Hide / renamed manager ke baad bhi mount table mein nishaan reh jata hai. */
    private static boolean hasRootMounts() {
        String[] files = {"/proc/self/mountinfo", "/proc/mounts"};
        for (String f : files) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String l = line.toLowerCase(Locale.ROOT);
                    if (l.contains("magisk") || l.contains("/.magisk") || l.contains("ksu")
                            || l.contains("apatch") || l.contains("/adb/modules")
                            || l.contains("core/mirror")) return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static boolean hasRootProps() {
        return "1".equals(sysProp("ro.debuggable")) || "0".equals(sysProp("ro.secure"))
                || "1".equals(sysProp("service.adb.root"));
    }

    private static boolean isSelinuxPermissive() {
        try (BufferedReader r = new BufferedReader(new FileReader("/sys/fs/selinux/enforce"))) {
            String v = r.readLine();
            return v != null && v.trim().equals("0");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean mapsContains(String... needles) {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String l = line.toLowerCase(Locale.ROOT);
                for (String n : needles) if (l.contains(n)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String sysProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return (String) c.getMethod("get", String.class).invoke(null, key);
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // 3b. APP CLONER / VIRTUAL SPACE (Parallel Space, VirtualApp, Island, Dual Space)
    // ------------------------------------------------------------------
    public static boolean isCloned(Context context) {
        try {
            String path = context.getFilesDir().getAbsolutePath();
            boolean normalRoot = path.startsWith("/data/user/") || path.startsWith("/data/data/")
                    || path.startsWith("/data/user_de/");
            return !normalRoot || !path.contains(context.getPackageName());
        } catch (Exception e) {
            return false;
        }
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
        // FIX: Process.waitFor(timeout) API 26+ hai
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"which", "su"});
            if (!p.waitFor(2, TimeUnit.SECONDS)) return false;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                return r.readLine() != null;
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    // ------------------------------------------------------------------
    // 4. EMULATOR DETECTION
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

        // Nox, MEmu, LDPlayer, BlueStacks, Andy, Droid4X, Genymotion, WSA, VirtualBox
        String[] emuFiles = {
                "/system/bin/nox", "/system/bin/nox-prop", "/system/bin/microvirt-prop",
                "/system/lib/libdroid4x.so", "/system/bin/androVM-prop", "/system/bin/ttVM-prop",
                "/system/bin/qemu-props", "/dev/vboxguest", "/dev/vboxuser", "/sys/qemu_trace",
                "/fstab.ranchu", "/init.ranchu.rc", "/ueventd.ranchu.rc", "/fstab.nox",
                "/init.nox.rc", "/ueventd.nox.rc", "/fstab.andy", "/ueventd.andy.rc",
                "/system/bin/windroyed", "/data/data/com.bluestacks.home",
                "/data/data/com.bluestacks.settings", "/mnt/windows/BstSharedFolder"
        };
        for (String f : emuFiles) {
            if (new File(f).exists()) { score += 2; break; }
        }
        if (manuf.contains("microvirt") || model.contains("ldplayer") || brand.contains("ldplayer")
                || model.contains("memu") || model.contains("andy") || model.contains("droid4x")) score += 2;
        if ("1".equals(sysProp("ro.kernel.qemu")) || sysProp("ro.hardware.audio.primary").equals("goldfish")) score += 2;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && Build.SUPPORTED_ABIS != null
                && Arrays.asList(Build.SUPPORTED_ABIS).contains("x86") && score >= 1) score++;

        return score >= 2;
    }

    // ------------------------------------------------------------------
    // 5. DEBUGGER / HOOKING
    // ------------------------------------------------------------------
    public static boolean isDebuggerAttached(Context context) {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true;
        // IDA android_server (23946) / gdbserver (5039) default ports
        if (isPortOpen(23946) || isPortOpen(5039)) return true;
        // ptrace-based debugger (gdb/lldb/frida): TracerPid != 0
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("TracerPid:")) {
                    return !line.substring(10).trim().equals("0");
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean isPortOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 150);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Renamed/custom frida-server bhi D-Bus AUTH ka jawab "REJECT" se deta hai. */
    private static boolean fridaServerResponds() {
        int[] ports = {27042, 27043, 27047};
        for (int port : ports) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 150);
                s.setSoTimeout(300);
                s.getOutputStream().write(new byte[]{0, 'A', 'U', 'T', 'H', '\r', '\n'});
                s.getOutputStream().flush();
                byte[] buf = new byte[64];
                int n = s.getInputStream().read(buf);
                if (n > 0 && new String(buf, 0, n, StandardCharsets.UTF_8).contains("REJECT")) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    /** Frida ke threads: gum-js-loop, pool-frida, gdbus+gmain. */
    private static boolean hasFridaThreads() {
        try {
            File[] tasks = new File("/proc/self/task").listFiles();
            if (tasks == null) return false;
            boolean gdbus = false, gmain = false;
            for (File t : tasks) {
                try (BufferedReader r = new BufferedReader(new FileReader(new File(t, "comm")))) {
                    String name = r.readLine();
                    if (name == null) continue;
                    name = name.trim().toLowerCase(Locale.ROOT);
                    if (name.equals("gum-js-loop") || name.equals("pool-frida") || name.contains("frida")) return true;
                    if (name.equals("gdbus")) gdbus = true;
                    if (name.equals("gmain")) gmain = true;
                } catch (Exception ignored) {}
            }
            return gdbus && gmain;
        } catch (Exception e) {
            return false;
        }
    }

    /** Xposed/LSPosed/Substrate: classes + stack trace mein hook frames. */
    private static boolean hasHookingTraces() {
        String[] classes = {
                "de.robv.android.xposed.XposedBridge", "de.robv.android.xposed.XposedHelpers",
                "com.saurik.substrate.MS", "io.github.lsposed.lspd.core.Main"
        };
        for (String c : classes) {
            try {
                Class.forName(c, false, SecurityModule.class.getClassLoader());
                return true;
            } catch (Throwable ignored) {}
        }
        try {
            throw new Exception("probe");
        } catch (Exception e) {
            for (StackTraceElement el : e.getStackTrace()) {
                String cn = el.getClassName();
                if (cn.contains("de.robv.android.xposed") || cn.contains("com.saurik.substrate")
                        || cn.contains("lsposed") || cn.contains("edxp")) return true;
            }
        }
        return false;
    }

    public static boolean isHooked() {
        if (hasHookingTraces() || hasFridaThreads() || fridaServerResponds()) return true;

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 27042), 200);
            return true;
        } catch (Exception ignored) {}

        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String l = line.toLowerCase(Locale.ROOT);
                if (l.contains("frida") || l.contains("xposed") || l.contains("lsposed")
                        || l.contains("linjector") || l.contains("substrate") || l.contains("edxp")
                        || l.contains("libgadget") || l.contains("frida-gadget")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ------------------------------------------------------------------
    // 6. SECURE STORAGE (synchronized + cached)
    // ------------------------------------------------------------------
    public static synchronized SharedPreferences getSecureStorage(Context context) {
        if (cachedPrefs != null) return cachedPrefs;
        Context app = context.getApplicationContext();
        try {
            cachedPrefs = createPrefs(app);
        } catch (Exception first) {
            try {
                deleteSharedPrefsCompat(app, PREFS_NAME);
                deleteKeystoreEntries();
                cachedPrefs = createPrefs(app);
            } catch (Exception second) {
                throw new IllegalStateException("Secure storage unavailable", second);
            }
        }
        return cachedPrefs;
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

    private static void deleteSharedPrefsCompat(Context app, String name) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            app.deleteSharedPreferences(name);
        } else {
            File dir = new File(dataDir(app), "shared_prefs");
            new File(dir, name + ".xml").delete();
        }
    }

    // ------------------------------------------------------------------
    // 7. SELF-DESTRUCT
    // ------------------------------------------------------------------
    public static void selfDestruct(Context context) {
        final Context app = context.getApplicationContext();

        runOnMainAndWait(() -> {
            try {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                WebStorage.getInstance().deleteAllData();
            } catch (Exception ignored) {}
        });

        synchronized (SecurityModule.class) { cachedPrefs = null; }
        deleteKeystoreEntries();

        deleteContents(dataDir(app));
        deleteContents(app.getCacheDir());
        deleteContents(app.getCodeCacheDir());
        deleteContents(app.getNoBackupFilesDir());
        File ext = app.getExternalCacheDir();
        if (ext != null) deleteContents(ext);
        File[] extDirs = app.getExternalFilesDirs(null);
        if (extDirs != null) {
            for (File dir : extDirs) if (dir != null) deleteContents(dir);
        }

        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.clearApplicationUserData();
        } catch (Exception ignored) {}

        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /** FIX: getDataDir() API 24+; purane devices ke liye fallback. */
    private static File dataDir(Context app) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return app.getDataDir();
        return app.getFilesDir().getParentFile();
    }

    private static void deleteKeystoreEntries() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            for (String alias : Collections.list(ks.aliases())) {
                ks.deleteEntry(alias);
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

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String toHex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
