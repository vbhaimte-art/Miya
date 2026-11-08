package com.miya.selfheal.pro;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MIYA SELF-HEALING CORE (pure Java, no Android dependency, needs API 26+ on Android).
 * Detect -> generate line-scoped fix -> backup -> journal -> atomic patch -> build verify -> rollback on failure.
 * Android glue lives in MiyaSelfHealingPro.java. This file also has a CLI main() so it can run inside Termux.
 */
public final class MiyaHealCore {
    private MiyaHealCore() {}

    // ------------------------------------------------------------------ logging
    public interface Logger {
        void d(String m);
        void w(String m);
        void e(String m, Throwable t);
        Logger STDOUT = new Logger() {
            public void d(String m) { System.out.println("[D] " + m); }
            public void w(String m) { System.out.println("[W] " + m); }
            public void e(String m, Throwable t) { System.out.println("[E] " + m + (t == null ? "" : " :: " + t)); }
        };
        Logger NOOP = new Logger() {
            public void d(String m) {}
            public void w(String m) {}
            public void e(String m, Throwable t) {}
        };
    }

    // ------------------------------------------------------------------ file utils
    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) { sb.append(Character.forDigit((b >> 4) & 0xF, 16)); sb.append(Character.forDigit(b & 0xF, 16)); }
        return sb.toString();
    }

    static String sha256(byte[] data) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String sha256(Path p) throws IOException { return sha256(Files.readAllBytes(p)); }

    /** Write tmp file in same dir, fsync, then atomic move (falls back to plain move if FS can't do atomic). */
    static void writeAtomic(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".miya.tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer bb = ByteBuffer.wrap(data);
            while (bb.hasRemaining()) ch.write(bb);
            ch.force(true);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Strict UTF-8 read: returns null for binary/non-UTF8 so we never corrupt a file by re-encoding. */
    static String readUtf8Strict(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) { return null; }
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: if (c < ' ') sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ audit
    public interface AuditSink {
        /** Must be durable (flushed) when it returns true. */
        boolean record(String jsonLine);
        void close();
    }

    public static final class FileAuditSink implements AuditSink {
        private final FileOutputStream fos;
        public FileAuditSink(File dir) throws IOException {
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            File f = new File(dir, "repair_" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()) + ".jsonl");
            fos = new FileOutputStream(f, true);
        }
        @Override public synchronized boolean record(String line) {
            try {
                fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
                return true;
            } catch (IOException e) { return false; }
        }
        @Override public synchronized void close() { try { fos.close(); } catch (IOException ignored) {} }
    }

    // ------------------------------------------------------------------ issue + state machine
    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }
    public enum Status { DETECTED, FIX_GENERATED, BACKED_UP, APPLIED, VERIFIED, FAILED, ROLLED_BACK, REPORTED }

    public static final class AuditFailureException extends RuntimeException {
        public AuditFailureException(String m) { super(m); }
    }

    public static final class Issue {
        public final String id = UUID.randomUUID().toString();
        public final String filePath, ruleId, description, lineText;
        public final int line; // 1-based
        public final Severity severity;
        public final List<String> context;
        private Status status = Status.DETECTED;
        private AuditSink audit;
        private final List<String> history = new ArrayList<>();
        String fixedLine, reasoning, backupPath, backupHash, patchedHash, failureReason;

        Issue(String filePath, int line, String description, Severity sev, String ruleId, String lineText, List<String> ctx) {
            if (filePath == null || filePath.isEmpty()) throw new IllegalArgumentException("empty path");
            if (line <= 0) throw new IllegalArgumentException("bad line");
            if (ruleId == null || ruleId.isEmpty()) throw new IllegalArgumentException("empty rule");
            this.filePath = filePath; this.line = line; this.description = description; this.severity = sev;
            this.ruleId = ruleId; this.lineText = lineText;
            this.context = Collections.unmodifiableList(new ArrayList<>(ctx));
        }

        public synchronized Status status() { return status; }
        public synchronized List<String> history() { return new ArrayList<>(history); }
        public String failureReason() { return failureReason; }
        public String suggestedFix() { return fixedLine; }

        synchronized void attach(AuditSink sink) {
            this.audit = sink;
            if (sink != null && !sink.record(json(null, Status.DETECTED, "Detected by " + ruleId)))
                throw new AuditFailureException("audit write failed");
        }

        private String json(Status from, Status to, String msg) {
            return "{\"ts\":" + System.currentTimeMillis() + ",\"issue\":\"" + id + "\",\"file\":\"" + escapeJson(filePath)
                    + "\",\"line\":" + line + ",\"rule\":\"" + ruleId + "\",\"from\":\"" + (from == null ? "null" : from.name())
                    + "\",\"to\":\"" + to.name() + "\",\"msg\":\"" + escapeJson(msg) + "\"}";
        }

        private static boolean valid(Status f, Status t) {
            switch (f) {
                case DETECTED:      return t == Status.FIX_GENERATED || t == Status.REPORTED || t == Status.FAILED;
                case FIX_GENERATED: return t == Status.BACKED_UP || t == Status.REPORTED || t == Status.FAILED;
                case BACKED_UP:     return t == Status.APPLIED || t == Status.FAILED;
                case APPLIED:       return t == Status.VERIFIED || t == Status.FAILED || t == Status.ROLLED_BACK;
                case FAILED:        return t == Status.ROLLED_BACK;
                default:            return false;
            }
        }

        /** Audit is written (and fsynced) BEFORE the state changes; if audit fails, state is untouched. */
        synchronized void transition(Status to, String msg) {
            Status from = status;
            if (!valid(from, to)) throw new IllegalStateException("Invalid transition " + from + " -> " + to);
            if (audit != null && !audit.record(json(from, to, msg))) throw new AuditFailureException("audit write failed");
            status = to;
            history.add(from + "->" + to + ": " + msg);
        }

        synchronized void note(String msg) {
            history.add("NOTE: " + msg);
            if (audit != null) audit.record(json(status, status, msg));
        }
    }

    // ------------------------------------------------------------------ detector
    public static final class Options {
        public int maxFiles = 10;
        public int maxFixes = 20;
        public boolean autoFix = false;   // false = report only
        public boolean dryRun = false;    // true = generate fixes, touch nothing
        public Options maxFiles(int v) { maxFiles = v; return this; }
        public Options maxFixes(int v) { maxFixes = v; return this; }
        public Options autoFix(boolean v) { autoFix = v; return this; }
        public Options dryRun(boolean v) { dryRun = v; return this; }
    }

    public static final class Detector {
        private static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList(
                "build", ".git", ".gradle", ".idea", "node_modules", ".miya_secure"));
        private static final Pattern SQLI = Pattern.compile("\\b(?:rawQuery|execSQL)\\s*\\(\\s*\"[^\"]*\"\\s*\\+");
        private static final Pattern SECRET = Pattern.compile(
                "(?i)\\bString\\s+\\w*(?:password|passwd|secret|token|api_?key)\\w*\\s*=\\s*\"([^\"]{6,})\"");
        private static final Pattern EXEC = Pattern.compile("\\bRuntime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec\\s*\\(");
        private static final Pattern LOOKS_LIKE_KEY_NAME = Pattern.compile("^[a-z_.]+$");

        public List<Issue> scan(Path root, Options o) throws IOException {
            List<Path> files = new ArrayList<>();
            collect(root, files);
            Collections.sort(files);
            List<Issue> all = new ArrayList<>();
            int filesWithIssues = 0;
            for (Path f : files) {
                List<Issue> found = analyze(f);
                if (found.isEmpty()) continue;
                if (++filesWithIssues > o.maxFiles) break;
                all.addAll(found);
            }
            return all;
        }

        private void collect(Path dir, List<Path> out) throws IOException {
            File[] kids = dir.toFile().listFiles();
            if (kids == null) return;
            for (File k : kids) {
                if (k.isDirectory()) { if (!SKIP_DIRS.contains(k.getName())) collect(k.toPath(), out); }
                else if (k.getName().endsWith(".java")) out.add(k.toPath());
            }
        }

        static String[] stripComments(String[] lines) {
            String[] out = new String[lines.length];
            boolean inBlock = false;
            for (int n = 0; n < lines.length; n++) {
                String line = lines[n];
                StringBuilder sb = new StringBuilder();
                boolean inStr = false; char q = 0;
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    char nx = i + 1 < line.length() ? line.charAt(i + 1) : 0;
                    if (inBlock) { if (c == '*' && nx == '/') { inBlock = false; i++; } continue; }
                    if (inStr) {
                        sb.append(c);
                        if (c == '\\' && i + 1 < line.length()) sb.append(line.charAt(++i));
                        else if (c == q) inStr = false;
                        continue;
                    }
                    if (c == '"' || c == '\'') { inStr = true; q = c; sb.append(c); continue; }
                    if (c == '/' && nx == '/') break;
                    if (c == '/' && nx == '*') { inBlock = true; i++; continue; }
                    sb.append(c);
                }
                out[n] = sb.toString();
            }
            return out;
        }

        List<Issue> analyze(Path file) throws IOException {
            String content = readUtf8Strict(file);
            if (content == null) return Collections.emptyList();
            String[] lines = content.split("\n", -1);
            String[] code = stripComments(lines);
            List<Issue> res = new ArrayList<>();
            String path = file.toAbsolutePath().toString();
            for (int i = 0; i < lines.length; i++) {
                String c = code[i];
                if (c.isEmpty()) continue;
                if (SQLI.matcher(c).find())
                    res.add(mk(path, i, lines, "SQL Injection Risk", Severity.CRITICAL, "RULE-SQLI"));
                Matcher m = SECRET.matcher(c);
                if (m.find() && !LOOKS_LIKE_KEY_NAME.matcher(m.group(1)).matches())
                    res.add(mk(path, i, lines, "Hardcoded Secret", Severity.HIGH, "RULE-SECRET"));
                if (EXEC.matcher(c).find())
                    res.add(mk(path, i, lines, "Unsafe Command Execution", Severity.MEDIUM, "RULE-EXEC"));
            }
            return res;
        }

        private Issue mk(String path, int idx, String[] lines, String desc, Severity s, String rule) {
            List<String> ctx = new ArrayList<>();
            for (int i = Math.max(0, idx - 2); i <= Math.min(lines.length - 1, idx + 2); i++) ctx.add(lines[i]);
            return new Issue(path, idx + 1, desc, s, rule, lines[idx], ctx);
        }
    }

    // ------------------------------------------------------------------ fixer (line-scoped, conservative)
    public interface Fixer {
        /** Return replacement for issue.lineText (must stay ONE line), or null if no safe automatic fix exists. */
        String fixLine(Issue issue);
    }

    /**
     * Only fixes the strict, provably-equivalent case:  rawQuery("... col=" + var, null)
     * -> rawQuery("... col=?", new String[]{String.valueOf(var)})
     * Secrets / exec are REPORT-ONLY: an automatic edit there would still compile but change behaviour.
     */
    public static final class SafeLineFixer implements Fixer {
        private static final Pattern SQL = Pattern.compile(
                "(rawQuery\\s*\\(\\s*\"[^\"]*?=\\s*)\"\\s*\\+\\s*([A-Za-z_][\\w.]*)\\s*,\\s*null\\s*\\)");
        @Override public String fixLine(Issue is) {
            if (!"RULE-SQLI".equals(is.ruleId)) return null;
            Matcher m = SQL.matcher(is.lineText);
            if (!m.find()) return null;
            String out = m.replaceFirst(Matcher.quoteReplacement(m.group(1) + "?\", new String[]{String.valueOf(" + m.group(2) + ")})"));
            return out.equals(is.lineText) ? null : out;
        }
    }

    // ------------------------------------------------------------------ verification
    public static final class VerifyResult {
        public final boolean passed; public final String details; public final long ms;
        public VerifyResult(boolean passed, String details, long ms) { this.passed = passed; this.details = details; this.ms = ms; }
    }

    public interface Verifier { VerifyResult verify(Path projectRoot) throws Exception; }

    /** Runs any build command (e.g. ./gradlew assembleDebug) in projectRoot; exit code 0 = pass. No shell, no injection. */
    public static final class CommandVerifier implements Verifier {
        private final List<String> cmd; private final long timeoutMs; private final Map<String, String> env; private final Path logDir;
        public CommandVerifier(List<String> cmd, long timeoutMs, Map<String, String> env, Path logDir) {
            this.cmd = new ArrayList<>(cmd); this.timeoutMs = timeoutMs;
            this.env = env == null ? new HashMap<String, String>() : new HashMap<>(env); this.logDir = logDir;
        }
        @Override public VerifyResult verify(Path root) throws Exception {
            long t0 = System.currentTimeMillis();
            Files.createDirectories(logDir);
            Path log = Files.createTempFile(logDir, "build_", ".log");
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
                pb.environment().putAll(env);
                p = pb.start();
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    return new VerifyResult(false, "TIMEOUT after " + timeoutMs + "ms", System.currentTimeMillis() - t0);
                }
                int code = p.exitValue();
                if (code == 0) return new VerifyResult(true, "exit 0", System.currentTimeMillis() - t0);
                List<String> lines = Files.readAllLines(log, StandardCharsets.ISO_8859_1);
                StringBuilder sb = new StringBuilder("exit " + code + "\n");
                for (int i = Math.max(0, lines.size() - 40); i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
                return new VerifyResult(false, sb.toString(), System.currentTimeMillis() - t0);
            } catch (IOException e) {
                return new VerifyResult(false, "cannot run build command: " + e.getMessage(), System.currentTimeMillis() - t0);
            } finally {
                if (p != null && p.isAlive()) p.destroyForcibly();
                Files.deleteIfExists(log);
            }
        }
    }

    // ------------------------------------------------------------------ journal (crash recovery)
    static final class Journal {
        private final Path dir; private final Logger log;
        Journal(Path dir, Logger log) throws IOException { this.dir = dir; this.log = log; Files.createDirectories(dir); }
        private Path of(Issue i) { return dir.resolve(i.id + ".j"); }
        void begin(Issue i) throws IOException {
            String s = i.filePath + "\n" + i.backupPath + "\n" + i.backupHash + "\n" + i.patchedHash + "\n";
            writeAtomic(of(i), s.getBytes(StandardCharsets.UTF_8));
        }
        void end(Issue i) { try { Files.deleteIfExists(of(i)); } catch (IOException e) { log.w("journal delete failed: " + e); } }
        void conflict(Issue i) {
            try { Files.move(of(i), dir.resolve(i.id + ".conflict"), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
        }
        /** Called at start of every run: a journal left behind means the process died mid-patch. */
        int recover() {
            int restored = 0;
            File[] fs = dir.toFile().listFiles();
            if (fs == null) return 0;
            for (File f : fs) {
                if (!f.getName().endsWith(".j")) continue;
                try {
                    List<String> l = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                    if (l.size() < 4) { f.delete(); continue; }
                    Path target = Paths.get(l.get(0)), bak = Paths.get(l.get(1));
                    String bakHash = l.get(2), patchHash = l.get(3);
                    String cur = Files.exists(target) ? sha256(target) : "";
                    if (cur.equals(bakHash)) { f.delete(); }
                    else if (cur.equals(patchHash) && Files.exists(bak) && sha256(bak).equals(bakHash)) {
                        writeAtomic(target, Files.readAllBytes(bak));
                        if (!sha256(target).equals(bakHash)) throw new IOException("post-restore hash mismatch");
                        restored++; f.delete();
                        log.w("RECOVERED unfinished patch: " + target);
                    } else {
                        Files.move(f.toPath(), dir.resolve(f.getName() + ".conflict"), StandardCopyOption.REPLACE_EXISTING);
                        log.w("Journal conflict (file changed externally), left alone: " + target);
                    }
                } catch (Exception e) { log.e("recover failed for " + f, e); }
            }
            return restored;
        }
    }

    // ------------------------------------------------------------------ engine
    public static final class Report {
        public final List<Issue> issues = new ArrayList<>();
        public int verified, rolledBack, reported, failed, recovered;
        public boolean aborted; public String abortReason;
        public String summary() {
            return "issues=" + issues.size() + " verified=" + verified + " rolledBack=" + rolledBack + " reported=" + reported
                    + " failed=" + failed + " recovered=" + recovered + (aborted ? " ABORTED(" + abortReason + ")" : "");
        }
    }

    public static final class Engine implements Closeable {
        private final Logger log; private final Verifier verifier; private final Fixer fixer; private final AuditSink audit;
        private final Path backups; private final Journal journal; private final Detector detector = new Detector();

        public Engine(File workDir, Logger log, Verifier verifier) throws IOException {
            this(workDir, log, verifier, new SafeLineFixer(), new FileAuditSink(new File(workDir, "audit")));
        }
        public Engine(File workDir, Logger log, Verifier verifier, Fixer fixer, AuditSink audit) throws IOException {
            this.log = log == null ? Logger.NOOP : log; this.verifier = verifier; this.fixer = fixer; this.audit = audit;
            this.backups = new File(workDir, "backups").toPath();
            Files.createDirectories(backups);
            this.journal = new Journal(new File(workDir, "journal").toPath(), this.log);
        }

        @Override public void close() { if (audit != null) audit.close(); }

        public synchronized Report run(File root, Options o) throws IOException {
            Report rep = new Report();
            rep.recovered = journal.recover();
            List<Issue> issues = detector.scan(root.toPath(), o);
            rep.issues.addAll(issues);
            for (Issue is : issues) is.attach(audit);

            List<Issue> fixable = new ArrayList<>();
            for (Issue is : issues) {
                String fx = fixer == null ? null : fixer.fixLine(is);
                if (fx == null || fixable.size() >= o.maxFixes) {
                    is.transition(Status.REPORTED, fx == null ? "No safe automatic fix; manual review needed" : "maxFixes reached");
                } else {
                    is.fixedLine = fx; is.reasoning = "Parameterized query";
                    is.transition(Status.FIX_GENERATED, "Fix generated");
                    fixable.add(is);
                }
            }

            if (!o.autoFix || o.dryRun) {
                for (Issue is : fixable) is.transition(Status.REPORTED, o.dryRun ? "dry-run, not applied" : "autoFix disabled");
            } else if (!fixable.isEmpty()) {
                VerifyResult base;
                try { base = verifier.verify(root.toPath()); }
                catch (Exception e) { base = new VerifyResult(false, "baseline verify threw: " + e, 0); }
                if (!base.passed) {
                    rep.aborted = true; rep.abortReason = "baseline build failing, refusing to patch: " + firstLine(base.details);
                    for (Issue is : fixable) is.transition(Status.REPORTED, rep.abortReason);
                } else {
                    for (Issue is : fixable) patchOne(is, root.toPath());
                }
            }

            for (Issue is : issues) {
                switch (is.status()) {
                    case VERIFIED: rep.verified++; break;
                    case ROLLED_BACK: rep.rolledBack++; break;
                    case REPORTED: rep.reported++; break;
                    default: rep.failed++;
                }
            }
            log.d("Run done: " + rep.summary());
            return rep;
        }

        private static String firstLine(String s) { int i = s.indexOf('\n'); return i < 0 ? s : s.substring(0, i); }

        private void patchOne(Issue is, Path root) {
            Path target = Paths.get(is.filePath);
            boolean journaled = false;
            try {
                String content = readUtf8Strict(target);
                if (content == null) throw new IOException("file not UTF-8");
                String[] lines = content.split("\n", -1);
                int idx = is.line - 1;
                if (idx >= lines.length || !lines[idx].equals(is.lineText)) throw new IOException("file changed since scan");
                lines[idx] = is.fixedLine;
                byte[] patched = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);

                Path bak = backups.resolve(is.id + ".bak");
                Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
                is.backupPath = bak.toString();
                is.backupHash = sha256(bak);
                if (!is.backupHash.equals(sha256(target))) throw new IOException("backup hash mismatch");
                is.patchedHash = sha256(patched);
                is.transition(Status.BACKED_UP, "Backup sha=" + is.backupHash.substring(0, 8));

                journal.begin(is); journaled = true;
                writeAtomic(target, patched);
                is.transition(Status.APPLIED, "Patch applied");

                VerifyResult vr = verifier.verify(root);
                if (vr.passed) {
                    is.transition(Status.VERIFIED, "Build OK (" + vr.ms + "ms)");
                    journal.end(is);
                } else {
                    is.failureReason = "Verification failed: " + firstLine(vr.details);
                    is.transition(Status.FAILED, is.failureReason);
                    rollback(is);
                }
            } catch (Exception e) {
                log.e("patch failed " + is.id, e);
                is.failureReason = String.valueOf(e.getMessage());
                try { if (is.status() != Status.FAILED && is.status() != Status.ROLLED_BACK && is.status() != Status.VERIFIED) is.transition(Status.FAILED, "Error: " + e.getMessage()); }
                catch (Exception ignored) {}
                if (journaled) rollback(is);
            }
        }

        /** Restores the backup only if the file is still exactly our patched version (never clobbers user edits). */
        boolean rollback(Issue is) {
            try {
                Path t = Paths.get(is.filePath), b = Paths.get(is.backupPath);
                String cur = Files.exists(t) ? sha256(t) : "";
                if (!cur.equals(is.backupHash)) {
                    if (!cur.equals(is.patchedHash)) {
                        is.note("ROLLBACK REFUSED: file was modified externally");
                        journal.conflict(is);
                        return false;
                    }
                    if (!Files.exists(b) || !sha256(b).equals(is.backupHash)) { is.note("ROLLBACK FAILED: backup missing/corrupt"); return false; }
                    writeAtomic(t, Files.readAllBytes(b));
                    if (!sha256(t).equals(is.backupHash)) { is.note("ROLLBACK FAILED: hash mismatch after restore"); return false; }
                }
                Status s = is.status();
                if (s == Status.APPLIED || s == Status.FAILED) is.transition(Status.ROLLED_BACK, "Restored from backup");
                journal.end(is);
                return true;
            } catch (Exception e) {
                log.e("rollback error", e);
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ CLI (for Termux / desktop)
    public static void main(String[] a) throws Exception {
        if (a.length < 1) {
            System.out.println("usage: MiyaHealCore <projectRoot> [--apply] [--dry-run] [--cmd \"./gradlew assembleDebug --console=plain\"]");
            return;
        }
        File root = new File(a[0]); boolean apply = false, dry = false; String cmd = "./gradlew assembleDebug --console=plain";
        for (int i = 1; i < a.length; i++) {
            if (a[i].equals("--apply")) apply = true;
            else if (a[i].equals("--dry-run")) dry = true;
            else if (a[i].equals("--cmd") && i + 1 < a.length) cmd = a[++i];
        }
        File work = new File(root, ".miya_secure");
        Verifier v = new CommandVerifier(Arrays.asList(cmd.trim().split("\\s+")), 10 * 60_000L, null, work.toPath().resolve("tmp"));
        try (Engine e = new Engine(work, Logger.STDOUT, v)) {
            Report r = e.run(root, new Options().autoFix(apply).dryRun(dry));
            for (Issue is : r.issues) System.out.println(is.status() + "  " + is.ruleId + "  " + is.filePath + ":" + is.line);
            System.out.println(r.summary());
        }
    }
}
