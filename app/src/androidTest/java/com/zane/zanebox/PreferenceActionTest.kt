package com.zane.zanebox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.config.Purpose
import com.zane.zanebox.data.AppData
import com.zane.zanebox.data.Group
import com.zane.zanebox.data.MergeGroup
import com.zane.zanebox.data.Node
import com.zane.zanebox.data.RouteRule
import com.zane.zanebox.runtime.ServiceClient
import com.zane.zanebox.ui.Preference
import com.zane.zanebox.ui.preferenceSections
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Keeps the settings regression contract executable without coupling the UI flow to production
 * code. The companion Maestro flow uses the same keys and deliberately leaves user data intact.
 */
@RunWith(AndroidJUnit4::class)
class PreferenceActionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private val uiKeys = setOf(
        "appTheme", "uiSkin", "launcherIcon", "theme", "appLanguage", "fontScale",
        "confirmProfileDelete", "alwaysShowAddress", "hideFromRecentApps", "showBottomBar"
    )

    /** Settings emitted into the sing-box config by ConfigBuilder. */
    private val nativeConfigKeys = setOf(
        "serviceMode", "tunStack", "mtu", "statsEnabled", "logLevel", "globalCustomConfig",
        "routeMode", "perAppEnabled", "perAppMode", "bypassLan", "bypassLanInCore",
        "concurrentDial", "sniff", "resolveDestination", "ipv6Mode", "ipv6", "dnsStrategy",
        "rulesUpdateInterval", "urlTestInterval", "urlTestTolerance", "enableTLSFragment",
        "dnsRemote", "dnsStrategyRemote", "dnsDirect", "dnsStrategyDirect", "dnsStrategyServer",
        "enableDnsRouting", "fakeDns", "dnsHosts", "allowLan", "shareEnabled", "sharePort",
        "disableMixedInbound", "mixedPort", "strictRoute", "muxEnabled", "muxProtocol",
        "muxMaxStreams", "muxPadding", "testUrl", "clashApi", "apiPort", "globalAllowInsecure"
    )

    private val outputDir: File
        get() = (context.getExternalFilesDir(null) ?: context.filesDir).apply { mkdirs() }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun category(preference: Preference): String = when {
        preference.availability.isNotBlank() -> "disabled"
        preference.key in uiKeys -> "UI"
        preference.key in nativeConfigKeys -> "config"
        else -> "runtime"
    }

    private fun action(preference: Preference): String = when {
        category(preference) == "disabled" -> "disabled"
        preference.boolean -> "toggle"
        preference.choices.isNotEmpty() -> "choice"
        else -> "edit"
    }

    private fun preferenceJson(): String {
        val rows = JSONArray()
        preferenceSections(AppData()).forEach { (section, preferences) ->
            preferences.forEach { preference ->
                rows.put(JSONObject().apply {
                    put("section", section)
                    put("key", preference.key)
                    put("title", preference.title)
                    put("default", preference.default)
                    put("boolean", preference.boolean)
                    put("availability", preference.availability)
                    put("category", category(preference))
                    put("action", action(preference))
                    put("choices", JSONArray().apply {
                        preference.choices.forEach { (value, label) ->
                            put(JSONObject().put("value", value).put("label", label))
                        }
                    })
                })
            }
        }
        return JSONObject().apply {
            put("schema", 1)
            put("source", "UiPreferenceCatalog.preferenceSections")
            put("language", "zh-CN")
            put("count", rows.length())
            put("preferences", rows)
        }.toString(2)
    }

    @Test
    fun catalogAllPreferencesAndWriteActionPlan() {
        val sections = preferenceSections(AppData())
        val preferences = sections.flatMap { it.second }
        val json = preferenceJson()
        val catalog = File(outputDir, "settings-catalog.json")
        catalog.writeText(json)
        File(outputDir, "settings-catalog.sha256").writeText(sha256(json) + "  " + catalog.name + "\n")

        val categories = preferences.groupingBy(::category).eachCount()
        val actions = preferences.groupingBy(::action).eachCount()
        File(outputDir, "settings-action-plan.json").writeText(JSONObject().apply {
            put("catalog", catalog.name)
            put("catalogSha256", sha256(json))
            put("count", preferences.size)
            put("sections", sections.map { it.first })
            put("categories", JSONObject(categories))
            put("actions", JSONObject(actions))
            put("keys", JSONArray(preferences.map { it.key }))
            put("disabledKeys", JSONArray(preferences.filter { category(it) == "disabled" }.map { it.key }))
            put("uiKeys", JSONArray(preferences.filter { category(it) == "UI" }.map { it.key }))
            put("runtimeKeys", JSONArray(preferences.filter { category(it) == "runtime" }.map { it.key }))
            put("configKeys", JSONArray(preferences.filter { category(it) == "config" }.map { it.key }))
        }.toString(2))

        assertEquals("设置目录必须完整枚举 UiPreferenceCatalog", 70, preferences.size)
        assertEquals("设置目录 JSON 数量不一致", preferences.size, JSONObject(json).getInt("count"))
        assertEquals("存在未知设置分类", preferences.size, categories.values.sum())
        assertTrue("缺少 disabled 分类", categories.containsKey("disabled"))
        assertTrue("缺少 UI 分类", categories.containsKey("UI"))
        assertTrue("缺少 runtime 分类", categories.containsKey("runtime"))
        assertTrue("缺少 config 分类", categories.containsKey("config"))
    }

    private fun fixture(extra: Map<String, String> = emptyMap()): AppData {
        val settings = linkedMapOf(
            "appLanguage" to "zh-CN",
            "selectedNodeId" to "1",
            "selectedGroupId" to "1"
        ).apply { putAll(extra) }
        return AppData(
            nodes = listOf(
                Node(
                    1,
                    1,
                    "设置回归 TLS",
                    """{"type":"trojan","server":"example.com","server_port":443,"password":"fixture","tls":{"enabled":true,"server_name":"example.com"}}"""
                )
            ),
            groups = listOf(Group(1, "设置回归组")),
            rules = listOf(RouteRule(1, "设置回归直连", domains = "example.com", outbound = "direct")),
            merges = listOf(MergeGroup(2, "设置回归测速", nodeIds = listOf(1), mode = "urltest")),
            settings = settings
        )
    }

    private fun allCoreSettings(): Map<String, String> = mapOf(
        "serviceMode" to "vpn",
        "tunStack" to "mixed",
        "mtu" to "1400",
        "statsEnabled" to "true",
        "logLevel" to "debug",
        "routeMode" to "rule",
        "perAppEnabled" to "true",
        "perAppMode" to "exclude",
        "perAppPackages" to "com.example.app",
        "bypassLan" to "true",
        "bypassLanInCore" to "true",
        "concurrentDial" to "true",
        "sniff" to "true",
        "resolveDestination" to "true",
        "ipv6Mode" to "prefer_ipv4",
        "ipv6" to "true",
        "dnsStrategy" to "prefer_ipv4",
        "rulesUpdateInterval" to "6h",
        "urlTestInterval" to "1m",
        "urlTestTolerance" to "10",
        "enableTLSFragment" to "true",
        "dnsRemote" to "https://1.1.1.1/dns-query",
        "dnsStrategyRemote" to "prefer_ipv4",
        "dnsDirect" to "local",
        "dnsStrategyDirect" to "ipv4_only",
        "dnsStrategyServer" to "prefer_ipv6",
        "enableDnsRouting" to "true",
        "fakeDns" to "true",
        "dnsHosts" to "198.18.0.2 example.com",
        "allowLan" to "true",
        "shareEnabled" to "true",
        "sharePort" to "2081",
        "disableMixedInbound" to "true",
        "mixedPort" to "2080",
        "strictRoute" to "true",
        "muxEnabled" to "true",
        "muxProtocol" to "smux",
        "muxMaxStreams" to "16",
        "muxPadding" to "true",
        "testUrl" to "https://example.com/204",
        "clashApi" to "true",
        "apiPort" to "9090",
        "globalAllowInsecure" to "true",
        "globalCustomConfig" to """{"log":{"timestamp":true}}""",
        "smart.ai.target" to "direct",
        "smartUrl.ai" to "https://example.test/AI.json",
        "smartRules.ai" to """{"version":3,"rules":[{"domain":["example.org"]}]}"""
    )

    private data class NativeCase(
        val name: String,
        val data: AppData,
        val purpose: Purpose = Purpose.MAIN,
        val nodeId: Long = 0
    )

    @Test
    fun coreSettingsBuildAndDecodeWithRealNativeService() {
        val core = allCoreSettings()
        val cases = listOf(
            NativeCase("main-core", fixture(core)),
            NativeCase(
                "proxy-core",
                fixture(core + mapOf("serviceMode" to "proxy", "perAppEnabled" to "false", "disableMixedInbound" to "false"))
            ),
            NativeCase("test-core", fixture(core), Purpose.TEST, 1),
            NativeCase(
                "minimal-direct",
                fixture(
                    mapOf(
                        "serviceMode" to "vpn",
                        "routeMode" to "direct",
                        "statsEnabled" to "false",
                        "clashApi" to "false",
                        "fakeDns" to "false",
                        "ipv6Mode" to "ipv4_only",
                        "ipv6" to "false",
                        "dnsStrategy" to "ipv4_only"
                    )
                )
            )
        )
        val generated = JSONArray()
        val client = ServiceClient(context)
        client.connect()
        try {
            cases.forEach { testCase ->
                val config = ConfigBuilder.build(testCase.data, testCase.purpose, testCase.nodeId)
                val file = File(outputDir, "settings-generated-${testCase.name}.json")
                file.writeText(config)
                generated.put(JSONObject().apply {
                    put("name", testCase.name)
                    put("purpose", testCase.purpose.name)
                    put("settings", JSONObject().apply { testCase.data.settings.forEach { (key, value) -> put(key, value) } })
                    put("path", file.name)
                    put("sha256", sha256(config))
                })
                client.validateConfig("settings-${testCase.name}", config)
            }
            File(outputDir, "settings-generated.json").writeText(JSONObject().apply {
                put("schema", 1)
                put("language", "zh-CN")
                put("cases", generated)
                put("coveredNativeConfigKeys", JSONArray(core.keys.toList()))
                put("missingNativeConfigKeys", JSONArray(nativeConfigKeys - core.keys))
            }.toString(2))

            val names = cases.map { "settings-${it.name}" }
            val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
            while (names.any { !client.validationResults.value.containsKey(it) } && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(100)
            }
            val results = names.associateWith { client.validationResults.value[it] ?: "未返回" }
            File(outputDir, "settings-native-result.json").writeText(JSONObject().apply {
                results.forEach { (name, error) -> put(name, error) }
            }.toString(2))
            File(outputDir, "settings-result.json").writeText(JSONObject().apply {
                put("catalog", "settings-catalog.json")
                put("generated", "settings-generated.json")
                put("result", "settings-native-result.json")
                put("caseCount", cases.size)
                put("allNativeDecoded", results.values.all(String::isEmpty))
            }.toString(2))

            assertEquals("原生设置配置未全部返回", names.toSet(), client.validationResults.value.keys.intersect(names.toSet()))
            assertTrue("原生设置配置解码失败：$results", results.values.all(String::isEmpty))
            assertTrue("存在未覆盖的 ConfigBuilder 设置", nativeConfigKeys.all { it in core })
        } finally {
            client.close()
        }
    }
}
