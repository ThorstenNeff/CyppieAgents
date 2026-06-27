package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.model.Role
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-33 regression guard: `PlatformConfig` must actually (de)serialize at runtime. Without the
 * kotlinx.serialization compiler plugin on `:server`, `@Serializable` here generates no serializer
 * and these throw `SerializationException` — exactly the crash that killed the real `main()` boot.
 * Earlier tests only built the config in-code, so they never exercised this path.
 */
class PlatformConfigTest {

    @Test
    fun roundTripsThroughJson() {
        val config = PlatformConfig(
            repo = RepoConfig("git@example:repo.git", "main"),
            agents = listOf(
                AgentConfig("po", "PO", Role.PO),
                AgentConfig("backend", "BE", Role.WORKER),
            ),
        )
        val decoded = CommJson.decodeFromString<PlatformConfig>(CommJson.encodeToString(config))
        assertEquals(config, decoded)
    }

    @Test
    fun loadsFromJsonFile() {
        val file = Files.createTempFile("platform-config", ".json").toFile()
        try {
            file.writeText(
                """
                {
                  "repo": { "url": "file:///tmp/repo.git", "branch": "main" },
                  "web": { "allowedOrigins": ["http://localhost:8080"] },
                  "agents": [
                    { "id": "po", "name": "Product Owner", "role": "PO", "worktree": "po" },
                    { "id": "frontend", "name": "Frontend", "role": "WORKER" },
                    { "id": "backend", "name": "Backend", "role": "WORKER" }
                  ]
                }
                """.trimIndent(),
            )
            val config = PlatformConfig.load(file) // the call that crashed the real boot

            assertEquals("file:///tmp/repo.git", config.repo.url)
            assertEquals(listOf("http://localhost:8080"), config.web.allowedOrigins)
            assertEquals(3, config.agents.size)
            assertEquals("frontend", config.agents[1].worktreeName) // defaults to id
            assertEquals(Role.PO, config.agents.first().role)
        } finally {
            file.delete()
        }
    }
}
