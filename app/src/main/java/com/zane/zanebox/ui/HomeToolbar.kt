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
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom=4.dp).height(127.dp).testTag("home_toolbar")) {
        AndroidView(factory={ZaneHomeToolbar(it).apply{importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO}},modifier=Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sideWidth=maxOf(96f,48f*androidx.compose.ui.platform.LocalDensity.current.fontScale+16f).dp.coerceAtMost((maxWidth-120.dp)/2)
            listOf(2 to .2f,1 to .8f).forEach { (page,fraction)->
                Column(Modifier.offset(x=24.dp+(maxWidth-48.dp)*fraction-sideWidth/2,y=52.dp).size(width=sideWidth,height=58.dp)
                    .clickable{onPage(page)}.testTag("tab_$page").padding(top=4.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                    Icon(painterResource(if(page==2)R.drawable.zb_ref_ic_baseline_tune_24 else R.drawable.zb_ref_ic_anybox_smart_routing),null,Modifier.size(26.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(uiText(if(page==2)"设置" else "智能分流"),fontSize=12.sp,lineHeight=12.sp,maxLines=1,
                        style=androidx.compose.ui.text.TextStyle(platformStyle=androidx.compose.ui.text.PlatformTextStyle(includeFontPadding=false)))
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
