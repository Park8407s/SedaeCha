package com.kumhoht.sedaecha.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 화면 시안(docs/reference/design 의 .dc.html)의 색.
 * 시안 크기 1682×1210 은 Pleos 앱 창(160dpi)과 같아서 시안 1px = 1dp = 1sp 로 옮긴다.
 */
object Sd {
    val Bg = Color(0xFF0F141C)
    val Card = Color(0xFF1A212C)
    val Card2 = Color(0xFF232C39)
    val Track = Color(0xFF2E3846)
    val Line = Color(0xFF3A4656)
    val Text = Color(0xFFF3F5F8)
    val Sub = Color(0xFFA9B4C2)
    /** 요즘말·주 버튼 */
    val Accent = Color(0xFFFF9F5A)
    val OnAccent = Color(0xFF1A1206)
    /** 그때말·다시 듣기·멈춤 */
    val Blue = Color(0xFF7CB8FF)
    val Badge = Color(0xFFFFD2B0)
    val AccentSoft = Color(0x1FFF9F5A)
    val AccentLine = Color(0x80FF9F5A)
    val Warn = Color(0xFFFFC94D)
}

/** 글자 스타일 (시안 px = sp). 글꼴은 시스템 기본(Noto Sans CJK) */
object SdType {
    private fun s(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Sd.Text, lineHeight: TextUnit = TextUnit.Unspecified) =
        TextStyle(fontSize = size.sp, fontWeight = weight, color = color, lineHeight = lineHeight)

    val Logo = s(52, FontWeight.Black)
    val Display = s(54, FontWeight.Black)
    val Hero = s(48, FontWeight.Bold, lineHeight = 62.sp)
    val Big = s(46, FontWeight.Black)
    val WordBig = s(52, FontWeight.Black)
    val Title = s(34, FontWeight.Bold)
    val Item = s(32, FontWeight.Bold)
    val Body = s(26, lineHeight = 39.sp)
    val Button = s(28, FontWeight.Bold)
    val ButtonSmall = s(24, FontWeight.Medium)
    val Label = s(24, color = Sd.Sub)
    val Small = s(22, color = Sd.Sub, lineHeight = 34.sp)
    val Tiny = s(20, color = Sd.Sub)
}

@Composable
fun SedaeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Sd.Accent, onPrimary = Sd.OnAccent, background = Sd.Bg, surface = Sd.Card, onSurface = Sd.Text, onBackground = Sd.Text,
        ),
        content = content,
    )
}
