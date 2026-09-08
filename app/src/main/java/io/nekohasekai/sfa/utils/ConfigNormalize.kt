package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime overwrite: keep the user's nodes/groups, replace DNS / route / inbounds
 * with a sing-box 1.12+ template that always starts without GitHub rule-sets.
 *
 * China split uses an inline domain_suffix list (no remote geoip/geosite).
 * Local DNS is UDP without detour so node hostnames and bootstrap never loop
 * through the proxy. WebRTC STUN ports are rejected.
 */
object ConfigNormalize {

    private val dropOutboundTypes = setOf("direct", "block", "dns", "chain")
    private val groupTypes = setOf("selector", "urltest")
    private val nodeTypes = setOf(
        "shadowsocks", "shadowsocks2022", "vmess", "vless", "trojan",
        "hysteria", "hysteria2", "tuic", "wireguard", "shadowtls", "anytls",
        "socks", "http", "naive", "ssh", "tor", "mieru",
    )
    private val legacyInboundFields = listOf(
        "sniff",
        "sniff_override_destination",
        "sniff_timeout",
        "domain_strategy",
        "inbound_sniffing",
    )

    val CN_DOMAIN_SUFFIXES: List<String> = listOf(
        "cn",
        "qq.com", "weixin.com", "wechat.com", "qpic.cn", "gtimg.cn", "idqqimg.com",
        "tencent.com", "tencent-cloud.net", "qcloud.com", "myqcloud.com",
        "baidu.com", "bdstatic.com", "bdimg.com",
        "alibaba.com", "alicdn.com", "aliyun.com", "alipay.com", "aliyuncs.com",
        "taobao.com", "tmall.com", "1688.com",
        "163.com", "126.com", "127.net", "netease.com",
        "jd.com", "360buyimg.com",
        "bilibili.com", "hdslb.com", "biliapi.net",
        "iqiyi.com", "iqiyipic.com",
        "youku.com", "ykimg.com",
        "douyin.com", "amemv.com", "toutiao.com", "bytedance.com", "pstatp.com", "snssdk.com",
        "weibo.com", "sina.com.cn", "sinaimg.cn",
        "zhihu.com", "zhimg.com",
        "meituan.com", "dianping.com", "sankuai.com",
        "pinduoduo.com", "yangkeduo.com",
        "xiaomi.com", "mi.com", "miui.com",
        "huawei.com", "honor.com", "hicloud.com", "vmall.com",
        "oppo.com", "heytap.com", "realme.com", "oneplus.com", "vivo.com",
        "ctrip.com", "qunar.com",
        "suning.com", "smzdm.com",
        "kugou.com", "kuwo.cn",
        "migu.cn", "10086.cn", "10010.com", "189.cn",
        "gov.cn", "edu.cn", "ac.cn", "org.cn", "com.cn", "net.cn",
        "douban.com", "csdn.net", "gitee.com",
        "ele.me", "dingtalk.com", "feishu.cn",
        "wps.cn", "unionpay.com",
        "alidns.com", "dnspod.cn", "360.cn",
        "sogou.com", "so.com", "uc.cn",
        "cctv.com", "people.com.cn", "xinhuanet.com",
        "coolapk.com", "thepaper.cn",
    )

    fun cnDomainSuffixArray(): JSONArray {
        val a = JSONArray()
        CN_DOMAIN_SUFFIXES.forEach { a.put(it) }
        return a
    }

    fun apply(content: String): String {
        val src = JSONObject(ConfigCompat.sanitize(content))
        val keptOuts = JSONArray()
        val nodeTags = mutableListOf<String>()
        val groupTags = mutableListOf<String>()
        val srcOuts = src.optJSONArray("outbounds") ?: JSONArray()
        for (i in 0 until srcOuts.length()) {
            val o = srcOuts.optJSONObject(i) ?: continue
            ConfigCompat.sanitizeOutbound(o)
            val type = o.optString("type").trim()
            val tag = o.optString("tag").trim()
            if (tag.isEmpty()) continue
            when {
                type in dropOutboundTypes -> {}
                type in groupTypes -> {
                    keptOuts.put(JSONObject(o.toString()))
                    groupTags.add(tag)
                }
                type in nodeTypes || isLikelyNode(o) -> {
                    keptOuts.put(JSONObject(o.toString()))
                    nodeTags.add(tag)
                }
            }
        }
        if (nodeTags.isEmpty() && groupTags.isEmpty()) {
            error("规范化失败：配置里没有可保留的节点")
        }
        val proxyTag = pickProxyTag(src, groupTags, nodeTags)
        if (groupTags.isEmpty()) {
            val members = JSONArray()
            nodeTags.forEach { members.put(it) }
            keptOuts.put(JSONObject().put("type", "selector").put("tag", proxyTag).put("outbounds", members))
        }
        if (findTag(keptOuts, "direct") == null) {
            keptOuts.put(JSONObject().put("type", "direct").put("tag", "direct"))
        }

        val serverHosts = collectServerHosts(keptOuts)
        val out = JSONObject()
        val logLevel = src.optJSONObject("log")?.optString("level").orEmpty().ifBlank { "info" }
        out.put("log", JSONObject().put("level", logLevel).put("timestamp", true))
        out.put("dns", buildDns(proxyTag, serverHosts))
        out.put("inbounds", buildInbounds())
        out.put("outbounds", keptOuts)
        if (src.has("endpoints")) out.put("endpoints", src.get("endpoints"))
        out.put("route", buildRoute(proxyTag))
        if (src.has("experimental")) out.put("experimental", src.get("experimental"))
        if (src.has("clash_api")) out.put("clash_api", src.get("clash_api"))
        return out.toString()
    }

    private fun isLikelyNode(o: JSONObject): Boolean =
        o.optString("server").isNotBlank() || o.optInt("server_port") > 0

    private fun pickProxyTag(src: JSONObject, groups: List<String>, nodes: List<String>): String {
        val fin = src.optJSONObject("route")?.optString("final").orEmpty().trim()
        if (fin.isNotEmpty() && (fin in groups || fin in nodes) && !isFinalLike(fin)) return fin
        fun score(tag: String): Int {
            val t = tag.lowercase()
            var s = 0
            if (t.contains("proxy") || t.contains("select") || t.contains("节点") || t.contains("选择") || t.contains("自动")) s += 20
            if (isFinalLike(tag)) s -= 50
            return s
        }
        return groups.maxByOrNull { score(it) } ?: "proxy"
    }

    private fun isFinalLike(tag: String): Boolean {
        val t = tag.lowercase()
        return t.contains("漏网") || t.contains("final") || t.contains("剩余") || t.contains("unmatched")
    }

    internal fun collectServerHosts(outs: JSONArray): List<String> {
        val hosts = LinkedHashSet<String>()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            addHost(hosts, o.optString("server"))
            addHost(hosts, o.optString("server_name"))
            o.optJSONObject("tls")?.let { addHost(hosts, it.optString("server_name")) }
            o.optJSONObject("transport")?.let { addHost(hosts, it.optString("host")) }
        }
        return hosts.toList()
    }

    private fun addHost(hosts: MutableSet<String>, raw: String) {
        val h = raw.trim().lowercase()
        if (h.isEmpty()) return
        if (h[0].isDigit() && h.all { it.isDigit() || it == '.' }) return
        if (':' in h) return
        hosts.add(h)
    }

    private fun buildDns(proxyTag: String, serverHosts: List<String>): JSONObject {
        val local = JSONObject()
            .put("type", "udp")
            .put("tag", "dns-local")
            .put("server", "223.5.5.5")
        val remote = JSONObject()
            .put("type", "https")
            .put("tag", "dns-remote")
            .put("server", "1.1.1.1")
            .put("path", "/dns-query")
        if (proxyTag.isNotBlank() && !proxyTag.equals("direct", ignoreCase = true)) {
            remote.put("detour", proxyTag)
        }
        val servers = JSONArray().put(remote).put(local)
        val rules = JSONArray()
        if (serverHosts.isNotEmpty()) {
            val domains = JSONArray()
            serverHosts.forEach { domains.put(it) }
            rules.put(JSONObject().put("domain", domains).put("server", "dns-local"))
        }
        rules.put(JSONObject().put("domain_suffix", cnDomainSuffixArray()).put("server", "dns-local"))
        return JSONObject()
            .put("servers", servers)
            .put("rules", rules)
            .put("final", "dns-remote")
            .put("strategy", "ipv4_only")
            .put("independent_cache", true)
    }

    /**
     * Always emit a 1.13-safe TUN + local mixed inbound. Do not copy the
     * subscription's inbounds: those commonly still carry removed sniff fields.
     * Sniffing is done via route action instead (see [buildRoute]).
     */
    fun buildInbounds(): JSONArray {
        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("address", JSONArray().put("172.19.0.1/30"))
            .put("mtu", 9000)
            .put("auto_route", true)
            .put("strict_route", false)
        val mixed = JSONObject()
            .put("type", "mixed")
            .put("tag", "mixed-in")
            .put("listen", "127.0.0.1")
            .put("listen_port", 2080)
        stripLegacyInboundFields(tun)
        stripLegacyInboundFields(mixed)
        return JSONArray().put(tun).put(mixed)
    }

    fun stripLegacyInboundFields(inbound: JSONObject) {
        for (field in legacyInboundFields) inbound.remove(field)
    }

    fun webrtcRejectRules(): JSONArray {
        val rules = JSONArray()
        for (p in intArrayOf(3478, 19302, 5349)) {
            rules.put(JSONObject().put("network", "udp").put("port", p).put("action", "reject"))
        }
        return rules
    }

    private fun buildRoute(proxyTag: String): JSONObject {
        val rules = JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        val webrtc = webrtcRejectRules()
        for (i in 0 until webrtc.length()) rules.put(webrtc.get(i))
        rules.put(JSONObject().put("domain_suffix", cnDomainSuffixArray()).put("outbound", "direct"))
        return JSONObject()
            .put("rules", rules)
            .put("final", proxyTag)
            .put("auto_detect_interface", true)
    }

    private fun findTag(outs: JSONArray, tag: String): JSONObject? {
        for (i in 0 until outs.length()) if (outs.optJSONObject(i)?.optString("tag") == tag) return outs.optJSONObject(i)
        return null
    }
}
