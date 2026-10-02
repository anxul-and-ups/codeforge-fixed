package com.example

import com.example.data.security.KeyStoreManager
import com.example.ui.components.parseMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeForgeUnitTest {

    @Test
    fun testSecretDetection() {
        assertTrue(KeyStoreManager.isPotentialSecretFile(".env"))
        assertTrue(KeyStoreManager.isPotentialSecretFile("release.keystore"))
        assertTrue(KeyStoreManager.isPotentialSecretFile("google-services.json"))
        assertTrue(KeyStoreManager.isPotentialSecretFile("id_rsa"))
        assertFalse(KeyStoreManager.isPotentialSecretFile("MainActivity.kt"))
        assertFalse(KeyStoreManager.isPotentialSecretFile("README.md"))
    }

    @Test
    fun testSecretDetectionDoesNotFlagNormalSources() {
        assertFalse(KeyStoreManager.isPotentialSecretFile("SecretsScreen.kt"))
        assertFalse(KeyStoreManager.isPotentialSecretFile("app/src/CredentialsHelper.kt"))
        assertFalse(KeyStoreManager.isPotentialSecretFile(".env.example"))
        assertTrue(KeyStoreManager.isPotentialSecretFile("app/.env.production"))
        assertTrue(KeyStoreManager.isPotentialSecretFile("keys/upload.jks"))
    }

    @Test
    fun testMarkdownParsing() {
        val markdown = """
            # CodeForge Agent
            Here is a test:
            ```kotlin
            fun hello() = "world"
            ```
            - Bullet 1
            - Bullet 2
        """.trimIndent()

        val blocks = parseMarkdownBlocks(markdown)
        assertTrue(blocks.isNotEmpty())
    }

    @Test
    fun testKeyMasking() {
        val key = "sk-ant-api03-1234567890abcdef"
        val masked = KeyStoreManager.maskKey(key)
        assertTrue(masked.startsWith("sk-a"))
        assertTrue(masked.endsWith("cdef"))
        assertTrue(masked.contains("••••••••"))
    }
}
