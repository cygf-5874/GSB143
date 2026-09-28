国际化域名那一层还没做。punycode 是 Java 17 的 Punycode 编解码库（仅 JDK 标准库），自检走 scripts/check.sh。
构建走 `scripts/build.sh`，既有用例走 `bash scripts/test.sh`（`check/` 是固定验收程序，别改）。

`encode` 已经能用，`PunycodeTest.java` 的 10 个用例现在是全绿的；`decode`、`encodeIdna`、`decodeIdna` 三个方法现在方法体全抛 `UnsupportedOperationException`。

任务：按 README「对外契约」的 9 条把解码与 IDNA 标签级处理补上，让固定件全过，并保证既有 encode 行为不回归。

验收：
- bash scripts/build.sh 退出码 0；
- bash scripts/test.sh 10/10 全绿；
- bash scripts/check.sh 退出码 0，8 个场景全过（legacy 2 + roundtrip 3 + overflow 1 + idna 2）。

约束：
1. 不改 `check/`、不改 `PunycodeException`；可以新增类。
2. 对外方法名与签名已定死，不要改；`PunycodeTest.java` 里的用例一条都不许删或改。
3. 不许引入任何第三方依赖，不许加 `pom.xml` / `build.gradle`。
4. 结果不许依赖时间、随机源或哈希顺序。