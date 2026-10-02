package com.example.scifilauncher

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit

data class VpnGateServer(
    val ip: String, val country: String, val countryCode: String,
    val pingMs: Long?, val speedMbps: Long, val sessions: Int, val profile: String
)

/** Public directory only: no account keys, traffic, or connection history is uploaded. */
object VpnGateDirectory {
    const val DIRECTORY_URL = "https://www.vpngate.net/api/iphone/"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false).build()

    fun fetch(): List<VpnGateServer> {
        client.newCall(Request.Builder().url(DIRECTORY_URL).build()).execute().use { response ->
            check(response.isSuccessful) { "Directory unavailable (HTTP " + response.code + "). Try again later." }
            val stream = response.body?.byteStream() ?: error("Empty directory response")
            val bytes = stream.readBytesLimited(MAX_BYTES)
            val servers = parse(bytes.toString(Charsets.UTF_8))
            check(servers.isNotEmpty()) { "No compatible servers available. Try refreshing later." }
            return servers
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(output.size() + count <= limit) { "Directory response is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun parse(csv: String): List<VpnGateServer> {
        require(csv.length <= MAX_BYTES) { "Directory response is too large" }
        val rows = csvRows(csv)
        val headerIndex = rows.indexOfFirst { it.firstOrNull()?.trimStart('#') == "HostName" }
        require(headerIndex >= 0) { "Unrecognized VPN directory format" }
        val columns = rows[headerIndex].map { it.trimStart('#') }
        fun List<String>.field(name: String) = getOrNull(columns.indexOf(name)).orEmpty()
        return rows.drop(headerIndex + 1).mapNotNull { row ->
            runCatching {
                val ip = row.field("IP")
                require(isPublicIpv4(ip))
                val encoded = row.field("OpenVPN_ConfigData_Base64")
                require(encoded.length in 1..90_000)
                val profile = Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
                validateProfile(profile, ip)
                val country = row.field("CountryLong").filter { it.isLetter() || it == ' ' || it == '-' }.take(60)
                require(country.isNotBlank())
                VpnGateServer(ip, country, row.field("CountryShort").take(2),
                    row.field("Ping").toLongOrNull()?.takeIf { it >= 0 },
                    (row.field("Speed").toLongOrNull() ?: 0).coerceAtLeast(0) / 1_000_000,
                    (row.field("NumVpnSessions").toIntOrNull() ?: 0).coerceAtLeast(0), profile)
            }.getOrNull()
        }.distinctBy { it.ip }
    }

    internal fun isPublicIpv4(ip: String): Boolean {
        val octets = ip.split('.')
        if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || it.any { c -> !c.isDigit() } }) return false
        val n = octets.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] in 1..223 && n[0] !in listOf(10, 127) &&
            !(n[0] == 172 && n[1] in 16..31) && !(n[0] == 192 && n[1] == 168) &&
            !(n[0] == 169 && n[1] == 254) && !(n[0] == 100 && n[1] in 64..127) &&
            !(n[0] == 192 && n[1] == 0) && !(n[0] == 198 && n[1] in 18..19) &&
            !(n[0] == 198 && n[1] == 51 && n[2] == 100) &&
            !(n[0] == 203 && n[1] == 0 && n[2] == 113)
    }

    /** Only self-contained client profiles; never accept scripts, plugins, file paths,
     * management sockets, credentials prompts, or a different/private destination. */
    internal fun validateProfile(profile: String, expectedIp: String) {
        require(profile.length in 1..65_536 && '\u0000' !in profile)
        val allowed = setOf("client", "dev", "proto", "remote", "resolv-retry", "nobind",
            "persist-key", "persist-tun", "cipher", "data-ciphers", "data-ciphers-fallback",
            "auth", "verb", "mute", "remote-cert-tls", "verify-x509-name", "ns-cert-type",
            "key-direction", "comp-lzo", "compress", "auth-nocache", "tun-mtu", "mssfix",
            "sndbuf", "rcvbuf", "explicit-exit-notify", "redirect-gateway", "route-delay",
            "connect-retry", "connect-retry-max", "connect-timeout", "ping", "ping-restart",
            "reneg-sec", "tls-cipher", "tls-version-min", "tls-version-max", "pull")
        var block: String? = null
        var hasCa = false
        var remotes = 0
        var clientMode = false
        for (raw in profile.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) continue
            if (block != null) {
                if (line == "</$block>") block = null
                else require(!line.contains('<') && !line.contains('>'))
                continue
            }
            if (line.startsWith('<')) {
                val tag = line.removePrefix("<").removeSuffix(">")
                require(tag in setOf("ca", "cert", "key", "tls-auth", "tls-crypt"))
                if (tag == "ca") hasCa = true
                block = tag
                continue
            }
            val parts = line.split(Regex("\\s+"))
            require(parts[0] in allowed) { "Unsupported profile option" }
            if (parts[0] == "client") clientMode = true
            if (parts[0] == "dev") require(parts.getOrNull(1) == "tun")
            if (parts[0] == "remote") {
                require(parts.size in 3..4 && parts[1] == expectedIp && isPublicIpv4(parts[1]))
                require(parts[2].toIntOrNull() in 1..65535)
                if (parts.size == 4) require(parts[3] in setOf("udp", "tcp", "tcp-client", "udp4", "tcp4"))
                remotes++
            }
        }
        require(block == null && hasCa && clientMode && remotes > 0) { "Incomplete client profile" }
    }

    private fun csvRows(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < csv.length) {
            val c = csv[i++]
            when {
                c == '"' && quoted && i < csv.length && csv[i] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { row.add(field.toString()); field.setLength(0) }
                c == '\n' && !quoted -> {
                    row.add(field.toString().trimEnd('\r')); field.setLength(0)
                    rows.add(row); row = mutableListOf()
                    require(rows.size <= 10_000)
                }
                else -> field.append(c)
            }
            require(field.length <= 100_000 && row.size <= 32)
        }
        require(!quoted) { "Incomplete directory response" }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString().trimEnd('\r')); rows.add(row) }
        return rows
    }
}
