package punycode;

import java.util.ArrayList;
import java.util.List;

/**
 * punycode 的既有用例（10 个，只覆盖已经实现好的 {@code encode}）。
 *
 * <p>不依赖 JUnit：静态方法加 {@code main} 聚合自跑，全部通过时打印 {@code 10/10 passed}
 * 并以退出码 0 结束；有失败时打印失败清单并以退出码 1 结束。
 *
 * <p>这些用例是「既有 encode 行为」的基线，**一条都不许删或改**。
 */
public final class PunycodeTest {

    private interface Body {
        /** 返回 null 表示通过，否则返回失败原因。 */
        String run();
    }

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        run("known-vectors", PunycodeTest::testKnownVectors);
        run("basic-only", PunycodeTest::testBasicOnly);
        run("empty", PunycodeTest::testEmpty);
        run("single-nonascii", PunycodeTest::testSingleNonAscii);
        run("cjk", PunycodeTest::testCjk);
        run("japanese", PunycodeTest::testJapanese);
        run("supplementary", PunycodeTest::testSupplementary);
        run("supplementary-pair", PunycodeTest::testSupplementaryPair);
        run("dots-preserved", PunycodeTest::testDotsPreserved);
        run("deterministic", PunycodeTest::testDeterministic);

        System.out.println(passed + "/10 passed");
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("FAIL " + f);
            }
            System.exit(1);
        }
    }

    private static void run(String name, Body body) {
        try {
            String err = body.run();
            if (err == null) {
                passed++;
            } else {
                failures.add(name + "：" + err);
            }
        } catch (Throwable t) {
            failures.add(name + "：抛出 " + t);
        }
    }

    private static String testKnownVectors() {
        String e = expect("b\u00fccher", Punycode.encode("b\u00fccher"), "bcher-kva");
        if (e != null) {
            return e;
        }
        e = expect("m\u00fcnchen", Punycode.encode("m\u00fcnchen"), "mnchen-3ya");
        if (e != null) {
            return e;
        }
        return expect("ma\u00f1ana", Punycode.encode("ma\u00f1ana"), "maana-pta");
    }

    private static String testBasicOnly() {
        String e = expect("abc", Punycode.encode("abc"), "abc-");
        if (e != null) {
            return e;
        }
        e = expect("A", Punycode.encode("A"), "A-");
        if (e != null) {
            return e;
        }
        return expect("z9", Punycode.encode("z9"), "z9-");
    }

    private static String testEmpty() {
        return expect("空串", Punycode.encode(""), "");
    }

    private static String testSingleNonAscii() {
        String e = expect("\u00e4", Punycode.encode("\u00e4"), "4ca");
        if (e != null) {
            return e;
        }
        return expect("\u00e9", Punycode.encode("\u00e9"), "9ca");
    }

    private static String testCjk() {
        return expect("\u4e2d\u56fd", Punycode.encode("\u4e2d\u56fd"), "fiqs8s");
    }

    private static String testJapanese() {
        return expect("\u65e5\u672c\u8a9e", Punycode.encode("\u65e5\u672c\u8a9e"), "wgv71a119e");
    }

    private static String testSupplementary() {
        return expect("U+1F600", Punycode.encode("\uD83D\uDE00"), "e28h");
    }

    private static String testSupplementaryPair() {
        return expect("U+1F600 x2", Punycode.encode("\uD83D\uDE00\uD83D\uDE00"), "e28ha");
    }

    private static String testDotsPreserved() {
        return expect("a.b.c", Punycode.encode("a.b.c"), "a.b.c-");
    }

    private static String testDeterministic() {
        String first = Punycode.encode("\u65e5\u672c\u8a9e.tokyo");
        String second = Punycode.encode("\u65e5\u672c\u8a9e.tokyo");
        if (!first.equals(second)) {
            return "同一输入两次 encode 期望=逐字符相同 实际=" + first + " vs " + second;
        }
        return null;
    }

    private static String expect(String label, String actual, String wanted) {
        if (wanted.equals(actual)) {
            return null;
        }
        return label + " 期望=" + wanted + " 实际=" + actual;
    }

    private PunycodeTest() {
    }
}