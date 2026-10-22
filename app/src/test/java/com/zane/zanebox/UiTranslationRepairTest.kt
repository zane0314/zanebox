package com.zane.zanebox

import com.zane.zanebox.data.AppData
import com.zane.zanebox.ui.*
import org.junit.Assert.*
import org.junit.Test

class UiTranslationRepairTest {
    @Test fun englishSettingsCoverTitlesSummariesAndChoices() {
        val sections=preferenceSections(AppData())
        val labels=sections.map {it.first}+sections.flatMap {(_,preferences)->preferences.flatMap { p->
            listOf(p.title,p.availability)+if(p.key in setOf("uiSkin","appLanguage"))emptyList() else p.choices.map {it.second}
        }}+preferenceAppearance.values.flatMap {listOf(it.title,it.summary)}
        val smartAndSettings=listOf("添加自定义应用组","智能应用路由依赖 VPN 模式。","删除自定义应用组？","添加域名规则","编辑域名规则","暂无应用流量数据","暂无活动连接","更新时强制解析服务器 IP")
        for(label in labels+smartAndSettings)assertFalse("English translation missing: $label",translateText(label,"en").any {it in '\u4e00'..'\u9fff'})
    }

    @Test fun dynamicLabelsTranslateTheTemplateAndPreserveUserValues() {
        val name="设置 %s https://example.test/中文"
        assertEquals("https://example.test/%20%s",translateText("https://example.test/%20%s","en",*emptyArray<Any?>()))
        assertEquals("Current: $name · Test every 10 minutes",translateText("当前：%s · 每 10 分钟测速","en",name))
        assertEquals("Replace the current configuration with $name?",translateText("将用 %s 替换当前配置。","en",name))
        assertEquals("Permanently delete $name? This cannot be undone.",translateText("将永久删除 %s，此操作不可撤销。","en",name))
        assertEquals("用户应用 %s：此规则格式暂不支持 USER-AGENT；该类条目保留但不参与匹配，其余有效规则仍生效",com.zane.zanebox.config.ConfigBuilder.ruleCompatibilityWarnings("用户应用 %s","USER-AGENT,*fixture*").single())
        val warning=com.zane.zanebox.config.ConfigBuilder.ruleCompatibilityWarnings("用户应用 %s","USER-AGENT,*fixture*"){service,type->translateText("%s：此规则格式暂不支持 %s；该类条目保留但不参与匹配，其余有效规则仍生效","en",service,type)}.single()
        assertEquals("用户应用 %s: USER-AGENT rules are not supported. These entries are preserved but excluded from matching; other valid rules remain active.",warning)
        assertEquals("Delete 3 nodes?",translateText("删除 %s 个节点？","en",3))
        assertEquals("2 nodes · Basic group",translateText("%s 个节点 · %s","en",2,translateText("基础分组","en")))
    }
}
