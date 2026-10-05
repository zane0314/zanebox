# 合成旧备份迁移后的 native 配置样本

全部地址、UUID、密码、密钥和数据均为测试常量，不含用户备份或凭据，禁止把它们当实际代理配置使用。

来源链路：按原 AnyBox smali Bean.serialize 字段顺序构造 Kryo bytes → Android Parcel 格式外壳 → logical.version=1 → BackupManager.import → ConfigBuilder。

`<type>.json` 是 EXPORT，包含完整迁移集合；`<type>-test.json` 是 TEST，隔离所选协议与必要链依赖，不带 TUN/持久缓存/固定端口。节点包括 AnyTLS、HY2、TUIC5、SSH、WireGuard、Chain（SOCKS+SS）、完整自定义 config、Juicity、Snell4、ShadowTLS、HTTP、VMess/VLESS、Trojan、SOCKS/SS，以及保留XHTTP extra的VLESS。

`synthetic-appdata.json` 保存导入后的数据字段及原模拟Parcel，便于检查节点customConfig/customOutbound、组/链引用和元数据无损。新ZIP导出与回读在JUnit核对。

JUnit只证明格式转换与配置编译；动态 nativeCheck 和 Android实际运行由主控验收。这些样本不做远端连接，不代表协议服务的网络互操作已完成。
