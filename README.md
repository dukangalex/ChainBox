# ChainBox

ChainBox 是基于 [sing-box](https://github.com/SagerNet/sing-box) 内核的 Android 代理客户端，在其上提供可配置的多级出站（链式代理）以及相关的运行时覆盖选项。

本项目与 SagerNet 及官方 sing-box 无从属或授权关系，不得使用官方名称及标志进行商业发布或应用商店上架。

- 发行版：[Releases](https://github.com/dukangalex/ChainBox/releases)
- 构建：[Actions](https://github.com/dukangalex/ChainBox/actions)
- 使用说明：[docs/USER_GUIDE.md](docs/USER_GUIDE.md)
- 维护说明：[docs/MAINTENANCE.md](docs/MAINTENANCE.md)

## 项目标识

| 项目 | 值 |
|------|-----|
| 应用名称 | ChainBox |
| 应用包名 | `io.chainbox.app` |
| 客户端仓库 | [dukangalex/ChainBox](https://github.com/dukangalex/ChainBox) （分支 `dev`） |
| 内核仓库 | [dukangalex/sing-box](https://github.com/dukangalex/sing-box) （分支 `chain-dev`） |
| 更新检查 | 仅本仓库 GitHub Releases |

## 功能范围

- 多级出站：在当前配置中选择入口分组/节点，再选择落地（可来自当前或其他配置）；外部访问的源地址应为落地节点地址。**每份配置独立保存链路**。
- 链路保持：出口选择保存于本地；远程订阅更新后仍按已保存的出口复用，不必重配。
- 运行时覆盖：中国直连、ECH（DNS HTTPS / EchConfig）、严格路由、DNS、IPv6、QUIC、WebRTC 防护，不修改订阅原文。
- 备份与恢复：本地文件及 WebDAV（连通性指示灯常驻；支持覆盖/兼容恢复；VPN 开启时尽量走系统直连）。

具体操作见 [docs/USER_GUIDE.md](docs/USER_GUIDE.md)。

## 多级出站

```
设备 → 入口节点 → 出口节点 → 目的站
```

1. 导入并启用配置，确认基础连通。
2. 在「工具 → 链式代理」中为**当前配置**选择入口与落地并保存。其他配置可各自绑定不同落地。
3. 重载服务后验证出站公网地址。

取消链式后，出站恢复为当前配置的默认出口。

## 下载

请从 [Releases](https://github.com/dukangalex/ChainBox/releases) 下载 `ChainBox-android.apk`。

覆盖安装要求使用相同签名证书，且新版本的 `versionCode` 须大于已安装版本。自行构建时须在仓库 Secrets 中配置：

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD` 或 `KEYSTORE_PASS`
- `KEY_ALIAS` 或 `ALIAS_NAME`
- `KEY_PASSWORD` 或 `ALIAS_PASS`

## 构建

使用工作流 `.github/workflows/build-chainbox.yml`：

1. 从 `chain-dev` 编译 `libbox.aar`
2. 组装 Android APK
3. 若 `publish_release=true` 并指定 `version_tag`，则发布至 GitHub Releases

客户端版本号以 `version.properties` 为准。

上游内核的跟进与发布节奏见 [docs/MAINTENANCE.md](docs/MAINTENANCE.md)。

## 致谢

ChainBox 建立在上游开源工作之上，谢谢：

- [sing-box](https://github.com/SagerNet/sing-box)，由 [nekohasekai](https://github.com/nekohasekai) 与 [SagerNet](https://github.com/SagerNet) 维护的通用代理平台
- [sing-box for Android](https://github.com/SagerNet/sing-box-for-android)，本客户端的上游界面与服务框架

上述致谢不构成从属、授权或官方认可。

## 许可

本仓库继承上游 [GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html)。
上游代码版权归属原作者。ChainBox 为独立衍生工作，不代表上游项目。
