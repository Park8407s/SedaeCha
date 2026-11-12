package com.kumhoht.sedaecha.vehicle

import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import com.kumhoht.sedaecha.core.Gear
import com.kumhoht.sedaecha.core.Signal
import com.kumhoht.sedaecha.core.Turn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 마지막으로 받은 차량 값 */
data class LiveVehicle(
    val gear: Gear = Gear.P,
    val speedKmh: Float = 0f,
    val turn: Turn = Turn.OFF,
    /** 누적 주행 거리 (km, 모르면 null) */
    val odometerKm: Double? = null,
    /** 주행 규제(화면 가림) 중인지 */
    val restricted: Boolean = false,
) {
    val signal: Signal get() = Signal(speedKmh, gear, turn)
}

/**
 * Drive Signal Source (실차): 차량 신호 수집. 차곡차곡 Telemetry 에서 세대차에 필요한 것만 남겼다.
 *
 * | 신호 | 출처 | 권한 |
 * |---|---|---|
 * | 차속 | AAOS PERF_VEHICLE_SPEED (m/s) | android.car.permission.CAR_SPEED (실행 중 허용) |
 * | 기어 | AAOS GEAR_SELECTION, 안 오면 Pleos Gear | CAR_POWERTRAIN / pleos CAR_DRIVE |
 * | 방향지시등 | Pleos TurnSignal (LEFT/RIGHT/OFF) | pleos CAR_LIGHTS |
 * | 누적 주행 거리 | Pleos Odometer (getCurrentDistanceDriven) | pleos CAR_INFO |
 * | 경로 시작·도착 | Pleos NaviHelper (로그만, 실차 단계에서 예상 주행 시간 보조) | pleos NAVI_ROUTE |
 * | 주행 규제 | AAOS CarUxRestrictionsManager | - |
 *
 * 차종별로 지원하지 않는 신호는 해당 규칙만 꺼진 채로 동작한다 (차속·기어만으로도 클래스가 돈다).
 */
class Telemetry(private val context: Context) {
    private val _live = MutableStateFlow(LiveVehicle())
    val live: StateFlow<LiveVehicle> = _live.asStateFlow()

    /** 디버그(adb) 덮어쓰기 */
    @Volatile var override: LiveVehicle? = null
    fun current(): LiveVehicle = override ?: _live.value

    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    private var aaosGearWorks = false
    private var started = false

    fun start() {
        if (started) return
        started = true
        startCarApi()
        startPleosVehicle()
        startNavi()
    }

    fun stop() {
        if (!started) return
        started = false
        safely("release car") { props?.unregisterCallback(propertyCallback); ux?.unregisterListener(); car?.disconnect() }
        safely("release vehicle") {
            vehicle?.let { v ->
                @Suppress("DEPRECATION") v.getGear().unregisterGearSelection(gearListener)
                v.getTurnSignal().unregisterTurnSignalState(turnListener)
                v.getOdometer().unregisterDistanceDriven(odoListener)
                v.release()
            }
        }
        safely("release navi") { navi?.removeListener(naviListener); navi?.release() }
        vehicle = null; navi = null; car = null; props = null; ux = null
    }

    /** 차속 권한을 허용받은 뒤 다시 등록 (권한 없이 등록하면 값이 안 온다) */
    fun onPermissionsGranted() {
        val pm = props ?: return
        safely("speed re-register") { pm.registerCallback(propertyCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL) }
        safely("gear re-register") { pm.registerCallback(propertyCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
        vehicle?.let { v -> safely("odometer re-read") { v.getOdometer().getCurrentDistanceDriven({ onOdometer(it) }, { }) } }
    }

    // ---------------------------------------------------------------- AAOS Car API

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                    val ms = (value.value as? Float) ?: return
                    val kmh = kotlin.math.abs(ms) * 3.6f // 후진은 음수로 올 수 있다
                    if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "speed=%.1f t=%d".format(kmh, value.timestamp / 1_000_000))
                    _live.update { it.copy(speedKmh = kmh) }
                }
                VehiclePropertyIds.GEAR_SELECTION -> {
                    val g = (value.value as? Int) ?: return
                    aaosGearWorks = true
                    val gear = aaosGear(g)
                    if (gear != _live.value.gear) Log.i(TAG, "gear=$gear (AAOS)")
                    _live.update { it.copy(gear = gear) }
                }
            }
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) { Log.w(TAG, "property error id=$propertyId zone=$zone") }
    }

    private fun startCarApi() = safely("car api") {
        car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
            if (!ready) { Log.w(TAG, "car service not ready"); return@createCar }
            safely("property manager") {
                val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                props = pm
                safely("speed") { Log.i(TAG, "speed registered=" + pm.registerCallback(propertyCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)) }
                safely("gear") { pm.registerCallback(propertyCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
            }
            safely("ux restrictions") {
                val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                ux = m
                applyUx(m.currentCarUxRestrictions)
                m.registerListener { r -> applyUx(r) }
            }
        }
    }

    private fun applyUx(r: CarUxRestrictions?) {
        val restricted = r?.isRequiresDistractionOptimization ?: false
        if (restricted != _live.value.restricted) Log.i(TAG, "uxRestricted=$restricted")
        _live.update { it.copy(restricted = restricted) }
    }

    private fun aaosGear(g: Int): Gear = when (g) {
        android.car.VehicleGear.GEAR_PARK -> Gear.P
        android.car.VehicleGear.GEAR_REVERSE -> Gear.R
        android.car.VehicleGear.GEAR_NEUTRAL -> Gear.N
        else -> Gear.D // DRIVE, 1~9단
    }

    // ---------------------------------------------------------------- Pleos Vehicle SDK

    private fun pleosGear(g: VehicleGear): Gear = when (g) {
        VehicleGear.GEAR_PARK -> Gear.P
        VehicleGear.GEAR_REVERSE -> Gear.R
        VehicleGear.GEAR_NEUTRAL -> Gear.N
        else -> Gear.D
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            if (gear == null || aaosGearWorks) return
            Log.i(TAG, "gear=${pleosGear(gear)} (Pleos)")
            _live.update { it.copy(gear = pleosGear(gear)) }
        }
        override fun onFailed(e: Exception) { Log.w(TAG, "gear failed: ${e.message}") }
    }

    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) { onTurn(value) }
        override fun onFailed(e: Exception) { Log.w(TAG, "turn signal failed: ${e.message}") }
    }

    private val odoListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(km: Float?) { onOdometer(km) }
        override fun onFailed(e: Exception) { Log.w(TAG, "odometer failed: ${e.message}") }
    }

    private fun onTurn(v: TurnSignalValue?) {
        val t = when (v) { TurnSignalValue.LEFT -> Turn.LEFT; TurnSignalValue.RIGHT -> Turn.RIGHT; else -> Turn.OFF }
        if (t != _live.value.turn) Log.i(TAG, "turn=$t")
        _live.update { it.copy(turn = t) }
    }

    private fun onOdometer(km: Float?) {
        val v = km?.toDouble()?.takeIf { it > 0 } ?: return
        _live.update { it.copy(odometerKm = v) }
    }

    @Suppress("DEPRECATION")
    private fun startPleosVehicle() = safely("pleos vehicle") {
        val v = Vehicle(context)
        vehicle = v
        v.initialize()
        // 권한이나 서비스가 없으면 register 가 바로 예외를 던져서 항목별로 감싼다
        safely("gear") {
            v.getGear().getCurrentGearSelectionState({ g -> if (g != null && !aaosGearWorks) _live.update { it.copy(gear = pleosGear(g)) } }, { e -> Log.w(TAG, "gear get failed: ${e.message}") })
            v.getGear().registerGearSelection(gearListener)
        }
        safely("turn signal") {
            v.getTurnSignal().getCurrentTurnSignalState({ onTurn(it) }, { e -> Log.w(TAG, "turn get failed: ${e.message}") })
            v.getTurnSignal().registerTurnSignalState(turnListener)
        }
        safely("odometer") {
            v.getOdometer().getCurrentDistanceDriven({ onOdometer(it) }, { e -> Log.w(TAG, "odometer get failed: ${e.message}") })
            v.getOdometer().registerDistanceDriven(odoListener)
        }
    }

    // ---------------------------------------------------------------- Pleos NaviHelper

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {}
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { Log.i(TAG, "navi routeStarted") }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { Log.i(TAG, "navi destinationArrived") }
        override fun onRouteEnded() {}
        override fun onRouteCancelled() {}
    }

    private fun startNavi() = safely("navi") {
        val n = NaviHelper(context)
        navi = n
        n.initialize({ safely("navi listener") { n.addListener(naviListener) } }, { t -> Log.w(TAG, "NaviHelper init failed: ${t.message}") })
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "$what: ${t.javaClass.simpleName} ${t.message}") }
    }

    private companion object { const val TAG = "Telemetry" }
}
