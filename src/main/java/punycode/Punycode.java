package punycode;

import java.util.function.Function;

/**
 * Punycode（RFC 3492）编解码。
 *
 * <p>当前状态：{@link #encode(String)} **已经实现**（本次不要回归它的行为）；
 * {@link #decode(String)}、{@link #encodeIdna(String)}、{@link #decodeIdna(String)}
 * 是本次要补的一层，方法体一律抛 {@link UnsupportedOperationException}。
 */
public final class Punycode {

    private static final int BASE = 36;
    private static final int TMIN = 1;
    private static final int TMAX = 26;
    private static final int SKEW = 38;
    private static final int DAMP = 700;
    private static final int INITIAL_BIAS = 72;
    private static final int INITIAL_N = 128;
    private static final char DELIMITER = '-';
    private static final String ACE_PREFIX = "xn--";

    /**
     * 把一个标签编码成 Punycode（**不含** {@code xn--} 前缀）。
     *
     * @param input 标签文本（可为纯 ASCII，也可含任意码点）
     * @return Punycode 串
     */
    public static String encode(String input) {
        int n = INITIAL_N;
        int delta = 0;
        int bias = INITIAL_BIAS;
        StringBuilder output = new StringBuilder();
        int total = input.codePointCount(0, input.length());
        int b = 0;
        for (int i = 0; i < input.length(); ) {
            int cp = input.codePointAt(i);
            if (cp < n) {
                output.appendCodePoint(cp);
                b++;
            }
            i += Character.charCount(cp);
        }
        int h = b;
        if (b > 0) {
            output.append(DELIMITER);
        }
        while (h < total) {
            int m = Integer.MAX_VALUE;
            for (int i = 0; i < input.length(); ) {
                int cp = input.codePointAt(i);
                if (cp >= n && cp < m) {
                    m = cp;
                }
                i += Character.charCount(cp);
            }
            if ((long) (m - n) * (h + 1) > Integer.MAX_VALUE - delta) {
                throw new PunycodeException("overflow");
            }
            delta += (m - n) * (h + 1);
            n = m;
            for (int i = 0; i < input.length(); ) {
                int cp = input.codePointAt(i);
                if (cp < n) {
                    if (delta == Integer.MAX_VALUE) {
                        throw new PunycodeException("overflow");
                    }
                    delta++;
                }
                if (cp == n) {
                    int q = delta;
                    for (int k = BASE; ; k += BASE) {
                        int t = k - bias;
                        if (t < TMIN) {
                            t = TMIN;
                        } else if (t > TMAX) {
                            t = TMAX;
                        }
                        if (q < t) {
                            break;
                        }
                        output.append(encodeDigit(t + (q - t) % (BASE - t)));
                        q = (q - t) / (BASE - t);
                    }
                    output.append(encodeDigit(q));
                    bias = adapt(delta, h + 1, h == b);
                    delta = 0;
                    h++;
                }
                i += Character.charCount(cp);
            }
            delta++;
            n++;
        }
        return output.toString();
    }

    /**
     * 把一个 Punycode 串（**不含** {@code xn--} 前缀）还原成标签文本。
     *
     * @param input Punycode 串
     * @return 还原后的标签文本
     * @throws PunycodeException 非法输入，或解码过程中发生 int 溢出
     */
    public static String decode(String input) {
        int lastDash = input.lastIndexOf(DELIMITER);
        String basic;
        String encoded;
        if (lastDash < 0) {
            for (int i = 0; i < input.length(); i++) {
                if (input.charAt(i) >= 0x80) {
                    throw new PunycodeException("non-basic input without delimiter");
                }
            }
            basic = "";
            encoded = input;
        } else {
            if (lastDash == 0) {
                throw new PunycodeException("empty basic section before delimiter");
            }
            basic = input.substring(0, lastDash);
            for (int i = 0; i < basic.length(); i++) {
                if (basic.charAt(i) >= 0x80) {
                    throw new PunycodeException("non-basic code point in basic section");
                }
            }
            encoded = input.substring(lastDash + 1);
        }

        int[] codePoints = new int[Math.max(1, input.length())];
        int outLen = 0;
        for (int i = 0; i < basic.length(); i++) {
            codePoints[outLen++] = basic.charAt(i);
        }

        int n = INITIAL_N;
        int bias = INITIAL_BIAS;
        int i = 0;
        int pos = 0;
        while (pos < encoded.length()) {
            int oldi = i;
            int w = 1;
            for (int k = BASE; ; k += BASE) {
                if (pos >= encoded.length()) {
                    throw new PunycodeException("truncated encoded section");
                }
                int digit = decodeDigit(encoded.charAt(pos++));
                if ((long) digit * w > Integer.MAX_VALUE - i) {
                    throw new PunycodeException("overflow");
                }
                i += digit * w;
                int t = k - bias;
                if (t < TMIN) {
                    t = TMIN;
                } else if (t > TMAX) {
                    t = TMAX;
                }
                if (digit < t) {
                    break;
                }
                if (w > Integer.MAX_VALUE / (BASE - TMIN)) {
                    throw new PunycodeException("overflow");
                }
                w *= BASE - TMIN;
            }
            int outPlusOne = outLen + 1;
            bias = adapt(i - oldi, outPlusOne, oldi == 0);
            if (i / outPlusOne > Integer.MAX_VALUE - n) {
                throw new PunycodeException("overflow");
            }
            n += i / outPlusOne;
            i %= outPlusOne;
            if (n > 0x10FFFF || (n >= 0xD800 && n <= 0xDFFF)) {
                throw new PunycodeException("decoded code point out of range");
            }
            if (outLen >= codePoints.length) {
                throw new PunycodeException("output longer than input");
            }
            System.arraycopy(codePoints, i, codePoints, i + 1, outLen - i);
            codePoints[i] = n;
            outLen++;
            i++;
        }
        return new String(codePoints, 0, outLen);
    }

    /**
     * 域名级编码：逐标签处理，点号原样保留；纯 ASCII 标签不加 {@code xn--} 前缀。
     *
     * @param domain 域名字符串
     * @return 编码后的域名字符串
     * @throws PunycodeException 存在空标签
     */
    public static String encodeIdna(String domain) {
        return mapLabels(domain, label -> {
            if (isAscii(label)) {
                return label;
            }
            return ACE_PREFIX + encode(label);
        });
    }

    /**
     * 域名级解码：逐标签处理，点号原样保留；{@code xn--} 前缀大小写不敏感。
     *
     * @param domain 域名字符串
     * @return 解码后的域名字符串
     * @throws PunycodeException 存在空标签，或非 {@code xn--} 标签里出现非 ASCII 字符
     */
    public static String decodeIdna(String domain) {
        return mapLabels(domain, label -> {
            boolean hasAcePrefix = label.length() >= ACE_PREFIX.length()
                    && asciiRegionEqualsIgnoreCase(label, 0, ACE_PREFIX);
            if (hasAcePrefix) {
                return decode(asciiToLower(label.substring(ACE_PREFIX.length())));
            }
            if (!isAscii(label)) {
                throw new PunycodeException("non-ASCII label without ACE prefix");
            }
            return label;
        });
    }

    private static String mapLabels(String domain, Function<String, String> mapper) {
        StringBuilder result = new StringBuilder(domain.length());
        int start = 0;
        boolean first = true;
        while (true) {
            int dot = domain.indexOf('.', start);
            int end = dot < 0 ? domain.length() : dot;
            if (end == start) {
                throw new PunycodeException("empty label");
            }
            if (!first) {
                result.append('.');
            }
            result.append(mapper.apply(domain.substring(start, end)));
            first = false;
            if (dot < 0) {
                break;
            }
            start = dot + 1;
        }
        return result.toString();
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiRegionEqualsIgnoreCase(String s, int offset, String ascii) {
        for (int j = 0; j < ascii.length(); j++) {
            char a = s.charAt(offset + j);
            char b = ascii.charAt(j);
            if (a >= 'A' && a <= 'Z') {
                a = (char) (a - 'A' + 'a');
            }
            if (a != b) {
                return false;
            }
        }
        return true;
    }

    private static String asciiToLower(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c - 'A' + 'a');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static int adapt(int delta, int numpoints, boolean firsttime) {
        delta = firsttime ? delta / DAMP : delta / 2;
        delta += delta / numpoints;
        int k = 0;
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= (BASE - TMIN);
            k += BASE;
        }
        return k + (((BASE - TMIN + 1) * delta) / (delta + SKEW));
    }

    private static char encodeDigit(int d) {
        if (d < 26) {
            return (char) ('a' + d);
        }
        return (char) ('0' + (d - 26));
    }

    private static int decodeDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0' + 26;
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a';
        }
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        throw new PunycodeException("illegal character in encoded section");
    }

    private Punycode() {
    }
}
