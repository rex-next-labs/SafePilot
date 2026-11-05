package ai.safepilot.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TirePressureListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pleos Vehicle SDK와 AAOS 차량 데이터를 [VehicleSnapshot] 하나로 모은다.
 * 무엇을 어디서 읽는지는 docs/sdk_measurement.md 실측 결과를 따른다.
 */
class VehicleRepository(private val context: Context) {
    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    /** 경고등을 읽을 수 있으면 주차 브레이크를 경고등으로 판단한다(PRIVATE_CAR_SAFETY) */
    private var telltalesAvailable = false

    private val driver = SeatArea(SeatValue.ROW_1_LEFT)
    private val wheels = mapOf(
        WheelValue.LEFT_FRONT to Wheel.LEFT_FRONT, WheelValue.RIGHT_FRONT to Wheel.RIGHT_FRONT,
        WheelValue.LEFT_REAR to Wheel.LEFT_REAR, WheelValue.RIGHT_REAR to Wheel.RIGHT_REAR,
    )

    private fun set(block: (VehicleSnapshot) -> VehicleSnapshot) = _snapshot.update(block)

    fun start() {
        startAaos()
        val v = Vehicle(context)
        vehicle = v
        runCatching { v.initialize() }.onFailure { Log.w(TAG, "Vehicle.initialize", it) }
        // 서비스 연결이 끝날 시간을 준 뒤 구독한다
        main.postDelayed({ subscribePleos(v) }, 1500)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        runCatching { vehicle?.release() }
        car?.disconnect()
    }

    private fun subscribePleos(v: Vehicle) {
        // 기어
        guard("gear") {
            v.getGear().getCurrentGearSelectionState({ g -> set { it.copy(gear = Known.Value(g.toGear())) } }, fail("gear"))
            v.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(p0: VehicleGear?) = set { it.copy(gear = Known.Value(p0.toGear())) }
                override fun onFailed(p0: Exception) = fail("gear")(p0)
            })
        }
        // 경고등: 주차 브레이크, 오토홀드, TPMS
        guard("telltales") {
            v.getSafety().getTelltalesInfo({ t -> onTelltales(t) }, { e ->
                Log.w(TAG, "telltales unavailable: ${e.message}")
                telltalesAvailable = false
                set { it.copy(autoHoldOn = Known.Unsupported, tpmsWarning = Known.Unsupported) }
            })
            v.getSafety().registerTelltales(object : TelltaleInfoListener {
                override fun onTelltaleInfoUpdated(p0: Telltales?) = onTelltales(p0)
                override fun onFailed(p0: Exception) = fail("telltales")(p0)
            })
        }
        // 타이어: 저압 기준값과 바퀴별 공기압(실차에서만 나올 수 있음)
        guard("tire") {
            v.getTire().getLowTirePressure(WheelArea(WheelValue.LEFT_FRONT),
                { kpa -> set { it.copy(lowTirePressureKpa = kpa?.let { k -> Known.Value(k) } ?: Known.Unsupported) } },
                { set { it.copy(lowTirePressureKpa = Known.Unsupported) } })
            for ((wv, wheel) in wheels) {
                v.getTire().registerTirePressure(WheelArea(wv), object : TirePressureListener {
                    override fun onTirePressureUpdated(p0: Float?) {
                        if (p0 == null) return
                        set { s ->
                            val current = s.tirePressureKpa.valueOrNull().orEmpty()
                            s.copy(tirePressureKpa = Known.Value(current + (wheel to p0)))
                        }
                    }
                    override fun onFailed(p0: Exception) = set { it.copy(tirePressureKpa = Known.Unsupported) }
                })
            }
        }
        guard("brake") {
            v.getBrake().getCurrentBrakePedalStatus({ b -> set { it.copy(brakePedalPressed = b.known()) } }, fail("brake"))
            v.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(p0: Boolean?) = set { it.copy(brakePedalPressed = p0.known()) }
                override fun onFailed(p0: Exception) = set { it.copy(brakePedalPressed = Known.Unsupported) }
            })
        }
        guard("airbag") {
            v.getSafety().hasSeatAirbagBeenDeployed(driver, { b -> set { it.copy(airbagDeployed = b.known()) } },
                { set { it.copy(airbagDeployed = Known.Unsupported) } })
            v.getSafety().registerSeatAirbagDeployed(driver, object : SeatAirbagDeployedListener {
                override fun onSeatAirbagDeployUpdated(p0: Boolean?) = set { it.copy(airbagDeployed = p0.known()) }
                override fun onFailed(p0: Exception) = set { it.copy(airbagDeployed = Known.Unsupported) }
            })
        }
        guard("steering") {
            v.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(p0: Float?) = set { it.copy(steeringAngleDeg = p0.known()) }
                override fun onFailed(p0: Exception) = set { it.copy(steeringAngleDeg = Known.Unsupported) }
            })
        }
        guard("odometer") {
            v.getOdometer().getCurrentDistanceDriven({ km -> set { it.copy(odometerKm = km.known()) } },
                { set { it.copy(odometerKm = Known.Unsupported) } })
        }
    }

    private fun onTelltales(t: Telltales?) {
        val list = t?.telltales ?: return
        telltalesAvailable = true
        fun on(vararg types: TelltaleType) = list.any { it.type in types && it.state != TelltaleState.OFF }
        set {
            it.copy(
                parkingBrakeOn = Known.Value(on(TelltaleType.PARKING_BRAKE, TelltaleType.ELECTRONIC_PARKING_BRAKE)),
                autoHoldOn = Known.Value(on(TelltaleType.AUTO_HOLD)),
                tpmsWarning = Known.Value(on(TelltaleType.TPMS)),
            )
        }
    }

    private fun startAaos() {
        try {
            val c = Car.createCar(context)
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            watch(pm, VehiclePropertyIds.PERF_VEHICLE_SPEED, 2f) { v ->
                // 단위 m/s
                val ms = (v.value as? Float) ?: return@watch
                set { it.copy(speedKmh = Known.Value(ms * 3.6f)) }
            }.also { ok -> if (!ok) set { it.copy(speedKmh = Known.Unsupported) } }
            watch(pm, VehiclePropertyIds.PARKING_BRAKE_ON, 0f) { v ->
                // 경고등을 읽을 수 있으면 경고등을 우선한다(에뮬레이터에서 조작 가능한 쪽)
                if (telltalesAvailable) return@watch
                val on = (v.value as? Boolean) ?: return@watch
                set { it.copy(parkingBrakeOn = Known.Value(on)) }
            }.also { ok -> if (!ok && !telltalesAvailable) set { it.copy(parkingBrakeOn = Known.Unsupported) } }
        } catch (e: Exception) {
            Log.w(TAG, "AAOS car service unavailable", e)
            set { it.copy(speedKmh = Known.Unsupported) }
        }
    }

    private fun watch(pm: CarPropertyManager, id: Int, rate: Float, onValue: (CarPropertyValue<*>) -> Unit): Boolean {
        if (pm.getCarPropertyConfig(id) == null) return false
        return runCatching {
            pm.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(v: CarPropertyValue<*>) = onValue(v)
                override fun onErrorEvent(p: Int, a: Int) { Log.w(TAG, "AAOS error prop=$p area=$a") }
            }, id, rate)
        }.getOrDefault(false)
    }

    private inline fun guard(name: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w(TAG, "$name unavailable", e) }
    }

    private fun fail(name: String): (Exception) -> Unit = { e -> Log.w(TAG, "$name failed: ${e.message}") }

    private fun VehicleGear?.toGear() = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_DRIVE -> Gear.DRIVE
        else -> Gear.OTHER
    }

    private fun <T> T?.known(): Known<T> = if (this == null) Known.Unsupported else Known.Value(this)

    companion object { private const val TAG = "SafePilot.Vehicle" }
}
