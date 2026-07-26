package com.mikeos.core.net

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Builds an [OkHttpClient] that trusts the daemon's self-signed localhost cert,
 * scoped ONLY to loopback hosts (127.0.0.1 / localhost). Traffic never leaves the
 * phone, so a permissive TrustManager is acceptable here and nowhere else.
 *
 * Non-loopback base URLs (e.g. the public hive/cloud) get a plain client with
 * normal certificate validation. Callers apply their own timeouts via `newBuilder()`.
 */
internal fun loopbackTrustingClient(baseUrl: String): OkHttpClient {
    val isLoopback = baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost")
    val builder = OkHttpClient.Builder()

    if (isLoopback && baseUrl.startsWith("https")) {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        builder.sslSocketFactory(sslContext.socketFactory, trustAll)
        builder.hostnameVerifier(HostnameVerifier { hostname, _ ->
            hostname == "127.0.0.1" || hostname == "localhost"
        })
    }
    return builder.build()
}

/**
 * Public wrapper over [loopbackTrustingClient] for use across packages (e.g. the
 * runtime's install step, which needs a loopback-trusting client to self-register
 * with the daemon). Same loopback-only trust scoping.
 */
fun loopbackTrustingClientPublic(baseUrl: String): OkHttpClient = loopbackTrustingClient(baseUrl)
