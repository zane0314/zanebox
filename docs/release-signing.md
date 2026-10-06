# Links 长期 release 签名

所有 Links release 更新必须继续使用同一份 `Links-release.jks`，alias 为 `links-release`。证书 SHA-256 固定为：

```
501a5ab904e2e601d38cf8b04b19cc3165a503235710d888ff121d2c55d9ee29
```

构建默认读取仓库之外的 `$HOME/.agent-shared/credentials/links-release-signing/signing.properties`。也可用 `LINKS_SIGNING_PROPERTIES` 指定受控配置文件。配置包含 `storeFile`（可相对于配置目录）、`storePassword`、`keyAlias`、`keyPassword`；不要把任何真实值复制到源码、GitHub、README 或日志。密码另在本机受控凭据库的 Links Android release signing 条目中保存。

`./gradlew :app:verifyReleaseSigning` 验证私钥、密码及固定证书。每次 release 构建都会先执行此检查；文件缺失、密码错误或证书改变时必须失败，禁止自动生成替代密钥或回退 debug 签名。debug 构建继续使用开发签名。

主副本与本机备用副本都在仓库之外的受控凭据目录。NAS 备份在飞牛 NAS 的用户存储卷 `Links-signing-backup` 下；准确版本目录及 SHA 记录保存在本地验收报告。恢复时将 JKS、signing.properties 和 manifest 一起取回受控目录，校验哈希并运行签名验证，再构建。不要只恢复 APK；APK 不能恢复私钥。

新长期证书与此前发布的开发证书不同，旧安装版本不能直接用新签名覆盖。首次迁移需要先备份应用配置，再按用户明确授权更换安装；本任务不卸载或更改手机。以后长期签名版本之间持续使用上述同一密钥。

JKS 及密码文件禁止提交、上传为 Release 附件或放入公开备份。GitHub 只保存公开证书指纹、构建代码和签好的 APK。
