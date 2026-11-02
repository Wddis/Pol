package dev.matryoshka.vm.core.guest

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Writes Android's `IpConfigStore` file (version 3) so that the guest's EthernetService brings up
 * `eth0` with a static address on boot. The guest-side setup script copies it to
 * `/data/misc/ethernet/ipconfig.txt` before system_server starts.
 */
object EthernetIpConfig {
    private const val VERSION = 3

    data class Plan(
        val iface: String = "eth0",
        val address: String,
        val prefixLength: Int,
        val gateway: String,
        val dns: List<String>,
    )

    fun encode(plan: Plan): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeUTF("ipAssignment"); out.writeUTF("STATIC")
            out.writeUTF("linkAddress"); out.writeUTF(plan.address); out.writeInt(plan.prefixLength)
            out.writeUTF("gateway")
            out.writeInt(0) // default route
            out.writeInt(1) // has gateway
            out.writeUTF(plan.gateway)
            for (d in plan.dns) {
                out.writeUTF("dns"); out.writeUTF(d)
            }
            out.writeUTF("proxySettings"); out.writeUTF("NONE")
            out.writeUTF("id"); out.writeUTF(plan.iface)
            out.writeUTF("eos")
        }
        return bytes.toByteArray()
    }
}
