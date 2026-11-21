package com.example

import com.example.data.agent.FileEditEngine
import com.example.data.agent.LoopGuard
import com.example.data.agent.LoopGuardDecision
import com.example.data.agent.RepoToolInstaller
import com.example.data.agent.SurgicalPatchEngine
import com.example.data.security.KeyStoreManager
import com.example.ui.components.parseMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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

    @Test
    fun testFileEditEngineMultiTierMatching() {
        val original = """
            package com.example
            
            fun calculateTotal(items: List<Int>): Int {
                var sum = 0
                for (i in items) {
                    sum += i
                }
                return sum
            }
        """.trimIndent()

        // 1. Line-number prefix stripping + indentation-insensitive match
        val oldWithLineNumbers = """
               4	var sum = 0
               5	for (i in items) {
               6	    sum += i
               7	}
        """.trimIndent()
        val newBlock = """
            val sum = items.sum()
        """.trimIndent()

        val res = FileEditEngine.applyEdit(original, oldWithLineNumbers, newBlock)
        assertTrue("Expected multi-tier edit to succeed: ${res.message}", res.success)
        assertTrue(res.newContent?.contains("val sum = items.sum()") == true)

        // 2. Nearest fuzzy region feedback on failure
        val failRes = FileEditEngine.applyEdit(original, "var sum = 999\nfor (x in items)", "val sum = 0")
        assertFalse(failRes.success)
        assertTrue("Expected nearest match snippet with line numbers", failRes.message.contains("Nearest match in file"))
    }

    @Test
    fun testFileEditEngineLineRangeAndInsert() {
        val original = "line1\nline2\nline3\nline4"
        val inserted = FileEditEngine.insertAfterLine(original, 2, "insertedLine")
        assertTrue(inserted.success)
        assertEquals("line1\nline2\ninsertedLine\nline3\nline4", inserted.newContent)

        val replaced = FileEditEngine.replaceLineRange(original, 2, 3, "newMiddle")
        assertTrue(replaced.success)
        assertEquals("line1\nnewMiddle\nline4", replaced.newContent)
    }

    @Test
    fun testSurgicalPatchEngineUnifiedDiffAndFuzzyApply() {
        val original = """
            def greet(name):
                prefix = "Hello"
                msg = f"{prefix}, {name}!"
                return msg

            def add(a, b):
                return a - b
        """.trimIndent()

        // Patch with intentionally shifted line number (hunk says line 3 instead of line 6) to verify fuzzy offset
        val patch = """
            --- a/app.py
            +++ b/app.py
            @@ -3,3 +3,3 @@
             def add(a, b):
            -    return a - b
            +    return a + b
        """.trimIndent()

        val (patched, outcome) = SurgicalPatchEngine.applyUnifiedPatch(original, patch, "app.py")
        assertTrue("Expected unified patch to succeed: ${outcome.message}", outcome.success)
        assertTrue(patched.contains("return a + b"))
        assertFalse(patched.contains("return a - b"))
        assertEquals(1, outcome.linesAdded)
        assertEquals(1, outcome.linesRemoved)
        assertTrue(outcome.diffPreview.contains("--- a/app.py"))
        assertTrue(outcome.diffPreview.contains("+++ b/app.py"))
        assertTrue(outcome.diffPreview.contains("-    return a - b"))
        assertTrue(outcome.diffPreview.contains("+    return a + b"))
    }

    @Test
    fun testSurgicalPatchGuardBlocksFullRewriteOnExistingLargeFiles() {
        assertNull(SurgicalPatchEngine.checkFullRewriteAllowed(-1, "brand_new.py"))
        assertNull(SurgicalPatchEngine.checkFullRewriteAllowed(10, "small.json"))
        val blocked = SurgicalPatchEngine.checkFullRewriteAllowed(120, "app/MainActivity.kt")
        assertNotNull("Expected full-file rewrite guard to block 120-line existing file", blocked)
        assertTrue(blocked!!.contains("SURGICAL PATCH GUARD"))
    }

    @Test
    fun testLoopGuardDetectsRepeatsAndUnblocksOnWorldChange() {
        val guard = LoopGuard()
        val d1 = guard.recordAndCheck("read_file", """{"path":"a.kt"}""", isError = false, fileChanged = false, newEvidence = true)
        val d2 = guard.recordAndCheck("read_file", """{"path":"a.kt"}""", isError = false, fileChanged = false, newEvidence = true)
        val d3 = guard.recordAndCheck("read_file", """{"path":"a.kt"}""", isError = false, fileChanged = false, newEvidence = true)
        assertTrue(d1 is LoopGuardDecision.Continue)
        assertTrue(d2 is LoopGuardDecision.Continue)
        assertTrue("3rd identical call must be blocked", d3 is LoopGuardDecision.BlockRepeatCall)
        assertNotNull(guard.shouldBlock("read_file", """{"path":"a.kt"}"""))

        // Editing a file changes the world and unblocks previously forbidden read_file/build calls
        val editDecision = guard.recordStep("apply_patch", """{"path":"a.kt"}""", "Patched +1 -1", isError = false, fileChanged = true, filePath = "a.kt")
        assertFalse(editDecision.triggered)
        assertNull("World change should unblock previously forbidden calls", guard.shouldBlock("read_file", """{"path":"a.kt"}"""))

        // 3 consecutive patch failures on the same file
        val guard2 = LoopGuard()
        guard2.recordAndCheck("apply_patch", """{"path":"b.kt","hunk":"1"}""", isError = true, fileChanged = false, newEvidence = false, targetFilePath = "b.kt")
        guard2.recordAndCheck("apply_patch", """{"path":"b.kt","hunk":"2"}""", isError = true, fileChanged = false, newEvidence = false, targetFilePath = "b.kt")
        val f3 = guard2.recordAndCheck("apply_patch", """{"path":"b.kt","hunk":"3"}""", isError = true, fileChanged = false, newEvidence = false, targetFilePath = "b.kt")
        assertTrue("3rd patch failure on same file must force re-read and strategy switch", f3 is LoopGuardDecision.ForceReReadAndSwitchStrategy)
    }

    @Test
    fun testRepoToolInstallerCompatibilityCheck() {
        val tmpDir = Files.createTempDirectory("forge_repo_test").toFile()
        try {
            File(tmpDir, "package.json").writeText("""{"name":"demo-mcp","dependencies":{"@modelcontextprotocol/sdk":"1.0.0"}}""")
            File(tmpDir, "Dockerfile").writeText("FROM ubuntu:22.04\nRUN sudo apt-get update")
            val analysis = RepoToolInstaller.inspectDirectory(tmpDir)
            assertTrue(analysis.isMcpServer)
            assertTrue(analysis.compatibilityWarnings.any { it.contains("Docker", ignoreCase = true) })
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun testReaApkReverseEngineeringReport() {
        val tmpDir = Files.createTempDirectory("forge_rea_test").toFile()
        val zipFile = File(tmpDir, "sample-app.apk")
        val outDir = File(tmpDir, "extracted")
        try {
            java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
                zos.write("<manifest package=\"com.demo.cloneapp\"><application/></manifest>".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("res/layout/activity_main.xml"))
                zos.write("<LinearLayout/>".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("assets/config.json"))
                zos.write("{\"api\":\"https://api.tokenharbor.ai/v1/models\"}".toByteArray())
                zos.closeEntry()
            }
            val report = RepoToolInstaller.analyzeApkOrArchive(zipFile, outDir)
            assertTrue(report.analysisMarkdown.contains("1. Evidence & Binary Profile"))
            assertTrue(report.analysisMarkdown.contains("2. Recovered Graph & UI Screen Architecture"))
            assertTrue(report.analysisMarkdown.contains("3. Extracted API Endpoints & Config URLs"))
            assertTrue(report.analysisMarkdown.contains("https://api.tokenharbor.ai/v1/models"))
            assertTrue(report.analysisMarkdown.contains("5. Clean-Room App Clone Plan"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
