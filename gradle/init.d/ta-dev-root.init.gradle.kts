import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

val TRUST_STORE_PROP = "javax.net.ssl.trustStore"
val taDevRootFile = file("TA-Dev-Root.pem")

gradle.afterProject {
    if (this != rootProject || System.getProperty(TRUST_STORE_PROP) != null) return@afterProject
    val fs = FileSystems.getDefault()
    val securityDir = fs.getPath(System.getProperty("java.home"), "lib", "security")
    val defaultTrustStore = securityDir.resolve("cacerts")
    val alternateTrustStore = securityDir.resolve("jssecacerts")
    try {
        if (Files.notExists(defaultTrustStore)) return@afterProject
        if (!Files.exists(alternateTrustStore) ||
            Files.getLastModifiedTime(defaultTrustStore) >=
                Files.getLastModifiedTime(alternateTrustStore)
        ) {
            if (!Files.isWritable(securityDir)) logger.warn("$securityDir is not writable")
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            val trustStorePassword =
                System.getProperty("javax.net.ssl.trustStorePassword")?.toCharArray()
            Files.newInputStream(defaultTrustStore).use { keyStore.load(it, trustStorePassword) }
            val taDevRoot = with(CertificateFactory.getInstance("X.509")) {
                taDevRootFile.inputStream().use { generateCertificate(it) }
            } as X509Certificate
            keyStore.setCertificateEntry(taDevRoot.subjectX500Principal.name, taDevRoot)
            val tempFile = Files.createTempFile(securityDir, "jssecacerts", null)
            try {
                Files.newOutputStream(tempFile).use { outputStream ->
                    keyStore.store(outputStream, trustStorePassword ?: "changeit".toCharArray())
                    outputStream.flush()
                    try {
                        Files.setPosixFilePermissions(
                            tempFile,
                            Files.getPosixFilePermissions(defaultTrustStore)
                        )
                    } catch (e: UnsupportedOperationException) {
                        logger.warn("trustStore warning", e)
                    }
                    try {
                        Files.move(
                            tempFile,
                            alternateTrustStore,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.copy(
                            tempFile,
                            alternateTrustStore,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES
                        )
                    }
                }
                logger.info("trustStore copied")
            } finally {
                Files.deleteIfExists(tempFile)
            }
        }
    } catch (e: Exception) {
        logger.error("trustStore error", e)
        return@afterProject
    }
    System.setProperty(TRUST_STORE_PROP, alternateTrustStore.toAbsolutePath().toString())
    logger.info("$TRUST_STORE_PROP set to $alternateTrustStore")
}
