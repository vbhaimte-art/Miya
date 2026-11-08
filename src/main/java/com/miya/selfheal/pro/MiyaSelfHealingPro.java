package com.miya.selfheal.pro;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.util.Arrays;

/**
 * Android glue for MiyaHealCore. Needs minSdk 26 (java.nio.file).
 * NOTE: Android 10+ blocks executing binaries from app-private storage and apps cannot run
 * another app's (Termux) files. VERIFY_CMD only works if your build tool is actually executable
 * from this app's sandbox. Otherwise run MiyaHealCore.main() inside Termux (see CLI usage).
 */
public class MiyaSelfHealingPro {
    private static final String TAG = "MiyaSelfHealPro";

    static final MiyaHealCore.Logger ANDROID_LOG = new MiyaHealCore.Logger() {
        public void d(String m) { Log.d(TAG, m); }
        public void w(String m) { Log.w(TAG, m); }
        public void e(String m, Throwable t) { Log.e(TAG, m, t); }
    };

    public static class HealingWorker extends Worker {
        public HealingWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }

        @NonNull @Override
        public Result doWork() {
            String root = getInputData().getString("PROJECT_ROOT");
            String cmd = getInputData().getString("VERIFY_CMD");
            boolean apply = getInputData().getBoolean("APPLY", false);
            if (root == null || root.isEmpty() || cmd == null || cmd.trim().isEmpty()) return Result.failure();

            File work = new File(getApplicationContext().getFilesDir(), ".miya_secure");
            MiyaHealCore.Verifier verifier = new MiyaHealCore.CommandVerifier(
                    Arrays.asList(cmd.trim().split("\\s+")), 9 * 60_000L, null, new File(work, "tmp").toPath());
            try (MiyaHealCore.Engine engine = new MiyaHealCore.Engine(work, ANDROID_LOG, verifier)) {
                MiyaHealCore.Report r = engine.run(new File(root),
                        new MiyaHealCore.Options().maxFiles(5).autoFix(apply));
                Log.i(TAG, r.summary());
                return r.aborted ? Result.failure() : Result.success();
            } catch (Exception e) {
                Log.e(TAG, "Worker error", e);
                return getRunAttemptCount() < 3 ? Result.retry() : Result.failure();
            }
        }
    }

    public static class HealingOrchestrator {
        private final Context ctx;
        private final File projectRoot;
        private final String verifyCmd;

        /** verifyCmd example: "./gradlew assembleDebug --console=plain" */
        public HealingOrchestrator(Context c, File projectRoot, String verifyCmd) {
            this.ctx = c.getApplicationContext(); this.projectRoot = projectRoot; this.verifyCmd = verifyCmd;
        }

        /** apply=false -> report only (safe default). */
        public void scheduleHealing(boolean apply) {
            Constraints constraints = new Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build();
            Data input = new Data.Builder()
                    .putString("PROJECT_ROOT", projectRoot.getAbsolutePath())
                    .putString("VERIFY_CMD", verifyCmd)
                    .putBoolean("APPLY", apply)
                    .build();
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(HealingWorker.class)
                    .setConstraints(constraints).setInputData(input).addTag("miya_healing").build();
            WorkManager.getInstance(ctx).enqueue(req);
            Log.i(TAG, "Healing scheduled (apply=" + apply + ")");
        }
    }
}
