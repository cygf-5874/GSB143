import punycode.Punycode;
import punycode.PunycodeException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * punycode 的固定验收程序（勿改）。
 *
 * <p>8 个场景覆盖 README「对外契约」的 9 条：
 * legacy 2 / roundtrip 3 / overflow 1 / idna 2。
 * 每个场景都在**独立 JVM 子进程**里跑（{@link ProcessBuilder}），带看门狗，超时判不通过；
 * 某个场景失败不会遮蔽其余场景（失败不早退）。判据全部确定性：没有随机源、没有墙钟、
 * 没有哈希顺序依赖、没有性能门槛。
 *
 * <p>用法：java -cp out;out-check Checker [-list] [--only &lt;组名&gt;[,&lt;组名&gt;...]]
 */
public final class Checker {

    private static final long WATCHDOG_SECONDS = 30L;

    private interface Body {
        /** 返回 null 表示 PASS，否则返回可判定的失败原因（内含 期望=… 实际=…）。 */
        String run() throws Exception;
    }

    private static final class Scenario {
        final String name;
        final String group;
        final Body body;

        Scenario(String name, Body body) {
            this.name = name;
            this.body = body;
            int slash = name.indexOf('/');
            this.group = slash < 0 ? name : name.substring(0, slash);
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> onlyGroups = new ArrayList<>();
        boolean list = false;
        String scenarioOnly = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-list".equals(a)) {
                list = true;
            } else if ("--only".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--only 需要一个组名");
                    System.exit(2);
                }
                for (String g : args[++i].split(",")) {
                    if (!g.isEmpty()) {
                        onlyGroups.add(g);
                    }
                }
            } else if ("--scenario".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--scenario 需要一个场景名");
                    System.exit(2);
                }
                scenarioOnly = args[++i];
            } else {
                System.out.println("未知参数：" + a);
                System.exit(2);
            }
        }

        List<Scenario> all = scenarios();
        if (list) {
            for (Scenario s : all) {
                System.out.println(s.name);
            }
            return;
        }
        if (scenarioOnly != null) {
            System.exit(runOne(all, scenarioOnly));
        }

        List<Scenario> selected = new ArrayList<>();
        for (Scenario s : all) {
            if (onlyGroups.isEmpty() || onlyGroups.contains(s.group)) {
                selected.add(s);
            }
        }
        if (selected.isEmpty()) {
            System.out.println("没有匹配的场景（--only " + onlyGroups + "）");
            System.exit(2);
        }

        int pass = 0;
        for (Scenario s : selected) {
            String line = runInChildProcess(s);
            System.out.println(line);
            if (line.startsWith("PASS ")) {
                pass++;
            }
        }
        System.out.println("结果：通过 " + pass + "/" + selected.size());
        if (pass != selected.size()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------- 子进程调度（一场景一 JVM）

    private static String runInChildProcess(Scenario s) {
        String name = s.name;
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(javaBin,
                "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8",
                "-cp", classpath, "Checker", "--scenario", name);
        pb.redirectErrorStream(true);

        Process proc;
        try {
            proc = pb.start();
        } catch (Exception ex) {
            return "FAIL " + name + "  期望=能在独立 JVM 子进程里跑该场景 实际=子进程启动失败：" + ex;
        }

        StringBuilder sink = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    synchronized (sink) {
                        sink.append(chunk);
                    }
                }
            } catch (Exception ignored) {
                // 子进程被强杀时读流会失败，忽略
            }
        }, "checker-child-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = proc.waitFor(WATCHDOG_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            proc.destroyForcibly();
            try {
                reader.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "FAIL " + name + "  期望=场景在 " + WATCHDOG_SECONDS + " 秒内给出 PASS/FAIL 实际="
                    + WATCHDOG_SECONDS + " 秒看门狗超时（子进程已强杀）";
        }
        try {
            reader.join(5000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        String out;
        synchronized (sink) {
            out = sink.toString().trim();
        }
        String line = null;
        for (String l : out.split("\n")) {
            String t = l.trim();
            if (t.startsWith("PASS ") || t.startsWith("FAIL ")) {
                line = t;
            }
        }
        if (line == null) {
            String shown = out.isEmpty() ? "(无输出)" : out.replace('\n', ' ');
            return "FAIL " + name + "  期望=子进程打印 PASS/FAIL 行 实际=输出=" + shown;
        }
        return line;
    }

    private static int runOne(List<Scenario> all, String name) {
        for (Scenario s : all) {
            if (!s.name.equals(name)) {
                continue;
            }
            String err;
            try {
                err = s.body.run();
            } catch (Throwable t) {
                err = "检查过程抛异常：" + t;
            }
            if (err == null) {
                System.out.println("PASS " + s.name);
                return 0;
            }
            if (!err.contains("期望=")) {
                err = "期望=场景 " + s.name + " 通过 实际=" + err;
            }
            System.out.println("FAIL " + s.name + "  " + err);
            return 1;
        }
        System.out.println("FAIL " + name + "  期望=场景名存在于固定件 实际=未找到该场景");
        return 1;
    }

    // ------------------------------------------------------------------ 场景表

    private static List<Scenario> scenarios() {
        List<Scenario> list = new ArrayList<>();
        list.add(new Scenario("legacy/known-vectors", Checker::legacyKnownVectors));
        list.add(new Scenario("legacy/basic-only", Checker::legacyBasicOnly));
        list.add(new Scenario("roundtrip/mixed", Checker::roundtripMixed));
        list.add(new Scenario("roundtrip/ascii-and-illegal", Checker::roundtripAsciiAndIllegal));
        list.add(new Scenario("roundtrip/max-codepoint", Checker::roundtripMaxCodepoint));
        list.add(new Scenario("overflow/decode-int", Checker::overflowDecodeInt));
        list.add(new Scenario("idna/encode-labels", Checker::idnaEncodeLabels));
        list.add(new Scenario("idna/decode-labels", Checker::idnaDecodeLabels));
        return list;
    }

    // ------------------------------------------------------------------ legacy

    private static String legacyKnownVectors() {
        String e = eq("encode(\"b\u00fccher\")", Punycode.encode("b\u00fccher"), "bcher-kva");
        if (e != null) {
            return e;
        }
        e = eq("encode(\"m\u00fcnchen\")", Punycode.encode("m\u00fcnchen"), "mnchen-3ya");
        if (e != null) {
            return e;
        }
        e = eq("encode(\"ma\u00f1ana\")", Punycode.encode("ma\u00f1ana"), "maana-pta");
        if (e != null) {
            return e;
        }
        e = eq("encode(\"\u4e2d\u56fd\")", Punycode.encode("\u4e2d\u56fd"), "fiqs8s");
        if (e != null) {
            return e;
        }
        e = eq("encode(\"\u65e5\u672c\u8a9e\")", Punycode.encode("\u65e5\u672c\u8a9e"), "wgv71a119e");
        if (e != null) {
            return e;
        }
        return eq("encode(U+1F600)", Punycode.encode("\uD83D\uDE00"), "e28h");
    }

    private static String legacyBasicOnly() {
        String e = eq("encode(\"abc\")", Punycode.encode("abc"), "abc-");
        if (e != null) {
            return e;
        }
        e = eq("encode(\"A\")", Punycode.encode("A"), "A-");
        if (e != null) {
            return e;
        }
        e = eq("encode(\"z9\")", Punycode.encode("z9"), "z9-");
        if (e != null) {
            return e;
        }
        e = eq("encode(\"0\")", Punycode.encode("0"), "0-");
        if (e != null) {
            return e;
        }
        e = eq("encode(\"a.b.c\")", Punycode.encode("a.b.c"), "a.b.c-");
        if (e != null) {
            return e;
        }
        return eq("encode(\"\")", Punycode.encode(""), "");
    }

    // ------------------------------------------------------------------ roundtrip

    private static String roundtripMixed() {
        String e = round("b\u00fccher");
        if (e != null) {
            return e;
        }
        e = round("ma\u00f1ana");
        if (e != null) {
            return e;
        }
        e = round("\u4e2d\u56fd");
        if (e != null) {
            return e;
        }
        e = round("\u65e5\u672c\u8a9e");
        if (e != null) {
            return e;
        }
        e = round("\u30c9\u30e1\u30a4\u30f3");
        if (e != null) {
            return e;
        }
        return round("\uD83D\uDE00\uD83D\uDE00");
    }

    private static String roundtripAsciiAndIllegal() {
        for (String s : new String[]{"abc", "", "A", "z9", "a.b.c", "xn--bcher-kva", "0", "9zZ"}) {
            String e = round(s);
            if (e != null) {
                return e;
            }
        }
        String e = throwsPunycode("decode(\"\u00e9\")（含非 basic 却无分隔符）",
                () -> Punycode.decode("\u00e9"));
        if (e != null) {
            return e;
        }
        e = throwsPunycode("decode(\"-a\")（分隔符前为空）", () -> Punycode.decode("-a"));
        if (e != null) {
            return e;
        }
        e = throwsPunycode("decode(\"\u00e9-\")（basic 段含非 basic）", () -> Punycode.decode("\u00e9-"));
        if (e != null) {
            return e;
        }
        e = throwsPunycode("decode(\"a-\u00e9\")（编码段含非 basic）", () -> Punycode.decode("a-\u00e9"));
        if (e != null) {
            return e;
        }
        return throwsPunycode("decode(\"a-b!\")（编码段含非法字符）", () -> Punycode.decode("a-b!"));
    }

    private static String roundtripMaxCodepoint() {
        String e = round("\uD83C\uDF10");
        if (e != null) {
            return e;
        }
        e = round("\uDBFF\uDFFF");
        if (e != null) {
            return e;
        }
        e = round("\u00e4");
        if (e != null) {
            return e;
        }
        e = round("\u007f\u0080");
        if (e != null) {
            return e;
        }
        return round("\uD83C\uDF10\u4e2d");
    }

    // ------------------------------------------------------------------ overflow

    private static String overflowDecodeInt() {
        String e = throwsPunycode("decode(\"a-9999999999\")",
                () -> Punycode.decode("a-9999999999"));
        if (e != null) {
            return e;
        }
        e = throwsPunycode("decode(\"a-99999999999999\")",
                () -> Punycode.decode("a-99999999999999"));
        if (e != null) {
            return e;
        }
        return throwsPunycode("decode(\"zzzzzzzzzzzzzzzzzzzz\")",
                () -> Punycode.decode("zzzzzzzzzzzzzzzzzzzz"));
    }

    // ------------------------------------------------------------------ idna

    private static String idnaEncodeLabels() {
        String e = eq("encodeIdna(\"b\u00fccher.de\")",
                Punycode.encodeIdna("b\u00fccher.de"), "xn--bcher-kva.de");
        if (e != null) {
            return e;
        }
        e = eq("encodeIdna(\"example.com\")",
                Punycode.encodeIdna("example.com"), "example.com");
        if (e != null) {
            return e;
        }
        e = eq("encodeIdna(\"\u4e2d\u56fd.\u4e2d\u56fd\")",
                Punycode.encodeIdna("\u4e2d\u56fd.\u4e2d\u56fd"), "xn--fiqs8s.xn--fiqs8s");
        if (e != null) {
            return e;
        }
        e = eq("encodeIdna(\"a.b.c\")", Punycode.encodeIdna("a.b.c"), "a.b.c");
        if (e != null) {
            return e;
        }
        e = eq("encodeIdna(\"b\u00fccher.example\")",
                Punycode.encodeIdna("b\u00fccher.example"), "xn--bcher-kva.example");
        if (e != null) {
            return e;
        }
        for (String bad : new String[]{"a..b", "a.", ".a", ""}) {
            String err = throwsPunycode("encodeIdna(\"" + bad + "\")（空标签）",
                    () -> Punycode.encodeIdna(bad));
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    private static String idnaDecodeLabels() {
        String e = eq("decodeIdna(\"xn--bcher-kva.de\")",
                Punycode.decodeIdna("xn--bcher-kva.de"), "b\u00fccher.de");
        if (e != null) {
            return e;
        }
        e = eq("decodeIdna(\"XN--BCHER-KVA.de\")（前缀大小写混合）",
                Punycode.decodeIdna("XN--BCHER-KVA.de"), "b\u00fccher.de");
        if (e != null) {
            return e;
        }
        e = eq("decodeIdna(\"example.com\")",
                Punycode.decodeIdna("example.com"), "example.com");
        if (e != null) {
            return e;
        }
        e = eq("decodeIdna(\"xn--fiqs8s.xn--fiqs8s\")",
                Punycode.decodeIdna("xn--fiqs8s.xn--fiqs8s"), "\u4e2d\u56fd.\u4e2d\u56fd");
        if (e != null) {
            return e;
        }
        e = throwsPunycode("decodeIdna(\"b\u00fccher.de\")（非 xn-- 标签含非 ASCII）",
                () -> Punycode.decodeIdna("b\u00fccher.de"));
        if (e != null) {
            return e;
        }
        return throwsPunycode("decodeIdna(\"a..b\")（空标签）",
                () -> Punycode.decodeIdna("a..b"));
    }

    // ------------------------------------------------------------------ 工具

    private static String round(String s) {
        String decoded;
        try {
            decoded = Punycode.decode(Punycode.encode(s));
        } catch (Throwable t) {
            return "往返 " + escape(s) + " 期望=还原成原文 实际=抛出 " + t;
        }
        if (s.equals(decoded)) {
            return null;
        }
        return "往返 " + escape(s) + " 期望=" + escape(s) + " 实际=" + escape(decoded);
    }

    private static String throwsPunycode(String label, Runnable body) {
        try {
            body.run();
            return label + " 期望=抛 PunycodeException 实际=正常返回";
        } catch (PunycodeException expected) {
            return null;
        } catch (Throwable t) {
            return label + " 期望=PunycodeException 实际=" + t;
        }
    }

    private static String eq(String label, String actual, String wanted) {
        if (wanted.equals(actual)) {
            return null;
        }
        return label + " 期望=" + escape(wanted) + " 实际=" + escape(actual);
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Checker() {
    }
}