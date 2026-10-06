package com.zane.zanebox.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zane.zanebox.data.AppData

@Composable internal fun ImportDialog(pending:PendingImport,data:AppData,busy:Boolean,onConfirm:(String,Long)->Unit,onDismiss:()->Unit) {
    var name by remember(pending) { mutableStateOf((pending as? PendingImport.Subscription)?.name ?: "导入节点") }
    var groupId by remember(pending) { mutableLongStateOf((pending as? PendingImport.Nodes)?.preferredGroupId ?: -1) }
    val (title,body,action)=when(pending) {
        is PendingImport.Subscription->Triple("添加外部订阅？","地址：${pending.url.take(200)}\n确认后会新建分组并立即下载该订阅。","添加")
        is PendingImport.Nodes->Triple("导入外部节点？","将导入 ${pending.report.nodes.size} 个节点"+(if(pending.report.skipped>0)"，跳过 ${pending.report.skipped} 条无法识别内容" else "")+"：\n"+pending.report.nodes.take(5).joinToString("\n"){"· "+it.name.take(60)},"导入")
        is PendingImport.Backup->Triple("用外部备份替换全部数据？","备份含 ${pending.data.groups.size} 个组、${pending.data.nodes.size} 个节点、${pending.data.rules.size} 条规则。\n恢复会覆盖当前节点、订阅和设置。","替换")
    }
    val missing=pending is PendingImport.Nodes && groupId>=0 && data.groups.none { it.id==groupId }
    val needsName=pending is PendingImport.Subscription || (pending is PendingImport.Nodes && groupId<0)
    UiAlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text(title)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(body)
            if(pending is PendingImport.Nodes) {
                (listOf(-1L to "新建分组")+data.groups.sortedBy { it.order }.map { it.id to it.name }).forEach { (id,label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(if(id<0)"import_new_group" else "import_group_$id")
                        .selectable(selected=groupId==id,enabled=!busy,role=Role.RadioButton){groupId=id},verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected=groupId==id,onClick=null,enabled=!busy)
                        Text(label)
                    }
                }
            }
            if(needsName)OutlinedTextField(name,{name=it},enabled=!busy,singleLine=true,label={Text("分组名称")},isError=name.isBlank(),modifier=Modifier.fillMaxWidth().testTag("import_group_name"))
            if(missing)Text("所选分组已删除，请重新选择",color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(onClick={onConfirm(name,groupId)},enabled=!busy && !missing && (!needsName || name.isNotBlank()),modifier=Modifier.testTag("import_confirm")){Text(if(busy)"正在导入…" else action)}},
        dismissButton={TextButton(onClick=onDismiss,enabled=!busy,modifier=Modifier.testTag("import_cancel")){Text(uiText("取消"))}})
}
