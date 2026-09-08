# ChainBox 使用说明

## 安装

1. 从 [Releases](https://github.com/dukangalex/ChainBox/releases) 下载 **ChainBox-android.apk**。
2. 允许安装未知来源应用后安装。
3. 同一签名且 versionCode 更大的新版可直接覆盖。

设置 → 应用版本 应与 Release tag 一致（如 1.0.15）。

语言可在 **设置 → 应用 → 语言** 中选择简体中文、繁体中文、English 等；不要只留「跟随系统」时若系统是中文、界面仍大量英文，请更新到 1.0.13+。

检查更新：打开 **设置 → 应用 → 检查更新**。有新版本会弹出「下载安装 / 查看发布」；已是最新或失败也会弹窗，并可跳到 GitHub Releases。请先允许安装未知应用。

## 导入配置

1. 仪表 → 添加配置。
2. 导入机场或自建节点订阅，设为当前配置。
3. 如需跨配置串联，再导入另一份落地配置。落地不必设为当前配置。

远程订阅若含 Clash 风格 `plugin_opts` 对象，1.0.15 会在导入/刷新时自动转成 sing-box 字符串，不再报 unmarshal 错误。

## 链式代理

Chain 是 sing-box 原生 outbound：按你指定的顺序串联已有出站。不对节点规定「前置 / 中转 / 落地」以外的固定角色，也不绑定机场或协议。

1. 打开 **工具 → 链式代理生成器**。
2. **选择入口**：当前配置里的分组或节点（前置机场）。不要依赖「漏网之鱼」。
3. **选择落地**：当前配置里的另一个节点，或另一份配置里的分组/节点。
4. 点 **保存并固定为链式出口**。
5. 重载服务。链路为：入口 → 落地 → 目标。IP 检测网站应显示**落地**地址，不是前置机场。

**Fail Closed**：链路或节点失败会明确报错并停止启动，不会偷偷改走 DIRECT。哪些流量走 Chain，仍由你自己的路由规则决定。

订阅更新后不必重新点选，只要曾经保存过。入口/落地分组被订阅改名后，到链式页重新选择即可。

**取消链式**：同一页点「取消链式」，出口回到普通出站。

1.0.14 及更早内核把 hop 的 detour 接反了，出口 IP 会显示成前置机场。1.0.15 已按文档「第一个是入口、最后一个是落地」编译。

## 配置覆盖

路径：**设置 → 配置覆盖**。开关只改运行时，不改订阅文件。点 ⓘ 可看说明。

| 开关 | 作用 |
|------|------|
| 配置规范化 | **覆写模式**：保留节点与分组，DNS/路由/TUN 换成内置分流模板（国内域名直连、私网直连、UDP 本地 DNS、WebRTC 拦截）。不下载 GitHub rule-set |
| 防 WebRTC 泄露 | 拦截 UDP 3478/19302/5349。规范化开启时已包含 |
| 严格路由 | TUN `strict_route` |
| DNS 防泄漏倾向 | DNS 独立缓存、自动探测网卡 |
| 禁用 IPv6 | DNS `ipv4_only`，拦截 IPv6 |
| 禁用 QUIC | 拦截 UDP 443 |
| 排除国内 QUIC | 国内域名 UDP 443 走 direct，其余仍拦 |

1.0.12 及更早版本开启规范化会因 legacy inbound sniff 字段无法启动，1.0.13 已修复。
1.0.13 仍会把本地 DNS 的 `detour` 写成 `direct`，sing-box 1.12+ 会报 *empty direct outbound*；1.0.14 已去掉该 detour。
1.0.14 仍会在启动时下载 GitHub geoip/geosite，DNS 走代理后会 *query loopback*；1.0.15 改为内置域名列表，不再访问 GitHub。

## 备份

**设置 → 备份与恢复**。

## 调试

**工具**页：网络质量 / STUN / 崩溃报告。

开启规范化或防 WebRTC 后，STUN 测试失败是预期（端口被拦）。测 NAT 时请临时关掉这两个开关。

## 常见问题

**必须卸载才能装新版**  
debug 与正式签名混过。卸载后装正式 `ChainBox-android.apk`，以后同一 keystore 可覆盖。

**点更新闪退**  
versionCode 没变大或签名不同。换正式版并且版本号递增的包。

**链式保存后无法启动，提示 unknown field fail_closed**  
内核 chain 出站只有 `outbounds` 字段。更新到 1.0.14+。

**链式保存后无法启动，提示含 DIRECT**  
入口被锁在「漏网之鱼」。更新到 1.0.13+，在链式页手动选「节点选择」等代理分组再保存。

**链式连上了但 IP 是前置机场**  
1.0.14 内核 detour 方向反了。更新到 1.0.15，保存链式后重载，再用 ippure 等检查，应显示落地 IP。

**配置规范化开启后报 empty direct outbound / detour to an empty direct**  
本地 DNS 不要 detour 到空的 direct。更新到 1.0.14+。

**配置规范化开启后报 DNS query loopback / geoip-cn GitHub**  
规范化不再远程下载 rule-set。更新到 1.0.15。

**配置规范化开启后报 legacy inbound fields**  
更新到 1.0.13+。规范化不再把 `sniff` 写进入站。

**远程更新报 plugin_opts cannot unmarshal object**  
Clash 订阅把 plugin_opts 写成了对象。1.0.15 会自动转成字符串。

**STUN 测试 EOF**  
防 WebRTC 或规范化拦截了 3478。这是防泄漏生效，不是网络坏了。

**检查更新没反应 / 没有安装提示**  
1.0.14 起会弹窗；若系统拦截未知来源，会先跳到「允许安装未知应用」。也可点「查看发布」用浏览器下载 `ChainBox-android.apk`。
