# Links 1.0.30（工程名 zanebox）

工程目录：`/Users/zane/Documents/ChatGPT/zanebox`。源码、内核、构建产物、测试与全部历史验收证据已整体迁入本目录；固定 Go 工具链位于 `.toolchains/go-1.26.8`。旧报告中的绝对路径记录的是当时位置，文件相对路径及内容保留。

独立 Android Kotlin + Compose 工程，应用包名 `com.zane.zanebox`，保留 AnyBox 2.1.9 的 Go libcore/sing-box。当前构建版本为 **1.0.30 / versionCode 31**（2026-10-22），发布状态以 GitHub Release 为准。

## 当前版本入口

- [本地正式 APK：Links-1.0.30.apk](reports/repair-1.0.30/Links-1.0.30.apk)
- [1.0.30 验收汇总](reports/repair-1.0.30/ACCEPTANCE.md)与[本地发布核对记录](reports/repair-1.0.30/publication-summary.json)
- [当前本机交接](.ai/HANDOFF.md)与[36 项复核和完整修复方案](docs/plans/2026-10-22-links-1.0.29-full-repair-plan.md)
- [版本发布入口](https://github.com/zane0314/zanebox/releases/tag/v1.0.30)与[正式 APK 下载地址](https://github.com/zane0314/zanebox/releases/download/v1.0.30/Links-1.0.30.apk)

本轮修复前台服务停止、指定 DNS/强制解析、时长与深层 JSON、IPC/测速轮次及资源收尾、节点设置清理、英文文案和验收入口。195 项 JVM、60 类共 135 项仪器验收及签名候选包检查已通过；仪器结果由完整首轮和受影响项复测组成。最终包的安装、身份和发布核对见本机验收记录。

验收仅使用 API35 标准 Android 模拟器；三星 One UI、真机耗电/内存未验证。WireGuard 独立自动测速休眠仍为单列增强，未纳入本轮修复。

应用按 ui/data/config/subscription/backup/runtime/core 包划分职责。服务运行在 `:bg`，连接、计量、恢复和原生box由后台管理；UI通过AIDL客户端投影状态。Go源码及许可保存在native，业务Android源码使用Kotlin。自动生成AIDL/JNI与第三方依赖保留各自实现。

## 本地构建

固定JDK17、Gradle8.11.1、AGP8.7.3、Kotlin2.0.21、API35；Go1.26.8/NDK25/固定gomobile由native/build-native.sh校验。配置本机local.properties的sdk.dir后：

```sh
# 日常修改按影响范围运行目标检查；以下测试类仅为示例
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests com.zane.zanebox.SmartRuleUpdateTest
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleDebug

# 声明全套验收或跨模块变化时，准备完整应用与 Probe 产物
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew -p tests/probe assembleDebug
```

preBuild接入当前native源码构建/ABI/对齐检查；缺少原生工具链不得忽略验证。版本唯一来源为 [version.properties](version.properties)，当前构建1.0.30/31（发布状态见版本入口）。内部 debug 产物位于app/build/outputs/apk/debug/app-debug.apk，正式使用包见上方入口。后续 release 必须复用长期 Links-release.jks（alias=links-release），证书 SHA256 为 `501a5ab904e2e601d38cf8b04b19cc3165a503235710d888ff121d2c55d9ee29`，凭据引用 `~/.agent-shared/credentials/links-release-signing/`；不得生成替代 key 或改用 debug 签名，详见 [签名约定](docs/release-signing.md)。

Go 测试必须在 `native/OwnBoxForAndroid/libcore` 工作目录执行，并与 `native/build-native.sh` 的生产标签一致：

```sh
cd native/OwnBoxForAndroid/libcore
../../../.toolchains/go-1.26.8/go/bin/go test -tags 'with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' ./...
```

1.0.30 最低 Android API 为 35，compileSdk/targetSdk 保持 35；不支持 Android 14 及以下。旧版 1.0.29 的安装边界保持其原包声明。

## 模拟器验收

```sh
S25_HEADLESS=1 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ADB=/opt/homebrew/bin/adb ./tests/with_s25_emulator.sh ./tests/run_emulator_acceptance.sh
```

上面的命令运行默认核心验收范围；全量验收须用 `ZANE_TEST_CLASSES` 明确列出全部实际测试类。专项检查用 `ZANE_TEST_CLASSES` 指定全限定测试类名；仅在需要严格核对方法数量时设置 `ZANE_TEST_COUNT`，默认采用实际测试结果，不要求固定数量或旧截图文件名。默认范围包含 `NodeSwitchLatencyTest` 与 `SelectedNodeDelayUiTest`；这些类或 `XhttpCompatibilityTest` 会启动 selected-delay 夹具，所有所需端口就绪后才运行仪器测试。设置 `ZANE_FIXTURE_GEOIP=/path/to/geoip.db` 可传入资产测试夹具，`ZANE_FIXTURE_DELAY_A_MS=250` / `ZANE_FIXTURE_DELAY_B_MS=0` 可控制既有出口延迟。

`run_optimization_acceptance.sh` 未设置 `ZANE_OPT_CLASSES` 时保留完整流程，包括额外选择缓存测试和 Maestro；设置该变量（逗号分隔的简单类名）时只验收指定仪器测试。需要的界面流程另用现有 `run_maestro_ui.py FLOW...` 执行。

界面 MCP 会话也只使用 Links，必须显式指定 APK，并与 `version.properties` 的包版本匹配：

```sh
./tests/with_s25_emulator.sh ./tests/ui_mcp_session.sh /absolute/path/to/Links.apk
```

`tests/compare_config_optimization.py` 必须提供 `--baseline-source /path/to/historical/source` 与 `--output /path/to/report`，并先构建当前 debug Kotlin classes。基线必须包含旧 `ConfigBuilder.kt`、`RouteAssets.kt`；基线与当前相同会拒绝运行。

`python3 tests/check_acceptance_scope.py` 使用本地假 ADB、夹具和 Maestro 检查入口的范围与失败处理，不连接设备或运行 Gradle。

仅一台模拟器、禁用快照、独立临时data。脚本在新应用包中使用合成数据和独立UID Probe验证真实TUN流量，不操作AnyBox、真机或用户资料。新一轮证据只保留最少摘要、最终APK和必要截图，不复制可重建的build缓存；历史reports不自动删除。三星布局参数模拟器不代表三星后台策略、真实耗电或全部网络/协议永不失败。

## 本地文件分布

| 路径 | 用途与保留范围 |
| --- | --- |
| `app/src/`、`native/`、`gradle/`、根目录构建配置 | 当前应用、原生内核和构建入口 |
| `tests/` | 主机检查、设备回归、Maestro 流程与模拟器包装器 |
| `docs/` | 兼容约定、实现说明、优化范围与历史计划 |
| `reports/repair-1.0.30/` | 当前正式 APK、验收与发布证据 |
| `reports/` 其他目录和文件 | 历代 APK、截图、日志与成功/失败验收证据，保持原路径 |
| `.ai/HANDOFF.md` | 当前状态与继续工作入口 |
| `.ai/history/` | 本次整理前的完整交接与原 README 归档 |
| `.ai/source-backup-*` | 历代源码备份，全部保留 |
| `.toolchains/`、`.build/` | 工具链、已有 APK、原生验证材料与 AVD 配置，全部保留 |
| `app/build/outputs/`、`app/build/reports/`、`app/build/test-results/` | 已有构建成品、映射、报告与测试结果，全部保留 |
| `app/build/intermediates/`、`app/build/tmp/` | 可重建中间文件，本次精确清理；后续构建按需重新生成 |

[兼容范围](docs/backup-compatibility.md)、[配置与订阅兼容约定](docs/config-subscription-compatibility.md)、[首版历史验收](reports/final-acceptance.md)、[测试设计](reports/emulator-acceptance-design.md)继续保留。整理前内容见[原 README](.ai/history/README-before-local-organization-20261007.md)与[完整旧交接](.ai/history/HANDOFF-before-local-organization-20261007.md)；旧版本说明和历史待办仅代表当时状态，旧归档中的路径按工程根目录解释。

原 AnyBox 基线只读；保留长期签名、历史证据和已有 Git 改动。当前任务状态以顶部版本入口与交接为准。
