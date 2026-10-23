package com.zane.zanebox.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zane.zanebox.config.*
import com.zane.zanebox.data.*
import org.json.JSONObject

@Composable internal fun chainTargetLabel(value:String,data:AppData):String=when {
    value=="inherit"->uiText("继承分组")
    value=="none" || value=="0"->uiText("无")
    value.startsWith("group:")->uiText("%s · 自动最低延迟",data.groups.firstOrNull{it.id==value.substringAfter(':').toLongOrNull()}?.name ?: uiText("引用已失效"))
    else->data.nodes.firstOrNull{it.id==value.substringAfter(':').toLongOrNull()}?.name ?: uiText("引用已失效")
}

@Composable internal fun chainPathText(data:AppData,nodeId:Long,selections:String="{}"):String {
    if(data.nodes.none{it.id==nodeId})return uiText("未选择节点")
    val node=data.nodes.first{it.id==nodeId}
    chainUnavailableReason(data,node)?.let{return uiText("链路已失效：%s",uiText(it))}
    val targets=effectiveChainTargets(data,node)
    val selected=runCatching{JSONObject(selections)}.getOrDefault(JSONObject())
    val labels=chainPathLabels(data,nodeId).mapIndexed{index,label->
        if(label.endsWith(" · 自动")) {
            val front=index==0 && targets.front.startsWith("group:")
            val target=if(front)targets.front else targets.landing
            val key=if(front)"chain-$nodeId-front" else "node-$nodeId"
            val prefix=if(front)"chain-$nodeId-front-" else "chain-$nodeId-landing-"
            val tag=selected.optString(key)
            val memberId=when { tag.startsWith(prefix)->tag.removePrefix(prefix).toLongOrNull();front && tag.startsWith("node:")->tag.removePrefix("node:").toLongOrNull();front && tag.startsWith("node-")->tag.removePrefix("node-").toLongOrNull();else->null }
            val member=data.nodes.firstOrNull{it.id==memberId && it.groupId==target.substringAfter(':').toLongOrNull()}
            uiText("%s · 自动最低延迟",label.removeSuffix(" · 自动"))+member?.let{" · "+uiText("当前已选：%s",it.name)}.orEmpty()
        } else if(label in listOf("节点已失效","分组已失效"))uiText("引用已失效") else label
    }
    return (listOf(uiText("本机"))+labels+uiText("目标网站")).joinToString(" → ")
}

@Composable internal fun GroupedChainPicker(title:String,data:AppData,onDismiss:()->Unit,onChoose:(String)->Unit,value:String="",allowGroup:Boolean=true,groupLabel:String="整个分组",extras:List<Pair<String,String>> = emptyList(),allowedNodes:Set<Long>?=null,allowedGroups:Set<Long>?=null) {
    var open by remember{mutableStateOf<Long?>(null)}
    val nodes=data.nodes.filter{allowedNodes==null || it.id in allowedNodes}
    val groups=data.groups.filter{(allowedGroups==null || it.id in allowedGroups) && (allowGroup || nodes.any{n->n.groupId==it.id})}
    val group=groups.firstOrNull{it.id==open}
    if(group==null)ChoiceDialog(title,value,extras+groups.map{"group:${it.id}" to it.name},onDismiss,dismissOnChoose=false,translateChoices=false){target->
        if(target.startsWith("group:"))open=target.substringAfter(':').toLong()else{onChoose(target);onDismiss()}
    } else ChoiceDialog(group.name,value,(if(allowGroup)listOf("group:${group.id}" to uiText(groupLabel))else emptyList())+nodes.filter{it.groupId==group.id}.map{"node:${it.id}" to it.name},{open=null},dismissOnChoose=false,translateTitle=false,translateChoices=false){onChoose(it);onDismiss()}
}

@Composable internal fun ChainTargetPicker(title:String,value:String,data:AppData,onDismiss:()->Unit,onChoose:(String)->Unit,inherit:Boolean=false,excludeNodeId:Long?=null) {
    val extras=buildList{if(inherit)add("inherit" to uiText("继承分组"));add("none" to uiText("无"))}
    GroupedChainPicker(title,data,onDismiss,onChoose,value,groupLabel="整个分组 · 自动最低延迟",extras=extras,allowedNodes=data.nodes.filter{it.id!=excludeNodeId && JSONObject(it.outbound).optString("type")!="custom"}.map{it.id}.toSet(),allowedGroups=data.groups.filter{it.enabled}.map{it.id}.toSet())
}

@Composable internal fun ChainTargetRows(targets:ChainTargets,data:AppData,onEdit:(String)->Unit,prefix:String="chain") {
    UiCard {
        UiRow("前置代理",chainTargetLabel(targets.front,data),onClick={onEdit("front")},translateSubtitle=false,modifier=Modifier.testTag("${prefix}_front_proxy"))
        UiRow("后置代理（落地）",chainTargetLabel(targets.landing,data),onClick={onEdit("landing")},translateSubtitle=false,modifier=Modifier.testTag("${prefix}_landing_proxy"))
    }
}

internal fun isChainNode(node:Node)=runCatching{JSONObject(node.outbound).optString("type")=="chain"}.getOrDefault(false)
internal fun isChainGroup(group:Group)=runCatching{JSONObject(group.options).optLong("chainSourceGroupId")>0}.getOrDefault(false)

@Composable internal fun ChainSettingsPage(data:AppData,onDismiss:()->Unit,onGroup:(Group)->Unit,onNode:(Node)->Unit,onCreate:()->Unit) {
    var create by remember{mutableStateOf("")}
    UiPageList("链式代理",onDismiss,action={TextButton(onClick={create="type"},modifier=Modifier.testTag("chain_create")){Text(uiText("创建链式代理"))}}) {
        item{Text(uiText("链式代理保存为独立节点或分组，原节点和原订阅保持不变。"),style=MaterialTheme.typography.bodySmall)}
        item{UiSection("链式分组")}
        items(data.groups.filter(::isChainGroup),key={"chain_group_${it.id}"}){g->
            val targets=groupChainTargets(g)
            val unavailable=derivedGroupUnavailableReason(data,g)?.let{uiText(it)}
            val summary=unavailable?.let{uiText("链路已失效：%s",it)} ?: uiText("前置：%s；后置：%s",chainTargetLabel(targets.front,data),chainTargetLabel(targets.landing,data))
            UiCard{UiRow(g.name,summary,Icons.Outlined.AccountTree,onClick={onGroup(g)},translateTitle=false,translateSubtitle=false,summaryLines=3,modifier=Modifier.testTag("chain_group_${g.id}"))}
        }
        item{UiSection("链式节点")}
        items(data.nodes.filter(::isChainNode),key={"chain_node_${it.id}"}){n->
            UiCard{UiRow(n.name,chainPathText(data,n.id),Icons.Outlined.AccountTree,onClick={onNode(n)},translateTitle=false,translateSubtitle=false,summaryLines=4,modifier=Modifier.testTag("chain_node_${n.id}"))}
        }
    }
    when(create) {
        "type"->ChoiceDialog("创建链式代理","",listOf("source" to "选择分组或节点","manual" to "A → B"),{create=""},dismissOnChoose=false){if(it=="manual"){create="";onCreate()}else create=it}
        "source"->GroupedChainPicker("选择分组或节点",data,{create=""},{target->create="";if(target.startsWith("group:"))data.groups.firstOrNull{it.id==target.substringAfter(':').toLongOrNull()}?.let(onGroup)else data.nodes.firstOrNull{it.id==target.substringAfter(':').toLongOrNull()}?.let(onNode)},allowedNodes=data.nodes.filter{!isChainNode(it) && JSONObject(it.outbound).optString("type")!="custom"}.map{it.id}.toSet(),allowedGroups=data.groups.filter{!isChainGroup(it) && !JSONObject(it.options).optBoolean("chainNodeContainer")}.map{it.id}.toSet())
    }
}

internal fun chainNameParts(source:List<String>,targets:ChainTargets,data:AppData):List<String> {
    fun target(value:String):List<String> =when {
        value.startsWith("node:")->listOfNotNull(data.nodes.firstOrNull{it.id==value.substringAfter(':').toLongOrNull()}?.name)
        value.startsWith("group:")->listOfNotNull(data.groups.firstOrNull{it.id==value.substringAfter(':').toLongOrNull()}?.name)
        else->emptyList()
    }
    return target(targets.front)+source+target(targets.landing)
}

@Composable internal fun ChainRouteEditor(node:Node?,group:Group?,data:AppData,onDismiss:()->Unit,saving:Boolean,error:String,onSave:(String,ChainTargets)->Unit) {
    val creating=if(node!=null)!isChainNode(node) else !isChainGroup(requireNotNull(group))
    val sourceName=node?.name ?: group?.name.orEmpty()
    var targets by remember(node?.id,group?.id){mutableStateOf(if(creating)ChainTargets() else if(node!=null)nodeChainTargets(node) else groupChainTargets(requireNotNull(group)))}
    val initialName=if(creating)uiText("%s 链式代理",chainNameParts(listOf(sourceName),targets,data).joinToString(" → "))else sourceName
    var name by remember(node?.id,group?.id){mutableStateOf(initialName)}
    var nameEdited by remember(node?.id,group?.id){mutableStateOf(false)}
    LaunchedEffect(initialName){if(creating && !nameEdited)name=initialName}
    var side by remember{mutableStateOf("")}
    UiPageList(if(creating)"创建链式副本" else "链路设置",onDismiss,action={TextButton(enabled=!saving && name.isNotBlank(),onClick={onSave(name,targets)},modifier=Modifier.testTag("chain_save")){Text(uiText("保存"))}}) {
        item{OutlinedTextField(name,{name=it;nameEdited=true},label={Text(uiText("名称"))},modifier=Modifier.fillMaxWidth().testTag("chain_name"))}
        item{Text(uiText(if(creating)"保存为独立代理，原节点和原订阅不变。" else "修改当前链式副本，不影响原节点和原订阅。"),style=MaterialTheme.typography.bodySmall)}
        if(group!=null) {
            val source=if(creating)group else data.groups.firstOrNull{it.id==JSONObject(group.options).optLong("chainSourceGroupId")}
            item{Text(uiText("来源：%s，随原订阅更新",source?.name ?: uiText("引用已失效")),style=MaterialTheme.typography.bodySmall)}
        }
        item{ChainTargetRows(targets,data,{side=it})}
        item{Text(uiText("引用节点使用原始连接配置，不叠加它所属分组的链路。"),style=MaterialTheme.typography.bodySmall)}
        if(error.isNotBlank())item{Text(uiText(error),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("chain_error"))}
    }
    if(side.isNotEmpty())ChainTargetPicker(if(side=="front")"前置代理" else "后置代理（落地）",if(side=="front")targets.front else targets.landing,data,{side=""},{targets=if(side=="front")targets.copy(front=it)else targets.copy(landing=it)},inherit=node!=null && !creating,excludeNodeId=node?.id)
}
