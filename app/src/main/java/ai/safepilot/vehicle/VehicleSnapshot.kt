package ai.safepilot.vehicle

/** 읽을 수 있는지까지 담은 값. 읽을 수 없는 값은 운전자에게 묻는다. */
sealed interface Known<out T> {
    data class Value<T>(val value: T) : Known<T>
    data object Unsupported : Known<Nothing>
    data object Pending : Known<Nothing>
}

fun <T> Known<T>.valueOrNull(): T? = (this as? Known.Value<T>)?.value

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, OTHER }

enum class Wheel(val label: String) {
    LEFT_FRONT("앞 왼쪽"), RIGHT_FRONT("앞 오른쪽"), LEFT_REAR("뒤 왼쪽"), RIGHT_REAR("뒤 오른쪽")
}

/** 앱이 쓰는 차량 상태 전부. 출처가 달라도(Pleos SDK, AAOS) 여기로 모은다. */
data class VehicleSnapshot(
    val gear: Known<Gear> = Known.Pending,
    val speedKmh: Known<Float> = Known.Pending,
    val parkingBrakeOn: Known<Boolean> = Known.Pending,
    val autoHoldOn: Known<Boolean> = Known.Pending,
    val tpmsWarning: Known<Boolean> = Known.Pending,
    /** 바퀴별 공기압(kPa). 에뮬레이터는 지원하지 않는다 */
    val tirePressureKpa: Known<Map<Wheel, Float>> = Known.Pending,
    /** 저압 기준값(kPa). getLowTirePressure가 돌려주는 값 */
    val lowTirePressureKpa: Known<Float> = Known.Pending,
    val brakePedalPressed: Known<Boolean> = Known.Pending,
    val airbagDeployed: Known<Boolean> = Known.Pending,
    val steeringAngleDeg: Known<Float> = Known.Pending,
    val odometerKm: Known<Float> = Known.Pending,
) {
    val isStopped: Boolean get() = (speedKmh.valueOrNull() ?: Float.MAX_VALUE) < 1f

    /** 기준값보다 낮은 바퀴. 공기압이나 기준값을 읽을 수 없으면 빈 집합 */
    val lowPressureWheels: Set<Wheel>
        get() {
            val limit = lowTirePressureKpa.valueOrNull() ?: return emptySet()
            val pressures = tirePressureKpa.valueOrNull() ?: return emptySet()
            return pressures.filterValues { it < limit }.keys
        }
}
