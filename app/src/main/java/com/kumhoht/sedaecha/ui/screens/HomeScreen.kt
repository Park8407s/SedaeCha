package com.kumhoht.sedaecha.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kumhoht.sedaecha.audio.VoiceStatus
import com.kumhoht.sedaecha.core.ClassPlan
import com.kumhoht.sedaecha.core.Direction
import com.kumhoht.sedaecha.core.Level
import com.kumhoht.sedaecha.core.WordMode
import com.kumhoht.sedaecha.ui.components.ChoiceButton
import com.kumhoht.sedaecha.ui.components.GearIcon
import com.kumhoht.sedaecha.ui.components.Pill
import com.kumhoht.sedaecha.ui.components.ProgressBar
import com.kumhoht.sedaecha.ui.components.RadioIcon
import com.kumhoht.sedaecha.ui.components.SdCard
import com.kumhoht.sedaecha.ui.components.SoftButton
import com.kumhoht.sedaecha.ui.components.card
import com.kumhoht.sedaecha.ui.components.dashed
import com.kumhoht.sedaecha.ui.theme.Sd
import com.kumhoht.sedaecha.ui.theme.SdType

/** 지난 클래스에서 들은 말 (홈 오른쪽 카드) */
data class LastClassUi(val title: String, val words: List<String>, val playKey: String)

/** 출발 전 홈 (그림 2) */
@Composable
fun HomeScreen(
    plan: ClassPlan,
    demoMode: Boolean,
    clockLabel: String,
    direction: Direction,
    onDirection: (Direction) -> Unit,
    learnedCount: Int,
    lastClass: LastClassUi?,
    playingKey: String?,
    onReplayLast: () -> Unit,
    voice: VoiceStatus,
    onOpenBook: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDemo: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(40.dp)) {
        // 앱명 + 서브명 배지 · 심사용 데모 · 설정
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(buildAnnotatedString { append("세대"); withStyle(SpanStyle(color = Sd.Accent)) { append("차") } }, style = SdType.Logo)
            Spacer(Modifier.width(22.dp))
            Row(
                Modifier.height(68.dp).clip(RoundedCornerShape(999.dp)).background(Sd.AccentSoft)
                    .border(2.dp, Sd.AccentLine, RoundedCornerShape(999.dp)).padding(start = 22.dp, end = 30.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 방송 중 표시등
                Box(Modifier.size(28.dp).clip(CircleShape).background(Sd.AccentSoft), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(Sd.Accent))
                }
                RadioIcon(Sd.Badge)
                Text("세대통역 라디오", style = SdType.Title.copy(fontWeight = FontWeight.ExtraBold, color = Sd.Badge, letterSpacing = 1.sp))
            }
            Spacer(Modifier.weight(1f))
            if (onOpenDemo != null) {
                Row(
                    Modifier.height(76.dp).dashed(Sd.Line, 20.dp).clip(RoundedCornerShape(20.dp))
                        .clickable(role = Role.Button, onClick = onOpenDemo).padding(horizontal = 28.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Pill("심사용", Sd.Sub, Sd.Bg)
                    Text("가상 주행으로 들어 보기", style = SdType.Label.copy(color = Sd.Text))
                }
                Spacer(Modifier.width(16.dp))
            }
            Box(
                Modifier.size(76.dp).card(Sd.Card, 20.dp).clickable(role = Role.Button, onClickLabel = "설정", onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) { GearIcon(Sd.Text) }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            // 오늘의 클래스
            SdCard(Modifier.weight(2f), padding = PaddingValues(48.dp), gap = 32.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("오늘의 클래스 · $clockLabel ${plan.route}", style = SdType.Label.copy(fontSize = 26.sp))
                    val head = if (demoMode) "데모 모드에 맞춰" else "예상 주행 ${plan.tripMin.toInt()}분에 맞춰"
                    Text("$head\n오늘의 ${modeWord(plan.direction)} ${plan.words.size}개를 준비했어요", style = SdType.Hero)
                    Text(
                        when {
                            demoMode -> "데모 모드: 말 3개 + 덤 코너 고정, 정체 판단 20초 (설정에서 기본 모드로 바꿀 수 있어요)"
                            plan.tripFromHistory -> "최근 같은 시간대 주행 시간을 기준으로 편성 (실차: 내비 경로 예상 시간)"
                            else -> "주행 기록이 없어 15분 기준으로 편성 (기록이 쌓이면 같은 시간대 평균으로)"
                        },
                        style = SdType.Label,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    plan.words.forEachIndexed { i, w ->
                        Row(
                            Modifier.fillMaxWidth().card(Sd.Card2, 20.dp).padding(horizontal = 28.dp, vertical = 24.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            Text("%02d".format(i + 1), style = SdType.Label.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(64.dp))
                            val then = w.mode == WordMode.THEN
                            Text(if (then) w.item.pair else w.item.word, style = SdType.Item, modifier = Modifier.weight(1f))
                            if (i < plan.carryOverCount) Pill("지난번 남은 말", Sd.Card, Sd.Sub)
                            Text(
                                when {
                                    then -> "요즘말 · ${w.item.word}"
                                    w.item.hasPair -> "그때말 짝 · ${w.item.pair}"
                                    else -> w.item.meaning
                                },
                                style = SdType.Label.copy(color = Sd.Blue), maxLines = 1,
                            )
                        }
                    }
                    if (plan.words.isEmpty()) Text("열린 테마 팩의 말을 모두 배웠어요. 다시 듣기 말부터 편성돼요.", style = SdType.Label)
                    plan.bonus?.let { b ->
                        Row(
                            Modifier.fillMaxWidth().dashed(Sd.Line, 20.dp).padding(horizontal = 28.dp, vertical = 22.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            Text("덤", style = SdType.Label.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(64.dp))
                            Text("요즘 문화 한 스푼 · ${b.title}", style = SdType.Body.copy(fontSize = 28.sp, fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f), maxLines = 1)
                            Text("정체 구간에서 재생", style = SdType.Small)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("배우는 방향", style = SdType.Label)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Direction.entries.forEach { d -> ChoiceButton(d.label, d == direction, { onDirection(d) }, Modifier.weight(1f)) }
                    }
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                // 세대 공감 레벨 → 내 말모음
                val level = Level.of(learnedCount)
                SdCard(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "내 말모음", onClick = onOpenBook)) {
                    Text("세대 공감 레벨", style = SdType.Label)
                    Text("Lv.${level.number} ${level.name}", style = SdType.Big.copy(fontSize = 44.sp))
                    ProgressBar(Level.progress(learnedCount))
                    val left = Level.remaining(learnedCount)
                    Text("배운 말 ${learnedCount}개" + (left?.let { " · 다음 레벨까지 ${it}개" } ?: " · 최고 레벨"), style = SdType.Label)
                }
                SdCard(Modifier.fillMaxWidth()) {
                    Text(lastClass?.title ?: "지난 클래스에서 들은 말", style = SdType.Label)
                    Text(lastClass?.words?.joinToString(" · ")?.ifBlank { null } ?: "아직 들은 말이 없어요", style = SdType.Item)
                    val playing = lastClass != null && playingKey == lastClass.playKey
                    SoftButton(if (playing) "듣는 중 · 멈추기" else "다시 듣기", onReplayLast, Modifier.fillMaxWidth(), enabled = lastClass != null && lastClass.words.isNotEmpty(), active = playing)
                    Spacer(Modifier.height(8.dp))
                    if (!voice.usable && voice != VoiceStatus.CHECKING) {
                        Text(
                            "한국어 음성(${voice.label})이 없어 클래스를 시작할 수 없어요.\n설정 › 음성 상태에서 음성 데이터를 설치해 주세요.",
                            Modifier.fillMaxWidth().card(Sd.Card2, 20.dp, Sd.Warn).padding(24.dp),
                            style = SdType.Body.copy(color = Sd.Warn),
                        )
                    } else {
                        Text(
                            buildAnnotatedString {
                                append("기어를 "); withStyle(SpanStyle(color = Sd.Accent, fontWeight = FontWeight.Bold)) { append("D") }
                                append("로 바꾸면\n화면이 내려가고 클래스가 시작돼요")
                            },
                            Modifier.fillMaxWidth().card(Sd.Card2, 20.dp).padding(24.dp),
                            style = SdType.Body,
                        )
                    }
                }
            }
        }
    }
}

private fun modeWord(d: Direction) = when (d) { Direction.NOW -> "요즘말"; Direction.THEN -> "그때말"; Direction.MIX -> "말" }
