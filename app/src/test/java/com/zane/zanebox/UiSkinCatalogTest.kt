package com.zane.zanebox

import com.zane.zanebox.data.AppData
import com.zane.zanebox.ui.preferenceSections
import com.zane.zanebox.ui.resolvedUiSkin
import com.zane.zanebox.ui.launcherAliasForSkin
import com.zane.zanebox.runtime.skinNotificationIcons
import com.zane.zanebox.runtime.skinNotificationIcon
import org.junit.Assert.*
import org.junit.Test

class UiSkinCatalogTest {
    @Test fun fiveChineseSkinsReplaceIndependentLauncherChoice() {
        val preferences=preferenceSections(AppData()).flatMap { it.second }
        val skins=preferences.single { it.key=="uiSkin" }.choices
        assertEquals(listOf("prism","linear","claude","supabase","miro"),skins.map { it.first })
        assertEquals(listOf("棱镜流光","简洁留白","暖纸陶色","深色翡翠","明黄工作台"),skins.map { it.second })
        assertFalse(preferences.any { it.key=="launcherIcon" })
        assertFalse(preferences.any { it.key=="theme" })
        for((id,name) in skins) {
            assertEquals(id,resolvedUiSkin(id.uppercase()))
            for(language in listOf("en","zh-TW","ja","ko"))assertEquals(name,com.zane.zanebox.ui.translateText(name,language))
        }
    }
    @Test fun everySkinHasOneDistinctLauncherAlias() {
        val choices=preferenceSections(AppData()).flatMap { it.second }.single {it.key=="uiSkin"}.choices
        assertEquals(5,choices.map {launcherAliasForSkin(it.first)}.toSet().size)
        assertEquals(choices.map {it.first}.toSet(),skinNotificationIcons.keys)
        assertEquals(5,choices.map {skinNotificationIcon(it.first)}.toSet().size)
        for((skin,_) in choices)assertEquals("launcher.${skin.replaceFirstChar {it.uppercase()}}Alias",launcherAliasForSkin(skin))
        assertEquals("launcher.PrismAlias",launcherAliasForSkin("spectrum"))
    }
    @Test fun retiredOrUnknownSkinsFallBackWithoutChangingStoredSettings() {
        for(skin in listOf("azure","spectrum","unknown","prism",""))assertEquals("prism",resolvedUiSkin(skin))
        assertEquals("linear",resolvedUiSkin("LINEAR"))
        val old=AppData(settings=mapOf("uiSkin" to "spectrum","theme" to "dark"))
        assertEquals("prism",resolvedUiSkin(old.setting("uiSkin")))
        assertEquals("spectrum",old.setting("uiSkin"));assertEquals("dark",old.setting("theme"))
    }
}
