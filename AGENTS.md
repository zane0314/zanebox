# zanebox 项目约定

- 当前维护工程：本目录 `/Users/zane/Documents/ChatGPT/zanebox/`。保留工作区已有改动和历史产物。
- 每轮应用优化或修复，在该轮声明的验收范围全部 PASS 后，版本末位和 `versionCode` 各递增一次。同轮多项修复只递增一次；候选构建不等于验收通过。明确记录验收范围，缺少真机证据不得声称真实耗电或真机内存改善。
- 真机截图优先通过 `adb exec-out screencap -p` 直接保存到电脑。必须在设备落盘的临时证据，回传且验证成功后，只按本轮创建清单中的精确路径删除。回传失败或来源不明则保留；禁止清理其他文件或历史证据。
- 模拟器验收使用工程内 `tests/with_s25_emulator.sh COMMAND [ARGS...]`。每次仅运行一台，禁止快照，使用本轮独立临时数据目录。退出后确认模拟器停止，再清理该目录；保留 AVD 配置、系统镜像、工具链及电脑上的证据，不批量清理其他缓存。
- S25+ 模拟器仅匹配屏幕布局参数，标准 Android 镜像不代表三星 One UI、三星后台策略或真实耗电。

- zanebox 为独立 Kotlin/Compose 工程，仅保留 AnyBox 的 libcore/sing-box；当前 1.0.0/1 是内部开发版本，未经用户授权不发布测试版或正式版、不提交推送。
- 仅使用模拟器验收。原 AnyBox 基线 `/Users/zane/Documents/ChatGPT/zane 代理软件/work/AnyBox-2.0.0-clean-150/` 只读，保留其数据、产物、签名和既有 Git 改动。
