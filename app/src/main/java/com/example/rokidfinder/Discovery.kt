package com.example.rokidfinder

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds the Mac "brain" on the LAN so no IP address is ever typed into the glasses.
 *
 * Two rungs, in order: mDNS via [NsdManager], then the last endpoint that actually
 * worked (SharedPreferences). The cache exists because consumer routers drop multicast
 * unpredictably — band steering between 2.4 and 5 GHz is enough to make Bonjour vanish
 * for a minute. Without the cache, a flaky router moment becomes a dead product; with
 * it, it is a non-event.
 *
 * [resolve] blocks for up to ~10 s and must never be called from the main thread.
 */
object Discovery {

    private const val TAG = "RokidFinder.Discovery"
    private const val SERVICE_TYPE = "_syntropic-brain._tcp."
    private const val MDNS_TIMEOUT_MS = 5_000L

    private const val PREFS = "rokidfinder_link"
    private const val KEY_HOST = "link_last_host"
    private const val KEY_PORT = "link_last_port"
    private const val KEY_OK_TS = "link_last_ok_ts"

    data class Endpoint(val host: String, val port: Int, val fromCache: Boolean)

    /** Blocking resolve. Returns null when neither mDNS nor the cache yields a host. */
    fun resolve(context: Context): Endpoint? {
        discoverViaMdns(context)?.let { return it }
        Log.i(TAG, "mDNS found nothing; falling back to cached endpoint")
        return cached(context)
    }

    /** Remember an endpoint that produced a real connection. */
    fun cache(context: Context, host: String, port: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putLong(KEY_OK_TS, System.currentTimeMillis())
            .apply()
    }

    private fun cached(context: Context): Endpoint? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val port = prefs.getInt(KEY_PORT, 0)
        if (port <= 0) return null
        // The protocol allots the cache rung 5 s of its own. We have no socket to
        // probe with here, so the budget is spent by the caller's connect timeout;
        // sleeping would only delay it.
        Log.i(TAG, "using cached endpoint $host:$port (ok ${prefs.getLong(KEY_OK_TS, 0L)})")
        return Endpoint(host, port, fromCache = true)
    }

    @Suppress("DEPRECATION") // resolveService: the typed callback API is API 34, device is 32.
    private fun discoverViaMdns(context: Context): Endpoint? {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return null

        val latch = CountDownLatch(1)
        val found = AtomicReference<Endpoint?>(null)
        val resolving = AtomicBoolean(false)

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "resolve failed: $errorCode")
                resolving.set(false)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                val port = serviceInfo.port
                if (host != null && port > 0) {
                    found.set(Endpoint(host, port, fromCache = false))
                    latch.countDown()
                } else {
                    resolving.set(false)
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "discovery start failed: $errorCode")
                latch.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String?) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // One resolve at a time: NsdManager on older HALs misbehaves when
                // resolves overlap, and there is only ever one brain on the LAN.
                if (resolving.compareAndSet(false, true)) {
                    runCatching { nsd.resolveService(serviceInfo, resolveListener) }
                        .onFailure { resolving.set(false) }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
        }

        return try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            latch.await(MDNS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            found.get()
        } catch (t: Throwable) {
            Log.w(TAG, "mDNS discovery threw", t)
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        }
    }
}
