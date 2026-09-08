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
    for leak in ("Kitty", "MYCF", "edgetunne"):
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
    if "echDns" not in settings:
        errors.append("Settings.echDns missing")
    if "fun closeDatabase" not in settings:
        errors.append("Settings.closeDatabase missing; restore would hit open WAL")
    if "restoreCompat" not in settings:
        errors.append("Settings.restoreCompat missing")

    china = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigChinaDirect.kt")
    for needle in ("ip_is_private", "CHINA_DNS_IPS", "CHINA_DNS_DOMAINS", "LAN_DOMAIN_SUFFIXES", "cnDomainSuffixArray"):
        if needle not in china:
            errors.append(f"China direct overlay missing {needle}")
    if "unblockHttpsQueries" not in china:
        errors.append("ECH DNS unblock helper missing")
    if "applyEchDns" not in china or "ECH_DNS_TAG" not in china:
        errors.append("ECH overlay must inject a dedicated HTTPS DNS server")

    override = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigQuicOverride.kt")
    if "Settings.chinaDirect" not in override:
        errors.append("ConfigQuicOverride must apply china direct")
    if "Settings.echDns" not in override:
        errors.append("ConfigQuicOverride must honor ECH DNS overlay")
    if "applyEchDns" not in override:
        errors.append("runtime overlay must call applyEchDns, not only unblock rejects")
    if "entryMissing" not in override:
        errors.append("subscription update should fall back when entry tag is gone")

    ui_override = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/ProfileOverrideScreen.kt")
    if "中国直连" not in ui_override:
        errors.append("Profile override UI must expose 中国直连")
    if "ECH" not in ui_override:
        errors.append("Profile override UI must expose ECH")

    compiler = read("app/src/main/java/io/nekohasekai/sfa/chain/ChainRuntimeCompiler.kt")
    if "保存的入口" in compiler and "已不存在" in compiler:
        errors.append("compiler must not fail closed when a saved entry tag disappeared after subscription update")
    if "MAX_CONFIG_CHARS" not in compiler:
        errors.append("compiler must cap JSON size before JSONObject(content)")
    if "pinTrafficToChain" not in compiler:
        errors.append("compiler must rewrite proxy routes so the entry cannot become the public exit")

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

    if errors:
        print("FAIL")
        for e in errors:
            print(" -", e)
        return 1
    print("override guards ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
