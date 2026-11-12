package com.kumhoht.sedaecha.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kumhoht.sedaecha.core.Level
import com.kumhoht.sedaecha.core.ThemePack
import com.kumhoht.sedaecha.core.WordMode
import com.kumhoht.sedaecha.core.content.WordItem
import com.kumhoht.sedaecha.data.LearnedWordEntity
import com.kumhoht.sedaecha.ui.components.ChoiceButton
import com.kumhoht.sedaecha.ui.components.Pill
import com.kumhoht.sedaecha.ui.components.ProgressBar
import com.kumhoht.sedaecha.ui.components.ScreenHeader
import com.kumhoht.sedaecha.ui.components.SdCard
import com.kumhoht.sedaecha.ui.components.card
import com.kumhoht.sedaecha.ui.theme.Sd
import com.kumhoht.sedaecha.ui.theme.SdType
import java.util.Calendar

/** 말모음 한 줄 */
data class BookEntry(val learned: LearnedWordEntity, val item: WordItem)

private enum class Tab(val label: String) { NOW("요즘말"), THEN("그때말"), REPLAY("다시 듣기") }

/** 내 말모음 · 세대 공감 레벨 · 함께 달린 거리 (그림 6) */
@Composable
fun BookScreen(
    entries: List<BookEntry>,
    km: Double,
    demoMode: Boolean,
    playingKey: String?,
    onPlay: (BookEntry, WordMode) -> Unit,
    onHome: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.NOW) }
    val learned = entries.size
    val level = Level.of(learned)
    val now = entries.filter { it.learned.heardNow }
    val then = entries.filter { it.learned.heardThen && it.item.thenMode != null }
    val replay = entries.filter { it.learned.replay }
    val list = when (tab) { Tab.NOW -> now; Tab.THEN -> then; Tab.REPLAY -> replay }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(36.dp)) {
        ScreenHeader("내 말모음", onHome)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                SdCard(Modifier.fillMaxWidth(), gap = 16.dp) {
                    Text("세대 공감 레벨", style = SdType.Label)
                    Text("Lv.${level.number} ${level.name}", style = SdType.Big)
                    ProgressBar(Level.progress(learned))
                    Text(Level.ALL.joinToString(" → ") { it.name }, style = SdType.Label.copy(lineHeight = SdType.Small.lineHeight))
                    Text("배운 말 ${learned}개" + (Level.remaining(learned)?.let { " · 다음 레벨까지 ${it}개" } ?: ""), style = SdType.Label)
                }
                SdCard(Modifier.fillMaxWidth()) {
                    Text("함께 달린 거리", style = SdType.Label)
                    Text("%.0fkm".format(km), style = SdType.Big)
                    Text(if (demoMode) "데모 모드: 모든 테마 팩이 열려 있어요" else "달린 거리만큼 새 테마 팩이 열려요", style = SdType.Label)
                    ThemePack.ALL.forEach { p ->
                        val open = demoMode || km >= p.unlockKm
                        Row(Modifier.fillMaxWidth().card(Sd.Card2, 16.dp).padding(horizontal = 22.dp, vertical = 18.dp)) {
                            Text(p.name, style = SdType.Label.copy(color = Sd.Text), modifier = Modifier.weight(1f))
                            Text(if (open) "열림" else "${p.unlockKm}km", style = SdType.Label.copy(color = if (open) Sd.Accent else Sd.Sub, fontWeight = if (open) FontWeight.Bold else FontWeight.Normal))
                        }
                    }
                }
            }

            SdCard(Modifier.weight(2f), gap = 24.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Tab.entries.forEach { t ->
                        val n = when (t) { Tab.NOW -> now.size; Tab.THEN -> then.size; Tab.REPLAY -> replay.size }
                        ChoiceButton("${t.label} $n", tab == t, { tab = t }, height = 80.dp)
                    }
                }
                if (list.isEmpty()) {
                    Text(
                        when (tab) {
                            Tab.NOW -> "아직 배운 요즘말이 없어요. 기어를 D로 바꾸면 클래스가 시작돼요."
                            Tab.THEN -> "‘그때말 배우기’나 ‘섞어서 듣기’로 들은 그때말이 여기에 모여요."
                            Tab.REPLAY -> "다시 들을 말이 없어요. 급제동 때 듣던 말이나 복습 퀴즈에서 틀린 말이 여기에 모여요."
                        },
                        style = SdType.Body.copy(color = Sd.Sub),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    list.forEach { e ->
                        val mode = if (tab == Tab.THEN) WordMode.THEN else WordMode.NOW
                        val playing = playingKey == "word:${e.item.id}"
                        Row(
                            Modifier.fillMaxWidth().card(if (playing) Sd.Track else Sd.Card2, 20.dp)
                                .clickable(role = Role.Button, onClickLabel = "다시 듣기") { onPlay(e, mode) }
                                .padding(horizontal = 28.dp, vertical = 22.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (tab == Tab.THEN) e.item.pair else e.item.word, style = SdType.Item)
                                Text("${whenLabel(e.learned.lastHeardAt)} ${e.learned.lastRoute} · " + subtitle(e, tab), style = SdType.Small, maxLines = 1)
                            }
                            when {
                                playing -> Pill("듣는 중", Sd.Accent, Sd.OnAccent)
                                e.learned.replay -> Pill("다시 듣기")
                                e.learned.quizCorrect > 0 -> Text("퀴즈 정답", style = SdType.Small.copy(color = Sd.Accent, fontWeight = FontWeight.Bold))
                            }
                        }
                    }
                }
                Text("항목을 누르면 다시 들려줘요. 다시 듣기 표시는 끝까지 들으면 사라져요.", style = SdType.Small)
            }
        }
    }
}

private fun subtitle(e: BookEntry, tab: Tab): String = when {
    tab == Tab.THEN -> "요즘말 ${e.item.word}"
    e.item.hasPair -> "그때말 ${e.item.pair}"
    else -> e.item.meaning
}

/** 오늘 / 어제 / 11월 3일 */
fun whenLabel(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance()
    val days = ((startOfDay(today) - startOfDay(c)) / 86_400_000L).toInt()
    return when (days) {
        0 -> "오늘"
        1 -> "어제"
        else -> "${c.get(Calendar.MONTH) + 1}월 ${c.get(Calendar.DAY_OF_MONTH)}일"
    }
}

private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
