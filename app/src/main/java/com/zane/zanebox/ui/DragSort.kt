package com.zane.zanebox.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keep gesture frames in memory; persist one order only when the finger is released. */
internal class DragSortState(private val list:LazyListState,private val scope:CoroutineScope,private val edge:Float) {
    var order by mutableStateOf<List<Any>>(emptyList());private set
    var dragging by mutableStateOf<Any?>(null);private set
    var canMove:(Any,Any)->Boolean={_,_->true}
    var onDrop:(List<Any>)->Unit={}
    private var original=emptyList<Any>()
    private var startTop=0f
    private var itemSize=0
    private var distance by mutableFloatStateOf(0f)
    private var scroll:Job?=null
    private var pinned:PinnableContainer.PinnedHandle?=null
    fun sync(keys:List<Any>) { original=keys;if(dragging==null)order=keys else if(order.toSet()!=keys.toSet())cancel() }
    private fun start(key:Any,container:PinnableContainer?) {
        val item=list.layoutInfo.visibleItemsInfo.firstOrNull{it.key==key} ?: return
        scroll?.cancel();pinned?.release();pinned=container?.pin();dragging=key;startTop=item.offset.toFloat();itemSize=item.size;distance=0f
        scroll=scope.launch {
            while(isActive && dragging!=null) {
                withFrameNanos { }
                val center=startTop+distance+itemSize/2
                val layout=list.layoutInfo
                val amount=when {
                    center<layout.viewportStartOffset+edge -> (center-layout.viewportStartOffset-edge)/4
                    center>layout.viewportEndOffset-edge -> (center-layout.viewportEndOffset+edge)/4
                    else -> 0f
                }.coerceIn(-edge/3,edge/3)
                val index=order.indexOf(dragging)
                if(amount<0 && index>0 || amount>0 && index<order.lastIndex) {list.scrollBy(amount);move()}
            }
        }
    }
    private fun move() {
        val key=dragging ?: return
        val from=order.indexOf(key)
        val center=startTop+distance+itemSize/2
        val candidates=list.layoutInfo.visibleItemsInfo.filter { other ->
            other.key!=key && other.key in order && canMove(key,other.key) &&
                ((order.indexOf(other.key)>from && center>=other.offset+other.size/2) ||
                (order.indexOf(other.key)<from && center<=other.offset+other.size/2))
        }
        val target=candidates.lastOrNull{order.indexOf(it.key)>from} ?: candidates.firstOrNull()
        if(target!=null)order=order.toMutableList().apply { add(indexOf(target.key),removeAt(from)) }
    }
    private fun finish() {
        val changed=order!=original
        scroll?.cancel();scroll=null;pinned?.release();pinned=null;dragging=null;distance=0f
        if(changed)onDrop(order)
    }
    fun cancel() {scroll?.cancel();scroll=null;pinned?.release();pinned=null;dragging=null;distance=0f;order=original}
    private fun moveAccessible(key:Any,step:Int):Boolean {
        if(dragging!=null)return false
        val from=order.indexOf(key);val target=order.getOrNull(from+step) ?: return false
        if(!canMove(key,target))return false
        order=order.toMutableList().apply{add(from+step,removeAt(from))};onDrop(order);return true
    }
    @Composable fun modifier(key:Any):Modifier {
        val container=LocalPinnableContainer.current
        val previous=uiText("向前移动");val next=uiText("向后移动")
        return Modifier.semantics {
            val index=order.indexOf(key)
            customActions=listOfNotNull(
                order.getOrNull(index-1)?.takeIf{canMove(key,it)}?.let{CustomAccessibilityAction(previous){moveAccessible(key,-1)}},
                order.getOrNull(index+1)?.takeIf{canMove(key,it)}?.let{CustomAccessibilityAction(next){moveAccessible(key,1)}})
        }.zIndex(if(dragging==key)1f else 0f).graphicsLayer {
        if(dragging==key)translationY=startTop+distance-(list.layoutInfo.visibleItemsInfo.firstOrNull{it.key==key}?.offset ?: startTop.toInt())
        else translationY=0f
    }.pointerInput(this,key) {
        detectDragGesturesAfterLongPress(onDragStart={start(key,container)},onDragEnd=::finish,onDragCancel=::cancel) { change,amount ->
            change.consume();distance+=amount.y;move()
        }
    }
    }
}

@Composable internal fun rememberDragSort(keys:List<Any>,list:LazyListState,canMove:(Any,Any)->Boolean={_,_->true},onDrop:(List<Any>)->Unit):DragSortState {
    val scope=rememberCoroutineScope()
    val edge=with(LocalDensity.current){48.dp.toPx()}
    val state=remember(list,scope,edge){DragSortState(list,scope,edge).also{it.sync(keys)}}
    SideEffect {state.canMove=canMove;state.onDrop=onDrop}
    LaunchedEffect(keys){state.sync(keys)}
    DisposableEffect(state){onDispose{state.cancel()}}
    return state
}
