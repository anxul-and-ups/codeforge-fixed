package com.example

import android.app.Application
import com.example.data.agent.AgentEngine
import com.example.data.local.AppDatabase
import com.example.data.repository.ChatRepository
import com.example.data.repository.GitHubRepository
import com.example.data.repository.ProjectRepository
import com.example.data.repository.ProviderRepository
import com.example.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CodeForgeApp : Application() {

    /** Lives as long as the process: agent runs keep going when the screen/activity is closed. */
    val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore by lazy { SettingsStore(this) }
    val database by lazy { AppDatabase.getInstance(this) }
    val projectRepository by lazy {
        ProjectRepository(
            context = this,
            projectDao = database.projectDao(),
            checkpointDao = database.checkpointDao()
        )
    }
    val providerRepository by lazy {
        ProviderRepository(
            providerDao = database.providerDao(),
            usageDao = database.usageDao()
        )
    }
    val chatRepository by lazy {
        ChatRepository(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao()
        )
    }
    val gitHubRepository by lazy { GitHubRepository() }
    val agentEngine by lazy {
        AgentEngine(
            appContext = this,
            scope = agentScope,
            projectRepository = projectRepository,
            providerRepository = providerRepository,
            gitHubRepository = gitHubRepository,
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao(),
            usageDao = database.usageDao(),
            settings = settingsStore
        )
    }

    override fun onCreate() {
        super.onCreate()
        agentScope.launch(Dispatchers.IO) {
            providerRepository.initializeDefaultPresets()
        }
    }
}
