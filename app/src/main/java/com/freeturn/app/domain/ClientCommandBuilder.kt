package com.freeturn.app.domain

import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.data.DnsMode
import com.freeturn.app.tunnel.ClientTransportResolver

object ClientCommandBuilder {
    fun build(
        executable: String,
        cfg: ClientConfig,
        srv: AppPreferences.ServerOpts,
        carrierDns: String = ""
    ): List<String> {
        val cmdArgs = mutableListOf<String>()

        if (cfg.isRawMode) {
            val parts = cfg.rawCommand.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            cmdArgs.add(executable)
            cmdArgs.addAll(parts.drop(1))
            return cmdArgs
        }

        val transport = ClientTransportResolver.resolve(cfg, serverVlessBond = srv.vlessBond)
        cmdArgs.add(executable)
        cmdArgs.add("-peer")
        cmdArgs.add(cfg.serverAddress)

        cmdArgs.add(if (cfg.vkLink.contains("yandex")) "-yandex-link" else "-vk-link")
        cmdArgs.add(cfg.vkLink)
        cmdArgs.add("-listen")
        cmdArgs.add(cfg.localPort)
        if (cfg.threads > 0) {
            cmdArgs.add("-n")
            cmdArgs.add(cfg.threads.toString())
        }
        if (cfg.streamsPerCred > 0 && cfg.streamsPerCred != 10) {
            cmdArgs.add("-streams-per-cred")
            cmdArgs.add(cfg.streamsPerCred.toString())
        }
        if (transport.vlessMode) cmdArgs.add("-vless")
        else if (cfg.useUdp) cmdArgs.add("-udp")
        if (transport.vlessBond) cmdArgs.add("-vless-bond")
        if (srv.wrapEnabled &&
            srv.wrapKey.length == 64 &&
            srv.wrapKey.matches(Regex("^[0-9a-fA-F]+$"))
        ) {
            cmdArgs.add("-wrap")
            cmdArgs.add("-wrap-key")
            cmdArgs.add(srv.wrapKey)
        }
        // VK's captchaNotRobot auto-solve is dead server-side (anti-bot validates
        // signals only a real browser engine produces). The in-app WebView
        // auto-solver handles captchas now, so always run the core in manual mode:
        // it skips the doomed auto attempts (which only waste time and flag the IP)
        // and immediately serves the captcha proxy that the WebView drives.
        cmdArgs.add("--manual-captcha")

        if (cfg.debugMode) cmdArgs.add("-debug")
        if (cfg.useCarrierDns && carrierDns.isNotBlank()) {
            cmdArgs.add("-dns-servers")
            cmdArgs.add(carrierDns)
        }
        if (cfg.dnsMode == DnsMode.UDP || cfg.dnsMode == DnsMode.DOH) {
            cmdArgs.add("-dns")
            cmdArgs.add(cfg.dnsMode)
        }
        if (cfg.forcePort443) {
            cmdArgs.add("-port")
            cmdArgs.add("443")
        }
        if (cfg.magicSwitch) {
            val turn = cfg.magicTurn.trim()
            if (turn.isNotEmpty()) {
                cmdArgs.add("-turn")
                cmdArgs.add(turn)
            }
        }
        return cmdArgs
    }

    fun sanitizeForLogs(args: List<String>): String {
        val secretValueFlags = setOf("-vk-link", "-yandex-link", "-wrap-key")
        var maskNext = false
        return args.joinToString(" ") { arg ->
            if (maskNext) {
                maskNext = false
                "***"
            } else if (arg in secretValueFlags) {
                maskNext = true
                arg
            } else {
                arg
            }
        }
    }
}
