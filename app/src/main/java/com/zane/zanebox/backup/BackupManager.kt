package com.zane.zanebox.backup

import android.content.Context
import com.zane.zanebox.data.AppData
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal const val MAX_BACKUP = 64 * 1024 * 1024
internal fun InputStream.readBounded(limit:Int = MAX_BACKUP):ByteArray {
    val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true) { val n=read(buffer);if(n<0) break;require(output.size().toLong()+n<=limit) { "备份超过64MiB限制" };output.write(buffer,0,n) }
    return output.toByteArray()
}
internal fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
data class BackupScope(val configurations:Boolean=true,val rules:Boolean=true,val settings:Boolean=true)
internal fun backupSelection(data:AppData,scope:BackupScope):AppData {
    require(scope.configurations || scope.rules || scope.settings) { "至少选择一项备份内容" }
    val rules=if(scope.rules)data.rules else emptyList()
    require(scope.configurations || rules.all { it.outbound in listOf("proxy","direct","block") }) { "路由规则引用节点或分组，请同时勾选组与配置" }
    var settings=if(scope.settings)data.settings else emptyMap()
    if(!scope.configurations) settings=settings.filterKeys { it !in setOf("selectedNodeId","selectedGroupId","browseGroupId","smartSourceGroupId","smartSourceMergeId") && !it.startsWith("nodeRegion.") && !it.startsWith("sort_group_") && !it.startsWith("sort_mode_group_") }
        .mapValues { (key,value)->if(key.startsWith("smart.") && key.endsWith(".target") && listOf("node:","group:","merge:").any(value::startsWith))"off" else value }
    return AppData(if(scope.configurations)data.nodes else emptyList(),if(scope.configurations)data.groups else emptyList(),rules,if(scope.configurations)data.merges else emptyList(),settings).validate()
}
/** Does not write to the store. Caller replaces data only after this returns successfully. */
class BackupManager(@Suppress("UNUSED_PARAMETER") context:Context? = null) {
    fun export(data:AppData):ByteArray {
        val payload=data.validate().toJson().toByteArray(Charsets.UTF_8)
        require(payload.size<=MAX_BACKUP) { "备份超过64MiB限制" }
        val manifest=JSONObject().put("app","zanebox").put("format",1).put("createdAt",System.currentTimeMillis()).put("dataSha256",digest(payload))
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            listOf("manifest.json" to manifest.toString().toByteArray(), "data.json" to payload).forEach { (name,bytes) -> zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() }
        }
        return output.toByteArray().also { require(it.size<=MAX_BACKUP) }
    }
    fun `import`(bytes:ByteArray):AppData {
        require(bytes.size<=MAX_BACKUP && bytes.isNotEmpty()) { "备份为空或超过64MiB限制" }
        if(bytes.firstOrNull { it.toInt() !in listOf(9,10,13,32) }?.toInt()== '{'.code) return checkedData(LegacyBackup.read(backupJson(bytes),JSONObject()))
        val expectedEntries=validateZipDirectory(bytes)
        val entries=linkedMapOf<String,ByteArray>();var total=0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while(true) {
                val entry=zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in setOf("manifest.json","data.json","logical.json","preferences.json","nekobox_backup.json")) { "备份包含不允许的路径" }
                require(!entries.containsKey(entry.name)) { "备份包含重复条目" }
                val content=zip.readBounded(MAX_BACKUP-total);total+=content.size;entries[entry.name]=content;zip.closeEntry()
            }
        }
        require(entries.size==expectedEntries) { "ZIP目录与数据条目不一致" }
        if(entries.keys==setOf("nekobox_backup.json")) return checkedData(LegacyBackup.read(backupJson(entries.getValue("nekobox_backup.json")),JSONObject()))
        val manifest=backupJson(entries["manifest.json"] ?: error("缺少备份清单"))
        fun checked(name:String,key:String):ByteArray {
            val content=entries[name] ?: error("备份缺少$name")
            require(manifest.getString(key).matches(Regex("[0-9a-fA-F]{64}")) && digest(content).equals(manifest.getString(key),true)) { "备份校验失败：$name" }
            return content
        }
        return checkedData(when(manifest.getString("app")) {
            "zanebox" -> { require(manifest.getInt("format")==1 && entries.keys==setOf("manifest.json","data.json")) { "不支持的备份格式" };val root=backupJson(checked("data.json","dataSha256"));require(root.getInt("format")==1);require(root.keys().asSequence().all { it in setOf("format","nodes","groups","rules","merges","settings") }) { "备份包含未知顶层字段，停止恢复" };listOf("nodes","groups","rules","merges").forEach { root.getJSONArray(it) }
                val schemas=mapOf("nodes" to setOf("id","groupId","name","outbound","shareLink","ping","tx","rx","order","status","metadata"),"groups" to setOf("id","name","subscriptionUrl","enabled","order","updatedAt","userInfo","frontProxy","landingProxy","options"),"rules" to setOf("id","name","domains","packages","ipCidrs","outbound","enabled","order","advanced","prioritize"),"merges" to setOf("id","name","nodeIds","groupIds","mode","selectedId"))
                schemas.forEach { (array,keys) -> val values=root.getJSONArray(array);repeat(values.length()) { require(values.getJSONObject(it).keys().asSequence().all { key -> key in keys }) { "备份包含未知${array}记录字段，停止恢复" } } }
                listOf("nodes" to "metadata","groups" to "options","rules" to "advanced").forEach { (array,key) -> val values=root.getJSONArray(array);repeat(values.length()) { val record=values.getJSONObject(it);if(record.has(key)) { val value=record.get(key);require(value is String || value is JSONObject) { "高级字段必须是JSON对象" };if(value is String) record.put(key,backupJson(value)) } } }
                val settings=root.getJSONObject("settings");settings.optString("globalCustomConfig").takeIf { it.isNotBlank() }?.let { backupJson(it) };AppData.fromJson(root.toString()) }
            "AnyBox" -> { require(manifest.getInt("format")==2 && entries.keys==setOf("manifest.json","logical.json","preferences.json")) { "不支持的AnyBox备份格式" };LegacyBackup.read(backupJson(checked("logical.json","logicalSha256")),backupJson(checked("preferences.json","preferencesSha256"))) }
            else -> error("不支持的备份应用")
        })
    }
    private fun checkedData(data:AppData):AppData {
        data.validate();require(data.toJson().toByteArray(Charsets.UTF_8).size<=MAX_BACKUP) { "迁移后的完整数据超过64MiB" };return data
    }
    private fun validateZipDirectory(bytes:ByteArray):Int {
        require(bytes.size>=22) { "ZIP不完整" }
        val b=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val eocd=(bytes.size-22 downTo maxOf(0,bytes.size-65557)).firstOrNull { b.getInt(it)==0x06054b50 && it+22+(b.getShort(it+20).toInt() and 65535)==bytes.size } ?: error("ZIP末尾目录缺失")
        require(b.getShort(eocd+4).toInt()==0 && b.getShort(eocd+6).toInt()==0) { "不支持分卷ZIP" }
        val count=b.getShort(eocd+10).toInt() and 65535
        require(count in 1..5 && (b.getShort(eocd+8).toInt() and 65535)==count) { "ZIP目录条目无效" }
        val size=b.getInt(eocd+12);val offset=b.getInt(eocd+16)
        require(offset>=0 && size>=0 && offset.toLong()+size==eocd.toLong()) { "ZIP目录范围无效" }
        var position=offset;val names=mutableSetOf<String>();val offsets=mutableSetOf<Int>()
        repeat(count) {
            require(position+46<=eocd && b.getInt(position)==0x02014b50) { "ZIP中央目录无效" }
            val flags=b.getShort(position+8).toInt() and 65535
            require(flags and 1==0 && b.getShort(position+34).toInt()==0) { "不支持加密或分卷ZIP" }
            val local=b.getInt(position+42)
            require(offsets.add(local) && local>=0 && local+30<=offset && b.getInt(local)==0x04034b50) { "ZIP本地条目无效" }
            val name=b.getShort(position+28).toInt() and 65535;val extra=b.getShort(position+30).toInt() and 65535;val comment=b.getShort(position+32).toInt() and 65535
            val localName=b.getShort(local+26).toInt() and 65535
            require(name==localName && position.toLong()+46+name+extra+comment<=eocd && local.toLong()+30+localName<=offset) { "ZIP文件名范围无效" }
            require(bytes.copyOfRange(position+46,position+46+name).contentEquals(bytes.copyOfRange(local+30,local+30+localName))) { "ZIP目录文件名不一致" }
            require(names.add(bytes.copyOfRange(position+46,position+46+name).toString(Charsets.UTF_8))) { "ZIP中央目录包含重复文件" }
            position+=46+name+extra+comment
        }
        require(position==eocd) { "ZIP目录含未知数据" };return count
    }

}
