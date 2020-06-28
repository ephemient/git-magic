import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

val gitRemoteOriginUri: URI? by lazy {
    startParameter.projectDir
        ?.let { projectDir ->
            ProcessBuilder("git", "remote", "get-url", "origin")
                .directory(projectDir)
                .redirectInput(File("/dev/null"))
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(File("/dev/null"))
                .start()
        }
        ?.run {
            val text = inputStream.use { it.bufferedReader().readText().trim() }
            waitFor()
            text.takeIf { exitValue() == 0 }
        }
        ?.let { try { URI(it).parseServerAuthority() } catch (_: URISyntaxException) { null } }
}

val isTripAdvisor: Boolean
    get() {
        val hostMatch = gitRemoteOriginUri?.host?.run { '.' + removeSuffix(".") }
        return hostMatch?.endsWith(".github.com", ignoreCase = true) == true &&
            gitRemoteOriginUri?.rawPath?.trimStart('/')?.startsWith("tamgio/") == true ||
            hostMatch?.endsWith(".tripadvisor.com", ignoreCase = true) == true
    }

try {
    if (isTripAdvisor) InetAddress.getByName("maven.dev.tripadvisor.com")
} catch (_: UnknownHostException) {
    logger.warn("*** Using TripAdvisor project off VPN: setting gradle --offline ***")
    startParameter.isOffline = true
}
