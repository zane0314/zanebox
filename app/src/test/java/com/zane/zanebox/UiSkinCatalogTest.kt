package com.zane.zanebox

import com.zane.zanebox.data.AppData
import com.zane.zanebox.ui.preferenceSections
import com.zane.zanebox.ui.resolvedUiSkin
import org.junit.Assert.*
import org.junit.Test

class UiSkinCatalogTest {
    @Test fun onlyOriginalAndWhiteUiAreSelectableAndNightModeIsAbsent() {
        val preferences=preferenceSections(AppData()).flatMap { it.second }
        assertEquals(listOf("prism","linear"),preferences.single { it.key=="uiSkin" }.choices.map { it.first })
        assertFalse(preferences.any { it.key=="theme" })
    }
    @Test fun retiredOrUnknownSkinsFallBackWithoutChangingStoredSettings() {
        for(skin in listOf("azure","spectrum","unknown","prism",""))assertEquals("prism",resolvedUiSkin(skin))
        assertEquals("linear",resolvedUiSkin("LINEAR"))
        val old=AppData(settings=mapOf("uiSkin" to "spectrum","theme" to "dark"))
        assertEquals("prism",resolvedUiSkin(old.setting("uiSkin")))
        assertEquals("spectrum",old.setting("uiSkin"));assertEquals("dark",old.setting("theme"))
    }
}
