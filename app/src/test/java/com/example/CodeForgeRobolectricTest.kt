package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.LlmClient
import com.example.data.local.AppDatabase
import com.example.data.local.entity.ProviderConfigEntity
import com.example.data.local.entity.ToolStepEntity
import com.example.data.repository.ProviderRepository
import com.example.data.settings.SettingsStore
import com.example.data.termux.TermuxBridge
import com.example.ui.components.parseExecResultBlock
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CodeForgeRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var providerRepo: ProviderRepository
    private var server: HttpServer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        providerRepo = ProviderRepository(
            providerDao = db.providerDao(),
            usageDao = db.usageDao(),
            llmClient = LlmClient()
        )
    }

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
        db.close()
    }

    @Test
    fun `addCustomProvider persists into Room DB and normalizes endpoint`() = runBlocking {
        val created = providerRepo.addCustomProvider(
            name = "My Custom Gateway",
            baseUrl = "https://proxy.example.com/v1/chat/completions",
            apiFormat = "OPENAI",
            rawApiKey = "sk-custom-12345",
            selectedModel = "deepseek-r1-custom",
            customHeadersJson = """{"X-Org":"forge"}"""
        )

        assertEquals("https://proxy.example.com/v1", created.baseUrl)
        assertEquals("deepseek-r1-custom", created.selectedModel)
        assertTrue(created.isEnabled)

        val all = db.providerDao().getAllProvidersOnce()
        val fetched = all.firstOrNull { it.id == created.id }
        assertNotNull("Custom provider MUST be saved in Room DB", fetched)
        assertEquals("My Custom Gateway", fetched!!.name)
        assertEquals("sk-custom-12345", com.example.data.security.KeyStoreManager.decrypt(fetched.encryptedApiKey))

        // Verify model cache was also seeded so the model picker shows it immediately
        val cached = db.providerDao().getCachedModelsForProviderOnce(created.id)
        assertTrue("Custom model should be seeded in model_cache", cached.any { it.modelId == "deepseek-r1-custom" })

        // Verify delete works cleanly
        providerRepo.deleteProvider(created.id)
        assertTrue(db.providerDao().getAllProvidersOnce().none { it.id == created.id })
    }

    @Test
    fun `updateProvider upserts new custom provider instead of silently dropping it`() = runBlocking {
        val brandNew = ProviderConfigEntity(
            id = "custom_direct_upsert",
            name = "Direct Upsert Provider",
            baseUrl = "https://api.myproxy.dev/v1",
            apiFormat = "OPENAI",
            encryptedApiKey = com.example.data.security.KeyStoreManager.encrypt("sk-upsert-999"),
            selectedModel = "qwen-coder-32b",
            priority = 99,
            isEnabled = true
        )
        // Previously updateProvider used Room @Update which affected 0 rows on new custom providers!
        providerRepo.updateProvider(brandNew)

        val saved = db.providerDao().getProviderById("custom_direct_upsert")
        assertNotNull("updateProvider MUST upsert new custom providers", saved)
        assertEquals("Direct Upsert Provider", saved!!.name)
        assertEquals("qwen-coder-32b", saved.selectedModel)
    }

    @Test
    fun `testAndFetchModels verifies custom provider via real HTTP models endpoint and chat fallback`() = runBlocking {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Endpoint 1: Standard OpenAI /v1/models
        srv.createContext("/v1/models") { ex ->
            val body = """{"data":[{"id":"custom-coder-v1"},{"id":"custom-fast-v2"}]}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        // Endpoint 2: Proxy WITHOUT /models (404) but working /chat/completions
        srv.createContext("/nomodels/v1/models") { ex ->
            val body = """{"error":"not implemented"}""".toByteArray()
            ex.sendResponseHeaders(404, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        srv.createContext("/nomodels/v1/chat/completions") { ex ->
            val body = """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        srv.start()
        server = srv
        val port = srv.address.port

        // Case A: Standard custom provider with /models
        val p1 = providerRepo.addCustomProvider(
            name = "Local Standard Proxy",
            baseUrl = "http://127.0.0.1:$port/v1",
            apiFormat = "OPENAI",
            rawApiKey = "sk-test-a",
            selectedModel = ""
        )
        val diag1 = providerRepo.testAndFetchModels(p1)
        assertTrue("Expected testAndFetchModels to succeed: ${diag1.plainReason}", diag1.success)
        assertEquals(2, diag1.modelsFound)
        val p1After = db.providerDao().getProviderById(p1.id)!!
        assertEquals("WORKING", p1After.lastTestStatus)
        assertTrue(p1After.selectedModel.isNotBlank())

        // Case B: Custom proxy without /models endpoint (must fall back to /chat/completions ping)
        val p2 = providerRepo.addCustomProvider(
            name = "Local Chat-Only Proxy",
            baseUrl = "http://127.0.0.1:$port/nomodels/v1",
            apiFormat = "OPENAI",
            rawApiKey = "sk-test-b",
            selectedModel = "my-private-model"
        )
        val diag2 = providerRepo.testAndFetchModels(p2)
        assertTrue("Expected fallback chat ping to succeed: ${diag2.plainReason}", diag2.success)
        val p2After = db.providerDao().getProviderById(p2.id)!!
        assertEquals("WORKING", p2After.lastTestStatus)
        assertEquals("my-private-model", p2After.selectedModel)
    }

    @Test
    fun `parseExecResultBlock parses both exec JSON and surgical diff preview JSON`() {
        val diffJson = JSONObject()
            .put("command", "apply_patch app/Main.kt")
            .put("cwd", "/data/data/com.termux/files/home")
            .put("file_path", "app/Main.kt")
            .put("diff_preview", "--- a/app/Main.kt\n+++ b/app/Main.kt\n@@ -1,2 +1,2 @@\n-val x = 1\n+val x = 2")
            .put("added_lines", 1)
            .put("removed_lines", 1)
            .put("stdout", "Applied 1 surgical hunk")
            .put("stderr", "")
            .put("exit_code", 0)
            .put("duration_ms", 42L)
            .toString()

        val step = ToolStepEntity(
            id = "step-1",
            messageId = "msg-1",
            stepIndex = 1,
            toolName = "apply_patch",
            type = "EDIT",
            filePath = "app/Main.kt",
            argumentsJson = """{"path":"app/Main.kt"}""",
            resultText = diffJson,
            isError = false,
            status = "COMPLETED",
            durationMs = 42L
        )
        val parsed = parseExecResultBlock(step)
        assertEquals("app/Main.kt", parsed.filePath)
        assertEquals(1, parsed.addedLines)
        assertEquals(1, parsed.removedLines)
        assertEquals(0, parsed.exitCode)
        assertTrue(parsed.diffPreview.contains("-val x = 1"))
        assertTrue(parsed.diffPreview.contains("+val x = 2"))
    }

    @Test
    fun `TermuxBridge maps Android sandbox paths to Termux home and blocks dangerous commands`() {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val bridge = TermuxBridge(context, SettingsStore(context), testScope)
            assertEquals(
                TermuxBridge.TERMUX_HOME,
                bridge.toTermuxPath("/data/user/0/com.aistudio.codeforge.vxqkjt/files/projects/scratch")
            )
            assertEquals(
                "${TermuxBridge.TERMUX_HOME}/myrepo",
                bridge.toTermuxPath("~/myrepo")
            )
            assertTrue(bridge.isCommandDangerous("rm -rf /"))
            assertFalse(bridge.isCommandDangerous("python3 main.py"))
        } finally {
            testScope.cancel()
        }
    }
}
