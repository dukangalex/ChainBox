# ChainBox

**独立 Android 代理客户端**：在可跟随官方 [sing-box](https://github.com/SagerNet/sing-box) 同步的内核上，提供**傻瓜式链式代理（Chain）**。

> 与 SagerNet / 官方 sing-box **无隶属关系**。不得以官方名义上架应用商店。

## 为什么是独立 App？

- **内核可维护**：Chain 做在低耦合 outbound 层，官方内核升级时主要合并 `chain-dev` 再编 libbox。
- **体验独立**：组链按 NekoBox 思路（点选分组/节点），并保留 sing-box 的 **urltest/selector 自动优选**（含分组→分组）。
- **不绑架官方客户端仓库**：App 只消费 `libbox.aar`，不向官方提交补丁。

## 标识

| 项目 | 值 |
|------|-----|
| 应用名 | ChainBox |
| 包名 | `io.chainbox.app` |
| 内核仓库 | [dukangalex/sing-box](https://github.com/dukangalex/sing-box) `chain-dev` |
| 客户端仓库 | 本仓库 |

## 已有能力

1. 带原生 `type: "chain"` 的 libbox 内核  
2. **工具 → 链式代理**：快速建链（前置分组 + 落地节点/分组）  
3. 一键写入当前配置，可选设为 `route.final`  
4. GitHub Actions 一键出 APK（`Build ChainBox APK`）

## 组链示例

**分组 → 单节点（前置自动优选）**

```json
{
  "type": "chain",
  "tag": "my-chain",
  "outbounds": ["urltest-front", "exit-node"]
}
```

**分组 → 分组（类似订阅接力，两端均可优选）**

```json
{
  "type": "chain",
  "tag": "relay",
  "outbounds": ["urltest-a", "urltest-b"]
}
```

更多内核说明：https://github.com/dukangalex/sing-box/blob/chain-dev/docs/CHAIN.md  
维护与同步流程：[docs/MAINTENANCE.md](docs/MAINTENANCE.md)

## 下载

1. 打开 [Actions → Build ChainBox APK](https://github.com/dukangalex/sing-box-for-android/actions)  
2. 最新成功 run → Artifacts → `ChainBox-apk`  
3. 后续将固定发布到 [Releases](https://github.com/dukangalex/sing-box-for-android/releases)

## 构建

```text
workflow_dispatch: build-chainbox.yml
  → 编译 chain-dev libbox.aar
  → assemble otherDebug APK
```

## 许可

继承上游 GPL-3.0 等条款。衍生作品不得使用官方原名上架商店。
