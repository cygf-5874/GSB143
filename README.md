# punycode — Punycode（RFC 3492）编解码

`punycode` 是一个 Java 17 的 Punycode 编解码库，只用 JDK 标准库（`javac` / `java`），
不引入 Maven / Gradle，也不下载任何第三方包。`encode` 已经能用；本次要把 `decode` 与
IDNA 标签级处理补齐。

```java
Punycode.encode("bücher");              // "bcher-kva"（不含 xn-- 前缀）
Punycode.decode("bcher-kva");           // "bücher"
Punycode.encodeIdna("bücher.de");       // "xn--bcher-kva.de"
Punycode.decodeIdna("XN--BCHER-KVA.de"); // "bücher.de"
```

## 目录

```
src/main/java/punycode/Punycode.java        编解码（encode 已实现；decode / IDNA 待补）
src/main/java/punycode/PunycodeException.java 非法输入 / 溢出时抛出（已给全，勿改）
src/test/java/punycode/PunycodeTest.java    既有用例（10 个，只覆盖 encode，起点全绿）
scripts/build.sh                            编译 main / test
scripts/test.sh                             跑既有用例
scripts/check.sh                            固定验收入口（勿改）
check/Checker.java                          固定验收程序（勿改）
```

## 语言版本前提

- Java 17（`javac` / `java`）。
- **只用 JDK 标准库**：不引入 Maven / Gradle，不引入任何第三方依赖。
- 构建产物放在 `out/`、`out-test/`、`out-check/`，已在 `.gitignore` 中忽略。

## 怎么跑

```bash
bash scripts/build.sh            # 编译 src/main/java 到 out/、src/test/java 到 out-test/
bash scripts/test.sh             # 既有 10 个用例，全绿时打印 10/10 passed
bash scripts/check.sh            # 固定验收，支持 -list 与 --only <组名>
bash scripts/check.sh -list      # 列出全部 8 个验收场景
bash scripts/check.sh --only idna
```

起点状态：`encode` 已实现，`bash scripts/test.sh` 打印 `10/10 passed` 并以退出码 0 结束；
`bash scripts/check.sh` 打印 `结果：通过 2/8` 并以退出码 1 结束（`legacy` 两组过，
`roundtrip` / `overflow` / `idna` 全败，因为 `decode` 与 IDNA 尚未实现）。

> `check/` 下的是**固定验收程序，勿改**。公开的**类型名、方法名与签名**已经是最终形态
> （可以新增内部成员、私有方法或新的类），不要改动下面 `API` 一节列出的这些。

## 对外契约

下面 9 条是 `punycode` 的**对外契约**，它们是本题验收点的唯一出处。
**它们是契约，不是「当前行为」的转述**；实现方式不限，但必须让这 9 条同时成立。

1. **既有 `encode` 不回归**：`encode(String)` 把标签编码成 Punycode（**不含** `xn--` 前缀）：
   纯 basic 输入 → `basic` + `-`（`"abc"` → `"abc-"`）；含非 basic 码点 → basic 段 + `-` +
   编码段（`"bücher"` → `"bcher-kva"`）；basic 段为空时**不写**分隔符；空串 → 空串。
   它的输出**一个字符都不许改**。
2. **新增 `decode` 与 `encode` 往返等价**：对任意标签 `s`，`decode(encode(s))` 必须逐字符等于 `s`
   （含 BMP 之外的码点 / 代理对）。
3. **溢出检测**：编解码过程中任何累加量（`delta`、`i`、`w`、`n`）超出 `int` 范围时，
   必须抛 `PunycodeException`，**不得**发生 `int` 回绕后继续算出结果。
4. **大小写规范化**：`decodeIdna` 对 `xn--` 前缀**大小写不敏感**（`XN--`、`Xn--` 都识别），
   且前缀之后的 Punycode 段先按 **ASCII 小写**规范化再解码 —— 因此 `XN--BCHER-KVA.de`
   与 `xn--bcher-kva.de` 的结果**相同**。`encode` / `decode` 本身对 basic 段不做大小写折叠。
5. **IDNA 标签级处理**：`encodeIdna` / `decodeIdna` 按 `.` 拆成标签逐个处理，**点号原样保留**
   （不去除、不合并）；**空标签**（`..`、以 `.` 开头、以 `.` 结尾、空串）抛 `PunycodeException`。
6. **纯 ASCII 标签不加 `xn--`**：`encodeIdna` 对全部字符 `< 0x80` 的标签**原样输出**；
   只有含非 ASCII 的标签才 `encode` 后加 `xn--`。
7. **结构性非法输入**抛 `PunycodeException`：① `decode` 输入含非 basic 字符（`>= 0x80`）
   却**没有**分隔符 `-`；② 输入以分隔符开头（分隔符前为空，形如 `-…`）；③ basic 段里出现
   非 basic 字符；④ 编码段里出现 `[A-Za-z0-9]` 之外的字符；⑤ `decodeIdna` 里出现不是 `xn--`
   却又含非 ASCII 的标签。
8. **确定性**：同一输入任意多次调用结果**逐字符相同**；不依赖时间、随机源或哈希迭代顺序。
9. **边界**：空标签、单字符（`"ä"`）、最大码点（`U+10FFFF`）都必须正确往返；
   `decode` 产出的码点必须落在合法范围（不落在代理区 `U+D800..U+DFFF`、不超过 `U+10FFFF`）。

## API

```
punycode.Punycode
  static String encode(String input)        （已实现；不含 xn-- 前缀）
  static String decode(String input)        （非法输入 / 溢出抛 PunycodeException）
  static String encodeIdna(String domain)   （逐标签；点号保留；空标签抛 PunycodeException）
  static String decodeIdna(String domain)   （xn-- 前缀大小写不敏感）

punycode.PunycodeException extends RuntimeException
```

## 验收

`check/Checker.java` 是固定验收程序，**不要修改**。它按 4 组共 8 个场景检查上面的契约。
**每个场景都在独立 JVM 子进程里跑，带看门狗**，超时判不通过；
一个场景失败不会遮蔽其余场景（失败不早退）。

| 组 | 场景 | 对应契约 |
| --- | --- | --- |
| `legacy` | `known-vectors` | 1 |
| `legacy` | `basic-only` | 1 |
| `roundtrip` | `mixed` | 2、9 |
| `roundtrip` | `ascii-and-illegal` | 2、7 |
| `roundtrip` | `max-codepoint` | 2、9 |
| `overflow` | `decode-int` | 3 |
| `idna` | `encode-labels` | 5、6 |
| `idna` | `decode-labels` | 4、5、7 |

共 8 个场景。判据全部确定性：没有随机源、没有墙钟、没有哈希顺序依赖、没有性能门槛。