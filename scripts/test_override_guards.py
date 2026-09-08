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
    if "configNormalize" in settings:
        errors.append("Settings.configNormalize must stay removed")
    if "chinaDirect" not in settings:
        errors.append("Settings.chinaDirect missing")
    if "echDns" in settings or "ECH_DNS" in settings:
        errors.append("ECH overlay was removed; Settings.echDns must not return")
    if "fun closeDatabase" not in settings:
        errors.append("Settings.closeDatabase missing; restore would hit open WAL")
    if "restoreCompat" not in settings:
        errors.append("Settings.restoreCompat missing")

    china = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigChinaDirect.kt")
    for needle in ("ip_is_private", "CHINA_DNS_IPS", "CHINA_DNS_DOMAINS", "LAN_DOMAIN_SUFFIXES", "cnDomainSuffixArray"):
        if needle not in china:
            errors.append(f"China direct overlay missing {needle}")
    if "applyEchDns" in china or "ECH_DNS_TAG" in china or "unblockHttpsQueries" in china:
        errors.append("ECH DNS overlay must stay removed from ConfigChinaDirect")

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
    if "#22C55E" not in icon_fg and "#16A34A" not in icon_fg and "#4ADE80" not in icon_fg:
        errors.append("launcher foreground must use a green bow")
    if "#DC2626" in icon_fg or "#991B1B" in icon_fg:
        errors.append("launcher foreground must not keep the red ribbon")

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

    if errors:
        print("FAIL")
        for e in errors:
            print(" -", e)
        return 1
    print("override guards ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
