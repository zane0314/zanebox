# 订阅更新与持久调度契约

生产模块：`SubscriptionUpdater` 的 pure prepare/begin/apply/record 与唯一 store-aware `SubscriptionUpdater.update(store, group, context, connected, automatic=false)`（扩展函数，调用方导入 `subscription.update`）。AppViewModel手动更新、SubscriptionUpdateWorker自动更新均调用同一网络/解析/提交流水线，没有第二套节点替换实现。

## 已核实的原选项语义

- `autoUpdate=false`、`autoUpdateDelay=1440`，间隔为分钟。原SubscriptionBean.initializeDefault与SubscriptionUpdater.smali:575-708；原同样使用OneTimeWorkRequest。
- `updateWhenConnectedOnly=false`；启用后当前实现要求真实代理state=2；后台断开延期5分钟，连接边沿立即补偿已到期组，普通自动更新可在代理断开、应用UI关闭时执行。
- `customUserAgent=""` 使用默认zanebox/1.0.0，否则完整单行ASCII UA应用于请求及各跳重定向；禁止CR/LF和超过1024字符。
- `filterMode=0`关闭；1保留节点显示名正则find匹配、2排除find匹配；regex空不筛选。原RawUpdater.smali:2640-2815同为Pattern.matcher(displayName).find。正则编译错误或结果为空拒绝整次节点提交，保留旧数据。
- `deduplication=false`；启用后按canonical outbound身份去重，保留第一条，显示名不参与。
- `forceResolve=false`；启用后在更新阶段使用非VPN底层Network.getAllByName（无可用底层网时系统DNS）解析server和WireGuard peers，保留TLS SNI。优先/限制地址族遵循ipv6及dnsStrategyServer/domainStrategy；没有允许族或解析失败保留域名并写metadata.subscriptionResolveError；用户取消则不提交。四条节点批次界限，已成功主机结果缓存。原GroupUpdater.forceResolve与rewriteAddress确为实际改写IP/SNI，不只是运行时配置标记。
- `serverDnsResolver=""`；原ConfigBuilder.smali:6655-6745在构建阶段针对节点服务器域名设置单独DNS。新SubscriptionDnsOptions.apply已集成ConfigBuilder，生成现代DNS server并给实际node/endpoint domain_resolver引用；支持local、UDP/TCP/TLS/HTTPS/QUIC/H3，不改变订阅下载DNS。

未知原始Group.options字段保留；成功更新同步updatedAt、userinfo及旧subscriptionLastUpdated秒时间字段。我们的运行日志放options.subscriptionRuntime，避免覆写原配置。

## 稳定提交与身份

begin在SQLite事务中生成持久request token；apply必须确认组仍存在、URL与用户选项签名一致、updatedAt未更新、request token仍属于本请求。后到响应、新请求、删除/编辑组均不能覆盖当前节点。

身份匹配复用NodeIdentity及原分享链接，保留ID、测速、统计和自定义metadata；forceResolve前身份放metadata.subscriptionIdentity避免IP变更导致每轮换ID。去除节点后统一清理选中项、前置/落地引用、普通/智能规则、合并组和区域覆盖；丢失跳点的旧链节点删除并继续清理引用，避免隐式缩短代理链。合法已选节点保留；空组首次订阅或无效/禁用选择按启用组order→节点order选择默认并同步selectedGroupId。

## Android接入与后台证据

依赖 `androidx.work:work-runtime-ktx:2.9.1`。主进程ZaneApplication设置 `SubscriptionScheduler.connectionCheck: suspend(Context)->Boolean` 后调用initialize(context)；:bg不初始化。root提供真正绑定当前前台包装器AIDL读取state2的探测，禁止依赖陈旧SharedPreferences connected。

- initialize安装15分钟持久reconcile任务并初次遍历数据库。每组唯一工作名 `zanebox-subscription-<id>`，约束NetworkType.CONNECTED；延迟按lastUpdated+分钟间隔，成功后APPEND_OR_REPLACE续排。
- reconcile涵盖进程重启/重启设备/备份恢复后数据库当前组；组删除、关闭、选项或URL变化取消/替换旧工作。计划指纹包含URL hash、全部用户选项（含未知字段）及更新时间，WorkManager input只含group ID。
- ServiceClient主进程state2边沿调用onConnectionChanged(context,true)；探测client不会递归触发。
- `SubscriptionScheduler.status(context)` 返回groupId/state/lastAttempt/nextAt/error/workState，供root仪器验收持久调度与断开延期；运行日志可从Group.options.subscriptionRuntime读取。错误信息过滤URL，避免暴露订阅令牌。
- 自动成功发updates SharedFlow，VM实际reload SQLite并提示“后台订阅已更新，请点击应用修改”。不自动重启当前内核。原finishUpdateInternal(smali134-222)是GroupManager.postUpdate及SubscriptionGroupAutoTest；没有查到订阅成功自动内核reload。保留onUpdated扩展回调，root当前不安装重启。

WorkManager服从系统网络/省电约束，延迟任务不是精确定时器。独立JVM证明业务与计划计算；Android进程死亡、设备重启、only-connected真实Binder探测、实际WorkManager工作状态仍由root模拟器验收，不以计算测试替代。

## 验证

`python3 tests/run_subscription_update_tests.py`：过滤find两模式、去重/空拒绝/正则错误、强制IP保SNI/原身份、稳定ID与流量/引用清理/旧字段、持久请求过期拦截、分钟到期/连接策略、custom UA请求、节点DNS现代配置、首订阅默认与禁用选择、WireGuard peer解析及地址族失败保域名。

TDD RED实证：生产缺类首测失败；首订阅默认选择expected99/actual0；WireGuard peer解析expected192.0.2.5/actualwg.example。对应实现后各回归转GREEN。精确最终结果见docs/evidence/subscription-updater-jvm.txt，配置附近回归见docs/evidence/config-subscription-jvm.txt。
