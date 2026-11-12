package com.kumhoht.sedaecha.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 배운 말 (단어 id 기준 1개: 요즘말·그때말 모드로 다 들어도 1개) */
@Entity(tableName = "learned_word")
data class LearnedWordEntity(
    @PrimaryKey val wordId: String,
    val firstLearnedAt: Long,
    val lastHeardAt: Long,
    /** 마지막으로 들은 길 (출근길·퇴근길·운전길) */
    val lastRoute: String,
    val heardNow: Boolean,
    val heardThen: Boolean,
    /** 다시 듣기 표시 */
    val replay: Boolean,
    val quizCorrect: Int = 0,
    val quizWrong: Int = 0,
)

/** 지난번 못 들은 말 (다음 운행에 먼저 편성) */
@Entity(tableName = "carry_over")
data class CarryOverEntity(@PrimaryKey val position: Int, val wordId: String, val mode: String)

/** 클래스 기록 */
@Entity(tableName = "class_log")
data class ClassLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val route: String,
    val direction: String,
    /** 편성한 말 "W001:NOW,W015:NOW" */
    val planned: String,
    /** 끝까지 들은 말 (같은 형식) */
    val heard: String,
    val replay: String,
    val quizzes: Int,
    val turnStops: Int,
    val hardStops: Int,
    val distanceKm: Double,
    val arrived: Boolean,
)

/** 운행 (예상 주행 시간 계산용) */
@Entity(tableName = "drive_stat")
data class DriveStatEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val startedAt: Long, val durationMs: Long)

/** 함께 달린 거리 = 현재 Odometer − 설치 시 Odometer. Odometer 를 못 받으면 차속을 시간으로 적분한 값 */
@Entity(tableName = "distance")
data class DistanceEntity(
    @PrimaryKey val id: Int = 0,
    val odometerAtInstall: Double? = null,
    val odometerNow: Double? = null,
    val integratedKm: Double = 0.0,
) {
    val km: Double get() = if (odometerAtInstall != null && odometerNow != null) (odometerNow - odometerAtInstall).coerceAtLeast(0.0) else integratedKm
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM learned_word ORDER BY lastHeardAt DESC")
    fun learnedFlow(): Flow<List<LearnedWordEntity>>

    @Query("SELECT * FROM learned_word")
    suspend fun learned(): List<LearnedWordEntity>

    @Query("SELECT * FROM learned_word WHERE wordId = :id")
    suspend fun learned(id: String): LearnedWordEntity?

    @Upsert
    suspend fun upsertLearned(e: LearnedWordEntity)

    @Query("UPDATE learned_word SET replay = :replay WHERE wordId = :id")
    suspend fun setReplay(id: String, replay: Boolean)

    @Query("SELECT * FROM carry_over ORDER BY position")
    suspend fun carryOver(): List<CarryOverEntity>

    @Query("DELETE FROM carry_over")
    suspend fun clearCarryOver()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCarryOver(list: List<CarryOverEntity>)

    @Insert
    suspend fun insertClass(e: ClassLogEntity): Long

    @Query("SELECT * FROM class_log ORDER BY endedAt DESC LIMIT 1")
    fun lastClassFlow(): Flow<ClassLogEntity?>

    @Query("SELECT * FROM class_log ORDER BY endedAt DESC LIMIT 1")
    suspend fun lastClass(): ClassLogEntity?

    @Insert
    suspend fun insertDrive(e: DriveStatEntity)

    @Query("SELECT * FROM drive_stat ORDER BY startedAt DESC LIMIT 200")
    suspend fun drives(): List<DriveStatEntity>

    @Query("SELECT * FROM distance WHERE id = 0")
    suspend fun distance(): DistanceEntity?

    @Query("SELECT * FROM distance WHERE id = 0")
    fun distanceFlow(): Flow<DistanceEntity?>

    @Upsert
    suspend fun upsertDistance(e: DistanceEntity)

    @Query("DELETE FROM learned_word") suspend fun clearLearned()
    @Query("DELETE FROM class_log") suspend fun clearClasses()
    @Query("DELETE FROM drive_stat") suspend fun clearDrives()
    @Query("DELETE FROM distance") suspend fun clearDistance()
}

@Database(
    entities = [LearnedWordEntity::class, CarryOverEntity::class, ClassLogEntity::class, DriveStatEntity::class, DistanceEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class SedaeDatabase : RoomDatabase() {
    abstract fun progress(): ProgressDao

    companion object {
        fun create(c: Context): SedaeDatabase = Room.databaseBuilder(c, SedaeDatabase::class.java, "sedaecha.db").build()
    }
}
