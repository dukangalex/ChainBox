# ChainBox 维护说明

ChainBox 是**独立 App**，不是官方 sing-box / SFA 的分支产品名。
维护目标：**内核可长期跟随官方 sing-box；App 只维护组链体验与发布。**

## 仓库分工

| 仓库 | 职责 |
|------|------|
| [dukangalex/sing-box](https://github.com/dukangalex/sing-box) `chain-dev` | Chain 内核（低耦合 outbound） |
| [dukangalex/sing-box-for-android](https://github.com/dukangalex/sing-box-for-android) | ChainBox Android 客户端 |

包名：`io.chainbox.app`  
应用名：ChainBox

## 内核同步（最重要）

官方上游：`https://github.com/SagerNet/sing-box`

```bash
cd sing-box
git fetch upstream
git checkout chain-dev
git merge upstream/dev   # 或官方当前开发分支
# 只解决与 chain 相关的冲突，禁止为“省事”改写官方无关代码
go test ./...
# 重点：outbound/chain、依赖解析、官方测试矩阵
```

原则：

1. **Fail Closed**：链路失败不得静默落到 DIRECT。
2. **低耦合**：Chain 尽量只挂在 outbound 注册与 dial 链路上。
3. **冲突即停**：与官方架构无法兼容时，停止发版并记录原因，不靠覆盖官方代码强行同步。

内核文档：`docs/CHAIN.md`（在 sing-box 仓库）。

## App 与内核衔接

1. CI（`build-chainbox.yml`）从 `chain-dev` 编译 `libbox.aar`。
2. App 使用该 aar，不内嵌第二套代理实现。
3. 组链 UI 只生成/写入原生 `type: "chain"` outbound，并复用已有 selector/urltest。

## 发版节奏

1. 内核无破坏性变更 → 只升 App 补丁版。
2. 内核跟随官方大版本 → 重编 libbox → 回归组链 → 打 GitHub Release。
3. Release 资产命名：`ChainBox-<version>-android.apk`。

## 能力边界

- 支持：分组→节点（前置 urltest 优选）、分组→分组（两端优选）、多跳 chain。
- 不支持冒充官方；不向官方仓库提交 Chain 补丁。
