# 并行实现接口契约

包名 com.zane.zanebox；一个 app 模块；Kotlin2.0.21、AGP8.7.3、API35、JDK17；Compose BOM2024.10.01。

主控拥有构建、data/Models.kt、data/ZaneStore.kt、runtime/、core/、AIDL和最终验收。配置协作者拥有 config/、subscription/及其测试；UI协作者拥有 MainActivity.kt、ui/；备份协作者拥有 backup/、迁移读取及其测试。不要修改其他人的责任文件。

## 数据接口（由主控实现）

`Node(id:Long, groupId:Long, name:String, outbound:String, shareLink:String="", ping:Int=-1, tx:Long=0, rx:Long=0, order:Int=0, status:Int=0)`。
outbound 为完整 sing-box outbound JSON，保留协议未知字段，不含用户显示名；每次生成由配置层赋稳定 tag `node-<id>`。持久化显示名与协议身份独立。

`Group(id:Long,name:String,subscriptionUrl:String="",enabled:Boolean=true,order:Int=0,updatedAt:Long=0,userInfo:String="")`。

`RouteRule(id:Long,name:String,domains:String="",packages:String="",ipCidrs:String="",outbound:String="proxy",enabled:Boolean=true,order:Int=0)`。目标字符串 proxy/direct/block/node:<id>/group:<id>/merge:<id>。

`MergeGroup(id:Long,name:String,nodeIds:List<Long>=emptyList(),groupIds:List<Long>=emptyList(),mode:String="selector",selectedId:Long=0)`。

`AppData(nodes:List<Node>=emptyList(),groups:List<Group>=emptyList(),rules:List<RouteRule>=emptyList(),merges:List<MergeGroup>=emptyList(),settings:Map<String,String>=emptyMap())`，提供 `selectedNodeId:Long`、`selectedGroupId:Long`、`setting(key,default)`、`bool(key,default)`。

`ZaneStore(context)` 提供 `val data:StateFlow<AppData>`、`snapshot():AppData`、`reload():AppData`、`update(transform:(AppData)->AppData):AppData`、`replace(data:AppData):AppData`、`nextId():Long`。SQLite事务保证读-改-写跨进程序列化；JSON编解码 `AppData.toJson():String` / `AppData.fromJson(String)`。

设置键：selectedNodeId/selectedGroupId、routeMode(rule/global/direct)、ipv6、dnsRemote(https://1.1.1.1/dns-query)、dnsDirect(local)、tunStack(mixed)、mtu(1500)、mixedPort(2080)、allowLan(false)、shareEnabled(false)、sharePort(2081)、apiPort(9090)、perAppEnabled(false)、perAppMode(include/exclude)、perAppPackages(换行)、testUrl(https://www.gstatic.com/generate_204)、testTimeout(10000)、testConcurrency(4)、logLevel(info)、clashApi(false)、sniff(true)、autoStart(false)、networkReset(true)、theme(system)、fontScale(1.0)、webdavUrl/User/Password。

## 配置/订阅接口

`config.ConfigBuilder.build(data:AppData,purpose:Purpose=MAIN, testNodeId:Long=0, runtimeSecret:String=""):String`。`enum Purpose { MAIN, TEST, EXPORT }`。main 有TUN；test仅目标节点无TUN/端口/持久cache；export不含运行secret。`validate(String)` 结构校验，native动态校验由主控负责。共享路由、DNS、tag引用规则。

`subscription.SubscriptionParser.parse(text:String):List<ParsedNode>`；`ParsedNode(name:String,outbound:String,shareLink:String="")`；支持分享链接、Base64、Clash YAML、sing-box outbounds/config、JSON数组；错误不返回悄悄截断的集合。`SubscriptionClient.fetch(url):FetchedSubscription(body,userInfo)`；UI在IO调用；取消/限额/HTTPS/重定向处理。

## 运行接口（由主控实现）

`runtime.RuntimeSnapshot(state:Int=0,started:Long=0,generation:Long=0,txRate:Long=0,rxRate:Long=0,txTotal:Long=0,rxTotal:Long=0,error:String="")`；0停止1连接中2已连接3停止中4失败。

`runtime.ServiceClient(context)` 提供 `val snapshot:StateFlow<RuntimeSnapshot>`，`connect()`/`close()`，`start()`/`stop()`/`reload()`/`selectNode(id:Long)`/`testNodes(ids:List<Long>)`/`cancelTests()`；长任务异步，`val events:SharedFlow<String>`反馈、`val testResults:StateFlow<Map<Long,Int>>`。UI收到结果后store.reload。

## 备份接口

`backup.BackupManager(context)` 提供 `export(data:AppData):ByteArray` ZIP、`import(bytes:ByteArray):AppData` 先完整验证再返回。`WebDavClient(url,user,password)` 提供 list/upload/download/delete，HTTP在IO，规范URL/大小限制/超时，凭据不记录日志。原AnyBox logical.json含Android Parcel/Base64及Kryo bean，必须显式兼容解析或标记待支持，不能将原备份静默丢弃。

## 协作边界

补充接口：RouteRule 增加末位默认参数 advanced:String="{}", prioritize:Boolean=false；Group 增加 frontProxy:Long=0,landingProxy:Long=0,options:String="{}"；Node增加metadata:String="{}"。Models主控已实现持久化往返。高级rule JSON只包含匹配/action参数，outbound仍由目标解析统一管理；prioritize=true 的用户规则位于内建智能分流之前。

智能设置：smart.<service>.target=off/auto/region:hk/us/kr/jp/sg/tw/group:<id>/node:<id>/merge:<id>；smartRules.<service> 为原AnyBox .list内容；服务key youtube/telegram/netflix/disney/tiktok/x/meta/spotify/google/ai/custom；smartSourceMergeId 源合并组。节点区域覆盖 nodeRegion.<id>=hk/us/kr/jp/sg/tw。服务默认target=off；UI提供可编辑源URL smartUrl.<service> 和手动更新，runtime实现自动24h更新。

ServiceClient新增 val logs:StateFlow<List<String>>、refreshLogs()、val exitIp:StateFlow<String>、queryExitIp()；连接started是elapsedRealtime，VPN权限由Activity准备。扩展val traffic:StateFlow<String>（JSON含apps/domains/nodes数组，条目name/tx/rx）、refreshTraffic()、resetTraffic()、setTrafficEnabled(Boolean)；val connections:StateFlow<String>、refreshConnections()、closeConnection(id:String)、closeAllConnections()；val speedResult:StateFlow<String>、speedTest(nodeId:Long,mode:String="simple")、cancelSpeedTest()。所有任务异步，消息走events。

WebDAV接口：WebDavEntry(name:String,size:Long,modified:String,href:String)；WebDavClient(url,user,password).list():List<WebDavEntry>、upload(bytes:ByteArray,retain:Int=10):WebDavEntry、download(entry):ByteArray、delete(entry):Unit。BackupManager.`import` 使用Kotlin反引号调用。

所有agent不操作模拟器或真机、不改原AnyBox、不发布、不读真实凭据。不得用占位实现或不可用按钮冒充完整功能；未支持项列入验收清单。主控实际构建与模拟器验收。
