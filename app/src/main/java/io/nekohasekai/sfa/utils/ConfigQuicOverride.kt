package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.chain.ChainBindings
import io.nekohasekai.sfa.database.Settings
import org.json.JSONArray
import org.json.JSONObject

class ChainApplyException(message: String) : IllegalStateException(message)

object ConfigQuicOverride {

    suspend fun apply(content: String): String {
        OverrideStatus.clear()
        val warnings = mutableListOf<OverrideNotice>()
        var out = ConfigCompat.sanitize(content)

        val binding = ChainBindings.get(Settings.selectedProfile)
        if (binding != null) {
            val savedEntry = binding.entryTag.trim()
            val entryMissing = savedEntry.isNotEmpty() && !outboundExists(out, savedEntry)
            try {
                out = ConfigChainReapply.apply(out)
                if (entryMissing) {
                    warnings += OverrideNotice(
                        title = "链式入口已随订阅更新",
                        reason = "保存的入口「$savedEntry」在新订阅里不存在，已自动改用当前配置的主分组。落地绑定仍有效。",
                        hint = "不必重新配链式。若入口不对，到「工具 → 链式代理」重选一次即可。",
                    )
                }
            } catch (e: Exception) {
                val notice = OverrideNotice(
                    title = "链式代理未生效，已停止启动",
                    reason = e.message ?: "无法串联出站",
                    hint = "链路只绑定当前配置，订阅更新不会清掉绑定。请到「工具 → 链式代理」确认入口和落地。失败不会自动改走 DIRECT。",
                )
                OverrideStatus.set(warnings + notice)
                throw ChainApplyException(notice.reason)
            }
        }

        val extras = Settings.disableQuic || Settings.strictRoute || Settings.dnsProtect ||
            Settings.disableIpv6 || Settings.webrtcProtect || Settings.chinaDirect
        if (extras || Settings.echDns) {
            try {
                val root = JSONObject(out)
                var changed = extras
                if (Settings.echDns) {
                    val dns = root.optJSONObject("dns")
                    if (dns != null && ConfigChinaDirect.unblockHttpsQueries(dns) > 0) changed = true
                }
                if (Settings.webrtcProtect) applyWebrtc(root)
                if (Settings.chinaDirect) ConfigChinaDirect.apply(root)
                if (Settings.disableQuic) applyQuic(root)
                if (Settings.strictRoute) applyStrictRoute(root)
                if (Settings.dnsProtect) applyDnsProtect(root)
                if (Settings.disableIpv6) applyDisableIpv6(root)
                if (changed) out = root.toString()
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

    private fun applyWebrtc(root: JSONObject) {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val old = route.optJSONArray("rules") ?: JSONArray()
        val merged = JSONArray()
        val extra = ConfigNormalize.webrtcRejectRules()
        for (i in 0 until extra.length()) merged.put(extra.get(i))
        for (i in 0 until old.length()) merged.put(old.get(i))
        route.put("rules", merged)
    }

    private fun applyQuic(root: JSONObject) {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val oldRules = route.optJSONArray("rules") ?: JSONArray()
        val injected = JSONArray()
        if (Settings.excludeCnQuic) {
            injected.put(
                JSONObject()
                    .put("network", "udp")
                    .put("port", 443)
                    .put("domain_suffix", ConfigNormalize.cnDomainSuffixArray())
                    .put("outbound", "direct"),
            )
        }
        injected.put(
            JSONObject().put("network", "udp").put("port", 443).put("action", "reject"),
        )
        val merged = JSONArray()
        for (i in 0 until injected.length()) merged.put(injected.get(i))
        for (i in 0 until oldRules.length()) merged.put(oldRules.get(i))
        route.put("rules", merged)
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
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val old = route.optJSONArray("rules") ?: JSONArray()
        val merged = JSONArray().put(JSONObject().put("ip_version", 6).put("action", "reject"))
        for (i in 0 until old.length()) merged.put(old.get(i))
        route.put("rules", merged)
    }

    private fun outboundExists(content: String, tag: String): Boolean {
        return try {
            val outs = JSONObject(content).optJSONArray("outbounds") ?: return false
            for (i in 0 until outs.length()) {
                if (outs.optJSONObject(i)?.optString("tag") == tag) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}
