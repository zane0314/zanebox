package com.zane.zanebox.backup

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** href is a server supplied URL; every operation revalidates it against the configured directory. */
data class WebDavEntry(val name:String,val size:Long,val modified:String,val href:String)
class WebDavClient internal constructor(url:String,user:String,password:String,private val client:OkHttpClient) {
    constructor(url:String,user:String,password:String):this(url,user,password,OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).writeTimeout(30,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build())
    private val base:HttpUrl
    private val auth=Credentials.basic(user,password,Charsets.UTF_8)
    init {
        val parsed=url.toHttpUrlOrNull() ?: error("WebDAV地址无效")
        require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query==null && parsed.fragment==null) { "WebDAV需要无内嵌凭据的HTTPS目录地址" }
        base=if(parsed.encodedPath.endsWith('/')) parsed else parsed.newBuilder().addPathSegment("").build()
    }
    private fun owned(href:String):HttpUrl {
        val target=base.resolve(href) ?: error("WebDAV返回无效路径")
        require(target.scheme==base.scheme && target.host==base.host && target.port==base.port && target.username.isEmpty() && target.password.isEmpty() && target.query==null && target.fragment==null && target.encodedPath.startsWith(base.encodedPath)) { "WebDAV路径超出备份目录" }
        val relative=target.encodedPath.removePrefix(base.encodedPath)
        require(relative.isNotEmpty() && !relative.contains('/') && !target.pathSegments.last().contains('/') && !target.pathSegments.last().contains('\\') && !target.pathSegments.last().contains('%')) { "WebDAV只允许备份目录的直接文件" }
        return target
    }
    private fun file(name:String):HttpUrl {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,150}\\.(zip|json)")) && !name.contains("..")) { "备份文件名无效" }
        return owned(base.newBuilder().addPathSegment(name).build().toString())
    }
    private fun request(target:HttpUrl,method:String,body:ByteArray?=null,xml:Boolean=false):ByteArray {
        try {
            val builder=Request.Builder().url(target).header("Authorization",auth)
            if(method=="PROPFIND") builder.header("Depth","1")
            builder.method(method,body?.toRequestBody((if(xml) "application/xml; charset=utf-8" else "application/zip").toMediaType()))
            return client.newCall(builder.build()).execute().use { response ->
                if(!response.isSuccessful) throw IOException("WebDAV请求失败（HTTP ${response.code}）")
                val content=response.body ?: return@use ByteArray(0)
                require(content.contentLength()<=MAX_BACKUP) { "WebDAV文件超过64MiB" }
                content.byteStream().use { it.readBounded() }
            }
        } catch(e:IOException) { throw IOException(if(e.message?.startsWith("WebDAV请求失败（HTTP ")==true) e.message else "WebDAV网络请求失败，请检查连接与服务器设置") }
    }
    fun list():List<WebDavEntry> {
        val bytes=request(base,"PROPFIND","<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getcontentlength/><d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>".toByteArray(),true)
        // Android's XML implementation does not expose every Xerces feature. Explicitly
        // reject declarations before parsing, and parse a UTF-8 Reader so no encoding bypass exists.
        val xml=try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch(e:Exception) { throw IOException("WebDAV目录响应不是UTF-8") }
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)",RegexOption.IGNORE_CASE).containsMatchIn(xml) && !xml.contains('\u0000')) { "WebDAV响应包含不允许的XML声明" }
        val factory=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true;isExpandEntityReferences=false }
        listOf("http://apache.org/xml/features/disallow-doctype-decl" to true,"http://xml.org/sax/features/external-general-entities" to false,"http://xml.org/sax/features/external-parameter-entities" to false).forEach { (name,value) -> try { factory.setFeature(name,value) } catch(_:javax.xml.parsers.ParserConfigurationException) { /* Lexical rejection above remains mandatory. */ } }
        val document=try { factory.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml))) } catch(e:Exception) { throw IOException("WebDAV目录响应无效") }
        val responses=document.getElementsByTagNameNS("DAV:","response");require(responses.length<=10000) { "WebDAV条目过多" }
        val found=linkedMapOf<String,WebDavEntry>()
        for(i in 0 until responses.length) {
            val element=responses.item(i) as org.w3c.dom.Element
            fun text(name:String)=element.getElementsByTagNameNS("DAV:",name).item(0)?.textContent.orEmpty()
            if(element.getElementsByTagNameNS("DAV:","collection").length>0) continue
            val href=text("href");if(base.resolve(href)?.encodedPath==base.encodedPath) continue
            val target=owned(href);val name=target.pathSegments.last()
            if(!name.matches(Regex("(zanebox_backup_[A-Za-z0-9_-]+\\.zip|zanebox_latest\\.zip|anybox_backup_[A-Za-z0-9_-]+\\.zip|AnyBox-backup-(latest|[0-9-]+)\\.zip|nekobox_backup[^/]*\\.json)"))) continue
            val size=text("getcontentlength").toLongOrNull() ?: -1
            require(size>=-1 && size<=MAX_BACKUP) { "WebDAV文件大小无效" }
            require(!found.containsKey(target.toString())) { "WebDAV响应包含重复文件" }
            found[target.toString()]=WebDavEntry(name,size,text("getlastmodified"),target.toString())
        }
        return found.values.sortedByDescending { it.name }
    }
    /** History upload must succeed before the convenience latest copy is updated. */
    fun upload(bytes:ByteArray,retain:Int=10):WebDavEntry {
        require(bytes.isNotEmpty() && bytes.size<=MAX_BACKUP);require(retain in 1..100)
        val name="zanebox_backup_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.zip"
        val target=file(name);request(target,"PUT",bytes);request(file("zanebox_latest.zip"),"PUT",bytes)
        list().filter { it.name.startsWith("zanebox_backup_") }.sortedByDescending { it.name }.drop(retain).forEach { delete(it) }
        return WebDavEntry(name,bytes.size.toLong(),"",target.toString())
    }
    fun download(entry:WebDavEntry):ByteArray { val target=owned(entry.href);require(target.pathSegments.last()==entry.name);require(file(entry.name)==target);return request(target,"GET") }
    fun download(name:String):ByteArray = request(file(name),"GET")
    fun delete(entry:WebDavEntry) { val target=owned(entry.href);require(target.pathSegments.last()==entry.name);delete(entry.name) }
    fun delete(name:String) { require(name.startsWith("zanebox_backup_") || name=="zanebox_latest.zip" || name.startsWith("anybox_backup_") || name.startsWith("AnyBox-backup-") || name.startsWith("nekobox_backup")) { "只能删除备份文件" };request(file(name),"DELETE") }
}
