# 配置与订阅兼容边界

当前最终验收记录：`reports/final-acceptance.md`、`reports/emulator-20261005-121940/`。下文修复过程中的“待root验收”是历史状态；最新34个EXPORT/TEST样本真实Go校验、域名FakeDNS路由、后台订阅行为已在最终轮PASS，仍不等于所有协议真实服务器握手PASS。

实现代码为新写 Kotlin，未复制 GitHub satelite-one 或其他第三方源码。只读参考本机 AnyBoxConfigPatcher.java 的 DNS/route 迁移行为与当前内核 option schema。YAML 使用 SnakeYAML 2.3，Apache-2.0；JSON 使用 Android org.json，测试使用 org.json:json:20240303。

配置支持完整节点 outbound JSON、稳定tag、源tag/detour映射、前置/落地代理链（引用及环校验）、selector/urltest合并组、全部声明目标、优先与普通高级规则、智能11服务的off/auto/region/节点/组/合并目标、域名和CIDR匹配、新DNS格式与直连DNS分流、IPv6、远程geosite/geoip规则集、mixed分享、Clash API运行密钥隔离。TEST无TUN/监听/缓存，仅保留测速目标和代理链依赖；EXPORT不包含运行密钥。

分享链接支持SS、VMess、VLESS、Trojan、Hysteria/Hysteria2、TUIC、Juicity、Snell、Socks、HTTP/HTTPS、SSH、AnyTLS、ShadowTLS；V2Ray TCP/WS/HTTP/HTTPUpgrade/gRPC/QUIC/XHTTP。完整sing-box outbound/config/JSON数组保留未知字段，WireGuard旧outbound字段转换至endpoint。Clash YAML支持常见协议转换，包括WireGuard endpoint；订阅Base64可嵌套两层。混合输入任一错误会拒绝整个导入，不静默返回截断集合。

明确差异与未验收部分：
- SSR、Naive、Mieru、Trojan-Go等外部插件专用分享协议不受单内核注册支持，显式拒绝；可用协议依当前内核动态校验结果判定。
- 当前内核无 USER-AGENT、IP-ASN 路由字段。智能列表保留原文本，但 USER-AGENT/IP-ASN/OR 行跳过；ConfigBuilder.warnings(data) 返回明确警告。域名/CIDR项仍生效。原基线 SmartRoutingRuleHelper.smali 的 parseList（1126起、1279-1343 switch）也仅处理DOMAIN/DOMAIN-SUFFIX/DOMAIN-KEYWORD/IP-CIDR/IP-CIDR6，其他类型跳过；1571自检明确 USER-AGENT,ignored。因此这些是继承的内核/解析边界，并非新回归。
- 完整sing-box配置只导入节点，不导入原入站/实验参数/原selector组；自定义节点tag保存在metadata.sourceTag以映射同订阅组内部detour。
- Clash专有非sing-box字段及全部插件变体没有逐项证明等价；支持项由现有行为测试和主控native动态检查确定，不声明全部Clash语义兼容。
- 网络下载限制8MiB、重定向5次、30秒调用超时；禁止HTTPS降级；订阅响应头subscription-userinfo保留。协程取消会立即取消Call，包括正文流读取阶段；读取循环亦检测取消。

独立运行测试：`python3 tests/run_config_tests.py`，使用JDK17与缓存Kotlin2.0.21编译器，不需要模拟器。覆盖未知字段保存、主/测试/导出隔离、规则目标和DNS规则集、SS/VLESS/Base64/YAML解析、批次失败原子性、智能分流/高级规则排序、前置落地链与环。Android/原生验收由主控负责。

## 全局设置与旧备份覆盖（补充）

- routeMode仅rule模式消费用户/智能规则；global保留DNS/sniff等系统规则，final=proxy；direct final=direct且DNS直连，不启用FakeIP。
- serviceMode默认vpn；proxy无TUN、mixed必须可用。disableMixedInbound仅关闭VPN模式mixed。perAppEnabled/include/exclude写入TUN include_package/exclude_package，严格校验包名并去重。
- fakeDns默认true（兼容fakeDNS），仅MAIN/EXPORT启用；旧DataStore.enableFakeDns_delegate$lambda$23明确true。strictRoute默认true，与原strictRoute_delegate$lambda$35相同。bypassLan以route_exclude_address绕过私网，bypassLanInCore以ip_is_private直连。
- dnsHosts（兼容hosts）支持原IP+域名文本及JSON predefined映射。dnsStrategy支持全局DNS策略；dnsStrategyDirect/Remote通过DNS根策略与对应query_type/predefined单家族过滤（1.14禁止旧DNS action strategy），dnsStrategyServer通过route默认服务器解析器；domainStrategy为服务器策略的兼容默认。空表示自动。
- globalAllowInsecure默认false，只作用已有TLS配置。muxEnabled默认false；muxProtocol h2mux/smux/yamux、muxMaxStreams默认8、muxPadding默认false。节点原完整multiplex JSON优先保存；原基线ProxyEntity.singMux是节点级设置，新增全局开关不覆盖已有节点multiplex。
- legacy type chain(node_ids)展开为有界递归、首节点至下个detour链；type custom(config)选中后保持完整配置。WireGuard旧outbound迁移至现代endpoint，保留peer密码/保留字节。
- 节点metadata.customConfig、globalCustomConfig及advanced.customRule实际消费；遵循原Util.mergeMap对象递归合并、普通数组替换、+key前插/key+后插。覆盖后重新执行TEST无入站/cache/API和EXPORT无secret约束，proxy含TUN明确拒绝。
- 原高级port/source_port字符串拆分数字与范围；其他协议/源IP/网络匹配字符串转数组。无法识别字段由native动态校验拒绝，保存未知字段不等于声明内核支持。


## 2026-10-05 原生失败后的修复

真实native EXPORT初始化错误证明 sing-box1.14不再接受DNS规则action.strategy。已移除该字段，DNS lookup优先策略放DNS根；IPv4/IPv6 only使用query_type+predefined NOERROR空响应，直连域名条件沿用原匹配，过滤位于FakeIP之前。自定义旧DNS规则也清除旧strategy并迁移单家族过滤，Server domain_resolver.strategy仍是合法独立schema。原AnyBoxConfigPatcher同样移除DNS规则strategy；没有通过启用旧模式绕过错误。

MAIN候选仅来自enabled组，已禁用selectedNodeId回落启用节点，显式前置/落地/链依赖可保留但不进入proxy候选。全部组禁用明确报错；TEST允许显式测试目标，且不存在目标直接拒绝。智能node/group/merge或region无候选按原ConfigBuilder cond_39不建立目标映射的行为省略该智能匹配，继续默认proxy。

Hysteria端口归一化复用SubscriptionParser.applyHysteriaPorts：单单端口server_port；多项/范围server_ports全部start:end。复核原HysteriaFmtKt.hopPortsToSingboxList/parsePortNumber，限制十进制1..5位、1..65535、有序范围、总展开数≤65535、文本≤65536；保留hop_interval。

新增行为回归覆盖disabled默认过滤/显式链依赖、无候选拒绝、DNS规则无strategy、现代单家族过滤、Hy2单443与多端口443:443形式。最终原生动态PASS仍由root后续重编验证确认。

## 2026-10-05 FakeIP持久缓存补齐

第8轮实际日志neko.log:277已记录probe.zanebox.test→198.18.0.2，:280记录TUN TCP到该FakeIP，:282还原域名并路由到node-3；host fixture也记录EXIT_B域名HTTP。该次SocketTimeout不能归为DNS未解析或规则未命中，root已查到请求期间真实底层网络切换。

独立缺口是多次reload后的其他旧Google FakeIP出现missing fakeip record。原ConfigBuilderKt.smali:964-986在非测速配置明确使用experimental.cache_file enabled=true、path="../cache/cache.db"、store_fakeip=true。新Builder恢复同一MAIN/EXPORT默认语义；当前libcore.InitCore(nb4a.go:63-65)将工作目录设为应用no_backup，因此相对路径实际位于该应用私有cache/cache.db，跨core重载维持同一FakeIP命名空间。

Purpose.TEST在节点/全局自定义覆盖后的最终sanitize仍删除整个experimental.cache_file和clash_api，并清空inbounds；自定义完整config的测速亦相同，不能创建共享bbolt缓存或控制监听。custom完整配置MAIN维持用户原配置，与原ConfigBean直接使用完整配置行为一致；普通配置的用户明确自定义覆盖仍保留。

新增TDD测试persistentFakeIpCacheAndTestIsolationAfterOverrides先RED（MAIN FakeIP持久缓存缺失），修复后验证MAIN/EXPORT路径及store_fakeip、关闭统计API时仍有缓存、普通/完整自定义测速无监听/共享缓存/API。JVM只证明配置不变量；实际跨重载映射、bbolt锁与退出资源由root后续native/模拟器验收。
