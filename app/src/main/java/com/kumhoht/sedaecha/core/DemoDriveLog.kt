package com.kumhoht.sedaecha.core

import org.json.JSONObject

/**
 * 가상 출근길 기록 v2 (assets/sedae_demo_drive_log_v2.json): 1초 단위 차속·기어·방향지시등과 장면 이벤트.
 * Demo Player 는 이 기록을 실제 차량 신호 대신 Drive Signal Source 로 넣는다. 판단 로직은 실차와 같다.
 */
data class DemoDriveLog(
    val name: String,
    val durationSec: Int,
    val speed: List<Float>,
    val gear: List<Gear>,
    val turn: List<Turn>,
    val events: List<Event>,
    val route: String,
) {
    data class Event(val t: Int, val type: String, val label: String, val word: String?, val card: String?)

    fun signalAt(sec: Int): Signal {
        val i = sec.coerceIn(0, speed.size - 1)
        return Signal(speed[i], gear[i], turn[i])
    }

    /** 편성 고정: 말 코너 장면 (말, 시작 초) */
    val wordTimes: List<Pair<String, Int>> get() = events.filter { it.type == "word" && it.word != null }.map { it.word!! to it.t }
    val cardId: String get() = events.firstOrNull { it.card != null }?.card ?: "C001"

    /** 진행 바에 표시할 장면 (출발·도착 제외) */
    val markers: List<Event> get() = events.filter { it.type in setOf("stop_quiz", "turn_signal", "congestion", "hard_brake") }

    companion object {
        fun parse(json: String): DemoDriveLog {
            val o = JSONObject(json)
            val sp = o.getJSONArray("speed_kmh")
            val gr = o.getJSONArray("gear")
            val ts = o.getJSONArray("turn_signal")
            val ev = o.getJSONArray("events")
            val rules = o.optJSONObject("demo_mode_rules")
            return DemoDriveLog(
                name = o.optString("name"),
                durationSec = o.optInt("duration_sec", sp.length()),
                speed = (0 until sp.length()).map { sp.getDouble(it).toFloat() },
                gear = (0 until gr.length()).map { Gear.valueOf(gr.getString(it)) },
                turn = (0 until ts.length()).map {
                    when (ts.getString(it)) { "left" -> Turn.LEFT; "right" -> Turn.RIGHT; else -> Turn.OFF }
                },
                events = (0 until ev.length()).map {
                    val e = ev.getJSONObject(it)
                    Event(
                        t = e.getInt("t"), type = e.getString("type"), label = e.optString("class"),
                        word = e.optString("word").ifBlank { null } ?: e.optString("quiz_word").ifBlank { null },
                        card = e.optString("culture_card").ifBlank { null },
                    )
                },
                route = rules?.optString("route_label")?.ifBlank { null } ?: "출근길",
            )
        }
    }
}
