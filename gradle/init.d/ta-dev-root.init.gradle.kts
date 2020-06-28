// Automatically inject TA-Dev-Root into a copy of the Java KeyStore for trusted certificates for Gradle.
// To enable, copy both this file and TA-Dev-Root.pem to ~/.gradle/init.d/

import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

val TRUST_STORE_PROP = "javax.net.ssl.trustStore"
val taDevRootFile = file("TA-Dev-Root.pem")

gradle.afterProject {
    if (this != rootProject || System.getProperty(TRUST_STORE_PROP)?.endsWith("+ta") == true) return@afterProject
    val defaultTrustStore = System.getProperty(TRUST_STORE_PROP)?.let { File(it) }
        ?: File(System.getProperty("java.home"), "lib/security/cacerts")
    val alternateTrustStore = file("build/tmp/cacerts+ta")
    try {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        val trustStorePassword = System.getProperty("${TRUST_STORE_PROP}Password")?.toCharArray()
        if (defaultTrustStore.exists()) defaultTrustStore.inputStream().use { keyStore.load(it, trustStorePassword) }
        val taDevRoot = with(CertificateFactory.getInstance("X.509")) {
            taDevRootFile.inputStream().use { generateCertificate(it) }
        } as X509Certificate
        keyStore.setCertificateEntry(taDevRoot.subjectX500Principal.name, taDevRoot)
        alternateTrustStore.parentFile.mkdirs()
        alternateTrustStore.outputStream().use {
            keyStore.store(it, trustStorePassword ?: "changeit".toCharArray())
        }
    } catch (e: Exception) {
        if (alternateTrustStore.exists()) alternateTrustStore.delete()
        throw e
    }
    System.setProperty(TRUST_STORE_PROP, alternateTrustStore.absolutePath)
    logger.info("$TRUST_STORE_PROP set to $alternateTrustStore")
}
