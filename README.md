# ChainBox

独立的 Android 代理客户端。基于 [sing-box](https://github.com/SagerNet/sing-box) 内核，面向「前置机场 + 落地 VPS」场景，把链式代理做成可点选、可固定、订阅更新后仍能保持的功能。

**与 SagerNet / 官方 sing-box 无隶属关系。不得使用官方名称上架应用商店。**

[Releases](https://github.com/dukangalex/ChainBox/releases) · [Actions](https://github.com/dukangalex/ChainBox/actions) · [使用说明](docs/USER_GUIDE.md)

## 特点

- **傻瓜式链式代理**：当前配置为入口，另选落地（通常是你的 VPS）。出网 IP 为落地。
- **订阅更新不丢链**：落地选择写在本地设置里，更新机场订阅后启动仍会自动套回。
- **显式配置覆盖**：规范化、严格路由、DNS 防泄漏、禁用 IPv6、禁用 QUIC、排除国内 QUIC。
- **本地 / 云备份**：配置与开关可备份恢复。
- **签名覆盖安装**：用仓库 Secrets 中的 keystore 打正式包，可覆盖升级。

## 标识

| 项目 | 值 |
|------|-----|
| 应用名 | ChainBox |
| 包名 | `io.chainbox.app` |
| 客户端仓库 | [dukangalex/ChainBox](https://github.com/dukangalex/ChainBox) `dev` |
| 内核 | [dukangalex/sing-box](https://github.com/dukangalex/sing-box) `chain-dev` |

## 链式怎么走

```
设备 → 前置机场节点 → 落地 VPS → 网站
```

网站看到的应是 **VPS 公网 IP**，不是前置机场 IP。

操作：

1. 仪表里选中「前置」订阅并启动一次，确认能上网。
2. 再导入落地配置（你的 VPS）。
3. **工具 → 链式代理生成器 → 选择落地 → 保存并固定**。
4. 重载服务，查 IP。应为 VPS。

取消链式后即恢复普通出口。详见 [docs/USER_GUIDE.md](docs/USER_GUIDE.md)。

## 下载

到 [Releases](https://github.com/dukangalex/ChainBox/releases) 下载最新 APK。  
未出 Release 时可在 [Actions → Build ChainBox APK](https://github.com/dukangalex/ChainBox/actions) 取构建产物。

首次覆盖安装需要同一签名。自行构建请在仓库 Settings → Secrets 配置：

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## 构建

仓库 Actions 工作流 `build-chainbox.yml`：

1. 编译 `chain-dev` 的 `libbox.aar`
2. 组装 APK
3. 可选发布到 Releases（`publish_release` + `version_tag`）

维护说明：[docs/MAINTENANCE.md](docs/MAINTENANCE.md)

## 许可与声明

- 上游许可为 GPL-3.0，本仓库继承该许可。
- 上游版权归属 nekohasekai / SagerNet。
- ChainBox 是独立衍生客户端，不代表官方。
- 仅供合法网络用途。使用者自行遵守当地法律。
