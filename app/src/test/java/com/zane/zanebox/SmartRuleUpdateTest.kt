package com.zane.zanebox

import com.zane.zanebox.config.applySmartRuleUpdate
import com.zane.zanebox.data.AppData
import org.junit.Assert.*
import org.junit.Test

class SmartRuleUpdateTest {
    private fun baseline() = AppData(settings=mapOf("smartUrl.ai" to "https://old.example/rules", "smartRules.ai" to "old", "smartUpdated.ai" to "1"))
    private fun rejected(current:AppData, original:AppData) {
        try { applySmartRuleUpdate(current,"ai",original,"response",now=2);fail("过期响应不应提交") }
        catch(_:IllegalArgumentException) {}
    }
    @Test fun changedSourcePreservesNewRules() {
        val original=baseline()
        rejected(original.copy(settings=original.settings+mapOf("smartUrl.ai" to "https://new.example/rules", "smartRules.ai" to "new")),original)
    }
    @Test fun laterCommitAndLocalEditInvalidateOldResponse() {
        val original=baseline()
        rejected(original.copy(settings=original.settings+("smartUpdated.ai" to "3")),original)
        rejected(original.copy(settings=original.settings+("smartRules.ai" to "manual")),original)
    }
    @Test fun deletedCustomGroupCannotBeResurrected() {
        val original=AppData(settings=mapOf("smartCustom.ai.name" to "custom"))
        rejected(AppData(),original)
    }
    @Test fun unrelatedSettingsArePreservedAndEditorCanSaveNewSource() {
        val original=baseline()
        val current=original.copy(settings=original.settings+("theme" to "dark"))
        val updated=applySmartRuleUpdate(current,"ai",original,"new rules",sourceUrl="https://new.example/rules",now=2)
        assertEquals("dark",updated.setting("theme"))
        assertEquals("https://new.example/rules",updated.setting("smartUrl.ai"))
        assertEquals("new rules",updated.setting("smartRules.ai"))
        assertEquals("2",updated.setting("smartUpdated.ai"))
    }
}
