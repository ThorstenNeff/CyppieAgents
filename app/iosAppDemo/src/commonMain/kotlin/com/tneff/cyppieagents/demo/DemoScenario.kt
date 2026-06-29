package com.tneff.cyppieagents.demo

import com.tneff.cyppieagents.crossproject.CrossAccess
import com.tneff.cyppieagents.crossproject.CrossMember
import com.tneff.cyppieagents.crossproject.CrossProjectRepository
import com.tneff.cyppieagents.crossproject.StubCrossProjectRepository
import com.tneff.cyppieagents.eventlog.EventsApi
import com.tneff.cyppieagents.eventlog.StubEventsApi
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.project.ProjectRepository
import com.tneff.cyppieagents.project.StubProjectRepository
import com.tneff.cyppieagents.settings.ApiKeyState
import com.tneff.cyppieagents.settings.ConfigRepository
import com.tneff.cyppieagents.settings.RepoConfigState
import com.tneff.cyppieagents.settings.StubConfigRepository

/**
 * CYP-116 — a deterministic cross-target demo scenario: a TWO-project world (`team-1`, `team-2`) with
 * ONE cross-project shared channel (`po-frontend`), an operator-configured repo + API key, and
 * Event-Log events spanning BOTH projects (so the cross-project view + project filter are non-trivial).
 *
 * Built PURELY from the prod `commonMain` Stub* sources with parameterised seed data — **no new stub
 * code, no prod touch**. Platform-neutral (no iOS/Compose APIs) → lift-and-shift to a shared
 * `:app:demoFixtures` module IF/when the android/web demo mounts adopt it (PO: lower-prio follow-up,
 * YAGNI now). Lives in `:app:iosAppDemo` so prod `:app:shared`/`Shared.framework`/`iosApp` stay
 * byte-unchanged (CYP-116 merge condition; the iOS mirror of the android/webAppDemo guardrail).
 */
object DemoScenario {
    const val PROJECT_A: String = "team-1"
    const val PROJECT_B: String = "team-2"
    const val ACTIVE_PROJECT: String = PROJECT_A

    /** The one channel shared across the project boundary (operator-consented in this scenario). */
    const val CROSS_CHANNEL: String = "po-frontend"

    val projects: List<Project> = listOf(
        Project(PROJECT_A, "Team Alpha"),
        Project(PROJECT_B, "Team Beta"),
    )

    /** The active operator's OTHER projects = the cross-project share targets (CYP-93 contract). */
    val targetProjects: List<Project> = projects.filter { it.id != ACTIVE_PROJECT }

    fun projectRepository(): ProjectRepository =
        StubProjectRepository(initial = projects, activeProjectId = ACTIVE_PROJECT)

    fun configRepository(): ConfigRepository = StubConfigRepository(
        initialRepo = RepoConfigState.Configured("git@github.com:cyppie/team-alpha.git", "main"),
        initialApiKey = ApiKeyState(set = true, masked = "***-key"),
    )

    fun crossProjectRepository(): CrossProjectRepository = StubCrossProjectRepository(
        reachByChannel = mapOf(
            CROSS_CHANNEL to listOf(
                CrossMember(agentId = "po", homeProjectId = PROJECT_A, access = CrossAccess.WRITE),
                CrossMember(agentId = "frontend", homeProjectId = PROJECT_B, access = CrossAccess.READ),
            ),
        ),
        initiallyShared = setOf(CROSS_CHANNEL),
    )

    fun eventsApi(): EventsApi = StubEventsApi(events = crossProjectEvents())

    /** Events spanning BOTH projects so the cross-project view indicator + project filter are non-trivial. */
    private fun crossProjectEvents(): List<Event> {
        fun ev(seq: Long, project: String, type: EventType, agent: String) = Event(
            id = "x$seq", ts = 1_000 + seq, seq = seq, agentId = agent, projectId = project,
            type = type, severity = Severity.INFO,
            correlationId = "run-$project", sessionId = "sess-$project",
        )
        return listOf(
            ev(1, PROJECT_A, EventType.TURN_START, "po"),
            ev(2, PROJECT_A, EventType.TOOL_CALL, "backend"),
            ev(3, PROJECT_B, EventType.TURN_START, "frontend"),
            ev(4, PROJECT_B, EventType.RESULT_FINAL, "frontend"),
            ev(5, PROJECT_A, EventType.RESULT_FINAL, "backend"),
        )
    }
}
