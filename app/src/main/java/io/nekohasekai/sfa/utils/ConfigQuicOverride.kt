package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.Settings
import org.json.JSONArray
import org.json.JSONObject

class ChainApplyException(message: String) : IllegalStateException(message)

object ConfigQuicOverride {

    suspend fun apply(content: String): String {
        OverrideStatus.clear()
        val warnings = mutableListOf<OverrideNotice>()
        var out = content

        if (Settings.configNormalize) {
            try {
                out = ConfigNormalize.apply(out)
            } catch (e: Exception) {
                warnings += OverrideNotice(
                    title = "配置规范化未生效",
                    reason = e.message ?: "JSON 无法解析",
                    hint = "请打开「设置 → 配置覆盖」关闭后重试，或检查订阅是否为合法 sing-box JSON。",
                )
            }
        }

        val chainNeeded = Settings.chainEnabled &&
            (Settings.chainBoundProfileId < 0L || Settings.chainBoundProfileId == Settings.selectedProfile)
        if (chainNeeded) {
            try {
                out = ConfigChainReapply.apply(out)
            } catch (e: Exception) {
                val notice = OverrideNotice(
                    title = "链式代理未生效，已停止启动",
                    reason = e.message ?: "无法串联出站",
                    hint = "请到「工具 → 链式代理」重新选择出口并保存，或取消链式后再启动。不会自动改走 DIRECT。",
                )
                OverrideStatus.set(warnings + notice)
                throw ChainApplyException(notice.reason)
            }
        }

        if (Settings.disableQuic || Settings.strictRoute || Settings.dnsProtect || Settings.disableIpv6) {
            try {
                val root = JSONObject(out)
                if (Settings.disableQuic) applyQuic(root)
                if (Settings.strictRoute) applyStrictRoute(root)
                if (Settings.dnsProtect) applyDnsProtect(root)
                if (Settings.disableIpv6) applyDisableIpv6(root)
                out = root.toString()
            } catch (e: Exception) {
                warnings += OverrideNotice(
                    title = "网络增强开关部分未生效",
                    reason = e.message ?: "覆盖失败",
                    hint = "请检查配置是否含 TUN/路由段，或临时关闭对应开关。",
                )
            }
        }

        OverrideStatus.set(warnings)
        return out
    }

    private fun applyQuic(root: JSONObject) {
        ensureBlockOutbound(root)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val oldRules = route.optJSONArray("rules") ?: JSONArray()
        val injected = JSONArray()
        if (Settings.excludeCnQuic) {
            val cnTag = ensureGeoipCnRuleSet(route)
            if (cnTag != null) {
                injected.put(
                    JSONObject()
                        .put("network", "udp")
                        .put("port", 443)
                        .put("rule_set", JSONArray().put(cnTag))
                        .put("outbound", "direct"),
                )
            }
        }
        injected.put(
            JSONObject().put("network", "udp").put("port", 443).put("outbound", "block"),
        )
        for (p in intArrayOf(3478, 19302, 5349)) {
            injected.put(JSONObject().put("network", "udp").put("port", p).put("outbound", "block"))
        }
        val merged = JSONArray()
        for (i in 0 until injected.length()) merged.put(injected.get(i))
        for (i in 0 until oldRules.length()) merged.put(oldRules.get(i))
        route.put("rules", merged)
    }

    private fun ensureGeoipCnRuleSet(route: JSONObject): String? {
        val sets = route.optJSONArray("rule_set") ?: JSONArray().also { route.put("rule_set", it) }
        for (i in 0 until sets.length()) {
            val s = sets.optJSONObject(i) ?: continue
            val tag = s.optString("tag")
            val t = tag.lowercase()
            if (t.contains("geoip-cn") || t.contains("geoip_cn") || t == "cn" || t.endsWith("-cn")) {
                return tag
            }
        }
        val tag = "geoip-cn"
        sets.put(
            JSONObject()
                .put("type", "remote")
                .put("tag", tag)
                .put("format", "binary")
                .put("url", "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs")
                .put("download_detour", "direct")
                .put("update_interval", "7d"),
        )
        return tag
    }

    private fun applyStrictRoute(root: JSONObject) {
        val inbounds = root.optJSONArray("inbounds") ?: return
        var touched = false
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.optJSONObject(i) ?: continue
            if (ib.optString("type") != "tun") continue
            ib.put("strict_route", true)
            touched = true
        }
        if (!touched) {
            throw IllegalStateException("当前配置没有 TUN 入站，严格路由无法生效")
        }
    }

    private fun applyDnsProtect(root: JSONObject) {
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        if (!dns.has("independent_cache")) dns.put("independent_cache", true)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        if (!route.has("auto_detect_interface")) route.put("auto_detect_interface", true)
    }

    private fun applyDisableIpv6(root: JSONObject) {
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        dns.put("strategy", "ipv4_only")
        ensureBlockOutbound(root)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val old = route.optJSONArray("rules") ?: JSONArray()
        val merged = JSONArray().put(JSONObject().put("ip_version", 6).put("outbound", "block"))
        for (i in 0 until old.length()) merged.put(old.get(i))
        route.put("rules", merged)
    }

    private fun ensureBlockOutbound(root: JSONObject) {
        val outs = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("type") == "block" || o.optString("tag") == "block") return
        }
        outs.put(JSONObject().put("type", "block").put("tag", "block"))
    }
}
