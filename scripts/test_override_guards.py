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

    chain = read("app/src/main/java/io/nekohasekai/sfa/chain/ChainRuntimeCompiler.kt")
    if "不可作为前置代理" in chain:
        errors.append("Chain compiler must not fail closed just because a selector contains DIRECT")
    if "chainEntryTag" not in read("app/src/main/java/io/nekohasekai/sfa/database/Settings.kt"):
        errors.append("Settings.chainEntryTag missing")
    if "fun resolveMainTag" not in chain:
        errors.append("resolveMainTag should be reusable by the UI")
    if "isFinalLike" not in chain:
        errors.append("isFinalLike missing; 漏网之鱼 would be locked as entry again")

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

    if errors:
        print("FAIL")
        for e in errors:
            print(" -", e)
        return 1
    print("override guards ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
