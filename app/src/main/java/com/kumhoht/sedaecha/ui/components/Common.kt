package com.kumhoht.sedaecha.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kumhoht.sedaecha.ui.theme.Sd
import com.kumhoht.sedaecha.ui.theme.SdType

/** 카드 배경 */
fun Modifier.card(color: Color = Sd.Card, radius: Dp = 32.dp, border: Color? = null, width: Dp = 2.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    var m = this.clip(shape).background(color, shape)
    if (border != null) m = m.border(width, border, shape)
    return m
}

/** 점선 테두리 (덤 코너 줄, 심사용 데모 버튼) */
fun Modifier.dashed(color: Color = Sd.Line, radius: Dp = 20.dp): Modifier = this.then(
    Modifier.drawDashed(color, radius)
)

private fun Modifier.drawDashed(color: Color, radius: Dp): Modifier = drawBehind {
    val r = radius.toPx()
    drawRoundRect(
        color = color, cornerRadius = CornerRadius(r, r),
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
        topLeft = Offset(1f, 1f), size = Size(size.width - 2f, size.height - 2f),
    )
}

@Composable
fun SdCard(modifier: Modifier = Modifier, color: Color = Sd.Card, radius: Dp = 32.dp, padding: PaddingValues = PaddingValues(40.dp), gap: Dp = 18.dp, border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.card(color, radius, border, 3.dp).padding(padding), verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

/** 주 버튼 (주황 배경) */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 88.dp, enabled: Boolean = true, color: Color = Sd.Accent, textColor: Color = Sd.OnAccent) {
    Button(
        onClick, modifier.height(height), enabled = enabled, shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = textColor, disabledContainerColor = Sd.Card2, disabledContentColor = Sd.Sub),
        contentPadding = PaddingValues(horizontal = 32.dp),
    ) { Text(text, style = SdType.Button.copy(color = if (enabled) textColor else Sd.Sub)) }
}

/** 테두리 버튼 */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 88.dp, color: Color = Sd.Text, borderColor: Color = Sd.Line, enabled: Boolean = true, bold: Boolean = false) {
    OutlinedButton(
        onClick, modifier.height(height), enabled = enabled, shape = RoundedCornerShape(22.dp), border = BorderStroke(2.dp, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color), contentPadding = PaddingValues(horizontal = 28.dp),
    ) { Text(text, style = SdType.ButtonSmall.copy(fontSize = 26.sp, color = if (enabled) color else Sd.Sub, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)) }
}

/** 카드 안 회색 버튼 */
@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 80.dp, enabled: Boolean = true, active: Boolean = false) {
    Button(
        onClick, modifier.height(height), enabled = enabled, shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Sd.Blue else Sd.Card2, contentColor = if (active) Sd.Bg else Sd.Text,
            disabledContainerColor = Sd.Card2, disabledContentColor = Sd.Sub,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) { Text(text, style = SdType.ButtonSmall.copy(fontSize = 26.sp, color = if (!enabled) Sd.Sub else if (active) Sd.Bg else Sd.Text)) }
}

/** 고르기 버튼 (선택되면 주황) */
@Composable
fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 88.dp) {
    if (selected) PrimaryButton(text, onClick, modifier, height)
    else OutlineButton(text, onClick, modifier, height)
}

/** 작은 알약 표시 ("다시 듣기", "심사용") */
@Composable
fun Pill(text: String, color: Color = Sd.Blue, textColor: Color = Sd.Bg, modifier: Modifier = Modifier) {
    Text(
        text, modifier.clip(RoundedCornerShape(999.dp)).background(color).padding(horizontal = 16.dp, vertical = 6.dp),
        style = SdType.Tiny.copy(color = textColor, fontWeight = FontWeight.Bold),
    )
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 16.dp, color: Color = Sd.Accent) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(Sd.Track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

@Composable
fun Divider() { Box(Modifier.fillMaxWidth().height(2.dp).background(Sd.Track)) }

/** 라디오 전파 아이콘 (서브명 배지) */
@Composable
fun RadioIcon(color: Color, size: Dp = 40.dp) {
    Canvas(Modifier.size(size)) {
        val c = center
        val u = this.size.minDimension / 24f
        drawCircle(color, radius = 2f * u, center = c, style = Stroke(2f * u))
        listOf(6f, 10f).forEach { r ->
            val rr = r * u
            drawArc(color, -45f, 90f, false, topLeft = Offset(c.x - rr, c.y - rr), size = androidx.compose.ui.geometry.Size(2 * rr, 2 * rr), style = Stroke(2f * u, cap = StrokeCap.Round))
            drawArc(color, 135f, 90f, false, topLeft = Offset(c.x - rr, c.y - rr), size = androidx.compose.ui.geometry.Size(2 * rr, 2 * rr), style = Stroke(2f * u, cap = StrokeCap.Round))
        }
    }
}

/** 톱니바퀴 아이콘 (설정) */
@Composable
fun GearIcon(color: Color, size: Dp = 34.dp) {
    Canvas(Modifier.size(size)) {
        val c = center
        val u = this.size.minDimension / 24f
        drawCircle(color, radius = 3f * u, center = c, style = Stroke(2f * u))
        drawCircle(color, radius = 7.5f * u, center = c, style = Stroke(2f * u))
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val x1 = c.x + (7.5f * u) * kotlin.math.cos(a).toFloat(); val y1 = c.y + (7.5f * u) * kotlin.math.sin(a).toFloat()
            val x2 = c.x + (10.5f * u) * kotlin.math.cos(a).toFloat(); val y2 = c.y + (10.5f * u) * kotlin.math.sin(a).toFloat()
            drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2.6f * u, cap = StrokeCap.Round)
        }
    }
}

/** 화면 제목 줄 + 오른쪽 '홈으로' */
@Composable
fun ScreenHeader(title: String, onHome: (() -> Unit)?, right: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = SdType.Display, modifier = Modifier.weight(1f))
        right()
        if (onHome != null) { Spacer(Modifier.width(16.dp)); OutlineButton("홈으로", onHome, height = 76.dp) }
    }
}

/** 되돌릴 수 없는 동작(초기화·끄기) 전에 한 번 더 묻는 창 */
@Composable
fun ConfirmDialog(title: String, body: String, confirmText: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Dialog(onCancel, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(760.dp).card(Sd.Bg, 28.dp, Sd.Line).padding(44.dp)) {
            Text(title, style = SdType.Title)
            Spacer(Modifier.height(18.dp))
            Text(body, style = SdType.Body.copy(color = Sd.Sub))
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlineButton("취소", onCancel, Modifier.weight(1f), height = 76.dp)
                PrimaryButton(confirmText, onConfirm, Modifier.weight(1f), height = 76.dp)
            }
        }
    }
}
