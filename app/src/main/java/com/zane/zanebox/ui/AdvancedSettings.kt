package com.zane.zanebox.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zane.zanebox.data.AppData
import org.json.JSONObject

@Composable
internal fun AdvancedSettings(
    data: AppData,
    vm: AppViewModel,
    running: Boolean,
    form: (String, List<Pair<String, String>>, (List<String>) -> Unit) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("运行模式与高级设置", style = MaterialTheme.typography.titleMedium)
            Text("代理模式仅提供本地代理端口；VPN 模式接管设备流量。")
            SettingChoices("服务模式", "serviceMode", data, vm, "vpn", listOf("vpn" to "VPN", "proxy" to "本地代理"), enabled = !running)
            if (running) Text("切换服务模式前请先断开连接。", style = MaterialTheme.typography.bodySmall)
            listOf(
                Triple("fakeDns", "Fake DNS", data.bool("fakeDNS", true)),
                Triple("bypassLan", "TUN 绕过局域网", false),
                Triple("bypassLanInCore", "内核直连局域网", false),
                Triple("strictRoute", "严格路由", true),
                Triple("disableMixedInbound", "VPN 模式关闭本地 mixed 入口", false),
                Triple("globalAllowInsecure", "全局允许不安全 TLS", false),
                Triple("muxEnabled", "启用连接复用 Mux", false),
                Triple("muxPadding", "Mux 填充", false)
            ).forEach { (key, label, default) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(label, Modifier.weight(1f))
                    Switch(
                        checked = data.bool(key, default),
                        onCheckedChange = { vm.setting(key, it.toString()) },
                        modifier = Modifier.testTag("setting_$key")
                    )
                }
            }
            Text("全局 Mux 作用于 SS、VMess、VLESS、Trojan；节点已有的 multiplex 配置优先。", style = MaterialTheme.typography.bodySmall)
            SettingChoices("Mux 协议", "muxProtocol", data, vm, "h2mux", listOf("h2mux", "smux", "yamux").map { it to it })
            TextButton(onClick = {
                form("Mux 并发", listOf("最大流数（正整数）" to data.setting("muxMaxStreams", "8"))) { values ->
                    val value = values[0].toIntOrNull()
                    if (value == null || value !in 1..1024) vm.message.value = "最大流数须为 1–1024" else vm.setting("muxMaxStreams", value.toString())
                }
            }, modifier = Modifier.testTag("setting_muxMaxStreams")) { Text("Mux 最大流数：${data.setting("muxMaxStreams", "8")}") }
            val strategies = listOf("prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only").map { it to it }
            SettingChoices("DNS 默认策略", "dnsStrategy", data, vm, if (data.bool("ipv6")) "prefer_ipv4" else "ipv4_only", strategies + ("" to "自动"))
            SettingChoices("域名解析策略", "domainStrategy", data, vm, "", strategies + ("" to "自动"))
            listOf("dnsStrategyRemote" to "远程 DNS 策略", "dnsStrategyDirect" to "直连 DNS 策略", "dnsStrategyServer" to "服务器地址解析策略").forEach { (key, label) ->
                SettingChoices(label, key, data, vm, "", strategies + ("" to "自动"))
            }
            TextButton(onClick = {
                form("Hosts", listOf("Hosts 文本（IP 域名）或 JSON 映射" to data.setting("dnsHosts", data.setting("hosts", "")))) { values ->
                    runCatching { if (values[0].trim().startsWith("{")) JSONObject(values[0]) }.onSuccess { vm.setting("dnsHosts", values[0]) }.onFailure { vm.message.value = "Hosts JSON 格式无效" }
                }
            }, modifier = Modifier.testTag("setting_hosts")) { Text("编辑 Hosts 映射") }
        }
    }
}

@Composable
private fun SettingChoices(
    label: String,
    key: String,
    data: AppData,
    vm: AppViewModel,
    default: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean = true
) {
    Text(label)
    choices.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (value, title) ->
                FilterChip(
                    selected = data.setting(key, default) == value,
                    onClick = { vm.setting(key, value) },
                    enabled = enabled,
                    label = { Text(title) },
                    modifier = Modifier.weight(1f).testTag("setting_${key}_$value")
                )
            }
        }
    }
}
