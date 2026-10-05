# 备份和WebDAV实现验收边界

当前最终验收记录：`reports/final-acceptance.md`、`reports/emulator-20261005-121940/`。下文“待主控运行”保留为历史过程；最终Android Parcel、34个生产迁移配置native校验和失败恢复回滚已PASS；主机BackupTest16项与独立runner17项（含真实TLS）PASS。未支持旧格式/插件范围仍按本文明确拒绝，不外推兼容性。

新应用备份是ZIP（manifest.json + data.json），格式1，app=zanebox，SHA-256校验。import只返回完整验证后的AppData，不访问数据库；UI验证后将私有暂存数据交给后台串行restore，后台停止/排空旧连接，再事务替换并重启；启动失败回滚数据库和内存统计。输入、迁移后持久JSON、ZIP展开累计量、单次HTTP响应均限制64MiB；JSON严格UTF8、无尾随对象、最大128层，含字符串封装的高级配置。ZIP检查EOCD/中央目录/本地文件名一致性、CRC、重复路径、路径白名单和必备JSON数据类型。

AnyBox格式2的manifest.json + logical.json + preferences.json校验两个摘要。旧nekobox_backup.json ZIP和裸logical JSON接受version=1。使用Kotlin实现Android Parcel（little endian、UTF16、4字节对齐）以及原ByteBufferInput Kryo字符串/固定整数/长度读取，未加载原应用字节码。

已实现迁移：普通/订阅分组版本0/1；订阅bean版本0..3；SOCKS bean0..2；SS bean0..5；标准V2Ray bean1..10的HTTP、VMess/VLESS、Trojan版本0..2与ShadowTLS；TCP、WS、HTTP、HTTPUpgrade、gRPC、XHTTP；TLS/Reality/ECH、packet编码、mux/Brutal；AnyTLS bean0/1、Hysteria/Hysteria2 bean0..7、TUIC5(bean2)、SSH bean0、WireGuard bean0..2、Juicity bean0/1、Snell4/6(bean0..3)、Chain bean0/1、ConfigBean0(type0完整sing-box配置/type1原生outbound)；规则的原始字段、优先路由标记、节点和分组出站目标；类型偏好1..5直接读取，StringSet6兼容canonical字节长度/legacy字符长度Unicode帧；原始字节持续保留。原logical、preferences、typed KeyValue以及节点原Parcel均持久保留，未知偏好不会被删除。

当前内核注册已核对`native/OwnBoxForAndroid/libcore/box_include.go`：内置AnyTLS/HY/HY2/TUIC/SSH/ShadowTLS/Snell/Juicity；WireGuard旧outbound是废弃报错stub，配置层转换为现代endpoint。Naive、Mieru、Trojan-Go、Neko没有当前native注册，且原ProxyEntity.needExternal明确标为外部插件路径；本次单内核应用尚未接入这些插件。SSR虽在旧ConfigBuilder有生成代码，但needExternal未标外部且当前native未注册，因此旧基线本身是否可运行也未确认，不能声称靠插件解决。遇到这些节点仍拒绝全恢复，以免变成不可运行unknown type。另TUIC4、Snell非4/6、KCP、V2Ray bean0的旧ECH启发式布局、Neko bean和未知版本仍属于未完成兼容。不得将这些范围报告PASS。

Chain以内部`type:chain,node_ids:[...]`持久化，完整config以内部`type:custom,config:{...}`持久化，由ConfigBuilder转为真正native配置，禁止把内部type传native；旧节点级customConfigJson存metadata.customConfig，由配置层合并；customOutboundJson覆盖最终节点字段，metadata保留原值；旧规则config存advanced.customRule。原始Parcel/偏好与原logical保留，可恢复未知源字段。旧日志级别0..4/TUN0..2/IPv6+DNS策略、选择ID、分应用白黑名单、DNS/WebDAV映射按原源字段消费语义转换；组userOrder映射新显示排序，组order保留节点排序选项；订阅lastUpdated秒换为毫秒且原值保留。分组订阅options与旧偏好保留不等于新应用运行层已实现每个旧行为。

WebDAV为HTTPS目录，拒绝URL内嵌凭据、查询/fragment、跨源/目录外href、嵌套文件、路径遍历和重定向。Depth1 PROPFIND解析禁用DOCTYPE/ENTITY的UTF8 XML；列出本应用备份和原AnyBox-backup-*及nekobox备份。上传顺序：唯一历史文件 -> latest镜像 -> 仅本应用历史保留数量；任何前步失败不进行后续删除。保留1..100，默认10。列表/下载最大64MiB，条目上限10000；错误不附URL、密码、认证头或原始服务器响应。

验证：`python3 tests/backup_checks.py` 独立编译生产backup与Models，JUnit4使用合成Parcel/Kryo（依据只读原smali序列化顺序）和人工恶意ZIP。完整Gradle和模拟器由主控执行。未读取真实用户备份/凭据，未操作ADB/模拟器，本地OkHttp MockWebServer真实HTTPS已验证真实TLS信任链/localhost SAN、Depth1 XML/list、历史上传/latest顺序、保留数量、下载、删除与XXE拒绝；未验证真实外部WebDAV服务的个别扩展。生产客户端默认系统TLS，测试仅internal注入限定夹具信任链。

跨模块合成配置：`docs/evidence/legacy-native/`存各迁移协议导入后的ConfigBuilder EXPORT与隔离TEST配置（全部synthetic值），由主控执行native动态CheckConfig。JUnit结构PASS不能代替native动态验证。

最新验证证据：独立runner 17项PASS（含真实本地TLS互操作）；此前根Gradle单元测试BackupTest15项PASS，WebDavInteropTest因无TLS夹具env明确skip1；Android真实Parcel instrumented源码已提供，待主控运行。

第二轮实际Android native曾出现HY2 `bad port range:443`：已核对原hopPortsToSingboxList并修正生产转换，Legacy/Parser共享同一normalizer，单端口用server_port、多项或范围均start:end，范围合法性与总量校验覆盖；旧v0..5嵌入serverAddress的跳跃端口也按原读取方式拆分。当前34个EXPORT/TEST样本由新生产代码再生成，全部DNS规则不再携带strategy，等待主控下一轮native验收，不能据此宣称Android整体PASS。
