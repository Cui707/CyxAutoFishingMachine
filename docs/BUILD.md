# 构建与本地运行

> 本文件记录**实际验证过**的构建路径。凡是本机没跑通的步骤都不会写在这里。

## 1. 环境要求

| 项目 | 版本 | 备注 |
| --- | --- | --- |
| JDK | **25 或更高**（本机实测 27） | `build.gradle` 用 `options.release = 25`，所以用高版本 JDK 编译也没问题 |
| Gradle | **9.8.0**（由 wrapper 提供） | Java 27 上跑 Gradle 需要 9.8.0 起 |
| Minecraft | `26.3` | 由 Loom 自动下载 |
| Fabric Loom | `1.18.2`（`1.18-SNAPSHOT` 解析结果） | |

Gradle 的依赖分发（Minecraft、Fabric API）在部分网络环境下需要代理。
**代理只写在用户级配置里**：

```
# ~/.gradle/gradle.properties
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
```

不要写进项目的 `gradle.properties` —— 那个文件会入库，会让 CI 连不上网。

---

## 2. 前置 Mod 的 jar

CrimsonCopperGrid **没有发布到任何 Maven 仓库**，本模组的编译依赖是它的构建产物。
`build.gradle` 会按顺序查找：

1. `libs/crimsoncoppergrid-1.1.0.jar`（仓库内自带，CI 走这条）
2. `../CrimsonCopperGrid/build/libs/crimsoncoppergrid-1.1.0.jar`（本机开发可以直接吃上游产物）
3. 命令行参数 `-PccgJar=<jar 绝对路径>`

三处都找不到时，Gradle 会在**配置阶段**直接失败并打印这三个选项，
而不是留下一个含糊的「找不到符号」错误。

自己重新生成第一处的方式：

```bash
cd ../CrimsonCopperGrid
./gradlew build
cp build/libs/crimsoncoppergrid-1.1.0.jar ../CyxAutoFishingMachine/libs/
```

> 升级上下游时，记得同步 `gradle.properties` 里的 `ccg_version`。

---

## 3. 构建

```bash
./gradlew build
```

产物：

```
build/libs/cyxautofishingmachine-<version>.jar
build/libs/cyxautofishingmachine-<version>-sources.jar
```

只编译不打包（更快，日常改代码用）：

```bash
./gradlew compileJava compileClientJava
```

---

## 4. 本地运行

```bash
./gradlew runClient   # 客户端
./gradlew runServer   # 服务端
```

运行目录是 `run/`（已被 `.gitignore` 排除）。

前置 Mod 在开发环境里靠**类路径**被 Fabric Loader 发现 ——
`build.gradle` 里的 `implementation files(ccgJar)` 会把它放进 `runtimeClasspath`，
Loom 启动开发环境时会把整条 `runtimeClasspath` 交给 Loader，Loader 扫描其中的
`fabric.mod.json`，因此 CCG 会以「来自类路径的模组」身份被加载。

**首次运行服务端需要 `run/eula.txt`**：

```
eula=true
```

需要重新生成 Minecraft 的反编译源码（阅读 / 核对原版实现时用）：

```bash
./gradlew genSources
```

产物落在项目的 `.gradle/loom-cache/` 下。它耗时较长（本机约 3 分 20 秒）。

### 4.1 无人值守测试（RCON）

需要在不启动客户端的情况下验证方块行为时，给 `run/server.properties` 加上：

```
enable-rcon=true
rcon.port=25585
rcon.password=<自定>
```

服务器起来后，用仓库里带的测试脚本跑场景（脚本会自己解析返回值并逐条断言）：

```bash
python tools/rcon_test.py --help                       # 列出全部场景
python tools/rcon_test.py phase3 --password <自定>      # 例：电网接入
```

需要停服才能验证的场景拆成 `-prepare` / `-check` 两步，中间由人停服、起服。
实测过哪些项、结果如何，见 `docs/TESTING.md`。

### 4.2 自动化测试（GameTest）

RCON 够不着界面里的槽位，也模拟不了玩家点击。菜单与界面的行为用 Fabric 的
GameTest 框架验证，两个入口都是无人值守的：

```bash
./gradlew runGameTest          # 服务端：起无头服务器跑 @GameTest 方法，报告写 build/junit.xml
./gradlew runClientGameTest    # 客户端：起真实客户端，自动进世界、放机器、右键、截图
```

- 服务端测试类：`gametest.MenuGameTests`（槽位准入 / 快速移动守恒 / 界面数据通道）
- 客户端测试类：`client.gametest.AutoFishingMachineClientGameTest`
  （端到端打开界面，截图落在 `build/client-gametest/screenshots/`）
- 测试类通过 `fabric.mod.json` 的 `fabric-gametest` / `fabric-client-gametest`
  入口点登记，跑 `build` 时不会被带进正式 jar 的执行路径（入口点只在测试运行器里被消费）。

---

## 5. 容易踩的坑

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `Could not find method modImplementation()` | Loom 1.18 已经没有 `modImplementation` / `modApi` 这类配置了。26.3 环境下开发命名空间与产物命名空间是同一套官方名，不再需要重映射，所以 mod 依赖与普通依赖走同一条 `implementation` 路径。 | 用 `implementation`（本项目的做法）。JiJ 仍然用 `include`。 |
| `找不到前置 Mod ... 的 jar` | `libs/` 里没有、`../CrimsonCopperGrid/build/libs/` 也没有 | 见上面第 2 节 |
| 服务端启动时报 `Missing or unsupported mandatory dependencies` | `mods/` 里没放 CrimsonCopperGrid | 本模组在 `fabric.mod.json` 里把它声明为硬依赖，这是有意的 |
| `Duplicate mod` 或 energy 相关报错 | Team Reborn Energy 同时以「CCG 内嵌 jar」和「独立类路径 jar」出现 | 见 `docs/ARCHITECTURE.md` 的说明；本项目只保留一条来源 |
