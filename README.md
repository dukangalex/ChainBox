# ChainBox

基于 [sing-box for Android (SFA)](https://github.com/SagerNet/sing-box-for-android) 的个人 Fork，在官方客户端体验上增加 **原生 Chain（链式代理）** 支持，并逐步提供傻瓜式组链界面。

> 与官方 sing-box / SFA **无隶属关系**。不得以官方名义上架应用商店。

## 标识

| 项目 | 值 |
|------|-----|
| 应用名 | ChainBox |
| 包名 | `io.chainbox.app` |
| 内核 | [dukangalex/sing-box](https://github.com/dukangalex/sing-box) `chain-dev` |

## 功能规划

1. 使用带 Chain 的 sing-box 内核（libbox）
2. 支持导入/运行含 `type: "chain"` 的配置
3. 傻瓜式添加/编辑链式节点（点选组链，无需手写 JSON）

## Chain 配置示例

```json
{
  "outbounds": [
    { "type": "shadowsocks", "tag": "entry" },
    { "type": "vless", "tag": "exit" },
    {
      "type": "chain",
      "tag": "my-chain",
      "outbounds": ["entry", "exit"]
    }
  ],
  "route": { "final": "my-chain" }
}
```

内核说明：https://github.com/dukangalex/sing-box/blob/chain-dev/docs/CHAIN.md

## 构建

客户端依赖 `app/libs/libbox.aar`。后续将通过 GitHub Actions 从 Chain 内核自动编译 libbox 并打包 APK。

## 许可

继承上游 GPL-3.0 等条款。衍生作品不得使用官方原名上架商店。
