package com.zane.zanebox

import com.zane.zanebox.backup.WebDavClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Entirely local TLS fixture with a real localhost SAN and explicit fixture trust root. */
class WebDavInteropTest {
    @Test fun localTlsHistoryLatestRetentionDownloadDelete() {
        val path=System.getenv("ZANEBOX_WEBDAV_TEST_KEYSTORE")
        org.junit.Assume.assumeTrue("通过 tests/backup_checks.py 创建本地TLS夹具",path!=null)
        val store=KeyStore.getInstance("PKCS12").apply { java.io.File(requireNotNull(path)).inputStream().use { load(it,"fixturepass".toCharArray()) } }
        val kmf=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,"fixturepass".toCharArray()) }
        val tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val trust=tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls=SSLContext.getInstance("TLS").apply { init(kmf.keyManagers,tmf.trustManagers,null) }
        val files=ConcurrentHashMap<String,ByteArray>().apply { put("important.txt","unrelated".toByteArray());put("AnyBox-backup-20261005-120000.zip","legacy".toByteArray()) };val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
        var maliciousXml:String?=null
        val authorization=okhttp3.Credentials.basic("fixture-user","fixture-password",Charsets.UTF_8)
        val server=MockWebServer().apply {
            useHttps(tls.socketFactory,false)
            dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    if(request.getHeader("Authorization")!=authorization) return MockResponse().setResponseCode(401)
                    val path=request.path.orEmpty();if(!path.startsWith("/backups/")) return MockResponse().setResponseCode(404)
                    val name=path.removePrefix("/backups/");val method=request.method;requests.add("$method $name")
                    return when(method) {
                        "PROPFIND" -> { if(request.getHeader("Depth")!="1") return MockResponse().setResponseCode(400)
                            val xml=maliciousXml ?: buildString { append("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/backups/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>");files.entries.sortedBy { it.key }.forEach { (key,value) -> append("<d:response><d:href>/backups/$key</d:href><d:propstat><d:prop><d:getcontentlength>${value.size}</d:getcontentlength><d:getlastmodified>Mon, 05 Oct 2026 00:00:00 GMT</d:getlastmodified><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>") };append("</d:multistatus>") }
                            MockResponse().setResponseCode(207).setHeader("Content-Type","application/xml; charset=utf-8").setBody(xml)
                        }
                        "PUT" -> { files[name]=request.body.readByteArray();MockResponse().setResponseCode(201) }
                        "GET" -> files[name]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                        "DELETE" -> { files.remove(name);MockResponse().setResponseCode(204) }
                        else -> MockResponse().setResponseCode(405)
                    }
                }
            }
            start()
        }
        try {
            val client=WebDavClient("https://localhost:${server.port}/backups/","fixture-user","fixture-password",OkHttpClient.Builder().sslSocketFactory(tls.socketFactory,trust).followRedirects(false).followSslRedirects(false).build())
            val old=client.upload("first".toByteArray(),2);val second=client.upload("second".toByteArray(),2);val third=client.upload("third".toByteArray(),2)
            assertFalse(files.containsKey(old.name));assertTrue(files.containsKey(second.name));assertTrue(files.containsKey(third.name));assertArrayEquals("third".toByteArray(),files["zanebox_latest.zip"])
            assertEquals(4,client.list().size);assertArrayEquals("unrelated".toByteArray(),files["important.txt"]);assertArrayEquals("legacy".toByteArray(),files["AnyBox-backup-20261005-120000.zip"]);assertArrayEquals("third".toByteArray(),client.download(third));client.delete(second);assertFalse(files.containsKey(second.name))
            assertTrue(requests.indexOf("PUT ${third.name}")<requests.lastIndexOf("PUT zanebox_latest.zip"));assertTrue(requests.lastIndexOf("PUT zanebox_latest.zip")<requests.indexOf("DELETE ${old.name}"))
            val wrong=WebDavClient("https://localhost:${server.port}/backups/","fixture-user","fixture-bad",OkHttpClient.Builder().sslSocketFactory(tls.socketFactory,trust).followRedirects(false).followSslRedirects(false).build())
            try { wrong.list();fail("认证失败应报错") } catch(e:java.io.IOException) { assertTrue(e.message.orEmpty().contains("401"));assertFalse(e.message.orEmpty().contains("fixture-bad"));assertFalse(e.message.orEmpty().contains("localhost")) }
            maliciousXml="<!DOCTYPE r [<!ENTITY injected SYSTEM \"file:///etc/passwd\">]><d:multistatus xmlns:d=\"DAV:\">&injected;</d:multistatus>"
            try { client.list();fail("XXE必须被拒绝") } catch(_:IllegalArgumentException) { }
        } finally { server.shutdown() }
    }
}
