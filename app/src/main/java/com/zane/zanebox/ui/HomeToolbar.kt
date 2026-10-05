package com.zane.zanebox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.zane.zanebox.R
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable internal fun HomeToolbar(state:Int,onPage:(Int)->Unit,onToggle:()->Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().height(127.dp)) {
        AndroidView(factory={ZaneHomeToolbar(it)},modifier=Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(top=45.dp).height(72.dp)) {
        val centerGap=maxWidth*.2f
        Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable{onPage(2)}.testTag("tab_2").padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.zb_ref_ic_baseline_tune_24),"设置",Modifier.size(26.dp));Text(uiText("设置"),fontSize=12.sp,lineHeight=16.sp)
            }
            Spacer(Modifier.width(centerGap))
            Column(Modifier.weight(1f).clickable{onPage(1)}.testTag("tab_1").padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.zb_ref_ic_anybox_smart_routing),"智能分流",Modifier.size(26.dp));Text(uiText("智能分流"),fontSize=12.sp,lineHeight=16.sp)
            }
        }
        }
        val label=uiText(listOf("连接","连接中…","断开","断开中…","连接").getOrElse(state){"同步中"})
        val description=uiText("代理开关")+"："+label
        Box(Modifier.align(Alignment.TopCenter).offset(y=10.dp).size(82.dp).testTag("connect_toggle")
            .semantics{contentDescription=description}.clickable(enabled=state!=1 && state!=3,onClick=onToggle)) {
            AndroidView(factory={ZanePowerButton(it).apply{isClickable=false;isFocusable=false}},update={it.render(state,false)},modifier=Modifier.fillMaxSize())
        }
        Text(label,Modifier.align(Alignment.TopCenter).offset(y=92.dp).testTag("tab_0").clickable(enabled=state!=1 && state!=3,onClick=onToggle),fontSize=12.sp,lineHeight=16.sp)
    }
}
