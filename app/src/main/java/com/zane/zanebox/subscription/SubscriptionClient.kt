package com.zane.zanebox.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class FetchedSubscription(val body: String, val userInfo: String = "")
object SubscriptionClient {
    fun ruleSetFormat(url:String)=com.zane.zanebox.config.ConfigBuilder.ruleSetFormat(url)
    suspend fun fetchSmartRules(url:String,settings:Map<String,String>):String {
        if(ruleSetFormat(url)=="binary") {
            val bytes=fetchBytes(url,settings)
            require(bytes.size>=4 && bytes.copyOfRange(0,3).contentEquals("SRS".toByteArray()) && bytes[3].toInt() in 1..5) { "文件不是当前内核支持的 SRS 规则集" }
            return ""
        }
        val body=fetch(url,settings=settings).body;require(body.isNotBlank()) { "规则源返回为空" }
        if(ruleSetFormat(url)=="source")org.json.JSONObject(body).getJSONArray("rules")
        return body
    }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    internal fun requestClient(settings:Map<String,String>):OkHttpClient {
        val insecure=settings["allowInsecureOnRequest"]=="true"
        val version=settings["appTLSVersion"] ?: "1.2"
        require(version in listOf("1.2","1.3")) { "订阅 TLS 版本无效" }
        if(!insecure && version=="1.2")return client
        val builder=client.newBuilder().connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(*if(version=="1.3")arrayOf(TlsVersion.TLS_1_3)else arrayOf(TlsVersion.TLS_1_3,TlsVersion.TLS_1_2)).build(),ConnectionSpec.CLEARTEXT))
        if(insecure) {
            val trust=object:javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain:Array<java.security.cert.X509Certificate>,authType:String) {}
                override fun checkServerTrusted(chain:Array<java.security.cert.X509Certificate>,authType:String) {}
                override fun getAcceptedIssuers()=emptyArray<java.security.cert.X509Certificate>()
            }
            val ssl=javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null,arrayOf(trust),java.security.SecureRandom()) }
            builder.sslSocketFactory(ssl.socketFactory,trust).hostnameVerifier { _,_->true }
        }
        return builder.build()
    }
    private data class HttpResult(val code:Int, val location:String?, val body:ByteArray, val userInfo:String)
    private suspend fun request(url: String,userAgent:String,client:OkHttpClient,limit:Int): HttpResult = suspendCancellableCoroutine { continuation ->
        val call=client.newCall(Request.Builder().url(url).header("User-Agent",userAgent.ifBlank { "zanebox/1.0.0" }).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val output=ByteArrayOutputStream()
                        if(it.code in 200..299) {
                            val body=it.body ?: error("订阅响应为空")
                            require(body.contentLength()<=limit) { "下载内容超过 ${limit/1024/1024} MiB" }
                            body.byteStream().use { input ->
                                val buffer=ByteArray(8192)
                                while(continuation.isActive) {
                                    val count=input.read(buffer); if(count<0) break
                                    require(output.size()+count<=limit) { "下载内容超过 ${limit/1024/1024} MiB" }; output.write(buffer,0,count)
                                }
                            }
                        }
                        if(continuation.isActive) continuation.resume(HttpResult(it.code,it.header("Location"),output.toByteArray(),it.header("subscription-userinfo").orEmpty()))
                    } catch(e:Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
                }
            }
        })
    }
    suspend fun fetch(url:String,userAgent:String="",settings:Map<String,String> = emptyMap()):FetchedSubscription {
        val result=fetchLimited(url,userAgent,settings,8*1024*1024)
        return FetchedSubscription(result.body.toString(Charsets.UTF_8),result.userInfo)
    }
    suspend fun fetchBytes(url:String,settings:Map<String,String> = emptyMap(),limit:Int=64*1024*1024):ByteArray=fetchLimited(url,"",settings,limit).body
    private suspend fun fetchLimited(url:String,userAgent:String,settings:Map<String,String>,limit:Int):HttpResult=withContext(Dispatchers.IO) {
        require(limit in 1..64*1024*1024)
        val requestClient=requestClient(settings)
        var target=url.trim().toHttpUrlOrNull()?.toUri() ?: URI(url)
        repeat(6) { redirect ->
            require(target.scheme=="https" || target.scheme=="http") { "订阅只支持 HTTP/HTTPS" }
            require(target.host!=null && target.userInfo==null) { "订阅 URL 无效" }
            currentCoroutineContext().ensureActive()
            val result=request(target.toString(),userAgent,requestClient,limit)
            if(result.code in listOf(301,302,303,307,308)) {
                require(redirect<5) { "订阅重定向过多" }
                val next=target.resolve(result.location ?: error("重定向缺少地址"))
                require(target.scheme!="https" || next.scheme=="https") { "拒绝 HTTPS 降级重定向" }; target=next
            } else {
                require(result.code in 200..299) { "订阅 HTTP ${result.code}" }
                return@withContext result
            }
        }
        error("订阅重定向过多")
    }
}
