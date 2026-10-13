package com.zane.zanebox.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
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

private val LinearLight = ZaneSkinPalette(
    name = "linear",
    primary = Color(0xFF1F5FD0), primaryDark = Color(0xFF1F5FD0),
    background = Color(0xFFF5F6F8), surface = Color.White, surfaceElevated = Color(0xFFEEF0F4),
    onSurface = Color(0xFF1D2733), secondaryText = Color(0xFF525E6C), divider = Color(0xFFE1E5EB),
    iconTile = Color(0xFFE8F0FE), selectedSurface = Color(0xFFEEF0F4), success = Color(0xFF187341),
    warning = Color(0xFF9C5700), error = Color(0xFFB3261E),
    backdropStart = Color(0xFFF5F6F8), backdropCenter = Color(0xFFF5F6F8), backdropEnd = Color(0xFFF5F6F8),
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
    val view=LocalView.current
    val context=LocalContext.current
    SideEffect { (context as? android.app.Activity)?.window?.let{configureSystemBars(it,view,false)} }
    val skin=if(resolvedUiSkin(data.setting("uiSkin"))=="linear")LinearLight else PrismLight
    val colors = lightColorScheme(
        primary = skin.primary, onPrimary = Color.White, primaryContainer = skin.iconTile,
        onPrimaryContainer = skin.onSurface, secondary = skin.secondaryText, onSecondary = Color.White,
        background = skin.background, onBackground = skin.onSurface, surface = skin.surface,
        onSurface = skin.onSurface, surfaceVariant = skin.surfaceElevated, onSurfaceVariant = skin.secondaryText,
        error = skin.error, onError = Color.White, outline = skin.divider, outlineVariant = skin.divider,
        surfaceContainer = skin.surfaceElevated,
    ).let { colors ->
        if(skin.name=="linear")colors.copy(
            secondaryContainer=skin.surfaceElevated,onSecondaryContainer=skin.onSurface,
            tertiary=skin.primary,onTertiary=Color.White,tertiaryContainer=skin.iconTile,onTertiaryContainer=skin.onSurface,
            surfaceContainerLowest=Color.White,surfaceContainerLow=skin.surface,
            surfaceContainerHigh=skin.surface,surfaceContainerHighest=skin.surfaceElevated,
        ) else colors
    }
    CompositionLocalProvider(
        LocalZaneSkin provides skin,
    ) {
        MaterialTheme(colorScheme = colors, typography = ZaneTypography, shapes = ZaneShapes.copy(large = androidx.compose.foundation.shape.RoundedCornerShape(if(skin.name=="linear")12.dp else 14.dp))){
            UiLanguageProvider(data, content)
        }
    }
}

/** Uses the original vector backgrounds instead of approximating their gradients. */
@Composable
fun UiBackdrop(modifier: Modifier = Modifier, home:Boolean=true) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val skin=LocalZaneSkin.current
    if(skin.name=="linear") { Canvas(modifier.fillMaxSize()){drawRect(skin.background)}; return }
    val name=if(home) "anybox_home_background" else "skin_prism_window"
    val drawable=androidx.compose.runtime.remember(name,context) {
        val id=context.resources.getIdentifier("zb_ref_${name}","drawable",context.packageName)
        androidx.core.content.ContextCompat.getDrawable(context,id)!!.mutate()
    }
    Canvas(modifier.fillMaxSize()) {
        drawIntoCanvas {canvas->
            drawable.setBounds(0,0,size.width.toInt(),size.height.toInt())
            drawable.draw(canvas.nativeCanvas)
        }
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
