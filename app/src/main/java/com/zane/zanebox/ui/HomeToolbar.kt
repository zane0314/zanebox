package com.zane.zanebox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Follows AnyBox's white toolbar, raised blue glass dial and two side destinations. */
@Composable
internal fun HomeToolbar(page:Int,state:Int,elapsedSeconds:Long,onPage:(Int)->Unit,onToggle:()->Unit) {
    val largeFont = LocalDensity.current.fontScale >= 1.5f
    val dialSize = if (largeFont) 152.dp else 116.dp
    Surface(color=MaterialTheme.colorScheme.surface,shadowElevation=8.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=12.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.SpaceBetween
        ) {
            TextButton(onClick={onPage(2)},contentPadding=PaddingValues(horizontal=4.dp,vertical=8.dp),modifier=Modifier.weight(1f).testTag("tab_2").semantics { contentDescription="设置" }) {
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("⚙",style=MaterialTheme.typography.headlineSmall)
                    Text("设置",style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=if(page==2)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(
                Modifier.testTag("tab_0").clickable {onPage(0)},
                horizontalAlignment=Alignment.CenterHorizontally
            ) {
                val label=listOf("代理已停止","连接中","代理已连接","停止中","连接失败").getOrElse(state){"同步状态"}
                val visualLabel=listOf("已停止","连接中","已连接","停止中","失败").getOrElse(state){"同步中"}
                Box(
                    Modifier.size(dialSize).testTag("connect_toggle").semantics {contentDescription=label}
                        .clickable(enabled=state!=3,onClick=onToggle),
                    contentAlignment=Alignment.Center
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val radius=size.minDimension/2-5.dp.toPx()
                        val center=Offset(size.width/2,size.height/2)
                        drawCircle(Brush.radialGradient(listOf(Color(0xfffefeff),Color(0xffe5e9fc),Color(0xffcedaf7)),center=center-Offset(radius*.25f,radius*.3f),radius=radius*1.6f),radius,center)
                        drawCircle(Brush.sweepGradient(listOf(Color(0xff7897ff),Color(0xffaaa6fb),Color(0xff6696f4),Color(0xff7897ff)),center),radius-2.dp.toPx(),center,style=Stroke(3.dp.toPx()))
                        drawCircle(Color.White,radius-6.dp.toPx(),center,style=Stroke(1.dp.toPx()))
                        val iconCenter=center-Offset(0f,(if(largeFont)26.dp else 14.dp).toPx())
                        val iconRadius=18.dp.toPx()
                        drawArc(Color(0xff354fd4),-48f,276f,false,iconCenter-Offset(iconRadius,iconRadius),androidx.compose.ui.geometry.Size(iconRadius*2,iconRadius*2),style=Stroke(2.5.dp.toPx()))
                        drawLine(Color(0xff354fd4),iconCenter-Offset(0f,22.dp.toPx()),iconCenter-Offset(0f,5.dp.toPx()),2.5.dp.toPx())
                    }
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom=if(largeFont)16.dp else 12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(visualLabel,style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis,color=Color(0xff454861))
                        Text(if(state==2) "%02d:%02d".format(elapsedSeconds/60,elapsedSeconds%60) else if(state==1 || state==3) "请稍候" else "点击连接",style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=Color(0xff73788f))
                    }
                }
                Text("首页 · 节点",modifier=Modifier.width(dialSize).padding(top=4.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall,color=if(page==0)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick={onPage(1)},contentPadding=PaddingValues(horizontal=4.dp,vertical=8.dp),modifier=Modifier.weight(1f).testTag("tab_1").semantics {contentDescription="智能分流"}) {
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("⇄",style=MaterialTheme.typography.headlineSmall)
                    Text("智能分流",style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=if(page==1)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
