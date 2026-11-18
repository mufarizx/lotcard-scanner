package com.sanwa.lotcard.scanner

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Hasil satu panggilan API. */
sealed class ApiResult {
    /** Server menjawab (status apa pun). */
    data class Response(val code: Int, val json: JSONObject, val baseUrl: String) : ApiResult() {
        val ok: Boolean get() = code in 200..299
        val message: String get() = json.optString("message", "").ifBlank { "Server menjawab kode $code." }
    }

    /** Sertifikat https server belum dipercaya (self-signed). */
    data class UntrustedCert(
        val baseUrl: String,
        val fingerprint: String,
        val subject: String,
        val validUntil: String,
    ) : ApiResult()

    /** Tidak ada alamat yang bisa dihubungi. */
    data class NetworkError(val message: String) : ApiResult()
}

/** SHA-256 sertifikat, format AA:BB:CC:... */
fun certFingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

/**
 * Percaya sertifikat yang dipercaya Android ATAU yang sidik jarinya sudah
 * disetujui pengguna (server lot-card memakai sertifikat self-signed).
 */
private class PinningTrustManager(private val pins: Set<String>) : X509TrustManager {
    private val system: X509TrustManager = TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    @Volatile
    var rejected: X509Certificate? = null

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isNotEmpty() && certFingerprint(chain[0]) in pins) return
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            rejected = chain.firstOrNull()
            throw e
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

class ApiClient(context: Context) {
    private val prefs = Prefs(context)

    private fun buildClient(trust: PinningTrustManager, pins: Set<String>): OkHttpClient {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        // Sertifikat yang dipercaya lewat sidik jari tidak dicek nama host-nya
        // (sertifikat self-signed dibuat untuk IP lokal, tapi juga dipakai
        // lewat IP publik). Sertifikat resmi tetap dicek seperti biasa.
        val verifier = HostnameVerifier { host, session ->
            val leaf = try { session.peerCertificates.firstOrNull() as? X509Certificate } catch (e: Exception) { null }
            if (leaf != null && certFingerprint(leaf) in pins) true else defaultVerifier.verify(host, session)
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier(verifier)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Kirim request ke alamat-alamat server berurutan (terakhir berhasil →
     * lokal → publik). Berhenti di alamat pertama yang menjawab.
     */
    suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        withToken: Boolean = true,
        onlyBaseUrl: String? = null,
    ): ApiResult = withContext(Dispatchers.IO) {
        val urls = if (onlyBaseUrl != null) listOf(onlyBaseUrl) else prefs.baseUrls()
        if (urls.isEmpty()) return@withContext ApiResult.NetworkError("Alamat server belum diisi. Buka Pengaturan server.")

        val pins = prefs.pinnedFingerprints
        var certProblem: ApiResult.UntrustedCert? = null
        val failures = mutableListOf<String>()

        for (base in urls) {
            val trust = PinningTrustManager(pins)
            val client = buildClient(trust, pins)
            val builder = Request.Builder()
                .url(base + path)
                .header("Accept", "application/json")
            if (withToken) prefs.token?.let { builder.header("Authorization", "Bearer $it") }
            if (method == "POST") {
                val json = (body ?: JSONObject()).toString()
                builder.post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            } else {
                builder.get()
            }
            try {
                client.newCall(builder.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val json = try {
                        JSONObject(text)
                    } catch (e: Exception) {
                        // Jawaban bukan JSON → kemungkinan bukan server lot-card / alamat salah.
                        JSONObject().put("message", "Jawaban server tidak dikenali (kode ${resp.code}). Periksa alamat server.")
                    }
                    if (onlyBaseUrl == null) prefs.lastGoodUrl = base
                    return@withContext ApiResult.Response(resp.code, json, base)
                }
            } catch (e: SSLException) {
                val cert = trust.rejected
                if (cert != null) {
                    if (certProblem == null) {
                        certProblem = ApiResult.UntrustedCert(
                            base,
                            certFingerprint(cert),
                            cert.subjectX500Principal.name,
                            SimpleDateFormat("dd/MM/yyyy", Locale.US).format(cert.notAfter),
                        )
                    }
                    failures += "${prefs.labelFor(base)}: sertifikat belum dipercaya"
                } else {
                    failures += "${prefs.labelFor(base)}: ${e.message ?: "gagal SSL"}"
                }
            } catch (e: IllegalArgumentException) {
                failures += "${prefs.labelFor(base)}: alamat tidak valid"
            } catch (e: IOException) {
                failures += "${prefs.labelFor(base)}: tidak tersambung"
            }
        }
        certProblem ?: ApiResult.NetworkError(
            "Tidak terhubung ke server.\n" + failures.joinToString("\n") +
                "\n\nPastikan HP tersambung ke Wi-Fi kantor atau internet."
        )
    }
}
