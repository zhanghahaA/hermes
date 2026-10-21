package com.hermes.mobile.data.store

/** 诊断信息只保留排错所需内容，不导出连接地址、凭据或私钥。 */
object DiagnosticRedactor {
    private const val Hidden = "[已隐藏]"
    private val privateKey = Regex("-----BEGIN [^-\\r\\n]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [^-\\r\\n]*PRIVATE KEY-----|$)")
    private val authorization = Regex("(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9+/=_~.:-]+")
    private val credentials = Regex("""(?i)(["']?(?:api[_-]?key|api_server_key|password|passwd|token|secret|ssh[_-]?password)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;]+)""")
    private val url = Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s<>"']+""")
    private val ipv4 = Regex("""(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])""")
    private val ipv6 = Regex("""(?i)(?<![\w:])(?:[0-9a-f]{1,4}:){2,}[0-9a-f:]*[0-9a-f](?![\w:])|(?<![\w:])::(?:[0-9a-f]{1,4}:)*[0-9a-f]{1,4}(?![\w:])""")
    private val hostname = Regex("""(?i)(?<![\w.])(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z][a-z0-9-]*(?![\w.])""")
    private val stackFrame = Regex("""\s*at [\w.$/]+\([^\r\n]*\)\s*""")

    fun redact(text: String, privateValues: Iterable<String> = emptyList()): String {
        var safe = text
        // 长值先替换，避免先处理短的地址片段后，完整凭据无法再匹配。
        privateValues.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }.forEach {
            safe = safe.replace(it, Hidden, ignoreCase = true)
        }
        safe = privateKey.replace(safe, Hidden)
        safe = authorization.replace(safe, Hidden)
        safe = credentials.replace(safe) { it.groupValues[1] + Hidden }
        safe = url.replace(safe, Hidden)
        safe = ipv4.replace(safe, Hidden)
        safe = ipv6.replace(safe) { match ->
            val address = match.value
            if ("::" in address || address.count { it == ':' } >= 3 || address.split(':').any { it.length > 2 }) Hidden else address
        }
        return safe.lineSequence().joinToString("\n") { line ->
            // 栈帧是类名、源码文件和行号，不把包名当域名抹掉；异常消息仍照常脱敏。
            if (stackFrame.matches(line)) line else hostname.replace(line, Hidden)
        }
    }
}
