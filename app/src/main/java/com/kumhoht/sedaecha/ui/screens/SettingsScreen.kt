package com.kumhoht.sedaecha.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kumhoht.sedaecha.audio.VoiceStatus
import com.kumhoht.sedaecha.core.Rules
import com.kumhoht.sedaecha.ui.components.ChoiceButton
import com.kumhoht.sedaecha.ui.components.ConfirmDialog
import com.kumhoht.sedaecha.ui.components.OutlineButton
import com.kumhoht.sedaecha.ui.components.ScreenHeader
import com.kumhoht.sedaecha.ui.components.SdCard
import com.kumhoht.sedaecha.ui.components.SoftButton
import com.kumhoht.sedaecha.ui.components.card
import com.kumhoht.sedaecha.ui.theme.Sd
import com.kumhoht.sedaecha.ui.theme.SdType

/** 설정 (그림 8) */
@Composable
fun SettingsScreen(
    demoMode: Boolean,
    onDemoMode: (Boolean) -> Unit,
    speechRate: Float,
    onSpeechRate: (Float) -> Unit,
    previewing: Boolean,
    onPreview: () -> Unit,
    voice: VoiceStatus,
    onInstallVoice: () -> Unit,
    onRecheckVoice: () -> Unit,
    learnedCount: Int,
    km: Double,
    onReset: () -> Unit,
    onShutdown: () -> Unit,
    versionLabel: String,
    onHome: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    val pad = PaddingValues(horizontal = 40.dp, vertical = 36.dp)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        ScreenHeader("설정", onHome)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                SdCard(Modifier.fillMaxWidth(), radius = 28.dp, padding = pad) {
                    Text("클래스 편성", style = SdType.Title.copy(fontSize = 32.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ChoiceButton("기본 모드", !demoMode, { onDemoMode(false) }, Modifier.weight(1f), height = 84.dp)
                        ChoiceButton("데모 모드", demoMode, { onDemoMode(true) }, Modifier.weight(1f), height = 84.dp)
                    }
                    Text(
                        if (demoMode) "데모 모드: 정체 판단 20초, 말 3개 + 덤 코너 고정, 테마 팩 모두 열림. 출품 빌드는 데모 모드가 기본이에요."
                        else "기본 모드: 예상 주행 시간에 맞춰 말 2·3·5개, 정체 판단 1분, 테마 팩은 달린 거리만큼 열려요.",
                        style = SdType.Small,
                    )
                }
                SdCard(Modifier.fillMaxWidth(), radius = 28.dp, padding = pad) {
                    Text("말하기 속도", style = SdType.Title.copy(fontSize = 32.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Rules.SPEECH_RATES.forEach { (label, rate) ->
                            ChoiceButton(label, kotlin.math.abs(speechRate - rate) < 0.01f, { onSpeechRate(rate) }, Modifier.weight(1f), height = 84.dp)
                        }
                    }
                    SoftButton(if (previewing) "듣는 중 · 멈추기" else "미리 듣기", onPreview, Modifier.fillMaxWidth(), height = 76.dp, active = previewing)
                    Text("60대 이상 운전자도 편하게 들을 수 있게 천천히 읽을 수 있어요.", style = SdType.Small)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                SdCard(Modifier.fillMaxWidth(), radius = 28.dp, padding = pad, gap = 14.dp) {
                    Text("음성 상태", style = SdType.Title.copy(fontSize = 32.sp))
                    Row(Modifier.fillMaxWidth().card(Sd.Card2, 18.dp).padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("한국어 음성", style = SdType.Label.copy(color = Sd.Text), modifier = Modifier.weight(1f))
                        Text(voice.label, style = SdType.Label.copy(color = if (voice.usable) Sd.Blue else Sd.Warn, fontWeight = FontWeight.Bold))
                    }
                    if (!voice.usable && voice != VoiceStatus.CHECKING) {
                        Text("한국어 음성이 없으면 클래스를 시작하지 않아요. 음성 데이터를 설치한 뒤 다시 확인해 주세요.", style = SdType.Small.copy(color = Sd.Warn))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            SoftButton("음성 데이터 설치", onInstallVoice, Modifier.weight(1f), height = 76.dp)
                            SoftButton("다시 확인", onRecheckVoice, Modifier.weight(1f), height = 76.dp)
                        }
                    } else {
                        Text(
                            if (voice == VoiceStatus.FILES) "이 기기에 한국어 TTS 가 없어, 같은 음성 스크립트를 미리 변환해 둔 내장 음성 파일로 읽어요."
                            else "안드로이드 기본 TextToSpeech(ko-KR)로 콘텐츠 팩의 음성 스크립트를 앱 안에서 바로 읽어요.",
                            style = SdType.Small,
                        )
                    }
                }
                SdCard(Modifier.fillMaxWidth(), radius = 28.dp, padding = pad, gap = 14.dp) {
                    Text("학습 기록", style = SdType.Title.copy(fontSize = 32.sp))
                    Text("배운 말 ${learnedCount}개 · 함께 달린 거리 %.0fkm".format(km), style = SdType.Label)
                    OutlineButton("학습 기록 초기화", { confirmReset = true }, Modifier.fillMaxWidth(), height = 76.dp)
                    Text("학습 기록은 차량에만 저장돼요", style = SdType.Small)
                }
                SdCard(Modifier.fillMaxWidth(), radius = 28.dp, padding = pad, gap = 14.dp) {
                    Text("앱 끄기", style = SdType.Title.copy(fontSize = 32.sp))
                    Text("끄면 다시 켜기 전까지 운전해도 클래스가 시작되지 않아요.", style = SdType.Small)
                    OutlineButton("세대차 끄기", { confirmOff = true }, Modifier.fillMaxWidth(), height = 76.dp, color = Sd.Blue, borderColor = Sd.Blue, bold = true)
                    Text(versionLabel, style = SdType.Tiny)
                }
            }
        }
    }

    if (confirmReset) ConfirmDialog(
        "학습 기록을 지울까요?", "배운 말 ${learnedCount}개, 세대 공감 레벨, 함께 달린 거리, 남은 말이 모두 지워져요. 되돌릴 수 없어요.", "지우기",
        onConfirm = { confirmReset = false; onReset() }, onCancel = { confirmReset = false },
    )
    if (confirmOff) ConfirmDialog(
        "세대차를 끌까요?", "다시 켜기 전까지 운전해도 클래스가 시작되지 않아요.", "끄기",
        onConfirm = { confirmOff = false; onShutdown() }, onCancel = { confirmOff = false },
    )
}

