package com.miya.selfheal.pro;

import com.miya.selfheal.pro.MiyaHealCore.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class MiyaHealTest {
    interface Scenario { void run(Path tmp) throws Exception; }
    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }

    static final String SRC =
        "package demo;\n" +
        "public class Db {\n" +
        "  // db.rawQuery(\"SELECT * FROM t WHERE id=\" + evil, null);\n" +
        "  /* String password = \"supersecret1\"; */\n" +
        "  void a(android.database.sqlite.SQLiteDatabase db, int id) {\n" +
        "    db.rawQuery(\"SELECT * FROM users WHERE id=\" + id, null);\n" +   // line 6
        "  }\n" +
        "  String password = \"hunter2hunter2\";\n" +                       // line 8
        "  String tokenKey = \"auth_token_key\";\n" +
        "  void run() throws Exception { Runtime.getRuntime().exec(\"ls\"); }\n" + // line 10
        "}\n";
    static final String FIXED_LINE = "    db.rawQuery(\"SELECT * FROM users WHERE id=?\", new String[]{String.valueOf(id)});";

    static Path project(Path tmp, String src) throws Exception {
        Path root = tmp.resolve("proj"); Files.createDirectories(root.resolve("src/demo")); Files.createDirectories(root.resolve("build"));
        Files.write(root.resolve("src/demo/Db.java"), src.getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("build/Gen.java"), "class Gen { String password = \"abcdefgh12\"; }".getBytes());
        return root;
    }
    static Path db(Path root) { return root.resolve("src/demo/Db.java"); }
    static String read(Path p) throws Exception { return new String(Files.readAllBytes(p), StandardCharsets.UTF_8); }
    static Engine eng(Path tmp, Verifier v) throws Exception { return new Engine(tmp.resolve("work").toFile(), Logger.NOOP, v); }

    static class Seq implements Verifier {
        final boolean[] results; int n = 0;
        Seq(boolean... r) { results = r; }
        public VerifyResult verify(Path root) { boolean ok = results[Math.min(n++, results.length - 1)]; return new VerifyResult(ok, ok ? "ok" : "error: boom", 1); }
    }

    public static void main(String[] args) throws Exception {
        Map<String, Scenario> sc = new LinkedHashMap<>();

        sc.put("detect: 3 issues, right lines, comments/build/key-names skipped", tmp -> {
            Path root = project(tmp, SRC);
            try (Engine e = eng(tmp, new Seq(true))) {
                Report r = e.run(root.toFile(), new Options());
                check(r.issues.size() == 3, "expected 3 issues got " + r.issues.size());
                check(r.issues.get(0).line == 6 && r.issues.get(0).ruleId.equals("RULE-SQLI"), "sqli line");
                check(r.issues.get(1).line == 8 && r.issues.get(1).ruleId.equals("RULE-SECRET"), "secret line");
                check(r.issues.get(2).line == 10 && r.issues.get(2).ruleId.equals("RULE-EXEC"), "exec line");
                check(read(db(root)).equals(SRC), "report-only must not touch files");
                check(r.reported == 3, "all reported");
            }
        });

        sc.put("autofix + build pass: only line 6 changes", tmp -> {
            Path root = project(tmp, SRC);
            try (Engine e = eng(tmp, new Seq(true))) {
                Report r = e.run(root.toFile(), new Options().autoFix(true));
                check(r.verified == 1 && r.reported == 2 && r.failed == 0, r.summary());
                String[] a = SRC.split("\n", -1), b = read(db(root)).split("\n", -1);
                check(a.length == b.length, "line count changed");
                for (int i = 0; i < a.length; i++) check(i == 5 ? b[i].equals(FIXED_LINE) : a[i].equals(b[i]), "line " + (i + 1) + " differs: " + b[i]);
            }
        });

        sc.put("build fails after patch -> rollback restores exact original", tmp -> {
            Path root = project(tmp, SRC);
            try (Engine e = eng(tmp, new Seq(true, false))) {
                Report r = e.run(root.toFile(), new Options().autoFix(true));
                check(r.rolledBack == 1 && r.verified == 0, r.summary());
                check(read(db(root)).equals(SRC), "original not restored");
                check(r.issues.get(0).status() == Status.ROLLED_BACK, "status");
                check(new File(tmp.resolve("work/journal").toFile().getPath()).list().length == 0, "journal not cleaned");
            }
        });

        sc.put("verifier throws -> rollback", tmp -> {
            Path root = project(tmp, SRC);
            Verifier v = new Verifier() { int n = 0; public VerifyResult verify(Path p) throws Exception { if (n++ == 0) return new VerifyResult(true, "ok", 1); throw new RuntimeException("kaboom"); } };
            try (Engine e = eng(tmp, v)) {
                Report r = e.run(root.toFile(), new Options().autoFix(true));
                check(read(db(root)).equals(SRC), "original not restored");
                check(r.rolledBack == 1, r.summary());
            }
        });

        sc.put("baseline build broken -> abort, nothing touched", tmp -> {
            Path root = project(tmp, SRC);
            try (Engine e = eng(tmp, new Seq(false))) {
                Report r = e.run(root.toFile(), new Options().autoFix(true));
                check(r.aborted && read(db(root)).equals(SRC), r.summary());
            }
        });

        sc.put("dry-run never writes", tmp -> {
            Path root = project(tmp, SRC);
            Seq v = new Seq(true);
            try (Engine e = eng(tmp, v)) {
                e.run(root.toFile(), new Options().autoFix(true).dryRun(true));
                check(read(db(root)).equals(SRC) && v.n == 0, "dry run touched things");
            }
        });

        sc.put("CRLF file keeps CRLF", tmp -> {
            String crlf = SRC.replace("\n", "\r\n");
            Path root = project(tmp, crlf);
            try (Engine e = eng(tmp, new Seq(true))) {
                e.run(root.toFile(), new Options().autoFix(true));
                String out = read(db(root));
                check(out.contains(FIXED_LINE + "\r\n"), "CRLF lost");
                check(out.replace("\r\n", "").indexOf('\n') < 0, "stray LF");
            }
        });

        sc.put("user edits file during verify -> rollback REFUSED, user edit kept", tmp -> {
            Path root = project(tmp, SRC);
            Verifier v = new Verifier() { int n = 0; public VerifyResult verify(Path p) throws Exception {
                if (n++ == 0) return new VerifyResult(true, "ok", 1);
                Files.write(db(p), (read(db(p)) + "// user edit\n").getBytes(StandardCharsets.UTF_8));
                return new VerifyResult(false, "fail", 1); } };
            try (Engine e = eng(tmp, v)) {
                Report r = e.run(root.toFile(), new Options().autoFix(true));
                check(read(db(root)).endsWith("// user edit\n") && read(db(root)).contains(FIXED_LINE), "user edit lost");
                check(r.rolledBack == 0 && r.failed == 1, r.summary());
            }
        });

        sc.put("crash mid-patch -> next run recovers original", tmp -> {
            Path root = project(tmp, SRC);
            // Verifier that kills the "process": throws Error after patch applied and skips normal catch flow by simulating a fresh Engine
            final Engine[] holder = new Engine[1];
            Verifier crash = new Verifier() { int n = 0; public VerifyResult verify(Path p) { if (n++ == 0) return new VerifyResult(true, "ok", 1); throw new ThreadDeath(); } };
            Engine e1 = eng(tmp, crash);
            try { e1.run(root.toFile(), new Options().autoFix(true)); } catch (ThreadDeath t) { /* simulated hard crash: no cleanup ran */ }
            check(!read(db(root)).equals(SRC), "precondition: file should still be patched after crash");
            e1.close();
            try (Engine e2 = eng(tmp, new Seq(true))) {
                Report r = e2.run(root.toFile(), new Options());
                check(r.recovered == 1, "recovered=" + r.recovered);
                check(read(db(root)).equals(SRC), "not restored after crash");
            }
        });

        sc.put("audit failure blocks state change", tmp -> {
            Path root = project(tmp, SRC);
            AuditSink bad = new AuditSink() { public boolean record(String j) { return false; } public void close() {} };
            Engine e = new Engine(tmp.resolve("work").toFile(), Logger.NOOP, new Seq(true), new SafeLineFixer(), bad);
            try { e.run(root.toFile(), new Options().autoFix(true)); check(false, "should have thrown"); }
            catch (AuditFailureException ok) { }
            check(read(db(root)).equals(SRC), "file touched despite audit failure");
        });

        sc.put("invalid transitions rejected", tmp -> {
            Issue is = new Issue("/x/A.java", 1, "d", Severity.LOW, "R", "l", new ArrayList<String>());
            try { is.transition(Status.VERIFIED, "skip"); check(false, "should throw"); } catch (IllegalStateException ok) {}
            is.transition(Status.FAILED, "x"); is.transition(Status.ROLLED_BACK, "x");
            try { is.transition(Status.APPLIED, "back"); check(false, "should throw"); } catch (IllegalStateException ok) {}
        });

        sc.put("fixer ignores non-matching SQL (LIKE / 2nd arg not null)", tmp -> {
            String s = "class A { void f(){ db.rawQuery(\"SELECT * FROM t WHERE n LIKE '%\" + x + \"%'\", null);\n db.rawQuery(\"SELECT 1 WHERE id=\" + id, args); } }";
            Path root = project(tmp, s);
            try (Engine e = eng(tmp, new Seq(true))) {
                Report r = e.run(root.toFile(), new Options().autoFix(true));
                check(read(db(root)).equals(s) && r.verified == 0, "must not touch unsafe patterns");
            }
        });

        sc.put("non-UTF8 file skipped, not corrupted", tmp -> {
            Path root = project(tmp, SRC);
            byte[] bin = {(byte) 0xFF, (byte) 0xFE, 'S', 'p', (byte) 0xC3, 0x28};
            Files.write(root.resolve("src/Bin.java"), bin);
            try (Engine e = eng(tmp, new Seq(true))) {
                e.run(root.toFile(), new Options().autoFix(true));
                check(Arrays.equals(Files.readAllBytes(root.resolve("src/Bin.java")), bin), "binary changed");
            }
        });

        int RUNS = 20, bad = 0;
        for (Map.Entry<String, Scenario> en : sc.entrySet()) {
            int pass = 0; String firstErr = null;
            for (int i = 0; i < RUNS; i++) {
                Path tmp = Files.createTempDirectory("miya");
                try { en.getValue().run(tmp); pass++; }
                catch (Throwable t) { if (firstErr == null) { firstErr = t.toString(); } }
                finally { delTree(tmp.toFile()); }
            }
            System.out.printf("%-72s %2d/%d %s%n", en.getKey(), pass, RUNS, firstErr == null ? "" : "FIRST ERR: " + firstErr);
            if (pass != RUNS) bad++;
        }
        System.out.println(bad == 0 ? "ALL SCENARIOS PASSED 20/20" : bad + " scenario(s) failing");
        System.exit(bad == 0 ? 0 : 1);
    }

    static void delTree(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) delTree(c); f.delete(); }
}
