package com.zane.zanebox.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zane.zanebox.data.AppData

/** AnyBox 2.1.9 palette values collapsed into the Compose surface model. */
@Immutable
data class ZaneSkinPalette(
    val name: String,
    val primary: Color,
    val primaryDark: Color,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val onSurface: Color,
    val secondaryText: Color,
    val divider: Color,
    val iconTile: Color,
    val selectedSurface: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val backdropStart: Color,
    val backdropCenter: Color,
    val backdropEnd: Color,
)

private val PrismLight = ZaneSkinPalette(
    name = "prism",
    primary = Color(0xFF176D95), primaryDark = Color(0xFF176D95),
    background = Color(0xFFEAEBF3), surface = Color(0x66FFFFFF), surfaceElevated = Color(0xFFF7F8FA),
    onSurface = Color(0xFF1D2733), secondaryText = Color(0xFF525E6C), divider = Color(0xFFE8EAED),
    iconTile = Color(0xFFE5F4FB), selectedSurface = Color(0x14229ED9), success = Color(0xFF248A3D),
    warning = Color(0xFFF28A00), error = Color(0xFFD70015),
    backdropStart = Color(0xFFEEF2FF), backdropCenter = Color(0xFFE9EDFC), backdropEnd = Color(0xFFE4EAFB),
)

private val PrismDark = PrismLight.copy(
    primary = Color(0xFF4DB8E8), primaryDark = Color(0xFF229ED9),
    background = Color(0xFF08090D), surface = Color(0xFF13141A), surfaceElevated = Color(0xFF1C1E26),
    onSurface = Color(0xFFF1F4F6), secondaryText = Color(0xFF8E9BA8), divider = Color(0xFF26333F),
    iconTile = Color(0xFF17384A), selectedSurface = Color(0x292AABEE), success = Color(0xFF5BD27B),
    warning = Color(0xFFFFB340), error = Color(0xFFFF453A),
    backdropStart = Color(0xFF0A0C12), backdropCenter = Color(0xFF08090D), backdropEnd = Color(0xFF06070A),
)

private val AzureLight = ZaneSkinPalette(
    name = "azure",
    primary = Color(0xFF1F5FD0), primaryDark = Color(0xFF1F5FD0),
    background = Color(0xFFF2F5FA), surface = Color(0xFFFFFFFF), surfaceElevated = Color(0xFFFFFFFF),
    onSurface = Color(0xFF131A24), secondaryText = Color(0xFF5C6776), divider = Color(0xFFECEFF3),
    iconTile = Color(0xFFE8F0FE), selectedSurface = Color(0x142F7BF6), success = Color(0xFF22A45D),
    warning = Color(0xFFF28A00), error = Color(0xFFD70015),
    backdropStart = Color(0xFFF5F8FC), backdropCenter = Color(0xFFF2F5FA), backdropEnd = Color(0xFFEDF2F8),
)

private val AzureDark = AzureLight.copy(
    primary = Color(0xFF5B9BFF), primaryDark = Color(0xFF2F7BF6),
    background = Color(0xFF0B0F16), surface = Color(0xFF151A22), surfaceElevated = Color(0xFF1D232D),
    onSurface = Color(0xFFEEF2F7), secondaryText = Color(0xFF95A1AF), divider = Color(0xFF232B36),
    iconTile = Color(0xFF16304F), selectedSurface = Color(0x295B9BFF), success = Color(0xFF3ECC7F),
    warning = Color(0xFFFFB340), error = Color(0xFFFF453A),
    backdropStart = Color(0xFF0C1119), backdropCenter = Color(0xFF0B0F16), backdropEnd = Color(0xFF080B10),
)

private val SpectrumLight = ZaneSkinPalette(
    name = "spectrum",
    primary = Color(0xFF0B73CF), primaryDark = Color(0xFF07579F),
    background = Color(0xFFDDF6F8), surface = Color(0x1AFFFFFF), surfaceElevated = Color(0x24FFFFFF),
    onSurface = Color(0xFF111A24), secondaryText = Color(0xFF45606E), divider = Color(0x668ABAC5),
    iconTile = Color(0x33FFFFFF), selectedSurface = Color(0x180B73CF), success = Color(0xFF1FA34A),
    warning = Color(0xFFF28A00), error = Color(0xFFD70015),
    backdropStart = Color(0xFFDDF6F8), backdropCenter = Color(0xFFCBEFF3), backdropEnd = Color(0xFFBFEAF1),
)

private val SpectrumDark = SpectrumLight.copy(
    primary = Color(0xFF66B8FF), primaryDark = Color(0xFF3B91D8),
    background = Color(0xFF10272D), surface = Color(0x33233E45), surfaceElevated = Color(0x4D2D4B53),
    onSurface = Color(0xFFF2FAFC), secondaryText = Color(0xFFB7CDD3), divider = Color(0x668BC4CE),
    iconTile = Color(0x263FC0D2), selectedSurface = Color(0x2966B8FF), success = Color(0xFF5BD27B),
    warning = Color(0xFFFFB340), error = Color(0xFFFF453A),
    backdropStart = Color(0xFF10272D), backdropCenter = Color(0xFF183A43), backdropEnd = Color(0xFF10272D),
)

val LocalZaneSkin = compositionLocalOf { PrismLight }

val skinPalette: ZaneSkinPalette
    @Composable get() = LocalZaneSkin.current
val skinPrimary: Color
    @Composable get() = LocalZaneSkin.current.primary
val skinPrimaryDark: Color
    @Composable get() = LocalZaneSkin.current.primaryDark
val skinBackground: Color
    @Composable get() = MaterialTheme.colorScheme.background
val skinSurface: Color
    @Composable get() = MaterialTheme.colorScheme.surface
val skinSurfaceElevated: Color
    @Composable get() = LocalZaneSkin.current.surfaceElevated
val skinOnSurface: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
val skinSecondaryText: Color
    @Composable get() = LocalZaneSkin.current.secondaryText
val skinDivider: Color
    @Composable get() = LocalZaneSkin.current.divider
val skinIconTile: Color
    @Composable get() = LocalZaneSkin.current.iconTile
val skinSelectedSurface: Color
    @Composable get() = LocalZaneSkin.current.selectedSurface
val skinSuccess: Color
    @Composable get() = LocalZaneSkin.current.success
val skinWarning: Color
    @Composable get() = LocalZaneSkin.current.warning
val skinError: Color
    @Composable get() = MaterialTheme.colorScheme.error

private val ZaneShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

private val ZaneTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = FontFamily.SansSerif),
        displayMedium = displayMedium.copy(fontFamily = FontFamily.SansSerif),
        displaySmall = displaySmall.copy(fontFamily = FontFamily.SansSerif),
        headlineLarge = headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        bodySmall = bodySmall.copy(fontFamily = FontFamily.SansSerif),
        labelLarge = labelLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontFamily = FontFamily.SansSerif),
    )
}

@Composable
fun ZaneTheme(data: AppData, content: @Composable () -> Unit) {
    val theme = data.setting("theme", "system").lowercase()
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val view=LocalView.current
    val context=LocalContext.current
    SideEffect { (context as? android.app.Activity)?.window?.let{configureSystemBars(it,view,dark)} }
    val baseSkin = when (data.setting("uiSkin", "prism").lowercase()) {
        "azure" -> if (dark) AzureDark else AzureLight
        "spectrum" -> if (dark) SpectrumDark else SpectrumLight
        else -> if (dark) PrismDark else PrismLight
    }
    val skin = baseSkin
    val colors = if (dark) darkColorScheme(
        primary = skin.primary, onPrimary = skin.background, primaryContainer = skin.primaryDark,
        onPrimaryContainer = skin.onSurface, secondary = skin.secondaryText, onSecondary = skin.background,
        background = skin.background, onBackground = skin.onSurface, surface = skin.surface,
        onSurface = skin.onSurface, surfaceVariant = skin.surfaceElevated, onSurfaceVariant = skin.secondaryText,
        error = skin.error, onError = skin.background, outline = skin.divider, outlineVariant = skin.divider,
    ) else lightColorScheme(
        primary = skin.primary, onPrimary = Color.White, primaryContainer = skin.iconTile,
        onPrimaryContainer = skin.onSurface, secondary = skin.secondaryText, onSecondary = Color.White,
        background = skin.background, onBackground = skin.onSurface, surface = skin.surface,
        onSurface = skin.onSurface, surfaceVariant = skin.surfaceElevated, onSurfaceVariant = skin.secondaryText,
        error = skin.error, onError = Color.White, outline = skin.divider, outlineVariant = skin.divider,
    )
    CompositionLocalProvider(
        LocalZaneSkin provides skin,
    ) {
        MaterialTheme(colorScheme = colors, typography = ZaneTypography, shapes = ZaneShapes.copy(large = androidx.compose.foundation.shape.RoundedCornerShape(when(data.setting("uiSkin","prism")){"azure"->16.dp;"spectrum"->22.dp;else->14.dp}))){
            UiLanguageProvider(data, content)
        }
    }
}

/** Uses the original vector backgrounds instead of approximating their gradients. */
@Composable
fun UiBackdrop(modifier: Modifier = Modifier, home:Boolean=true) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val skin=LocalZaneSkin.current
    val dark=skin.onSurface.red>.7f
    val name=if(home) "anybox_home_background" else "skin_${skin.name}_window"
    val drawable=androidx.compose.runtime.remember(name,dark,context) {
        val id=context.resources.getIdentifier("zb_ref_${name}"+(if(dark)"_dark" else ""),"drawable",context.packageName)
        androidx.core.content.ContextCompat.getDrawable(context,id)!!.mutate()
    }
    Canvas(modifier.fillMaxSize()) {
        drawIntoCanvas {canvas->
            drawable.setBounds(0,0,size.width.toInt(),size.height.toInt())
            drawable.draw(canvas.nativeCanvas)
        }
        if(dark && !home && skin.name!="prism")drawRect(skin.background.copy(alpha=.88f))
    }
}

private val LauncherAliases = mapOf(
    "prism" to "launcher.PrismAlias",
    "pixel" to "launcher.PixelAlias",
    "node_n" to "launcher.NodeNAlias",
    "channel_gate" to "launcher.ChannelGateAlias",
    "confluence" to "launcher.ConfluenceAlias",
    "launch_path" to "launcher.LaunchPathAlias",
)

fun applyLauncherIcon(context:Context,value:String) {
    val selected=value.lowercase().takeIf(LauncherAliases::containsKey) ?:"prism"
    val pm=context.packageManager
    val states=LauncherAliases.map{(key,name)->ComponentName(context.packageName,"${context.packageName}.$name") to if(key==selected)PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED}
    if(states.all{(name,state)->pm.getComponentEnabledSetting(name)==state})return
    if(android.os.Build.VERSION.SDK_INT>=33)pm.setComponentEnabledSettings(states.map{(name,state)->PackageManager.ComponentEnabledSetting(name,state,PackageManager.DONT_KILL_APP)})
    else {
        val previous=states.associate{(name,_)->name to pm.getComponentEnabledSetting(name)}
        try { states.sortedByDescending{it.second==PackageManager.COMPONENT_ENABLED_STATE_ENABLED}.forEach{(name,state)->pm.setComponentEnabledSetting(name,state,PackageManager.DONT_KILL_APP)} }
        catch(e:Exception) {previous.forEach{(name,state)->runCatching{pm.setComponentEnabledSetting(name,state,PackageManager.DONT_KILL_APP)}};throw e}
    }
}

/** The final AnyBox node scene uses a fixed light backdrop, including in night mode. */
@Composable internal fun NativeHomeAppearance(home:Boolean,content:@Composable ()->Unit) {
    if(!home){content();return}
    val view=LocalView.current;val context=LocalContext.current
    SideEffect{(context as? android.app.Activity)?.window?.let{configureSystemBars(it,view,false)}}
    MaterialTheme(colorScheme=MaterialTheme.colorScheme.copy(background=Color(0xFFE9EDFC),surface=Color.White.copy(alpha=.8f),surfaceVariant=Color.White.copy(alpha=.8f),onSurface=Color(0xFF182230),onBackground=Color(0xFF182230),onSurfaceVariant=Color(0xFF5D697A),primary=Color(0xFF0066CC),primaryContainer=Color(0xFFD3E2FF),onPrimaryContainer=Color(0xFF0066CC),outlineVariant=Color(0xFFCBD5E1)),content=content)
}
