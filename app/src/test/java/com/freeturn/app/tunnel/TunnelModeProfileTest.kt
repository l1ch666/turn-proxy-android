package com.freeturn.app.tunnel

import com.freeturn.app.data.ProfileJson
import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelModeProfileTest {

    @Test
    fun unknownStorageValueFallsBackToLocalProxy() {
        assertEquals(TunnelMode.LOCAL_PROXY, TunnelMode.fromStorage("legacy"))
    }

    @Test
    fun oldProfileJsonDefaultsToLocalProxy() {
        val profiles = ProfileJson.decodeList(
            """
            [{
              "id": "old",
              "name": "Old profile",
              "client": {
                "serverAddress": "1.2.3.4:56000",
                "vkLink": "https://vk.com/call/join/test"
              }
            }]
            """.trimIndent()
        )

        assertEquals(TunnelMode.LOCAL_PROXY, profiles.single().client.tunnelMode)
        assertEquals("", profiles.single().client.fullTunnelClientUri)
        assertEquals(false, profiles.single().client.enableVlessBond)
    }
}
