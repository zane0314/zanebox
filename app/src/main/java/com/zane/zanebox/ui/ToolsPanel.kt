package com.zane.zanebox.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

@Composable
internal fun ToolsPanel(vm:AppViewModel,onDismiss:()->Unit) {
    val raw by vm.service.stunResult.collectAsStateWithLifecycle()
    val result=remember(raw) {
        runCatching { JSONObject(raw.ifBlank { "{}" }) }
            .getOrElse { JSONObject().put("error","后台结果格式无效，请重新测试") }
    }
    val running=result.optBoolean("running",false)
    var server by remember { mutableStateOf("stun.voipgate.com:3478") }
    var inputError by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("网络工具 · STUN / NAT")},
        text={
            LazyColumn(Modifier.heightIn(max=480.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedTextField(
                        value=server,onValueChange={server=it;inputError=""},
                        label={Text("STUN 服务器（主机:端口）")},
                        enabled=!running,isError=inputError.isNotBlank(),
                        supportingText={if(inputError.isNotBlank())Text(inputError)},
                        modifier=Modifier.fillMaxWidth().testTag("stun_server")
                    )
                }
                item {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick={
                                val value=server.trim()
                                if(value.isBlank() || value.length>512 || value.any { it.isWhitespace() }) inputError="请输入有效的服务器地址"
                                else vm.service.stun(value)
                            },enabled=!running,modifier=Modifier.testTag("stun_start")
                        ){Text("开始测试")}
                        OutlinedButton(onClick={vm.service.cancelStun()},enabled=running,modifier=Modifier.testTag("stun_cancel")){Text("取消测试")}
                    }
                }
                if(running) item {
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text("正在执行 STUN / NAT 检测；可能需要数分钟。",Modifier.testTag("stun_running"))
                    }
                }
                if(!running && result.has("success")) item {Text(if(result.optBoolean("success")) "测试成功" else "测试未成功",Modifier.testTag("stun_status"))}
                if(result.optString("text").isNotBlank()) item {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(result.optString("text"),Modifier.testTag("stun_result"))
                    }
                }
                if(result.optString("error").isNotBlank()) item {Text(result.optString("error"),Modifier.testTag("stun_error"),color=MaterialTheme.colorScheme.error)}
                if(!running && result.optString("text").isBlank() && result.optString("error").isBlank()) item {Text("使用 STUN 检测当前网络的 NAT 映射与过滤行为。测试结果反映当前链路，不代表节点延迟。")}
            }
        },
        confirmButton={TextButton(onClick=onDismiss){Text("关闭")}}
    )
}
