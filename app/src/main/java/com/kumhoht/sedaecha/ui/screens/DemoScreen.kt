package com.kumhoht.sedaecha.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kumhoht.sedaecha.core.DemoDriveLog
import com.kumhoht.sedaecha.core.Turn
import com.kumhoht.sedaecha.demo.DemoController
import com.kumhoht.sedaecha.demo.DemoScene
import com.kumhoht.sedaecha.demo.DemoState
import com.kumhoht.sedaecha.ui.components.OutlineButton
import com.kumhoht.sedaecha.ui.components.Pill
import com.kumhoht.sedaecha.ui.components.PrimaryButton
import com.kumhoht.sedaecha.ui.components.SdCard
import com.kumhoht.sedaecha.ui.components.SoftButton
import com.kumhoht.sedaecha.ui.components.card
import com.kumhoht.sedaecha.ui.theme.Sd
import com.kumhoht.sedaecha.ui.theme.SdType

/** 심사용 데모: 가상 주행으로 클래스 들어 보기 (그림 7, 출품 빌드 전용) */
@Composable
fun DemoScreen(
    state: DemoState,
    log: DemoDriveLog,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onJump: (DemoScene) -> Unit,
    onHome: () -> Unit,
) {
    val dur = log.durationSec
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("심사용 데모 · 출품 빌드 전용", Sd.Sub, Sd.Bg)
                    Text("주차 중에만 표시 · 데모 기록은 내 말모음에 저장되지 않음", style = SdType.Small)
                }
                Text("가상 주행으로 클래스 들어 보기", style = SdType.Hero.copy(fontWeight = FontWeight.Black))
                Text(
                    "저장된 17분 출근길 기록을 ${dur / 60}분 ${dur % 60}초로 압축해 재생합니다. 클래스가 주행 데이터에 따라 멈추고 퀴즈를 내는 과정을 확인할 수 있어요.",
                    style = SdType.Label,
                )
            }
            Spacer(Modifier.width(24.dp))
            OutlineButton("홈으로", onHome, height = 76.dp)
        }

        // 진행 바
        SdCard(Modifier.fillMaxWidth(), radius = 28.dp, padding = PaddingValues(horizontal = 40.dp, vertical = 32.dp), gap = 20.dp) {
            Row(Modifier.fillMaxWidth()) {
                Text("가상 출근길 · 15.0km", style = SdType.Small, modifier = Modifier.weight(1f))
                Text("${clock(state.positionSec.toInt())} / ${clock(dur)}", style = SdType.Small.copy(color = Sd.Text, fontWeight = FontWeight.Bold))
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(120.dp)) {
                val w = maxWidth
                val at = state.positionSec / dur
                Box(Modifier.fillMaxWidth().height(14.dp).offset(y = 70.dp).clip(RoundedCornerShape(7.dp)).background(Sd.Track))
                Box(Modifier.fillMaxWidth(at.coerceIn(0f, 1f)).height(14.dp).offset(y = 70.dp).clip(RoundedCornerShape(7.dp)).background(Sd.Accent))
                Text("출발", style = SdType.Tiny)
                Text("도착", style = SdType.Tiny, modifier = Modifier.align(Alignment.TopEnd))
                log.markers.forEach { m ->
                    val x = w * (m.t.toFloat() / dur)
                    val passed = state.positionSec >= m.t
                    val now = state.positionSec >= m.t && state.positionSec < m.t + 8
                    val c = if (now) Sd.Blue else if (passed) Sd.Text else Sd.Sub
                    Text(markerLabel(m.type), style = SdType.Tiny.copy(color = c, fontWeight = if (now) FontWeight.Bold else FontWeight.Normal), modifier = Modifier.offset(x = (x - 30.dp).coerceAtLeast(40.dp), y = 0.dp))
                    Box(Modifier.offset(x = x, y = 40.dp).width(4.dp).height(58.dp).background(c))
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            val s = state.status
            SdCard(Modifier.weight(2f), radius = 28.dp, padding = PaddingValues(horizontal = 40.dp, vertical = 36.dp), gap = 22.dp) {
                Text("지금 일어나는 일", style = SdType.Small)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Gauge("차량 속도", "%.0fkm/h".format(state.signal.speedKmh), false, Modifier.weight(1f))
                    Gauge("기어", state.signal.gear.name, false, Modifier.weight(1f))
                    val turn = when (state.signal.turn) { Turn.LEFT -> "좌측 켜짐"; Turn.RIGHT -> "우측 켜짐"; Turn.OFF -> "꺼짐" }
                    Gauge("방향지시등", turn, state.signal.turn != Turn.OFF, Modifier.weight(1f))
                }
                val head = if (state.finished) "도착 · 도착 화면을 샘플로 보여 줘요" else if (!state.running) "처음부터 또는 장면 바로 가기를 눌러 시작하세요" else DemoController.headline(s)
                Text(head, style = SdType.Title.copy(color = if (s?.hold != null && state.running) Sd.Blue else Sd.Text))
                if (state.running) Text(DemoController.detail(s), style = SdType.Label.copy(lineHeight = 36.sp))
                if (state.running && s != null) Text("배운 말 ${s.heardCount}개 · 신호 대기 퀴즈 ${s.quizzes}문제", style = SdType.Small)
            }
            SdCard(Modifier.weight(1f), radius = 28.dp, padding = PaddingValues(horizontal = 32.dp, vertical = 36.dp), gap = 14.dp) {
                Text("장면 바로 가기", style = SdType.Small)
                DemoScene.entries.forEach { sc -> SoftButton(sc.label, { onJump(sc) }, Modifier.fillMaxWidth(), height = 72.dp) }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("직접 운전으로 확인하려면 설정 › 클래스 편성에서 ‘데모 모드’를 켜고 Virtual Console 로 기어를 D 로 바꾸세요", style = SdType.Small, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            OutlineButton("처음부터", onStart)
            Spacer(Modifier.width(16.dp))
            PrimaryButton(if (state.paused) "다시 재생" else "일시 정지", onPause, enabled = state.running)
        }
    }
}

@Composable
private fun Gauge(label: String, value: String, highlight: Boolean, modifier: Modifier) {
    Column(
        modifier.card(Sd.Card2, 18.dp, if (highlight) Sd.Blue else null, 3.dp).padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = SdType.Tiny)
        Text(value, style = SdType.Title.copy(fontSize = 36.sp, color = if (highlight) Sd.Blue else Sd.Text))
    }
}

private fun markerLabel(type: String) = when (type) {
    "stop_quiz" -> "신호 대기"
    "turn_signal" -> "방향지시등"
    "congestion" -> "정체"
    "hard_brake" -> "급제동"
    else -> type
}

private fun clock(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)
