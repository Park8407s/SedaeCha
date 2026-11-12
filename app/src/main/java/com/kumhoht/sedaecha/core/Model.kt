package com.kumhoht.sedaecha.core

/** 기어 (R·N 은 클래스 일시 정지, P + 차속 0 은 도착) */
enum class Gear { P, R, N, D }

enum class Turn { OFF, LEFT, RIGHT }

/** 한 시점의 차량 신호. 실제 차량과 저장된 주행 기록(데모)이 같은 형식으로 공급한다 (Drive Signal Source). */
data class Signal(val speedKmh: Float, val gear: Gear, val turn: Turn = Turn.OFF) {
    val stopped: Boolean get() = speedKmh < STOP_KMH

    companion object {
        /** 이 속도 미만이면 정차로 본다 (센서 잡음) */
        const val STOP_KMH = 0.5f
        val PARKED = Signal(0f, Gear.P)
    }
}

/** 배우는 방향 (홈에서 고름) */
enum class Direction(val key: String, val label: String) {
    NOW("now", "요즘말 배우기"),
    THEN("then", "그때말 배우기"),
    MIX("mix", "섞어서 듣기");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key || it.name == key }
    }
}

/** 말 하나를 어떤 방향으로 들려줬는지 */
enum class WordMode { NOW, THEN }

/** 세대 공감 레벨 (배운 말 개수 기준) */
data class Level(val number: Int, val name: String, val from: Int, val until: Int?) {
    companion object {
        val ALL = listOf(
            Level(1, "낯선 말", 0, 10),
            Level(2, "알아듣기", 10, 25),
            Level(3, "맞장구", 25, 50),
            Level(4, "세대 통역사", 50, null),
        )

        fun of(learned: Int): Level = ALL.last { learned >= it.from }

        /** 다음 레벨까지 진행률 0~1 (마지막 레벨은 1) */
        fun progress(learned: Int): Float {
            val l = of(learned)
            val until = l.until ?: return 1f
            return ((learned - l.from).toFloat() / (until - l.from)).coerceIn(0f, 1f)
        }

        fun remaining(learned: Int): Int? = of(learned).until?.let { it - learned }
    }
}

/** 테마 팩: 달린 거리만큼 열린다 (데모 모드는 모두 열림) */
data class ThemePack(val name: String, val themes: Set<String>, val unlockKm: Int) {
    companion object {
        val ALL = listOf(
            ThemePack("기본 팩 (감정·일상·직장)", setOf("감정 표현", "일상", "직장"), 0),
            ThemePack("SNS·사진 편", setOf("SNS·사진"), 300),
            ThemePack("음악·아이돌 편", setOf("음악·아이돌"), 500),
        )

        fun openThemes(km: Double, demo: Boolean): Set<String> =
            ALL.filter { demo || km >= it.unlockKm }.flatMap { it.themes }.toSet()
    }
}
