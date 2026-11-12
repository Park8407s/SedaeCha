package com.kumhoht.sedaecha.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kumhoht.sedaecha.core.ReviewQuestion
import com.kumhoht.sedaecha.ui.components.OutlineButton
import com.kumhoht.sedaecha.ui.components.PrimaryButton
import com.kumhoht.sedaecha.ui.components.ProgressBar
import com.kumhoht.sedaecha.ui.components.SdCard
import com.kumhoht.sedaecha.ui.theme.Sd
import com.kumhoht.sedaecha.ui.theme.SdType

/** 주차 중 복습 퀴즈 (그림 5): 4지선다, 다시 듣기 말 먼저. 답할 때마다 결과를 저장한다 (샘플은 저장 안 함) */
@Composable
fun QuizScreen(
    questions: List<ReviewQuestion>,
    onAnswer: (ReviewQuestion, Boolean) -> Unit,
    onBackToArrival: () -> Unit,
    onFinish: () -> Unit,
    sample: Boolean,
) {
    var index by remember(questions) { mutableIntStateOf(0) }
    var picked by remember(questions) { mutableStateOf<Int?>(null) }
    val q = questions.getOrNull(index) ?: return
    val last = index == questions.lastIndex

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(36.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("복습 퀴즈 · 주차 중에만 표시" + if (sample) " · 샘플 (저장되지 않음)" else "", style = SdType.Label.copy(fontSize = 26.sp), modifier = Modifier.weight(1f))
            Text("${index + 1} / ${questions.size}", style = SdType.Label.copy(fontSize = 26.sp, color = Sd.Text, fontWeight = FontWeight.Bold))
        }
        ProgressBar((index + if (picked != null) 1 else 0).toFloat() / questions.size, height = 12.dp)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(q.note, style = SdType.Label.copy(fontSize = 26.sp))
            Text(q.prompt, style = SdType.Display.copy(fontSize = 56.sp))
        }

        q.options.chunked(2).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEachIndexed { c, label ->
                    val i = r * 2 + c
                    val border = when {
                        picked != null && i == q.correctIndex -> Sd.Accent
                        picked == i -> Sd.Blue
                        else -> Sd.Track
                    }
                    OutlinedButton(
                        onClick = {
                            if (picked == null) { picked = i; onAnswer(q, i == q.correctIndex) }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 120.dp),
                        shape = RoundedCornerShape(26.dp),
                        border = BorderStroke(3.dp, border),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Sd.Card, contentColor = Sd.Text),
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
                    ) { Text(label, style = SdType.Body.copy(fontSize = 30.sp, fontWeight = FontWeight.Medium), textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth()) }
                }
            }
        }

        val p = picked
        if (p != null) {
            val right = p == q.correctIndex
            SdCard(Modifier.fillMaxWidth(), radius = 28.dp, padding = PaddingValues(horizontal = 40.dp, vertical = 32.dp), gap = 10.dp) {
                Text(if (right) "정답이에요! 세대 공감 +1" else "아쉬워요, 다시 듣기로 표시해 둘게요", style = SdType.Title.copy(color = if (right) Sd.Accent else Sd.Blue))
                Text(q.explain, style = SdType.Body)
            }
        } else {
            SdCard(Modifier.fillMaxWidth(), radius = 28.dp, padding = PaddingValues(horizontal = 40.dp, vertical = 32.dp)) {
                Text("보기를 눌러 답을 골라 보세요.", style = SdType.Body.copy(color = Sd.Sub))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.weight(1f))
            OutlineButton("도착 화면으로", onBackToArrival)
            when {
                p == null -> Unit
                !last -> PrimaryButton("다음 문제", { index++; picked = null })
                else -> PrimaryButton(if (sample) "말모음 보기" else "결과 저장하고 말모음 보기", onFinish)
            }
        }
    }
}
