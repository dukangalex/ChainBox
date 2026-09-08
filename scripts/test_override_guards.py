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
    if 'put("sniff"' in normalize or "put(\"sniff\"" in normalize:
        errors.append("ConfigNormalize must not write inbound sniff fields")
    if 'put("action", "sniff")' not in normalize:
        errors.append("ConfigNormalize must sniff via route action")
    if "legacyInboundFields" not in normalize:
        errors.append("ConfigNormalize should list legacy inbound fields to strip")

    if 'dnsServer("dns-local", "223.5.5.5", "direct")' in normalize:
        errors.append("dns-local must not detour to empty direct (sing-box 1.12+ rejects it)")
    if 'put("download_detour", "direct")' in normalize:
        errors.append("rule_set download_detour=direct is rejected by sing-box 1.12+")
    if "raw.githubusercontent.com" in normalize:
        errors.append("ConfigNormalize must not fetch GitHub rule-sets at startup")
    if 'put("type", "udp")' not in normalize:
        errors.append("dns-local should be UDP bootstrap without DoH/detour")
    if "webrtcRejectRules" not in normalize:
        errors.append("overwrite template should reject STUN ports")

    compat = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigCompat.kt")
    if "plugin_opts" not in compat or "objectToPluginOpts" not in compat:
        errors.append("ConfigCompat must coerce Clash plugin_opts objects to strings")

    chain = read("app/src/main/java/io/nekohasekai/sfa/chain/ChainRuntimeCompiler.kt")
    if "fail_closed" in chain:
        errors.append("Chain compiler must not emit fail_closed; kernel ChainOutboundOptions only has outbounds")
    if "不可作为前置代理" in chain:
        errors.append("Chain compiler must not fail closed just because a selector contains DIRECT")
    if "chainEntryTag" not in read("app/src/main/java/io/nekohasekai/sfa/database/Settings.kt"):
        errors.append("Settings.chainEntryTag missing")
    if "fun resolveMainTag" not in chain:
        errors.append("resolveMainTag should be reusable by the UI")
    if "isFinalLike" not in chain:
        errors.append("isFinalLike missing; 漏网之鱼 would be locked as entry again")
    if "landing/exit" not in chain and "public IP" not in chain:
        errors.append("chain compiler should document packet path: entry first, landing last")

    ui = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/ChainBuilderScreen.kt")
    if 'picker == "entry"' not in ui:
        errors.append("Chain builder must let the user pick the entry hop")

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

    if errors:
        print("FAIL")
        for e in errors:
            print(" -", e)
        return 1
    print("override guards ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
