package com.freeturn.app.domain.server

import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCommandTest {
    @Test
    fun startCommandAddsVlessBond() {
        val args = ServerCommand.Start(
            ServerOptions(
                listen = "0.0.0.0:56000",
                connect = "127.0.0.1:443",
                vless = true,
                vlessBond = true
            )
        ).toArgv()

        assertTrue(args.contains("--vless"))
        assertTrue(args.contains("--vless-bond"))
    }
}
