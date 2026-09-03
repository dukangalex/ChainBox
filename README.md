# ChainBox

基于 [sing-box for Android (SFA)](https://github.com/SagerNet/sing-box-for-android) 的个人 Fork，目标是在官方客户端体验上增加 **原生 Chain（链式代理）** 支持，并逐步提供更傻瓜式的组链界面。

> 本项目与官方 sing-box / SFA **无隶属关系**。不得以官方名义上架应用商店。

## 功能规划

1. **内核**：使用带 Chain 功能的 sing-box 内核  
   https://github.com/dukangalex/sing-box （`chain-dev` 分支）
2. **配置**：支持 `type: "chain"` 的完整配置导入与运行
3. **界面（进行中）**：傻瓜式添加/编辑链式节点（点选节点组链，无需手写 JSON）

## 与官方 SFA 的区别

| 项目 | 官方 SFA | ChainBox |
|------|----------|----------|
| 应用名 | sing-box | ChainBox |
| 包名 | io.nekohasekai.sfa | io.github.dukangalex.chainbox |
| 内核 | 官方 sing-box | dukangalex/sing-box（Chain） |
| 链式代理 UI | 无 | 规划中 |

## 构建说明

客户端依赖预编译的 `app/libs/libbox.aar`（由 sing-box 的 `build_libbox` 生成）。

### 1. 用 Chain 内核编译 libbox

```bash
# 需要：Go、Android NDK
git clone -b chain-dev https://github.com/dukangalex/sing-box.git
cd sing-box
go run ./cmd/internal/build_libbox -target android
# 将生成的 libbox.aar 复制到本仓库 app/libs/libbox.aar
```

### 2. 编译 APK

```bash
git clone https://github.com/dukangalex/sing-box-for-android.git
cd sing-box-for-android
# 放入 app/libs/libbox.aar 后
./gradlew :app:assembleOtherRelease
```

## Chain 配置示例

```json
{
  "outbounds": [
    { "type": "shadowsocks", "tag": "entry", "...": "..." },
    { "type": "vless", "tag": "exit", "...": "..." },
    {
      "type": "chain",
      "tag": "my-chain",
      "outbounds": ["entry", "exit"]
    }
  ],
  "route": { "final": "my-chain" }
}
```

内核文档：https://github.com/dukangalex/sing-box/blob/chain-dev/docs/CHAIN.md

## 许可

继承上游 GPL-3.0 等条款。衍生作品不得使用官方原名上架商店。
