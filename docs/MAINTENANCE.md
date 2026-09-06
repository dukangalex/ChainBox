# ChainBox 维护说明

ChainBox 是**独立 App**，不是官方 sing-box / SFA 的分支产品名。
维护目标：**内核可长期跟随官方 sing-box；App 只维护组链体验与发布。**

后期同步上游与发版由维护者发起，不要点仓库首页 **Sync fork**。

## 仓库分工

| 仓库 | 分支 | 职责 |
|------|------|------|
| [dukangalex/sing-box](https://github.com/dukangalex/sing-box) | `chain-dev` | Chain 内核（低耦合 outbound） |
| [dukangalex/ChainBox](https://github.com/dukangalex/ChainBox) | `dev` | ChainBox Android 客户端 |

| 项目 | 值 |
|------|-----|
| 应用名 | ChainBox |
| 包名 | `io.chainbox.app` |
| 更新源 | 仅本仓库 Releases |
| 内部代码包 | `io.nekohasekai.sfa`（上游遗留，不对外） |

## 对外身份（已落地）

- 仓库名、README、About、Release、APK 文件名都是 ChainBox。
- App 更新只查 `https://api.github.com/repos/dukangalex/ChainBox/releases`。
- 不走 F-Droid / 官方 SagerNet 更新源。
- 不得用官方名称上架应用商店。

不做事：整包重命名 `io.nekohasekai.sfa`。那会改数千个文件、容易跟丢上游同步能力，对用户无益。

## 内核同步

官方上游：`https://github.com/SagerNet/sing-box`

```bash
cd sing-box
git fetch upstream
git checkout chain-dev
git merge upstream/dev
# 只解决与 chain 相关的冲突

go test ./...
git push origin chain-dev
```

原则：

1. **Fail Closed**：链路失败不得静默落到 DIRECT。
2. **低耦合**：Chain 尽量只挂在 outbound 注册与 dial 链路上。
3. **冲突即停**：与官方架构无法兼容时停止发版。

## App 同步

官方上游：`https://github.com/SagerNet/sing-box-for-android`

```bash
cd ChainBox
git fetch upstream
git checkout dev
git merge upstream/dev
```

冲突时以 ChainBox 为准：包名、签名、组链、配置覆盖、备份、更新检查、`build-chainbox.yml`、`version.properties`。

## 发版

1. 改 `version.properties`（`VERSION_NAME` 与 tag 一致，`VERSION_CODE` 必须递增）。
2. Actions → **Build ChainBox APK** → `publish_release=true` → `version_tag=vX.Y.Z`。
3. 用户只装 `ChainBox-android.apk`。

Secrets：`KEYSTORE_BASE64`，以及 `KEYSTORE_PASSWORD`/`KEYSTORE_PASS`、`KEY_ALIAS`/`ALIAS_NAME`、`KEY_PASSWORD`/`ALIAS_PASS`。

## 能力边界

- 支持：分组→节点、分组→分组、多跳 chain，订阅更新后保持链路。
- 不支持冒充官方；不向官方仓库提交 Chain 补丁。
