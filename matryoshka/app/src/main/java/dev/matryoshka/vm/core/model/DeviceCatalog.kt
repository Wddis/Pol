package dev.matryoshka.vm.core.model

/**
 * Device identities a Nest guest can present to apps (Build.MANUFACTURER / BRAND / MODEL / DEVICE /
 * PRODUCT and the partition copies of those properties). Only names: the build fingerprint's
 * signing keys, the security patch level and key attestation stay the guest's own, so integrity
 * checks are never fooled; apps that pick features or layouts by model see a familiar device.
 * Product names identify the devices for compatibility only (nominative use).
 */
data class DeviceModel(
    val id: String,
    val group: String,
    val title: String,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
)

object DeviceCatalog {
    const val HOST = "host"
    const val GENERIC = "generic"

    val models: List<DeviceModel> = listOf(
        // Samsung
        DeviceModel("sm-s918b", "Samsung", "Galaxy S23 Ultra", "samsung", "samsung", "SM-S918B", "dm3q", "dm3qxxx"),
        DeviceModel("sm-s901b", "Samsung", "Galaxy S22", "samsung", "samsung", "SM-S901B", "r0s", "r0sxxx"),
        DeviceModel("sm-g991b", "Samsung", "Galaxy S21", "samsung", "samsung", "SM-G991B", "o1s", "o1sxxx"),
        DeviceModel("sm-a546b", "Samsung", "Galaxy A54 5G", "samsung", "samsung", "SM-A546B", "a54x", "a54xnsxx"),
        DeviceModel("sm-a525f", "Samsung", "Galaxy A52", "samsung", "samsung", "SM-A525F", "a52q", "a52qnsxx"),
        DeviceModel("sm-a515f", "Samsung", "Galaxy A51", "samsung", "samsung", "SM-A515F", "a51", "a51nsxx"),
        DeviceModel("sm-a315f", "Samsung", "Galaxy A31", "samsung", "samsung", "SM-A315F", "a31", "a31nsxx"),
        DeviceModel("sm-x700", "Samsung", "Galaxy Tab S8", "samsung", "samsung", "SM-X700", "gts8wifi", "gts8wifixx"),
        // Google
        DeviceModel("pixel8pro", "Google", "Pixel 8 Pro", "Google", "google", "Pixel 8 Pro", "husky", "husky"),
        DeviceModel("pixel7", "Google", "Pixel 7", "Google", "google", "Pixel 7", "panther", "panther"),
        DeviceModel("pixel6a", "Google", "Pixel 6a", "Google", "google", "Pixel 6a", "bluejay", "bluejay"),
        DeviceModel("pixel5", "Google", "Pixel 5", "Google", "google", "Pixel 5", "redfin", "redfin"),
        // Xiaomi
        DeviceModel("xiaomi13", "Xiaomi", "Xiaomi 13", "Xiaomi", "Xiaomi", "2211133G", "fuxi", "fuxi_global"),
        DeviceModel("pocof5", "Xiaomi", "POCO F5", "Xiaomi", "POCO", "23049PCD8G", "marble", "marble_global"),
        DeviceModel("rn12pro", "Xiaomi", "Redmi Note 12 Pro", "Xiaomi", "Redmi", "22101316G", "ruby", "ruby_global"),
        DeviceModel("rn10pro", "Xiaomi", "Redmi Note 10 Pro", "Xiaomi", "Redmi", "M2101K6G", "sweet", "sweet_eea"),
        // OnePlus
        DeviceModel("op11", "OnePlus", "OnePlus 11", "OnePlus", "OnePlus", "CPH2449", "OP594DL1", "CPH2449EEA"),
        DeviceModel("op9pro", "OnePlus", "OnePlus 9 Pro", "OnePlus", "OnePlus", "LE2123", "OnePlus9Pro", "OnePlus9Pro_EEA"),
        DeviceModel("nord2", "OnePlus", "OnePlus Nord 2", "OnePlus", "OnePlus", "DN2103", "OP515BL1", "DN2103EEA"),
        // Gaming
        DeviceModel("rog6", "Gaming", "ASUS ROG Phone 6", "asus", "asus", "ASUS_AI2201_D", "ASUS_AI2201", "WW_AI2201"),
        DeviceModel("rmx3301", "Gaming", "realme GT 2 Pro", "realme", "realme", "RMX3301", "RE54ABL1", "RMX3301EEA"),
        // Others
        DeviceModel("xq-dq72", "Other", "Sony Xperia 1 V", "Sony", "Sony", "XQ-DQ72", "XQ-DQ72", "XQ-DQ72"),
        DeviceModel("edge30", "Other", "Motorola edge 30", "motorola", "motorola", "motorola edge 30", "dubai", "dubai_g"),
        DeviceModel("nothing2", "Other", "Nothing Phone (2)", "Nothing", "Nothing", "A065", "Pong", "Pong"),
    )

    fun find(id: String?): DeviceModel? = models.firstOrNull { it.id == id }

    /** The identity a VM presents: its catalogue entry, this phone, or the neutral one. */
    fun resolve(vm: VmConfig): DeviceModel {
        val id = vm.deviceModelId ?: if (vm.deviceProfile == DeviceProfile.GENERIC) GENERIC else HOST
        find(id)?.let { return it }
        return if (id == GENERIC) {
            DeviceModel(GENERIC, "", "Matryoshka", "Matryoshka", "Matryoshka", "Matryoshka Virtual Device", "matryoshka", "matryoshka")
        } else {
            fun clean(v: String?) = (v ?: "").replace(Regex("[^A-Za-z0-9 ._+()-]"), "").trim().take(60)
            DeviceModel(
                HOST, "", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                clean(android.os.Build.MANUFACTURER), clean(android.os.Build.BRAND), clean(android.os.Build.MODEL),
                clean(android.os.Build.DEVICE), clean(android.os.Build.PRODUCT),
            )
        }
    }
}
