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
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class FetchedSubscription(val body: String, val userInfo: String = "")
object SubscriptionClient {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private data class HttpResult(val code:Int, val location:String?, val body:String, val userInfo:String)
    private suspend fun request(url: String,userAgent:String): HttpResult = suspendCancellableCoroutine { continuation ->
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
                            require(body.contentLength()<=8L*1024*1024) { "订阅超过 8 MiB" }
                            body.byteStream().use { input ->
                                val buffer=ByteArray(8192)
                                while(continuation.isActive) {
                                    val count=input.read(buffer); if(count<0) break
                                    require(output.size()+count<=8*1024*1024) { "订阅超过 8 MiB" }; output.write(buffer,0,count)
                                }
                            }
                        }
                        if(continuation.isActive) continuation.resume(HttpResult(it.code,it.header("Location"),output.toString("UTF-8"),it.header("subscription-userinfo").orEmpty()))
                    } catch(e:Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
                }
            }
        })
    }
    suspend fun fetch(url: String,userAgent:String=""): FetchedSubscription = withContext(Dispatchers.IO) {
        var target=url.trim().toHttpUrlOrNull()?.toUri() ?: URI(url)
        repeat(6) { redirect ->
            require(target.scheme=="https" || target.scheme=="http") { "订阅只支持 HTTP/HTTPS" }
            require(target.host!=null && target.userInfo==null) { "订阅 URL 无效" }
            currentCoroutineContext().ensureActive()
            val result=request(target.toString(),userAgent)
            if(result.code in listOf(301,302,303,307,308)) {
                require(redirect<5) { "订阅重定向过多" }
                val next=target.resolve(result.location ?: error("重定向缺少地址"))
                require(target.scheme!="https" || next.scheme=="https") { "拒绝 HTTPS 降级重定向" }; target=next
            } else {
                require(result.code in 200..299) { "订阅 HTTP ${result.code}" }
                return@withContext FetchedSubscription(result.body,result.userInfo)
            }
        }
        error("订阅重定向过多")
    }
}
