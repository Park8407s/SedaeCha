package com.kumhoht.sedaecha.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kumhoht.sedaecha.Arrival
import com.kumhoht.sedaecha.core.PlannedWord
import com.kumhoht.sedaecha.core.Review
import com.kumhoht.sedaecha.core.WordMode
import com.kumhoht.sedaecha.ui.components.Divider
import com.kumhoht.sedaecha.ui.components.OutlineButton
import com.kumhoht.sedaecha.ui.components.Pill
import com.kumhoht.sedaecha.ui.components.PrimaryButton
import com.kumhoht.sedaecha.ui.components.SdCard
import com.kumhoht.sedaecha.ui.components.SoftButton
import com.kumhoht.sedaecha.ui.theme.Sd
import com.kumhoht.sedaecha.ui.theme.SdType
import java.text.SimpleDateFormat
import java.util.Locale

/** 도착 화면: 오늘 배운 말 (그림 4) */
@Composable
fun ArrivalScreen(
    arrival: Arrival,
    /** 지금 다시 듣기 표시가 남아 있는 말 (복습 퀴즈·다시 듣기로 해제되면 빠진다) */
    replayIds: Set<String>,
    playingKey: String?,
    onReplay: (PlannedWord) -> Unit,
    onQuiz: () -> Unit,
    onBook: () -> Unit,
    onHome: () -> Unit,
) {
    val s = arrival.summary
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(36.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (arrival.sample) Pill("샘플 · 심사용 데모 결과 (저장되지 않음)", Sd.Sub, Sd.Bg)
                    Text(
                        "${SimpleDateFormat("HH:mm", Locale.KOREA).format(arrival.endedAtWall)} 도착 · ${s.plan.route} ${arrival.minutes}분 · %.1fkm".format(arrival.km),
                        style = SdType.Label.copy(fontSize = 26.sp),
                    )
                }
                Text("오늘 ${s.plan.route}에 배운 말 ${s.heard.size}개", style = SdType.Display)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("신호 대기 퀴즈", "${s.quizzes}문제 들음")
                Stat("자동 멈춤", "깜빡이 ${s.turnStops} · 급제동 ${s.hardStops}")
            }
        }

        // 말 카드 (3개씩)
        s.heard.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                row.forEach { w -> WordCard(w, w.item.id in s.replay, w.item.id in replayIds, playingKey == "word:${w.item.id}", { onReplay(w) }, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (s.unheard.isNotEmpty()) {
            Text("끝까지 못 들은 말 ${s.unheard.size}개(${s.unheard.joinToString(" · ") { it.item.word }})는 다음 운행 첫 순서로 이어 가요.", style = SdType.Label)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            SdCard(Modifier.weight(2f), color = Sd.Card2, radius = 28.dp, padding = PaddingValues(horizontal = 40.dp, vertical = 32.dp), gap = 10.dp) {
                Text("오늘의 써먹기 미션", style = SdType.Small)
                Text(Review.mission(s) ?: "", style = SdType.Title)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryButton("복습 퀴즈 풀기", onQuiz, Modifier.fillMaxWidth(), height = 96.dp)
                OutlineButton(if (arrival.sample) "홈으로" else "내 말모음에 저장됨", if (arrival.sample) onHome else onBook, Modifier.fillMaxWidth(), height = 96.dp)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    SdCard(radius = 20.dp, padding = PaddingValues(horizontal = 26.dp, vertical = 18.dp), gap = 4.dp) {
        Text(label, style = SdType.Tiny)
        Text(value, style = SdType.Item)
    }
}

@Composable
private fun WordCard(w: PlannedWord, hardCut: Boolean, replay: Boolean, playing: Boolean, onReplay: () -> Unit, modifier: Modifier) {
    val then = w.mode == WordMode.THEN
    SdCard(modifier, gap = 20.dp, border = if (replay) Sd.Blue else null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (then) "그때말" else "요즘말", style = SdType.Small.copy(color = if (then) Sd.Blue else Sd.Accent, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            if (hardCut) Pill("급제동 때 듣던 말")
        }
        Text(if (then) w.item.pair else w.item.word, style = SdType.WordBig)
        Text(
            if (then) w.item.pairMeaning else "${w.item.meaning}. ${w.item.origin}",
            style = SdType.Body,
        )
        Divider()
        if (replay) {
            SoftButton(if (playing) "듣는 중 · 멈추기" else "다시 듣기", onReplay, Modifier.fillMaxWidth(), height = 76.dp, active = true)
        } else if (then) {
            Text("요즘은 이렇게", style = SdType.Small.copy(color = Sd.Accent, fontWeight = FontWeight.Bold))
            Text(w.item.word, style = SdType.Item)
        } else if (w.item.hasPair) {
            Text("그때는 이렇게", style = SdType.Small.copy(color = Sd.Blue, fontWeight = FontWeight.Bold))
            Text(w.item.pair, style = SdType.Item)
        } else {
            Text("예문", style = SdType.Small.copy(color = Sd.Blue, fontWeight = FontWeight.Bold))
            Text(w.item.example, style = SdType.Body, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}
