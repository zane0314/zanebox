package com.zane.zanebox.runtime

import com.zane.zanebox.data.AppData
import org.json.JSONArray
import org.json.JSONObject

/** Bounded accumulated statistics; the runtime serializes connection sampling/restore. */
internal class RuntimeTraffic(private val snapshot:()->AppData,private val save:(String)->Unit) {
    val connectionBytes=LinkedHashMap<String,Pair<Long,Long>>()
    val counters=JSONObject().put("apps",JSONObject()).put("domains",JSONObject()).put("nodes",JSONObject())
    fun addCounter(type:String,name:String,tx:Long,rx:Long,limit:Int) {
        if(name.isBlank() || (tx==0L && rx==0L))return
        synchronized(counters) {
            val group=counters.getJSONObject(type)
            if(!group.has(name) && group.length()>=limit)return
            val item=group.optJSONObject(name) ?: JSONObject().put("tx",0).put("rx",0)
            item.put("tx",item.optLong("tx")+tx).put("rx",item.optLong("rx")+rx);group.put(name,item)
        }
    }
    fun sampleConnections(text:String) {
        val active=HashSet<String>()
        for(c in TrafficSamples.parse(text)) {
            val id=c.id;active.add(id)
            val up=c.tx;val down=c.rx;val previous=connectionBytes[id] ?: (0L to 0L)
            addCounter("apps",c.app,(up-previous.first).coerceAtLeast(0),(down-previous.second).coerceAtLeast(0),50)
            addCounter("domains",c.domain,(up-previous.first).coerceAtLeast(0),(down-previous.second).coerceAtLeast(0),100)
            connectionBytes[id]=up to down
        }
        connectionBytes.keys.retainAll(active)
    }
    fun trafficJson():String = synchronized(counters) {
        val nodes=snapshot().nodes.associateBy { it.id.toString() }
        JSONObject().apply { listOf("apps","domains","nodes").forEach { kind -> val values=counters.getJSONObject(kind);put(kind,JSONArray().apply { values.keys().asSequence().forEach { name -> put(JSONObject(values.getJSONObject(name).toString()).put("name",if(kind=="nodes")nodes[name]?.name ?: "节点 $name" else name)) } }) } }.toString()
    }
    fun persistTraffic() { val data=synchronized(counters) { counters.toString() };save(data) }
}
