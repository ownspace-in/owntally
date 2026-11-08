package owntally.ui.nearby

import kotlinx.coroutines.flow.StateFlow
import owntally.data.session.NearbyStatus

enum class NearbyAvailability { AVAILABLE, NO_PLAY_SERVICES }

/** What the Nearby screen drives (the app provides it; nothing starts until the person taps Start). */
interface NearbyController {
    val status: StateFlow<NearbyStatus>
    fun availability(): NearbyAvailability
    fun start()
    fun stop()
    fun lookAgain()
}

/** The permissions Nearby Connections needs at run time on each Android version (docs: developer.android.com, Nearby Connections "Get started"). */
object NearbyPermissions {
    const val BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    const val NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"

    /** What has to be granted before Nearby can look for other phones: location on Android 11 and 12, Bluetooth on 12 and up, Nearby devices on 13 and up. */
    fun required(sdk: Int): List<String> = when {
        sdk >= 33 -> listOf(BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, NEARBY_WIFI_DEVICES)
        sdk >= 32 -> listOf(BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN)
        sdk >= 31 -> listOf(BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, FINE_LOCATION)
        else -> listOf(FINE_LOCATION)
    }

    /** What to put in the system's request: on Android 12 and older Android asks for the approximate location together with the precise one. */
    fun request(sdk: Int): List<String> = if (sdk <= 31) required(sdk) + COARSE_LOCATION else required(sdk)

    fun missing(sdk: Int, granted: (String) -> Boolean): List<String> = required(sdk).filterNot(granted)

    /** True when the system will not ask again (the person said no for good): the screen then points to Settings. */
    fun explanation(sdk: Int): String =
        if (sdk >= 33) "OwnTally uses Bluetooth and nearby Wi-Fi to find friends in the same room."
        else if (sdk >= 32) "OwnTally uses Bluetooth to find friends in the same room."
        else if (sdk >= 31) "OwnTally uses Bluetooth to find friends in the same room. Android calls one of the permissions \"location\" at this version; OwnTally does not read your location."
        else "OwnTally uses Bluetooth and Wi-Fi to find friends in the same room. Android calls this permission \"location\" at this version and also needs the location switch on; OwnTally does not read your location."
}
