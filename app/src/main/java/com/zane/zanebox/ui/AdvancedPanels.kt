package com.zane.zanebox.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zane.zanebox.backup.WebDavEntry
import com.zane.zanebox.data.Group
import org.json.JSONObject

@Composable
internal fun WebdavDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val entries by vm.webdavEntries.collectAsStateWithLifecycle()
    var pendingRestore by remember { mutableStateOf<WebDavEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<WebDavEntry?>(null) }

    UiPageList(
        title = "WebDAV 备份",
        onDismiss = onDismiss,
        action = {
            IconButton(onClick = vm::listWebdav, modifier = Modifier.testTag("webdav_refresh")) {
                Icon(Icons.Outlined.Refresh, "刷新")
            }
        }
    ) {
        item {
            UiCard {
                UiRow(
                    "上传备份",
                    "将当前配置打包上传到 WebDAV",
                    Icons.Outlined.CloudUpload,
                    onClick = vm::uploadWebdav,
                    modifier = Modifier.testTag("webdav_upload")
                )
            }
        }
        if (entries.isEmpty()) {
            item { UiCard { Text(uiText("暂无云备份"), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
        } else {
            items(entries, key = { it.href }) { entry ->
                UiCard {
                    UiRow(
                        entry.name,
                        "${entry.size} 字节 · ${entry.modified}",
                        Icons.Outlined.Backup,
                        modifier = Modifier.testTag("webdav_entry_${entry.name.hashCode()}")
                    )
                    Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pendingRestore = entry }, modifier = Modifier.weight(1f)) { Text(uiText("恢复")) }
                        TextButton(onClick = { pendingDelete = entry }, modifier = Modifier.weight(1f)) { Text(uiText("删除云备份")) }
                    }
                }
            }
        }
    }

    pendingRestore?.let { entry ->
        UiAlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(uiText("恢复备份？")) },
            text = { Text("将用 ${entry.name} 替换当前配置。") },
            confirmButton = {
                TextButton(onClick = { vm.restoreWebdav(entry); pendingRestore = null }, modifier = Modifier.testTag("webdav_restore_confirm")) { Text(uiText("恢复")) }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text(uiText("取消")) } }
        )
    }
    pendingDelete?.let { entry ->
        UiAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(uiText("删除云备份？")) },
            text = { Text("将永久删除 ${entry.name}，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteWebdav(entry); pendingDelete = null }, modifier = Modifier.testTag("webdav_delete_confirm")) { Text(uiText("删除")) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(uiText("取消")) } }
        )
    }
}

@Composable
internal fun TrafficDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    LaunchedEffect(vm) { vm.service.refreshTraffic() }
    val traffic by vm.service.traffic.collectAsStateWithLifecycle()
    val root = runCatching { JSONObject(traffic) }.getOrDefault(JSONObject())
    var selectedTab by remember { mutableIntStateOf(0) }
    var resetPending by remember { mutableStateOf(false) }
    val tabs = listOf("apps" to "应用", "domains" to "域名", "nodes" to "节点")
    val selected = tabs[selectedTab]
    val array = root.optJSONArray(selected.first)

    UiPageList(
        title = "累计流量统计",
        onDismiss = onDismiss,
        action = {
            IconButton(onClick = vm.service::refreshTraffic, modifier = Modifier.testTag("traffic_refresh")) {
                Icon(Icons.Outlined.Refresh, "刷新")
            }
        }
    ) {
        item {
            UiCard {
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, (_, title) ->
                        Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) }, modifier = Modifier.testTag("traffic_tab_$index"))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { resetPending = true }, modifier = Modifier.weight(1f).testTag("traffic_reset")) { Text(uiText("清零")) }
                    TextButton(onClick = { vm.service.setTrafficEnabled(true) }, modifier = Modifier.weight(1f)) { Text(uiText("开启统计")) }
                    TextButton(onClick = { vm.service.setTrafficEnabled(false) }, modifier = Modifier.weight(1f)) { Text(uiText("停止统计")) }
                }
            }
        }
        if (array == null || array.length() == 0) {
            item { UiCard { Text("暂无${selected.second}流量数据", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) } }
        } else {
            items(array.length()) { index ->
                val item = array.getJSONObject(index)
                UiCard {
                    UiRow(
                        item.optString("name").ifBlank { "未知${selected.second}" },
                        "↑ ${bytes(item.optLong("tx"))} · ↓ ${bytes(item.optLong("rx"))}",
                        when (selectedTab) { 0 -> Icons.Outlined.Apps; 1 -> Icons.Outlined.Language; else -> Icons.Outlined.Dns }
                    )
                }
            }
        }
    }

    if (resetPending) {
        UiAlertDialog(
            onDismissRequest = { resetPending = false },
            title = { Text(uiText("清零累计统计？")) },
            text = { Text(uiText("应用、域名和节点的累计流量都会被清空。")) },
            confirmButton = {
                TextButton(onClick = { vm.service.resetTraffic(); resetPending = false }, modifier = Modifier.testTag("traffic_reset_confirm")) { Text(uiText("清零")) }
            },
            dismissButton = { TextButton(onClick = { resetPending = false }) { Text(uiText("取消")) } }
        )
    }
}

@Composable
internal fun ConnectionsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val connections by vm.service.connections.collectAsStateWithLifecycle()
    val array = runCatching { JSONObject(connections).optJSONArray("connections") }.getOrNull()

    UiPageList(
        title = "连接监控",
        onDismiss = onDismiss,
        action = {
            IconButton(onClick = vm.service::refreshConnections, modifier = Modifier.testTag("connections_refresh")) {
                Icon(Icons.Outlined.Refresh, "刷新")
            }
        }
    ) {
        item {
            UiCard {
                UiRow("关闭全部连接", "结束当前控制接口中的所有连接", Icons.Outlined.Close, onClick = vm.service::closeAllConnections, modifier = Modifier.testTag("connections_close_all"))
            }
        }
        if (array == null || array.length() == 0) {
            item { UiCard { Text(uiText("暂无活动连接"), Modifier.padding(16.dp)) } }
        } else {
            items(array.length()) { index ->
                val value = array.getJSONObject(index)
                val host = value.optJSONObject("metadata")?.let { metadata ->
                    metadata.optString("host").ifBlank { metadata.optString("destinationIP") }
                }.orEmpty().ifBlank { value.optString("id") }
                UiCard {
                    UiRow(host, "连接 ID：${value.optString("id")}", Icons.Outlined.SwapHoriz,
                        trailing = { TextButton(onClick = { vm.service.closeConnection(value.optString("id")) }) { Text(uiText("关闭")) } })
                }
            }
        }
    }
}

@Composable
internal fun QrDialog(text: String, vm: AppViewModel, onDismiss: () -> Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, text) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val matrix = com.google.zxing.MultiFormatWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 768, 768)
                android.graphics.Bitmap.createBitmap(768, 768, android.graphics.Bitmap.Config.ARGB_8888).apply {
                    val pixels = IntArray(768 * 768) { index -> if (matrix[index % 768, index / 768]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                    setPixels(pixels, 0, 768, 0, 0, 768, 768)
                }
            }.getOrNull()
        }
    }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null && bitmap != null) vm.saveQr(uri, bitmap!!)
    }
    UiAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(uiText("节点二维码")) },
        text = {
            Column(Modifier.fillMaxWidth().widthIn(max = 420.dp)) {
                bitmap?.let { Image(it.asImageBitmap(), "节点分享二维码", Modifier.fillMaxWidth().aspectRatio(1f)) }
                    ?: Text(uiText("内容过长，无法生成二维码；请使用节点分享。"))
                Text(text.take(200), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { bitmap?.let(vm::shareQr) }, enabled = bitmap != null) { Text(uiText("分享二维码")) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(uiText("关闭")) } },
        dismissButton = { TextButton(onClick = { save.launch("zanebox-qr.png") }, enabled = bitmap != null) { Text(uiText("保存图片")) } }
    )
}

@Composable
internal fun SpeedDialog(vm: AppViewModel, nodeId: Long, onDismiss: () -> Unit) {
    val result by vm.service.speedResult.collectAsStateWithLifecycle()
    val modes = listOf("simple" to "简单下载", "download" to "下载", "upload" to "上传", "full" to "下载 + 上传")
    UiPageList(
        title = "节点速度测试",
        onDismiss = onDismiss,
        action = { TextButton(onClick = vm.service::cancelSpeedTest, modifier = Modifier.testTag("speed_cancel")) { Text(uiText("取消测速")) } }
    ) {
        item {
            UiCard {
                UiSection("测试模式")
                modes.forEach { (mode, label) ->
                    UiRow(label, "测试会产生实际流量", Icons.Outlined.Speed,
                        trailing = { TextButton(onClick = { vm.service.speedTest(nodeId, mode) }, modifier = Modifier.testTag("speed_$mode")) { Text(uiText("开始")) } })
                }
            }
        }
        item { UiCard { Text(result.ifBlank { "选择测速模式；测试会产生实际流量。" }, Modifier.padding(16.dp).testTag("speed_result")) } }
    }
}

@Composable
internal fun SubscriptionOptionsDialog(group: Group, vm: AppViewModel, onDismiss: () -> Unit) {
    val original = remember(group.id, group.options) { runCatching { JSONObject(group.options) }.getOrDefault(JSONObject()) }
    var auto by remember(group.id) { mutableStateOf(original.optBoolean("autoUpdate", false)) }
    var minutes by remember(group.id) { mutableStateOf(original.optInt("autoUpdateDelay", 1440).toString()) }
    var connectedOnly by remember(group.id) { mutableStateOf(original.optBoolean("updateWhenConnectedOnly", false)) }
    var dedup by remember(group.id) { mutableStateOf(original.optBoolean("deduplication", false)) }
    var resolve by remember(group.id) { mutableStateOf(original.optBoolean("forceResolve", false)) }
    var agent by remember(group.id) { mutableStateOf(original.optString("customUserAgent")) }
    var mode by remember(group.id) { mutableIntStateOf(original.optInt("filterMode", 0)) }
    var regex by remember(group.id) { mutableStateOf(original.optString("filterRegex")) }
    var resolver by remember(group.id) { mutableStateOf(original.optString("serverDnsResolver")) }
    var error by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf<String?>(null) }
    val filterLabels = listOf("关闭", "保留匹配", "排除匹配")

    fun save() {
        runCatching {
            val interval = minutes.toInt()
            require(interval > 0) { "更新间隔须为正整数" }
            if (mode != 0 && regex.isNotBlank()) Regex(regex)
            require(!agent.contains('\r') && !agent.contains('\n')) { "User-Agent 不能包含换行" }
            val options = JSONObject(original.toString())
                .put("autoUpdate", auto).put("autoUpdateDelay", interval)
                .put("updateWhenConnectedOnly", connectedOnly).put("deduplication", dedup)
                .put("forceResolve", resolve).put("customUserAgent", agent)
                .put("filterMode", mode).put("filterRegex", regex).put("serverDnsResolver", resolver)
            com.zane.zanebox.subscription.SubscriptionOptions.parse(options.toString())
            vm.edit(onSaved=onDismiss,onError={error=it}) { data -> data.copy(groups = data.groups.map { if (it.id == group.id) it.copy(options = options.toString()) else it }) }
        }.onFailure { error = it.message ?: "选项无效" }
    }

    UiPageList(
        title = "订阅选项 · ${group.name}",
        onDismiss = onDismiss,
        action = { TextButton(onClick = ::save, modifier = Modifier.testTag("subscription_options_save")) { Text(uiText("保存")) } }
    ) {
        if (group.subscriptionUrl.isBlank()) item { UiCard { Text(uiText("此组暂无订阅 URL；在组编辑中设置 URL 后选项生效。"), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) } }
        item {
            UiCard {
                OptionSwitch("自动更新", auto, { auto = it }, "subscription_auto_update")
                UiRow("更新间隔", "$minutes 分钟", Icons.Outlined.Schedule, onClick = { choice = "interval" }, modifier = Modifier.testTag("subscription_interval"))
                OptionSwitch("仅连接时更新", connectedOnly, { connectedOnly = it }, "subscription_connected_only")
                OptionSwitch("按协议身份去重", dedup, { dedup = it }, "subscription_dedup")
                OptionSwitch("更新时强制解析服务器 IP", resolve, { resolve = it }, "subscription_resolve")
            }
        }
        item {
            UiCard {
                OutlinedTextField(agent, { agent = it }, label = { Text(uiText("自定义 User-Agent（空为默认）")) }, modifier = Modifier.fillMaxWidth().testTag("subscription_user_agent"))
                Spacer(Modifier.height(10.dp))
                UiRow("节点名称过滤", filterLabels[mode.coerceIn(filterLabels.indices)], Icons.Outlined.FilterList, onClick = { choice = "filter" }, modifier = Modifier.testTag("subscription_filter_$mode"))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    filterLabels.forEachIndexed { index, label ->
                        FilterChip(mode == index, { mode = index }, label = { Text(label) }, modifier = Modifier.testTag("subscription_filter_$index"))
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(regex, { regex = it }, label = { Text(uiText("正则表达式（find 匹配）")) }, modifier = Modifier.fillMaxWidth().testTag("subscription_regex"))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(resolver, { resolver = it }, label = { Text(uiText("服务器地址 DNS（空为默认）")) }, modifier = Modifier.fillMaxWidth().testTag("subscription_dns"))
            }
        }
        item { Text(uiText("保存选项后可手动更新。解析、正则或过滤失败会保留原节点；自动更新使用同一流水线。"), style = MaterialTheme.typography.bodySmall) }
        if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("subscription_options_error")) }
    }

    when (choice) {
        "interval" -> ChoiceDialog(
            "更新间隔",
            minutes,
            listOf("15" to "15 分钟", "30" to "30 分钟", "60" to "1 小时", "360" to "6 小时", "1440" to "24 小时"),
            onDismiss = { choice = null },
            onChoose = { minutes = it; choice = null }
        )
        "filter" -> ChoiceDialog(
            "节点名称过滤",
            mode.toString(),
            filterLabels.mapIndexed { index, label -> index.toString() to label },
            onDismiss = { choice = null },
            onChoose = { mode = it.toInt(); choice = null }
        )
    }
}

@Composable
private fun OptionSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, Modifier.weight(1f))
        UiSwitch(value, onChange, Modifier.testTag(tag))
    }
}
