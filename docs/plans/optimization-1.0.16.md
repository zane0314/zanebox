# Links 1.0.16 优化：已批准范围

用户2026-10-07确认执行A/B/C/D方案；排除14备份安全、15LAN共享安全。
A：同服务域名数组合并/IP进程独立，保持优先规则/服务/普通顺序；off关闭；FakeIP开启后域名A/AAAA优先FakeIP，hosts/reject/显式DNS例外保留；构建索引，复用配置，校验/回滚。
B：可见1s/无界面或息屏10s采样，重采样按时间；节点归因保留、60s写库、stop尾流；只读隔离；Doze sleep/wake；网络去重/UID缓存；connections有界大响应+可见错误；保留锁屏订阅alarm和租约，WM仅存在定时订阅时注册；规则按到期时间等待。
C：SQLite v3所有设置入kv，v1/v2迁移保留、未变节点免重复解析；异步UI开库/初始化，模式单键读取，tile/boot无主线程数据库。
D：日志有界尾读/default warn；复用集中默认值，枚举内部状态/命令兼容AIDL；Runtime统计职责抽取；合并seed；精确清理未用asset/pyc先备份。
验收：单元+旧schema原生解析/FakeDNS/路由/Runtime/自动应用/锁屏订阅/迁移并发回归，采样及命令日志、配置/耗时和APK大小证据；S25+ wrapper单台无快照，Maestro MCP为主Mobile为辅。最后全PASS后一次升1.0.16/17，长期501a签名正式R8再验。不发布推送，不操作手机，不宣称真机耗电内存改善。
主控负责config/runtime/store；small_cleanup仅LogTail/其测试/QuickTile/未用asset/pyc/gitignore。保留现有用户改动。
