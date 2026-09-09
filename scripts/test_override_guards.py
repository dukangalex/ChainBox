#!/usr/bin/env python3
"""Source guards for ChainBox overlay modules. Run from repo root."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def main() -> int:
    errors: list[str] = []
    normalize = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigNormalize.kt")
    if "fun apply(" in normalize:
        errors.append("ConfigNormalize rewriter was removed; do not add apply()")
    if "webrtcRejectRules" not in normalize:
        errors.append("WebRTC STUN reject helper missing")
    if "STUN_UDP_PORTS" not in normalize or "domain_keyword" not in normalize:
        errors.append("WebRTC overlay must cover extra STUN ports and stun./turn. hostnames")
    if "cnDomainSuffixArray" not in normalize:
        errors.append("CN domain helper missing")

    override = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigQuicOverride.kt")
    if "Settings.configNormalize" in override:
        errors.append("ConfigQuicOverride must not call config normalize")
    if "ChainBindings.get" not in override:
        errors.append("runtime chain must look up the current profile binding")

    ui_override = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/ProfileOverrideScreen.kt")
    if "配置规范化" in ui_override or "configNormalize" in ui_override:
        errors.append("Profile override UI must not expose config normalize")

    compat = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigCompat.kt")
    if "plugin_opts" not in compat or "objectToPluginOpts" not in compat:
        errors.append("ConfigCompat must coerce Clash plugin_opts objects to strings")

    chain = read("app/src/main/java/io/nekohasekai/sfa/chain/ChainRuntimeCompiler.kt")
    if "fail_closed" in chain:
        errors.append("Chain compiler must not emit fail_closed; kernel ChainOutboundOptions only has outbounds")
    if "不可作为前置代理" in chain:
        errors.append("Chain compiler must not fail closed just because a selector contains DIRECT")
    if "fun resolveMainTag" not in chain:
        errors.append("resolveMainTag should be reusable by the UI")
    if "isFinalLike" not in chain:
        errors.append("isFinalLike missing; 漏网之鱼 would be locked as entry again")
    if "landing/exit" not in chain and "public IP" not in chain:
        errors.append("chain compiler should document packet path: entry first, landing last")
    if "if (sameProfile) add(req.landingTag)" not in chain:
        errors.append("cross-profile landing tags must not be extraExcluded from the entry hop")
    if "detour in entryTags" not in chain:
        errors.append("DNS detours should only rewrite entry hops, not every proxy")

    bindings = read("app/src/main/java/io/nekohasekai/sfa/chain/ChainBindings.kt")
    if "per-profile" not in bindings.lower() and "Per-profile" not in bindings:
        errors.append("ChainBindings must document per-profile isolation")
    if "chainBindingsJson" not in read("app/src/main/java/io/nekohasekai/sfa/database/Settings.kt"):
        errors.append("Settings.chainBindingsJson missing")

    reapply = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigChainReapply.kt")
    if "ChainBindings.get(currentProfileId)" not in reapply:
        errors.append("runtime reapply must only chain the selected profile")

    ui = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/ChainBuilderScreen.kt")
    if 'picker == "entry"' not in ui:
        errors.append("Chain builder must let the user pick the entry hop")
    if "ChainBindings.put" not in ui:
        errors.append("Chain builder must save a per-profile binding")
    if "仅绑定当前" not in ui and "只绑定当前" not in ui:
        errors.append("Chain builder UI must say the binding is current-profile only")
    if "订阅更新" not in ui:
        errors.append("Chain builder should tell users bindings survive subscription refresh")
    for leak in ("Kitty", "MYCF", "edgetunne", "edgtgt", "longteng"):
        if leak in ui:
            errors.append(f"Chain builder UI must not hardcode airport name {leak}")
    if "validation-only" not in ui and "仅用于提前校验" not in ui and "validation-only here" not in ui:
        errors.append("save() must comment that apply() is validation-only")
    if 'popBackStack("dashboard"' not in ui:
        errors.append("saving a chain should return to the dashboard")
    if "showOtherBound" not in ui or "otherBoundLines" not in ui:
        errors.append("chain builder must let the user tap to see which other profiles are bound")
    if "点此查看" not in ui and "点这里查看" not in ui:
        errors.append("other-binding hint should be tappable")

    locales = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/AppSettingsScreen.kt")
    if "locales_config" not in locales:
        errors.append("Language picker must read res/xml/locales_config.xml")

    cn = read("app/src/main/res/values-zh-rCN/strings.xml")
    for key in ("core", "service", "network_quality", "silent_install", "remote_control", "chain_builder"):
        if f'name="{key}"' not in cn:
            errors.append(f"zh-rCN missing {key}")

    settings = read("app/src/main/java/io/nekohasekai/sfa/database/Settings.kt")
    if "webrtcProtect" not in settings:
        errors.append("Settings.webrtcProtect missing")
    if 'WEBRTC_PROTECT) { false }' in settings or 'WEBRTC_PROTECT) {false}' in settings:
        errors.append("WebRTC protect should default on so Chinese STUN cannot leak by default")
    if "configNormalize" in settings:
        errors.append("Settings.configNormalize must stay removed")
    if "chinaDirect" not in settings:
        errors.append("Settings.chinaDirect missing")
    if "echDns" in settings or "ECH_DNS" in settings:
        errors.append("ECH overlay was removed; Settings.echDns must not return")
    if "fun closeDatabase" not in settings:
        errors.append("Settings.closeDatabase missing; restore would hit open WAL")
    if "db?.close()" not in settings and "db = null" not in settings:
        errors.append("Settings.closeDatabase must drop the Room instance so restore can reopen")
    if "setQueryExecutor { GlobalScope.launch" in settings:
        errors.append("Settings must not queue Room queries on GlobalScope after close")
    if "restoreCompat" not in settings:
        errors.append("Settings.restoreCompat missing")

    profiles = read("app/src/main/java/io/nekohasekai/sfa/database/ProfileManager.kt")
    if "db = null" not in profiles:
        errors.append("ProfileManager.closeDatabase must drop the Room instance so restore can reopen")
    if "setQueryExecutor { GlobalScope.launch" in profiles:
        errors.append("ProfileManager must not queue Room queries on GlobalScope after close")

    china = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigChinaDirect.kt")
    for needle in ("ip_is_private", "CHINA_DNS_IPS", "CHINA_DNS_DOMAINS", "LAN_DOMAIN_SUFFIXES", "cnDomainSuffixArray"):
        if needle not in china:
            errors.append(f"China direct overlay missing {needle}")
    if "applyEchDns" in china or "ECH_DNS_TAG" in china or "unblockHttpsQueries" in china:
        errors.append("ECH DNS overlay must stay removed from ConfigChinaDirect")
    if "applyCnDns" in china or '.put("server", "223.5.5.5")' in china:
        errors.append("China direct must not inject a DNS server (empty-direct detour crash)")
    if '.put("detour", directTag)' in china or 'put("detour", direct' in china:
        errors.append("China DNS must not set detour to empty direct (sing-box 1.12 rejects it)")
    if "dropLegacyChinaDns" not in china:
        errors.append("China direct must drop leftover chainbox-cn-dns from older overlays")
    if "DIRECT_FALLBACK_TAG" not in china:
        errors.append("findOrCreateDirect must not reuse a non-direct tag named direct")
    if "stripBrokenDnsDetours" not in china:
        errors.append("China direct should strip leftover empty-direct DNS detours")

    compat = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigCompat.kt")
    if "stripBrokenDnsDetours" not in compat:
        errors.append("ConfigCompat must strip DNS detours to empty/missing direct")
    if "isEmptyDirect" not in compat:
        errors.append("ConfigCompat must detect empty direct outbounds")
    if "migrateLegacyDns" not in compat:
        errors.append("ConfigCompat must migrate dns.fakeip / legacy address servers")
    if 'put("type", "fakeip")' not in compat:
        errors.append("legacy fakeip object must become type=fakeip server")
    if "migrateRcodeServers" not in compat:
        errors.append("ConfigCompat must convert type:rcode DNS servers to rule actions")
    if "unknown transport type: rcode" not in compat:
        errors.append("ConfigCompat must document rcode transport removal")
    if "MAX_CONFIG_CHARS" not in compat:
        errors.append("ConfigCompat.sanitize must cap JSON size")
    inbound = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigInboundCompat.kt")
    overlay = compat + inbound
    if "ConfigInboundCompat.apply" not in compat:
        errors.append("ConfigCompat.sanitize must call ConfigInboundCompat.apply")
    if "migrateLegacyInbounds" not in inbound:
        errors.append("ConfigInboundCompat must migrate inbound sniff/domain_strategy to route actions")
    if "rewriteGithubRawUrl" not in inbound or "testingcf.jsdelivr.net" not in inbound:
        errors.append("ConfigInboundCompat must rewrite GitHub raw rule-set URLs to testingcf jsDelivr")
    if "migrateSpecialOutbounds" not in inbound:
        errors.append("ConfigInboundCompat must convert type:dns / type:block outbounds")
    if "legacy inbound fields" not in overlay:
        errors.append("compat overlay must document 1.13 inbound field removal")

    override = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigQuicOverride.kt")
    if "Settings.chinaDirect" not in override:
        errors.append("ConfigQuicOverride must apply china direct")
    if "echDns" in override or "applyEchDns" in override:
        errors.append("ECH overlay must stay removed from ConfigQuicOverride")
    if "applyLogLevel" not in override or '"info"' not in override:
        errors.append("runtime overlay must force log.level=info")
    if "applyOne" not in override:
        errors.append("each overlay switch must apply in isolation so one failure cannot skip the rest")
    if "entryMissing" not in override:
        errors.append("subscription update should fall back when entry tag is gone")
    if "independent_cache\", true)" not in override and "independent_cache\", true" not in override:
        if 'dns.put("independent_cache", true)' not in override:
            errors.append("DNS protect must force-overwrite independent_cache")
    if "stripBrokenDnsDetours" not in override:
        errors.append("runtime overlay must strip empty-direct DNS detours after other switches")
    webrtc_call = override.find('applyOne(warnings, "防 WebRTC 泄露")')
    china_call = override.find('applyOne(warnings, "中国直连")')
    if webrtc_call < 0 or china_call < 0:
        errors.append("WebRTC and China Direct overlays must both apply")
    elif webrtc_call < china_call:
        errors.append("WebRTC reject must apply after China Direct so STUN ports win over CN bypass")
    installer = read("app/src/github/java/io/nekohasekai/sfa/vendor/SystemPackageInstaller.kt")
    if "launchVisibleInstaller" not in installer:
        errors.append("in-app update must show the system package installer UI")
    if 'throw IllegalStateException("请先允许' in installer:
        errors.append("unknown-app-sources prompt must not crash the UI thread")
    if "Toast.makeText" not in installer:
        errors.append("unknown-app-sources should toast instead of throwing")

    ui_override = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/ProfileOverrideScreen.kt")
    if "中国直连" not in ui_override:
        errors.append("Profile override UI must expose 中国直连")
    if "ECH" in ui_override or "echDns" in ui_override:
        errors.append("Profile override UI must not expose ECH")
    if "强制" not in ui_override:
        errors.append("Profile override UI should say overlays are forced")

    compiler = read("app/src/main/java/io/nekohasekai/sfa/chain/ChainRuntimeCompiler.kt")
    if "保存的入口" in compiler and "已不存在" in compiler:
        errors.append("compiler must not fail closed when a saved entry tag disappeared after subscription update")
    if "MAX_CONFIG_CHARS" not in compiler:
        errors.append("compiler must cap JSON size before JSONObject(content)")
    if "pinTrafficToChain" not in compiler:
        errors.append("compiler must rewrite proxy routes so the entry cannot become the public exit")
    if "ENTRY_PREFIX" not in compiler:
        errors.append("compiler must keep generated entry hops from becoming the public exit")

    dav = read("app/src/main/java/io/nekohasekai/sfa/utils/BackupManager.kt")
    if "pickNonVpnNetwork" not in dav:
        errors.append("WebDAV should bypass VPN using the underlying network")
    if "TrustManagerFactory" not in dav:
        errors.append("WebDAV should use the system TrustManager explicitly")
    if "AndroidCAStore" not in dav:
        errors.append("WebDAV should load AndroidCAStore")
    if "deleteSidecars" not in dav:
        errors.append("restore must delete sqlite WAL/SHM sidecars")
    if "isZipFile" not in dav:
        errors.append("restore must reject non-zip downloads")
    if "closeDatabase" not in dav:
        errors.append("restore must close Room before overwriting db files")
    if "classifyProbe" not in dav:
        errors.append("WebDAV probe must classify 401 as auth failure, not success")
    if "if (code == 401 || code == 403) return@runCatching true" in dav:
        errors.append("WebDAV probe must not treat HTTP 401 as success")
    if "authFailedMessage" not in dav:
        errors.append("WebDAV 401 must produce a dedicated auth error")
    if "compat: Boolean" not in dav:
        errors.append("restore must support compatibility mode")
    if 'listOf("PROPFIND"' in dav or '"PROPFIND", "OPTIONS"' in dav:
        errors.append("WebDAV probe must not use PROPFIND; Android HttpURLConnection rejects it")
    if "friendlyProbeDetail" not in dav:
        errors.append("probe must hide ProtocolException / PROPFIND internals")
    if "HEAD" not in dav:
        errors.append("WebDAV probe should use HEAD/GET like the real backup path")

    dash = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/DashboardViewModel.kt")
    if "if (currentState.isLoading) return" in dash:
        errors.append("profile switch must not block on isLoading")
    if "selectedProfileId = profileId" not in dash:
        errors.append("profile switch must update UI immediately")

    boot = read("app/src/main/java/io/nekohasekai/sfa/bg/BootReceiver.kt")
    if "ACTION_MY_PACKAGE_REPLACED" not in boot or "launchApp" not in boot:
        errors.append("update install must relaunch the app")

    icon_bg = read("app/src/main/res/values/ic_launcher_background.xml")
    if "#000000" in icon_bg or "#000" in icon_bg:
        errors.append("launcher background must be white, not black")
    if "#FFFFFF" not in icon_bg and "#ffffff" not in icon_bg:
        errors.append("launcher background should be #FFFFFF")

    icon_fg = read("app/src/main/res/drawable/ic_launcher_foreground.xml")
    if "#FBBF24" not in icon_fg and "#F59E0B" not in icon_fg:
        errors.append("launcher foreground must be a Rubik cube (orange-yellow top missing)")
    if "#0EA5E9" not in icon_fg:
        errors.append("launcher foreground 正面 must be saturated sky-blue #0EA5E9")
    if "iso(0," not in read("scripts/gen_cube_icon.py") and "x=0" not in read("scripts/gen_cube_icon.py"):
        errors.append("cube 正面 must be the left x=0 face, not z=0")
    if "#F43F5E" not in icon_fg and "#E11D48" not in icon_fg:
        errors.append("launcher foreground must be a Rubik cube (rose face missing)")
    if "gift" in icon_fg.lower() and "cube" not in icon_fg.lower():
        errors.append("launcher foreground should be a cube, not a gift box")

    logs = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/log/LogModels.kt")
    if "filterLogLevel: LogLevel = LogLevel.INFO" not in logs:
        errors.append("log viewer default filter must be INFO")

    leaks = []
    for rel in (
        "app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/ChainBuilderScreen.kt",
        "app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/ProfileOverrideScreen.kt",
        "docs/USER_GUIDE.md",
        "README.md",
    ):
        text = read(rel)
        for leak in ("Kitty", "MYCF", "edgetunne", "longteng.de5"):
            if leak in text:
                leaks.append(f"{rel} contains private name {leak}")
    errors.extend(leaks)

    manifest = read("app/src/main/AndroidManifest.xml")
    if 'android:icon="@drawable/ic_menu"' in manifest:
        errors.append("QS tile must not use the upstream sing-box Z icon")
    if "ic_qs_tile" not in manifest:
        errors.append("QS tile should use ic_qs_tile")
    notif = read("app/src/main/java/io/nekohasekai/sfa/bg/ServiceNotification.kt")
    if 'setContentTitle("sing-box")' in notif or '?: "sing-box"' in notif:
        errors.append("service notification must not title itself sing-box")
    if "ic_qs_tile" not in notif:
        errors.append("service notification small icon should be ic_qs_tile")
    vpn = read("app/src/main/java/io/nekohasekai/sfa/bg/VPNService.kt")
    if '.setSession("sing-box")' in vpn:
        errors.append("VPN session name must match the app, not sing-box")
    importer = read(
        "app/src/main/java/io/nekohasekai/sfa/compose/screen/configuration/ProfileImportHandler.kt",
    )
    if "ConfigCompat.sanitize" not in importer:
        errors.append("JSON import must sanitize (legacy fakeip) before checkConfig")

    if errors:
        print("FAIL")
        for e in errors:
            print(" -", e)
        return 1
    print("override guards ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
