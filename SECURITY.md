# 安全边界与已知残余风险

> 本文件记录**已经在代码里强制执行的**安全约束，以及**已知但尚未全域收口的残余面**。
> 新增网络 / 分享 / 更新相关代码前先读一遍，避免把「局部收口」误当成「全域收口」。

## 1. 明文（http / ws）策略 —— 已知残余风险（P2-13）

**现状**

- `app/src/main/res/xml/network_security_config.xml` 的 `base-config` **仍然是
  `cleartextTrafficPermitted="true"`**（平台层放行）。
- 原因：Android 的 `<domain>` 只匹配具体主机名或 IP 字面量，**不支持 CIDR 网段**，
  无法表达「10/172.16-31/192.168 私网放行、其余禁止」。若把 base-config 改成 `false`，
  OkHttp 4.12 会在建立连接时抛
  `CLEARTEXT communication to <host> not permitted by network security policy`，
  所有用 IP 访问局域网 ComfyUI 的用户会直接连不上（本应用的核心场景）。
- 真正的边界在应用层：`util/NetworkPolicy.isCleartextAllowed(host)`（仅私网/本机放行）
  由 `util/CleartextGuardInterceptor` 执行，以**网络拦截器**（`addNetworkInterceptor`）挂在
  `ComfyUIClient.secured()` 上，覆盖该类的 5 个 OkHttp client
  （API / WebSocket / 上传下载 / object_info / 缩略图）。
- **为什么必须是网络拦截器**：OkHttp 的 30x 跳转由 `RetryAndFollowUpInterceptor` 内部重新
  发起，只重走「连接 + 网络拦截器」这一段，应用拦截器（`addInterceptor`）对初始请求只执行
  一次 —— 挂错层会让「https 主机 302 到公网 `http://`」这一跳绕过检查。行为测试见
  `util/CleartextRedirectGateTest`（同一跳转下：网络拦截器版本被拒、应用拦截器版本漏过）。
  仍需注意：网络拦截器运行在连接建立之后，因此跳转目标的 TCP 连接可能先建立，但**明文请求
  （含认证头）不会被发出**；要连 TCP 都不建，只能关闭自动跟随并逐跳校验（参考
  `UpdateDownloadWorker.redirectGuardInterceptor` 的写法）。

**§1 的残余面（仍然只受平台层约束，闸门管不到）**

| 链路 | 说明 |
|---|---|
| `BrowserAuthScreen` / `BrowserAuthActivity` 的 WebView | 浏览器认证登录页。WebView 只受平台 NSC 约束，可对公网主机发明文；WebView 没有按主机配置明文的 API，只能整页拦截或保持现状。 |
| 将来新增的 `HttpURLConnection` / 第三方下载库 / 推送 SDK 等 | 只要不是经 `ComfyUIClient.secured()` 构造的 client，就不经过 `CleartextGuardInterceptor`。 |

**新增网络链路时的正确做法**

1. 优先复用 `ComfyUIClient.secured()` 构造的 client；或
2. 至少挂上 `CleartextGuardInterceptor`，或在发请求前用
   `NetworkPolicy.isCleartextAllowed(host)` 判定并拒绝公网明文；
3. **不要**为了让某个功能跑通把 NSC 或闸门改宽 —— 需要明文的主机必须是私网/本机。

## 2. FileProvider 可共享范围（P2-12）

- `res/xml/file_paths.xml` 只声明两处：`cacheDir/share/` 与 `getExternalFilesDir()/update/`。
  **禁止再出现 `path="."`**（历史问题就是整个 cacheDir / filesDir 被当成可共享根）。
- 所有对外分享的载荷必须写进 `util/ShareFileStore`（`cacheDir/share/`），再从那里取 content URI。
- 视频/音频**播放**使用 `file://`（进程内 ExoPlayer / MediaMetadataRetriever），不经 FileProvider。
  若要把播放 URI 交给别的应用，必须先 `ShareFileStore.exportVideoForShare` 复制到 share/，
  否则 `file://` 离开进程会抛 `FileUriExposedException`。

## 3. 更新包完整性（P2-11）

- 下载完成、SHA-256 校验通过后，清单 sha256 落盘为 `update/update.apk.sha256`；
  冷启动 `UpdateRepository.restoreFromDisk` 重算磁盘文件 SHA-256 比对，并再走
  `AppInstaller.isApkTrusted`（只校验包名，与安装入口同一实现）才允许进入「可安装」态。
- **信任模型**：旁车文件与 APK 同目录，能替换 APK 的攻击者同样能替换旁车，
  所以 sha256 提供的是完整性与「只换包不换旁车」这类低成本替换的拦截；
  包名校验只拦「装成另一个 App」，**签名认证由系统安装器在安装时校验**，
  不要用 sha256 替代签名校验。
- 没有旁车的旧包（早期版本下载）会被判不可复验并删除（fail-closed），需要重新下载。

## 4. 备份恢复的输入校验（P2-24）

- 恢复时逐台校验 hostname / port（`BackupValidator.validateServer`），
  不合法条目整条跳过、不写服务器列表、不参与 ID 映射，并按个数提示用户。

## 5. 改动这些边界后的最低验证

```bash
./gradlew :app:testDebugUnitTest
```

以下测试钉住了上述边界，改动时同步更新测试与本文件：

- `SecurityConfigWiringTest`：file_paths 不得有 `path="."`、FileProvider URI 只能来自
  share/ 与 update/、明文闸门必须以**网络拦截器**挂在 `secured()` 上、NSC 不信任用户 CA、
  本文件必须存在；
- `CleartextPolicyTest` / `NetworkPolicyTest`：私网/公网主机判定；
- `CleartextRedirectGateTest`：30x 跳转后的公网明文请求必须被网络拦截器拦下
  （并证明应用拦截器会漏掉这一跳）；
- `UpdateRestorePolicyTest`：恢复复验判据与接线；
- `BackupServerValidationTest`：服务器条目校验与计数提示。