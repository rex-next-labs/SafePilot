package check

import android.app.Activity
import android.car.Car
import android.car.VehicleAreaWheel
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TirePressureListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener

/** SafePilot 데이터 실측용. 모든 결과를 logcat 태그 SPProbe와 화면에 남긴다. */
class ProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var out: TextView
    private lateinit var vehicle: Vehicle
    private var car: Car? = null

    private fun log(msg: String) {
        Log.i(TAG, msg)
        main.post { out.append(msg + "\n") }
    }

    private fun fail(name: String): (Exception) -> Unit = { e -> log("FAIL $name: ${e.javaClass.simpleName}: ${e.message}") }
    private fun <T> ok(name: String): (T) -> Unit = { v -> log("OK   $name = $v") }

    private val wheels = listOf(WheelValue.LEFT_FRONT, WheelValue.RIGHT_FRONT, WheelValue.LEFT_REAR, WheelValue.RIGHT_REAR)
    private val driver = SeatArea(SeatValue.ROW_1_LEFT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 14f; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(out) })
        vehicle = Vehicle(this)
        try { vehicle.initialize(); log("Vehicle.initialize() called") } catch (e: Exception) { fail("initialize")(e) }
        main.postDelayed({ probePleos(); subscribePleos() }, 3000)
        probeAaos()
        probeSensors()
    }

    private fun probePleos() {
        log("---- Pleos get ----")
        runCatching { vehicle.getGear().getCurrentGearSelectionState(ok<VehicleGear?>("gear"), fail("gear")) }.onFailure { log("THROW gear: $it") }
        runCatching { vehicle.getSafety().getTelltalesInfo({ t -> if (t != null) logTelltales("telltales(get)", t) else log("OK   telltales(get) = null") }, fail("telltales")) }.onFailure { log("THROW telltales: $it") }
        for (w in wheels) {
            val a = WheelArea(w)
            runCatching { vehicle.getTire().getLowTirePressure(a, ok<Float?>("lowTirePressure[$w]"), fail("lowTirePressure[$w]")) }.onFailure { log("THROW lowTire: $it") }
            runCatching { vehicle.getTire().getTirePressure(a, ok<Float?>("tirePressure[$w]"), fail("tirePressure[$w]")) }.onFailure { log("THROW tire: $it") }
        }
        runCatching { vehicle.getTire().checkTirePressureCapabilities({ c, l -> log("OK   cap tirePressure = $c $l") }, fail("cap tirePressure")) }
        runCatching { vehicle.getTire().checkLowTirePressureCapabilities({ c, l -> log("OK   cap lowTirePressure = $c $l") }, fail("cap lowTirePressure")) }
        runCatching { vehicle.getSafety().hasSeatAirbagBeenDeployed(driver, ok<Boolean?>("airbagDeployed"), fail("airbagDeployed")) }.onFailure { log("THROW airbag: $it") }
        runCatching { vehicle.getSafety().getEngineOilLevelState(ok("engineOilLevel"), fail("engineOilLevel")) }
        runCatching { vehicle.getSafety().getBrakeFluidLevelState(ok("brakeFluidLevel"), fail("brakeFluidLevel")) }
        runCatching { vehicle.getSafety().getTransmissionOilLevelState(ok("transmissionOilLevel"), fail("transmissionOilLevel")) }
        runCatching { vehicle.getOdometer().getCurrentDistanceDriven(ok<Float?>("odometer"), fail("odometer")) }
        runCatching { vehicle.getBrake().getCurrentBrakePedalStatus(ok<Boolean?>("brakePedal"), fail("brakePedal")) }
        runCatching { vehicle.getSteeringWheel().getCurrentSteeringWheelAngle(ok<Float?>("steeringAngle"), fail("steeringAngle")) }
        runCatching { vehicle.getSteeringWheel().checkSteeringWheelAngleCapabilities({ c, b -> log("OK   cap steeringAngle = $c $b") }, fail("cap steeringAngle")) }
        runCatching { vehicle.getTurnSignal().getCurrentTurnSignalState(ok<TurnSignalValue?>("turnSignal"), fail("turnSignal")) }
        runCatching { vehicle.getCarInfo().getVINInfo(ok<String?>("vin"), fail("vin")) }
        runCatching { vehicle.getCarInfo().getCapabilityInfo({ m -> log("OK   capabilityInfo keys(${m?.size}) = ${m?.keys?.sorted()?.joinToString()}") }, fail("capabilityInfo")) }
    }

    private fun logTelltales(tag: String, t: Telltales) {
        val on = t.telltales.filter { it.state != TelltaleState.OFF }
        log("OK   $tag: total=${t.telltales.size}, notOff=${on.map { "${it.type}:${it.state}:c${it.color}" }}")
        val pb = t.telltales.filter { it.type.name.contains("PARKING_BRAKE") || it.type.name == "AUTO_HOLD" || it.type.name == "TPMS" }
        log("     $tag key: ${pb.map { "${it.type}:${it.state}:c${it.color}" }}")
    }

    private fun subscribePleos() {
        log("---- Pleos subscribe ----")
        runCatching {
            vehicle.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(p0: VehicleGear?) = log("EVT  gear -> $p0")
                override fun onFailed(p0: Exception) = fail("sub gear")(p0)
            })
        }.onFailure { log("THROW sub gear: $it") }
        runCatching {
            vehicle.getSafety().registerTelltales(object : TelltaleInfoListener {
                override fun onTelltaleInfoUpdated(p0: Telltales?) { if (p0 != null) logTelltales("EVT  telltales", p0) }
                override fun onFailed(p0: Exception) = fail("sub telltales")(p0)
            })
        }.onFailure { log("THROW sub telltales: $it") }
        for (w in wheels) runCatching {
            vehicle.getTire().registerTirePressure(WheelArea(w), object : TirePressureListener {
                override fun onTirePressureUpdated(p0: Float?) = log("EVT  tirePressure[$w] -> $p0")
                override fun onFailed(p0: Exception) = fail("sub tire[$w]")(p0)
            })
        }.onFailure { log("THROW sub tire: $it") }
        runCatching {
            vehicle.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(p0: Float?) = log("EVT  steeringAngle -> $p0")
                override fun onFailed(p0: Exception) = fail("sub steering")(p0)
            })
        }.onFailure { log("THROW sub steering: $it") }
        runCatching {
            vehicle.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(p0: TurnSignalValue?) = log("EVT  turnSignal -> $p0")
                override fun onFailed(p0: Exception) = fail("sub turnSignal")(p0)
            })
        }.onFailure { log("THROW sub turnSignal: $it") }
        runCatching {
            vehicle.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(p0: Boolean?) = log("EVT  brakePedal -> $p0")
                override fun onFailed(p0: Exception) = fail("sub brake")(p0)
            })
        }.onFailure { log("THROW sub brake: $it") }
        runCatching {
            vehicle.getSafety().registerSeatAirbagDeployed(driver, object : SeatAirbagDeployedListener {
                override fun onSeatAirbagDeployUpdated(p0: Boolean?) = log("EVT  airbagDeployed -> $p0")
                override fun onFailed(p0: Exception) = fail("sub airbag")(p0)
            })
        }.onFailure { log("THROW sub airbag: $it") }
    }

    private fun probeAaos() {
        log("---- AAOS CarPropertyManager ----")
        try {
            val c = Car.createCar(this)
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            val ids = mapOf(
                "PERF_VEHICLE_SPEED" to VehiclePropertyIds.PERF_VEHICLE_SPEED,
                "GEAR_SELECTION" to VehiclePropertyIds.GEAR_SELECTION,
                "CURRENT_GEAR" to VehiclePropertyIds.CURRENT_GEAR,
                "PARKING_BRAKE_ON" to VehiclePropertyIds.PARKING_BRAKE_ON,
                "HAZARD_LIGHTS_STATE" to VehiclePropertyIds.HAZARD_LIGHTS_STATE,
                "TIRE_PRESSURE" to VehiclePropertyIds.TIRE_PRESSURE,
            )
            for ((name, id) in ids) {
                try {
                    val cfg = pm.getCarPropertyConfig(id)
                    if (cfg == null) { log("NONE aaos $name: no config (not supported or no permission)"); continue }
                    val areas = cfg.areaIds.toList()
                    for (area in areas) {
                        val v: CarPropertyValue<Any>? = pm.getProperty(id, area)
                        log("OK   aaos $name[area=$area] = ${v?.value}")
                    }
                    pm.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                        override fun onChangeEvent(v: CarPropertyValue<*>) = log("EVT  aaos $name[area=${v.areaId}] -> ${v.value}")
                        override fun onErrorEvent(p: Int, a: Int) = log("ERR  aaos $name[area=$a]")
                    }, id, if (name == "PERF_VEHICLE_SPEED") 1f else 0f)
                } catch (e: Exception) { fail("aaos $name")(e) }
            }
            log("     wheel area ids: LF=${VehicleAreaWheel.WHEEL_LEFT_FRONT} RF=${VehicleAreaWheel.WHEEL_RIGHT_FRONT} LR=${VehicleAreaWheel.WHEEL_LEFT_REAR} RR=${VehicleAreaWheel.WHEEL_RIGHT_REAR}")
        } catch (e: Exception) { fail("Car.createCar")(e) }
    }

    private fun probeSensors() {
        log("---- Sensors ----")
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.getSensorList(Sensor.TYPE_ALL).forEach { log("     sensor type=${it.type} ${it.stringType} '${it.name}'") }
        val types = listOf(Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
        for (t in types) {
            val s = sm.getDefaultSensor(t)
            log("     default sensor for type $t = ${s?.name}")
            if (s != null) {
                var n = 0
                sm.registerListener(object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        if (n++ < 3) log("EVT  sensor type $t = ${e.values.take(3).joinToString { "%.2f".format(it) }}")
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }, s, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    override fun onDestroy() {
        runCatching { vehicle.release() }
        car?.disconnect()
        super.onDestroy()
    }

    companion object { const val TAG = "SPProbe" }
}
