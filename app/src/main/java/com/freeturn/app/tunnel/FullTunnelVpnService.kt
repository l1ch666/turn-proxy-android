package com.freeturn.app.tunnel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.freeturn.app.MainActivity
import com.freeturn.app.domain.NetworkHandoverPolicy
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

class FullTunnelVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stopping = AtomicBoolean(false)
    private val interfaceCallbacks =
        ConcurrentHashMap<InterfaceUpdateListener, ConnectivityManager.NetworkCallback>()

    private var commandServer: CommandServer? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var lastConfigJson: String? = null
    private var openAppIntent: android.app.PendingIntent? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        openAppIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting full tunnel"))

        when (intent?.action) {
            ACTION_STOP -> serviceScope.launch { stopCoreAndSelf() }
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON)
                    ?: intent.getStringExtra(EXTRA_CONFIG_PATH)?.let { path ->
                        File(path).takeIf { it.isFile }?.readText()
                    }
                if (configJson.isNullOrBlank()) {
                    failAndStop("Full tunnel config is empty")
                } else {
                    serviceScope.launch { startCore(configJson) }
                }
            }
            else -> failAndStop("Unknown full tunnel action")
        }

        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        serviceScope.launch { stopCore() }
        serviceScope.cancel()
        FullTunnelRuntime.markStopped()
        super.onDestroy()
    }

    override fun onRevoke() {
        serviceScope.launch { stopCoreAndSelf() }
    }

    private suspend fun startCore(configJson: String) {
        FullTunnelRuntime.markStarting()
        try {
            if (prepare(this) != null) error("VPN permission is not granted")
            stopCore()
            applicationContext.ensureLibboxSetup()

            val server = CommandServer(this, this)
            server.start()
            server.checkConfig(configJson)
            server.startOrReloadService(
                configJson,
                OverrideOptions().apply {
                    excludePackage = StringArray(listOf(packageName).iterator())
                }
            )
            commandServer = server
            lastConfigJson = configJson
            FullTunnelRuntime.markRunning()
            withContext(Dispatchers.Main) {
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification("Full tunnel is running"))
            }
        } catch (e: Exception) {
            failAndStop(FullTunnelUriValidator.sanitizeMessage(e.message ?: "Full tunnel start failed"))
        }
    }

    private suspend fun stopCoreAndSelf() {
        stopCore()
        withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private suspend fun stopCore() {
        if (!stopping.compareAndSet(false, true)) return
        try {
            interfaceCallbacks.values.forEach { callback ->
                runCatching {
                    getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
                }
            }
            interfaceCallbacks.clear()

            val pfd = tunDescriptor
            if (pfd != null) {
                runCatching { pfd.close() }
                tunDescriptor = null
            }

            val server = commandServer
            commandServer = null
            if (server != null) {
                runCatching { server.closeService() }.onFailure {
                    runCatching { server.setError("android: close service: ${it.message}") }
                }
                runCatching { server.close() }
            }
            lastConfigJson = null
            FullTunnelRuntime.markStopped()
        } finally {
            stopping.set(false)
        }
    }

    private fun failAndStop(message: String) {
        val sanitized = FullTunnelUriValidator.sanitizeMessage(message)
        Log.e(TAG, sanitized)
        FullTunnelRuntime.markFailed(sanitized)
        serviceScope.launch { stopCoreAndSelf() }
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        val builder = Builder()
            .setSession("FreeTurn full tunnel")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            val dnsServerAddress = runCatching { options.dnsServerAddress.value }
                .getOrNull()
                .orEmpty()
            if (dnsServerAddress.isNotBlank()) builder.addDnsServer(dnsServerAddress)

            addRoutes(builder, options)
            applyPackageRules(builder, options)
        }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(
                    options.httpProxyServer,
                    options.httpProxyServerPort,
                    options.httpProxyBypassDomain.toList()
                )
            )
        }

        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Failed to exclude own app from VPN", e)
        }

        val pfd = builder.establish() ?: error("android: VPN establish returned null")
        tunDescriptor = pfd
        return pfd.fd
    }

    private fun addRoutes(builder: Builder, options: TunOptions) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val inet4RouteAddress = options.inet4RouteAddress
            if (inet4RouteAddress.hasNext()) {
                while (inet4RouteAddress.hasNext()) {
                    builder.addRoute(inet4RouteAddress.next().toAndroidIpPrefix())
                }
            } else {
                builder.addRoute("0.0.0.0", 0)
            }

            val inet6RouteAddress = options.inet6RouteAddress
            if (inet6RouteAddress.hasNext()) {
                while (inet6RouteAddress.hasNext()) {
                    builder.addRoute(inet6RouteAddress.next().toAndroidIpPrefix())
                }
            }

            val inet4RouteExcludeAddress = options.inet4RouteExcludeAddress
            while (inet4RouteExcludeAddress.hasNext()) {
                builder.excludeRoute(inet4RouteExcludeAddress.next().toAndroidIpPrefix())
            }

            val inet6RouteExcludeAddress = options.inet6RouteExcludeAddress
            while (inet6RouteExcludeAddress.hasNext()) {
                builder.excludeRoute(inet6RouteExcludeAddress.next().toAndroidIpPrefix())
            }
        } else {
            val inet4RouteAddress = options.inet4RouteRange
            if (inet4RouteAddress.hasNext()) {
                while (inet4RouteAddress.hasNext()) {
                    val address = inet4RouteAddress.next()
                    builder.addRoute(address.address(), address.prefix())
                }
            } else {
                builder.addRoute("0.0.0.0", 0)
            }

            val inet6RouteAddress = options.inet6RouteRange
            while (inet6RouteAddress.hasNext()) {
                val address = inet6RouteAddress.next()
                builder.addRoute(address.address(), address.prefix())
            }
        }
    }

    private fun applyPackageRules(builder: Builder, options: TunOptions) {
        val includePackage = options.includePackage
        if (includePackage.hasNext()) {
            while (includePackage.hasNext()) {
                runCatching { builder.addAllowedApplication(includePackage.next()) }
            }
        }

        val excludePackage = options.excludePackage
        while (excludePackage.hasNext()) {
            runCatching { builder.addDisallowedApplication(excludePackage.next()) }
        }
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("android: process lookup unavailable")
        val uid = getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort)
        )
        if (uid == Process.INVALID_UID) error("android: connection owner not found")
        val packages = packageManager.getPackagesForUid(uid)
        return ConnectionOwner().apply {
            userId = uid
            userName = packages?.firstOrNull().orEmpty()
            processPath = ""
            androidPackageName = packages?.firstOrNull().orEmpty()
        }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val javaInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        val interfaces = mutableListOf<BoxNetworkInterface>()

        for (network in connectivity.allNetworks) {
            val properties = connectivity.getLinkProperties(network) ?: continue
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (!capabilities.isFullTunnelOutboundNetwork()) continue
            val name = properties.interfaceName ?: continue
            val javaInterface = javaInterfaces.firstOrNull { it.name == name } ?: continue

            interfaces += BoxNetworkInterface().apply {
                this.name = name
                dnsServer = StringArray(properties.dnsServers.mapNotNull { it.hostAddress }.iterator())
                type = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                index = javaInterface.index
                mtu = runCatching { javaInterface.mtu }.getOrDefault(1500)
                addresses = StringArray(javaInterface.interfaceAddresses.map { it.toPrefix() }.iterator())
                flags = javaInterface.safeFlags(capabilities)
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }

        return InterfaceArray(interfaces.iterator())
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val outboundNetwork = connectivity.findFullTunnelOutboundNetwork(network) ?: return
                updateDefaultInterface(listener, outboundNetwork)
                if (connectivity.isFullTunnelOutboundNetwork(network)) {
                    commandServer?.resetNetwork()
                }
            }

            override fun onLost(network: Network) {
                if (connectivity.isFullTunnelOutboundNetwork(network)) {
                    commandServer?.resetNetwork()
                }
                connectivity.findFullTunnelOutboundNetwork()?.let {
                    updateDefaultInterface(listener, it)
                }
            }
        }
        interfaceCallbacks[listener] = callback
        connectivity.registerDefaultNetworkCallback(callback)
        connectivity.findFullTunnelOutboundNetwork()?.let { updateDefaultInterface(listener, it) }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val callback = interfaceCallbacks.remove(listener) ?: return
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        }
    }

    private fun updateDefaultInterface(listener: InterfaceUpdateListener, network: Network) {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val properties = connectivity.getLinkProperties(network) ?: return
        val name = properties.interfaceName ?: return
        val javaInterface = NetworkInterface.getByName(name) ?: return
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return
        if (!capabilities.isFullTunnelOutboundNetwork()) return
        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        listener.updateDefaultInterface(name, javaInterface.index, isWifi, isMetered)
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() = Unit

    override fun readWIFIState(): WIFIState? = null

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun systemCertificates(): StringIterator {
        val certificates = mutableListOf<String>()
        val keyStore = KeyStore.getInstance("AndroidCAStore")
        keyStore.load(null, null)
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            val cert = keyStore.getCertificate(aliases.nextElement()) ?: continue
            certificates += "-----BEGIN CERTIFICATE-----\n" +
                Base64.encodeToString(cert.encoded, Base64.NO_WRAP) +
                "\n-----END CERTIFICATE-----"
        }
        return StringArray(certificates.iterator())
    }

    override fun sendNotification(notification: Notification) {
        Log.i(TAG, FullTunnelUriValidator.sanitizeMessage(notification.title + ": " + notification.body))
    }

    override fun getSystemProxyStatus(): SystemProxyStatus =
        SystemProxyStatus().apply {
            available = false
            enabled = false
        }

    override fun serviceReload() {
        val config = lastConfigJson ?: return
        serviceScope.launch { startCore(config) }
    }

    override fun serviceStop() {
        serviceScope.launch { stopCoreAndSelf() }
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

    override fun writeDebugMessage(message: String?) {
        val safe = FullTunnelUriValidator.sanitizeMessage(message.orEmpty())
        if (safe.isNotBlank()) Log.d(TAG, safe)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_FULL_TUNNEL,
                    "FreeTurn full tunnel",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(text: String): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_FULL_TUNNEL)
            .setContentTitle("FreeTurn")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()

    class StringArray(iterator: Iterator<String>) : StringIterator {
        // Buffer the iterator so len() reports the real size. libbox uses len()
        // for some collections (package rules, DNS servers, certificates); a
        // hardcoded 0 made those appear empty and could break self-exclusion,
        // DNS and certificate handling in the tunnel.
        private val items: List<String> = iterator.asSequence().toList()
        private val cursor = items.iterator()

        override fun len(): Int = items.size

        override fun hasNext(): Boolean = cursor.hasNext()

        override fun next(): String = cursor.next()
    }

    private class InterfaceArray(private val iterator: Iterator<BoxNetworkInterface>) :
        NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): BoxNetworkInterface = iterator.next()
    }

    private fun StringIterator.toList(): List<String> {
        val result = mutableListOf<String>()
        while (hasNext()) result += next()
        return result
    }

    private fun InterfaceAddress.toPrefix(): String {
        val host = if (address is Inet6Address) {
            Inet6Address.getByAddress(address.address).hostAddress
        } else {
            address.hostAddress
        }.orEmpty().substringBefore('%')
        return "$host/$networkPrefixLength"
    }

    private fun RoutePrefix.toAndroidIpPrefix(): android.net.IpPrefix =
        android.net.IpPrefix(InetAddress.getByName(address()), prefix())

    private fun NetworkInterface.safeFlags(capabilities: NetworkCapabilities): Int {
        var dumpFlags = 0
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            dumpFlags = dumpFlags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
        }
        if (isLoopback) dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
        if (isPointToPoint) dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
        if (supportsMulticast()) dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST
        return dumpFlags
    }

    private fun ConnectivityManager.findFullTunnelOutboundNetwork(preferred: Network? = null): Network? {
        if (preferred != null && isFullTunnelOutboundNetwork(preferred)) return preferred
        activeNetwork?.takeIf { isFullTunnelOutboundNetwork(it) }?.let { return it }
        return allNetworks.firstOrNull { isFullTunnelOutboundNetwork(it) }
    }

    private fun ConnectivityManager.isFullTunnelOutboundNetwork(network: Network): Boolean =
        getNetworkCapabilities(network)?.isFullTunnelOutboundNetwork() == true

    private fun NetworkCapabilities.isFullTunnelOutboundNetwork(): Boolean =
        NetworkHandoverPolicy.shouldUseForFullTunnelOutbound(
            hasInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isVpn = hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        )

    companion object {
        private const val TAG = "FullTunnelVpnService"
        private const val CHANNEL_FULL_TUNNEL = "FullTunnelChannel"
        private const val NOTIFICATION_ID = 12
        private const val ACTION_START = "com.freeturn.app.tunnel.START"
        private const val ACTION_STOP = "com.freeturn.app.tunnel.STOP"
        private const val EXTRA_CONFIG_JSON = "config_json"
        private const val EXTRA_CONFIG_PATH = "config_path"

        private val libboxSetup = AtomicBoolean(false)

        fun start(context: Context, configJson: String) {
            val intent = Intent(context, FullTunnelVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG_JSON, configJson)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FullTunnelVpnService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun Context.ensureLibboxSetup() {
            if (!libboxSetup.compareAndSet(false, true)) return
            Libbox.setDefaultAppId(packageName)
            Libbox.setup(
                SetupOptions().apply {
                    basePath = filesDir.absolutePath
                    workingPath = filesDir.absolutePath
                    tempPath = cacheDir.absolutePath
                    fixAndroidStack = true
                    commandServerListenPort = 0
                    commandServerSecret = ""
                    logMaxLines = 256
                    debug = false
                }
            )
        }
    }
}
