package com.zane.zanebox

import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.config.Purpose
import com.zane.zanebox.data.AppData
import com.zane.zanebox.data.Group
import com.zane.zanebox.data.Node
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.Callable

class LatencyConfigPreparationTest {
    private fun socks(id:Long, group:Long=1, detour:String="", source:String="") = Node(id,group,"n$id",
        JSONObject().put("type","socks").put("server","example.com").put("server_port",1080).apply { if(detour.isNotEmpty())put("detour",detour) }.toString(),
        metadata=JSONObject().apply { if(source.isNotEmpty())put("sourceTag",source) }.toString())

    private val data=AppData(
        nodes=listOf(socks(1,source="first"),socks(2,source="source"),socks(3,2),socks(4,2),
            Node(5,1,"chain","""{"type":"chain","node_ids":[1,6]}""",metadata="""{"chain":{"front":"none","landing":"none"}}"""),
            Node(6,2,"nested","""{"type":"chain","node_ids":[2,3]}""")),
        groups=listOf(Group(1,"g",frontProxy=3,landingProxy=4),Group(2,"other")),
        settings=mapOf("globalAllowInsecure" to "true"))

    // Sort only object keys: array order is part of the generated configuration.
    private fun canonical(value:Any?):Any? = when(value) {
        is JSONObject -> value.keys().asSequence().sorted().associateWith { canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }
        else -> value
    }
    private fun assertEquivalent(expected:String,actual:String) = assertEquals(canonical(JSONObject(expected)),canonical(JSONObject(actual)))

    @Test fun preparedPreservesCrossGroupNestedChainAndGroupDependencies() {
        val prepared=ConfigBuilder.prepareTestBatch(data)
        data.nodes.forEach { assertEquivalent(ConfigBuilder.build(data,Purpose.TEST,it.id),prepared.build(it.id)) }
        val root=JSONObject(prepared.build(1))
        val out=root.getJSONArray("outbounds")
        val tags=(0 until out.length()).map { out.getJSONObject(it).getString("tag") }
        assertTrue(tags.containsAll(listOf("node-1","node-1-hop","node-3")))
    }

    @Test fun sourceDetourAndCustomAndWireguardRemainEquivalent() {
        val wg=Node(7,2,"wg","""{"type":"wireguard","server":"example.com","server_port":51820,"local_address":["10.0.0.2/32"],"private_key":"private","peer_public_key":"public"}""")
        val custom=Node(8,2,"custom","""{"type":"custom","config":{"inbounds":[{"type":"socks","listen_port":1234}],"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"},"experimental":{"clash_api":{"external_controller":"127.0.0.1:9999"}}}}""")
        val plain=data.copy(nodes=data.nodes.map { if(it.id==1L)socks(1,detour="source",source="first") else it }+wg+custom,groups=data.groups.map { it.copy(frontProxy=0,landingProxy=0) })
        val prepared=ConfigBuilder.prepareTestBatch(plain)
        listOf(1L,7L,8L).forEach { assertEquivalent(ConfigBuilder.build(plain,Purpose.TEST,it),prepared.build(it)) }
        val sourceOut=JSONObject(prepared.build(1)).getJSONArray("outbounds")
        val nodeOne=(0 until sourceOut.length()).map { sourceOut.getJSONObject(it) }.single { it.getString("tag")=="node-1" }
        assertEquals("node-1-source-1",nodeOne.getString("detour"))
        assertEquals(1,JSONObject(prepared.build(7)).getJSONArray("endpoints").length())
        assertEquals(0,JSONObject(prepared.build(8)).getJSONArray("inbounds").length())
    }

    @Test fun concurrentAndRepeatedBuildsDoNotMutatePreparedInputs() {
        val prepared=ConfigBuilder.prepareTestBatch(data)
        val expected=data.nodes.associate { it.id to prepared.build(it.id) }
        val pool=Executors.newFixedThreadPool(12)
        try {
            val results=pool.invokeAll((0 until 60).map { index -> Callable {
                val id=data.nodes[index%data.nodes.size].id
                id to prepared.build(id)
            } })
            results.forEach { val (id,text)=it.get();assertEquivalent(expected.getValue(id),text) }
            data.nodes.forEach { assertEquivalent(expected.getValue(it.id),prepared.build(it.id)) }
        } finally { pool.shutdownNow() }
    }

    @Test fun preparationSnapshotsMutableAppDataContainers() {
        val nodes=data.nodes.toMutableList();val groups=data.groups.toMutableList();val settings=data.settings.toMutableMap()
        val prepared=ConfigBuilder.prepareTestBatch(data.copy(nodes=nodes,groups=groups,settings=settings))
        val expected=prepared.build(5)
        nodes.clear();groups.clear();settings["routeMode"]="invalid"
        assertEquivalent(expected,prepared.build(5))
    }

    @Test fun invalidAndCyclicChainsStillFail() {
        val broken=data.copy(nodes=listOf(Node(1,1,"cycle","""{"type":"chain","node_ids":[1]}""")),groups=listOf(Group(1,"g")))
        val prepared=ConfigBuilder.prepareTestBatch(broken)
        assertThrows(IllegalArgumentException::class.java) { prepared.build(1) }
        assertThrows(IllegalStateException::class.java) { prepared.build(999) }
        val deep=broken.copy(nodes=(1L..65L).map { id ->
            if(id==65L)socks(id) else Node(id,1,"chain","""{"type":"chain","node_ids":[${id+1}]}""")
        })
        assertThrows(IllegalArgumentException::class.java) { ConfigBuilder.prepareTestBatch(deep).build(1) }
    }
}
