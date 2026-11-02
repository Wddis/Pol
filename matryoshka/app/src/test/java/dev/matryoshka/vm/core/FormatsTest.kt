package dev.matryoshka.vm.core

import dev.matryoshka.vm.core.display.ControlMessages
import dev.matryoshka.vm.core.guest.EthernetIpConfig
import dev.matryoshka.vm.core.guest.GuestClient
import dev.matryoshka.vm.core.image.BootImage
import dev.matryoshka.vm.core.image.ImageCatalog
import dev.matryoshka.vm.core.model.EngineType
import dev.matryoshka.vm.core.model.VmConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FormatsTest {

    @Test fun touchMessageLayout() {
        val m = ControlMessages.touch(ControlMessages.ACTION_MOVE, 3, 100, 200, 1080, 2340, 1f)
        assertEquals(32, m.size)
        val b = ByteBuffer.wrap(m) // big-endian
        assertEquals(2, b.get(0).toInt())
        assertEquals(2, b.get(1).toInt())
        assertEquals(3L, b.getLong(2))
        assertEquals(100, b.getInt(10))
        assertEquals(200, b.getInt(14))
        assertEquals(1080, b.getShort(18).toInt())
        assertEquals(2340, b.getShort(20).toInt())
        assertEquals(0xffff, b.getShort(22).toInt() and 0xffff)
    }

    @Test fun keyAndTextMessages() {
        assertEquals(14, ControlMessages.key(0, 4).size)
        val t = ControlMessages.text("привет")
        assertEquals(1, t[0].toInt())
        assertEquals("привет".toByteArray().size, ByteBuffer.wrap(t).getInt(1))
        assertEquals(21, ControlMessages.scroll(1, 2, 3, 4, 0f, -1f).size)
        assertEquals(0x8000.toShort(), ControlMessages.toSignedFixedPoint16(-1f))
        assertEquals(0x8000.toShort(), ControlMessages.toFixedPoint16(0.5f))
    }

    @Test fun ipConfigStoreFormat() {
        val bytes = EthernetIpConfig.encode(EthernetIpConfig.Plan(address = "10.77.1.2", prefixLength = 30, gateway = "10.77.1.1", dns = listOf("1.1.1.1", "8.8.8.8")))
        val i = DataInputStream(ByteArrayInputStream(bytes))
        assertEquals(3, i.readInt())
        val seen = mutableMapOf<String, String>()
        while (true) {
            when (val key = i.readUTF()) {
                "ipAssignment", "proxySettings", "id" -> seen[key] = i.readUTF()
                "linkAddress" -> seen[key] = i.readUTF() + "/" + i.readInt()
                "gateway" -> { assertEquals(0, i.readInt()); assertEquals(1, i.readInt()); seen[key] = i.readUTF() }
                "dns" -> seen[key] = (seen[key]?.plus(",") ?: "") + i.readUTF()
                "eos" -> break
                else -> error("unexpected key $key")
            }
        }
        assertEquals("STATIC", seen["ipAssignment"])
        assertEquals("10.77.1.2/30", seen["linkAddress"])
        assertEquals("10.77.1.1", seen["gateway"])
        assertEquals("1.1.1.1,8.8.8.8", seen["dns"])
        assertEquals("eth0", seen["id"])
    }

    @Test fun parsesPackagesWithEqualsInPath() {
        val out = """
            package:/data/app/~~Xk1J0K7W8nYlm4l8i9Jg7Q==/org.example.app-AbCd==/base.apk=org.example.app versionCode:42
            package:/system/app/Calc/Calc.apk=com.android.calculator2 versionCode:30
            garbage line
        """.trimIndent()
        val apps = GuestClient.parsePackages(out, isSystem = false)
        assertEquals(2, apps.size)
        assertEquals("org.example.app", apps[0].packageName)
        assertEquals("/data/app/~~Xk1J0K7W8nYlm4l8i9Jg7Q==/org.example.app-AbCd==/base.apk", apps[0].apkPaths.single())
        assertEquals(42L, apps[0].versionCode)
        assertEquals("com.android.calculator2", apps[1].packageName)
    }

    @Test fun bootImageV2AndV3Layout() {
        val v2 = ByteBuffer.allocate(1648).order(ByteOrder.LITTLE_ENDIAN)
        v2.put("ANDROID!".toByteArray()).putInt(8, 5000).putInt(16, 3000).putInt(36, 2048).putInt(40, 2)
        val l2 = BootImage.parse(v2.array())
        assertEquals(2048L, l2.kernelOffset)
        assertEquals(2048L + 6144, l2.ramdiskOffset)

        val v3 = ByteBuffer.allocate(1648).order(ByteOrder.LITTLE_ENDIAN)
        v3.put("ANDROID!".toByteArray()).putInt(8, 5000).putInt(12, 3000).putInt(40, 3)
        val l3 = BootImage.parse(v3.array())
        assertEquals(3, l3.headerVersion)
        assertEquals(4096L, l3.kernelOffset)
        assertEquals(4096L + 8192, l3.ramdiskOffset)
        assertEquals(3000, l3.ramdiskSize)
    }

    /** The catalog written by guest/tools/mkbundle.py must decode into the app's model. */
    @Test fun catalogFromPackerDecodes() {
        val text = javaClass.classLoader!!.getResource("sample-catalog.json")!!.readText()
        val catalog = Json { ignoreUnknownKeys = true }.decodeFromString(ImageCatalog.serializer(), text)
        val img = catalog.images.single()
        assertEquals(listOf(EngineType.HYPERVISOR, EngineType.EMULATOR, EngineType.CONTAINER), img.engines)
        assertEquals(listOf("rootfs.tar.gz"), img.filesFor(EngineType.CONTAINER).map { it.name })
        assertEquals(5, img.filesFor(EngineType.HYPERVISOR).size)
        assertTrue(img.files.all { it.url!!.startsWith("https://example.org/r/") && it.sha256.length == 64 })
    }

    @Test fun vmConfigJsonIsForwardCompatible() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val vm = VmConfig(id = "abc", name = "Test", engine = EngineType.CONTAINER, imageId = "img", netIndex = 7)
        val text = json.encodeToString(VmConfig.serializer(), vm)
        val back = json.decodeFromString(VmConfig.serializer(), text.replace("{", "{\"futureField\":1,"))
        assertEquals(vm, back)
        assertEquals("10.77.7.2", back.guestIp)
        assertTrue(text.contains("\"engine\": \"CONTAINER\"") || text.contains("\"engine\":\"CONTAINER\""))
    }
}
