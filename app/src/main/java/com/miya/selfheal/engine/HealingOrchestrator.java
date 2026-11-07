package com.miya.selfheal.engine;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Safe self-heal engine.
 *
 * SETUP:  minSdk 26
 *         implementation "com.github.javaparser:javaparser-core:3.25.10"
 *
 * HONEST LIMITS:
 *  - Syntax is checked with a real Java parser. Code is NOT compiled on-device
 *    (no javac / android.jar), so "APPLIED_SYNTAX_OK" never means "builds".
 *    Run your real Gradle build / CI after applying.
 *  - Default mode is DRY-RUN: nothing is written unless Options.apply = true.
 *  - Callbacks are delivered on the main thread.
 *
 * USAGE:
 *   HealingOrchestrator h = new HealingOrchestrator(getApplicationContext());
 *   h.heal(root, cb);                                  // dry-run report only
 *   HealingOrchestrator.Options o = new HealingOrchestrator.Options();
 *   o.apply = true;  h.heal(root, o, cb);              // apply safe fixes
 *   h.undoLastRun(cb);                                 // revert last applied run
 */
public class HealingOrchestrator {
    private static final String TAG = "HealOrchestrator";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int KEEP_RUNS = 5;

    private static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList(
            "build", ".git", ".gradle", ".idea", "node_modules", "_heal_out", ".miya_backups"));

    private static final Set<String> RESOURCE_TYPES = new HashSet<>(Arrays.asList(
            "FileInputStream", "FileOutputStream", "FileReader", "FileWriter",
            "BufferedReader", "BufferedWriter", "BufferedInputStream", "BufferedOutputStream",
            "InputStreamReader", "OutputStreamWriter", "PrintWriter", "PrintStream",
            "Scanner", "RandomAccessFile", "Socket"));

    // ==================================================================
    // PUBLIC TYPES
    // ==================================================================

    public enum Rule {
        EMPTY_CONTROL_BODY("Empty body after if/for/while (stray ';')", false, false),
        SWALLOWED_EXCEPTION("Empty catch block swallows the exception", false, false),
        POSSIBLE_RESOURCE_LEAK("Stream/reader opened without try-with-resources or close()", false, false),
        STRING_REF_EQUALITY("String compared with ==/!= instead of equals()", true, false),
        LITERAL_LAST_EQUALS("x.equals(\"lit\") can throw NPE; \"lit\".equals(x) cannot", true, true);

        public final String title;
        public final boolean autoFix;   // fix possible at all
        public final boolean styleOnly; // only applied when Options.includeStyleFixes
        Rule(String t, boolean a, boolean s) { title = t; autoFix = a; styleOnly = s; }
    }

    public enum Status { MANUAL, PROPOSED, APPLIED_SYNTAX_OK, FAILED, ROLLED_BACK }

    public static class Options {
        /** false = dry-run (default): report only, no file is touched. */
        public boolean apply = false;
        /** Also apply style-only fixes (e.g. literal-first equals). */
        public boolean includeStyleFixes = false;
        public long maxFileBytes = 2_000_000L;
    }

    public static class Issue {
        public String id = "";
        public final String filePath;
        public final Rule rule;
        public final int line, col;
        public final String description;
        public Status status = Status.MANUAL;
        public String before, after, note;
        Edit edit; // null => manual only

        Issue(String f, Rule r, int l, int c, String d) {
            filePath = f; rule = r; line = l; col = c; description = d;
        }
    }

    public static class Result {
        public String runId = "";
        public boolean dryRun = true;
        public boolean crashed = false;
        /** Always false: this engine never compiles code. */
        public final boolean compileChecked = false;
        public int filesScanned, filesParseFailed, filesSkipped, filesFailed, inaccessible;
        public int issues, manual, proposed, applied, failed, rolledBack;
        public int restored, undoSkipped, undoFailed;
        public String backupRunDir, reportPath;

        @Override public String toString() {
            return (dryRun ? "[DRY-RUN] " : "[APPLY] ") + "files=" + filesScanned
                    + " parseFail=" + filesParseFailed + " issues=" + issues
                    + " manual=" + manual + " proposed=" + proposed
                    + " applied(syntax OK, not compiled)=" + applied
                    + " failed=" + failed + " rolledBack=" + rolledBack;
        }
    }

    public interface Callback {
        void on(String msg);
        void done(Result result);
        void err(String msg);
    }

    // ==================================================================
    // INTERNAL TYPES
    // ==================================================================

    static final class Edit {
        final int start, end;
        final String replacement;
        Edit(int s, int e, String r) { start = s; end = e; replacement = r; }
    }

    static final class FileScan {
        final File file;
        final String src, sha;
        final List<Issue> issues;
        final int stmts, methods, ctors, fields;
        FileScan(File f, String s, String h, List<Issue> i, int st, int m, int c, int fl) {
            file = f; src = s; sha = h; issues = i; stmts = st; methods = m; ctors = c; fields = fl;
        }
    }

    static final class Applied {
        final File file, backup;
        final String patchedSha;
        final List<Issue> issues;
        Applied(File f, File b, String sha, List<Issue> i) { file = f; backup = b; patchedSha = sha; issues = i; }
    }

    static final class VerifyResult {
        final boolean ok;
        final String details;
        VerifyResult(boolean o, String d) { ok = o; details = d; }
    }

    // ==================================================================
    // FIELDS
    // ==================================================================

    private final Context appCtx;
    private final JavaParser parser;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean cancelled = false;

    public HealingOrchestrator(Context c) {
        this.appCtx = c.getApplicationContext(); // Activity leak nahi
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        cfg.setTabSize(1);
        this.parser = new JavaParser(cfg);
    }

    /** Dry-run: sirf report, koi file change nahi. */
    public void heal(File root, Callback cb) { heal(root, new Options(), cb); }

    public void cancel() { cancelled = true; }

    /** Current file complete hone ke baad rukta hai (mid-write kill nahi). */
    public void shutdown() { cancelled = true; pool.shutdown(); }

    // ==================================================================
    // MAIN
    // ==================================================================

    public void heal(final File root, final Options opt, final Callback cb) {
        if (!running.compareAndSet(false, true)) {
            postErr(cb, "Heal already running");
            return;
        }
        cancelled = false;
        pool.execute(() -> {
            final String runId = String.valueOf(System.currentTimeMillis());
            final Result res = new Result();
            res.runId = runId;
            res.dryRun = !opt.apply;
            final Audit audit = new Audit(new File(appCtx.getFilesDir(), ".miya_audit/audit_" + runId + ".log"));
            final List<Applied> applied = new ArrayList<>();
            final List<Issue> all = new ArrayList<>();
            try {
                audit.log("RUN_START", "root=" + root + " apply=" + opt.apply
                        + " style=" + opt.includeStyleFixes);
                say(cb, "🔍 Scanning…");
                List<FileScan> scans = scan(root, opt, runId, res, audit, all);

                if (res.filesScanned == 0 && res.inaccessible > 0) {
                    postErr(cb, "Folder access nahi mila (storage permission?)");
                }
                say(cb, "Found " + all.size() + " issue(s) in " + scans.size() + " file(s)");

                if (opt.apply && !all.isEmpty()) {
                    say(cb, "🛠 Applying safe fixes (backup + syntax check)…");
                    applyAll(scans, opt, runId, applied, res, audit, cb);
                } else if (!all.isEmpty()) {
                    say(cb, "Dry-run: kuch change nahi kiya. Apply ke liye Options.apply = true.");
                }

                tally(all, res);
                res.reportPath = exportReport(runId, all, res);
                audit.log("RUN_END", res.toString());
            } catch (Throwable t) {
                Log.e(TAG, "Crash: " + t.getMessage(), t);
                audit.log("CRASH", String.valueOf(t));
                res.crashed = true;
                rollbackAll(applied, audit);
                tally(all, res);
                try { res.reportPath = exportReport(runId, all, res); } catch (Throwable ignored) {}
                postErr(cb, "System error: " + t.getMessage() + " — applied fixes rolled back");
            } finally {
                audit.close();
                running.set(false);
                deliver(cb, res);
            }
        });
    }

    // ==================================================================
    // SCAN
    // ==================================================================

    private List<FileScan> scan(File root, Options opt, String runId, Result res,
                                Audit audit, List<Issue> all) throws IOException {
        List<File> files = listJava(root, res, audit);
        List<FileScan> out = new ArrayList<>();
        String tag = runId.length() > 6 ? runId.substring(runId.length() - 6) : runId;
        int seq = 0;

        for (File f : files) {
            if (cancelled) { audit.log("CANCELLED", "during scan"); break; }
            res.filesScanned++;
            try {
                if (f.length() > opt.maxFileBytes) {
                    res.filesSkipped++;
                    audit.log("SKIP_BIG", f.getPath());
                    continue;
                }
                byte[] bytes = Files.readAllBytes(f.toPath());
                String src = decodeUtf8Strict(bytes);
                ParseResult<CompilationUnit> pr = parser.parse(src);
                if (!pr.isSuccessful() || !pr.getResult().isPresent()) {
                    res.filesParseFailed++;
                    audit.log("PARSE_FAIL", f.getPath() + " :: " + firstProblem(pr));
                    continue; // already-broken file ko kabhi touch nahi karte
                }
                CompilationUnit cu = pr.getResult().get();
                List<Issue> found = Analyzer.analyze(f.getAbsolutePath(), src, cu);
                if (found.isEmpty()) continue;
                for (Issue i : found) i.id = tag + "_" + (++seq);
                out.add(new FileScan(f, src, sha(bytes), found,
                        cu.findAll(Statement.class).size(),
                        cu.findAll(MethodDeclaration.class).size(),
                        cu.findAll(ConstructorDeclaration.class).size(),
                        cu.findAll(FieldDeclaration.class).size()));
                all.addAll(found);
            } catch (Exception e) {
                res.filesFailed++;
                audit.log("READ_FAIL", f.getPath() + " :: " + e);
            }
        }
        return out;
    }

    private List<File> listJava(File root, final Result res, final Audit audit) throws IOException {
        final List<File> out = new ArrayList<>();
        if (root == null || !root.exists()) return out;
        if (root.isFile()) {
            if (root.getName().endsWith(".java")) out.add(root);
            return out;
        }
        final Path rootPath = root.toPath();
        Files.walkFileTree(rootPath, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                        Path name = dir.getFileName();
                        if (name != null && !dir.equals(rootPath) && SKIP_DIRS.contains(name.toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                        Path n = file.getFileName();
                        if (n != null && n.toString().endsWith(".java") && !isGenerated(file)) {
                            out.add(file.toFile());
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        res.inaccessible++;
                        audit.log("ACCESS_DENIED", file + " :: " + e.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });
        return out;
    }

    private static boolean isGenerated(Path file) {
        String p = file.toString().replace('\\', '/');
        String n = file.getFileName().toString();
        return n.equals("R.java") || n.equals("BuildConfig.java")
                || p.contains("/generated/") || p.contains("/build/");
    }

    // ==================================================================
    // APPLY (per-file transaction: verify in memory -> backup -> atomic write -> read-back)
    // ==================================================================

    private void applyAll(List<FileScan> scans, Options opt, String runId, List<Applied> applied,
                          Result res, Audit audit, Callback cb) {
        File runDir = null;
        int idx = 0;
        for (FileScan fs : scans) {
            if (cancelled) { audit.log("CANCELLED", "during apply"); break; }

            List<Issue> fixes = new ArrayList<>();
            for (Issue i : fs.issues) {
                if (i.edit == null) continue;
                if (i.rule.styleOnly && !opt.includeStyleFixes) {
                    i.note = "Style fix skipped (Options.includeStyleFixes = false)";
                    continue;
                }
                fixes.add(i);
            }
            if (fixes.isEmpty()) continue;

            try {
                if (runDir == null) {
                    runDir = new File(new File(appCtx.getFilesDir(), ".miya_backups"), runId);
                    if (!runDir.isDirectory() && !runDir.mkdirs()) {
                        throw new IOException("Backup dir nahi bani");
                    }
                    res.backupRunDir = runDir.getAbsolutePath();
                }
                applyOne(fs, fixes, runDir, ++idx, applied, audit);
            } catch (Exception e) {
                failAll(fixes, "Apply error: " + e.getMessage());
                audit.log("APPLY_ERROR", fs.file.getPath() + " :: " + e);
            }
        }
        if (runDir != null) pruneBackups(runDir.getParentFile());
    }

    private void applyOne(FileScan fs, List<Issue> fixes, File runDir, int idx,
                          List<Applied> applied, Audit audit) throws IOException {
        List<Issue> use = nonOverlapping(fixes);

        byte[] current = Files.readAllBytes(fs.file.toPath());
        if (!sha(current).equals(fs.sha)) {
            failAll(use, "File scan ke baad change ho gayi — skipped");
            audit.log("CHANGED_SINCE_SCAN", fs.file.getPath());
            return;
        }

        // 1) memory mein patch + verify. Fail to disk ko haath hi nahi lagta.
        String patched = applyEdits(fs.src, use);
        VerifyResult vr = verifyPatched(fs, patched, use);
        if (!vr.ok) {
            failAll(use, vr.details);
            audit.log("VERIFY_FAIL", fs.file.getPath() + " :: " + vr.details);
            return;
        }

        // 2) backup (bytes + read-back check)
        File bk = new File(runDir, idx + "_" + fs.file.getName() + ".bak");
        Files.write(bk.toPath(), current);
        if (!Arrays.equals(Files.readAllBytes(bk.toPath()), current)) {
            bk.delete();
            throw new IOException("Backup verify fail");
        }
        audit.log("BACKUP", fs.file.getPath() + " -> " + bk.getPath());

        // 3) atomic write + read-back
        byte[] out = patched.getBytes(StandardCharsets.UTF_8);
        try {
            atomicWrite(fs.file, out);
            if (!Arrays.equals(Files.readAllBytes(fs.file.toPath()), out)) {
                throw new IOException("Read-back mismatch");
            }
        } catch (IOException e) {
            boolean ok = restore(bk, fs.file);
            for (Issue i : use) {
                i.status = ok ? Status.ROLLED_BACK : Status.FAILED;
                i.not
