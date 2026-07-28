package com.tneff.cyppieagents.comm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.CreateChannelRequest
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.channel_mgmt_archive
import kmpcyppieagents.app.shared.generated.resources.channel_mgmt_create_id
import kmpcyppieagents.app.shared.generated.resources.channel_mgmt_create_name
import kmpcyppieagents.app.shared.generated.resources.channel_mgmt_create_submit
import kmpcyppieagents.app.shared.generated.resources.channel_mgmt_error_generic
import kmpcyppieagents.app.shared.generated.resources.channel_mgmt_error_operator_only
import kmpcyppieagents.app.shared.generated.resources.channel_mgmt_error_protected_hub
import kmpcyppieagents.app.shared.generated.resources.channel_mgmt_rename_placeholder
import kmpcyppieagents.app.shared.generated.resources.channel_mgmt_rename_submit
import kmpcyppieagents.app.shared.generated.resources.channel_mgmt_title
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-883 (OS-C) — the honest server-mutation reason ([ChannelMutationReason].name) exposed as a semantics property
 * on the error node, so a render test asserts WHICH honest error was surfaced (409/403/generic) locale-independently.
 */
val ChannelMutationReasonKey = SemanticsPropertyKey<String>("channelMutationReason")
var SemanticsPropertyReceiver.channelMutationReason by ChannelMutationReasonKey

/**
 * CYP-883 (OS-C, Compose mirror of web-ts CYP-875 `ChannelManagementPanel.tsx`) — the operator channel-management
 * panel: create (DIRECT/GROUP), rename, archive. Mutations are OPTIMISTIC with **rollback-on-reject**, and the server
 * stays the authority (**render ≠ authority**): a 409 (protected HUB) or 403 (operator-only) is shown HONESTLY (never
 * a silent success, never hidden), and the optimistic change is rolled back so no phantom hangs. HUB channels are
 * protected — the archive affordance is disabled (client HINT, [isArchivable]); the server 409 is the authoritative
 * guard. The real round-trip is the injected [ChannelMgmtApi] seam (this panel never speaks HTTP itself).
 */
@Composable
fun ChannelManagementPanel(
    channels: List<Channel>,
    agentIds: List<String>,
    /** Operator tier — mutations are operator-only. A non-operator sees them disabled; a server 403 still surfaces. */
    operator: Boolean,
    api: ChannelMgmtApi,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<ChannelMutationReason?>(null) }
    var optimisticArchived by remember { mutableStateOf<Set<String>>(emptySet()) }
    var optimisticCreated by remember { mutableStateOf<List<Channel>>(emptyList()) }
    val renameDraft = remember { mutableStateMapOf<String, String>() }
    var newId by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf(ChannelKind.GROUP) }
    val newMembers = remember { mutableStateListOf<String>() }

    // The visible set = server channels ∪ pending optimistic creates (deduped by id once the real one arrives via
    // /ws/comm), minus optimistically-archived ones.
    val visible = (channels + optimisticCreated.filter { o -> channels.none { it.id == o.id } })
        .filter { it.id !in optimisticArchived }

    fun createReq() = CreateChannelRequest(
        id = newId.trim(), name = newName.trim(), kind = newKind, members = newMembers.map(::memberGrant),
    )

    fun create() {
        val req = createReq()
        if (!isCreateValid(req)) return
        error = null
        val optimistic = Channel(id = req.id, name = req.name, kind = newKind, members = newMembers.toList())
        optimisticCreated = optimisticCreated + optimistic // OPTIMISTIC add
        newId = ""
        newName = ""
        newMembers.clear()
        scope.launch {
            try {
                api.create(req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                optimisticCreated = optimisticCreated.filterNot { it.id == optimistic.id } // ROLLBACK — no phantom
                error = channelMutationReason(e) // HONEST, server-authoritative
            }
        }
    }

    fun archive(c: Channel) {
        error = null
        optimisticArchived = optimisticArchived + c.id // OPTIMISTIC hide
        scope.launch {
            try {
                api.archive(c.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                optimisticArchived = optimisticArchived - c.id // ROLLBACK — the channel reappears, no phantom removal
                error = channelMutationReason(e) // HONEST (409 protected-HUB / 403 operator)
            }
        }
    }

    fun rename(c: Channel) {
        val name = (renameDraft[c.id] ?: "").trim()
        if (name.isEmpty() || name == c.name) return
        error = null
        scope.launch {
            try {
                api.rename(c.id, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = channelMutationReason(e) // HONEST; the name reverts (draft cleared, prop is source of truth)
                renameDraft[c.id] = ""
            }
        }
    }

    val canCreate = operator && isCreateValid(createReq())
    val title = stringResource(Res.string.channel_mgmt_title)

    Column(
        modifier = modifier.fillMaxWidth().testTag(ChannelMgmtTags.ROOT).semantics { contentDescription = title },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The HONEST, server-authoritative error — announced (a11y) and error-toned; never hidden behind a pre-guess.
        error?.let { reason ->
            val msg = when (reason) {
                ChannelMutationReason.PROTECTED_HUB -> stringResource(Res.string.channel_mgmt_error_protected_hub)
                ChannelMutationReason.OPERATOR_ONLY -> stringResource(Res.string.channel_mgmt_error_operator_only)
                ChannelMutationReason.GENERIC -> stringResource(Res.string.channel_mgmt_error_generic)
            }
            Text(
                msg,
                modifier = Modifier.testTag(ChannelMgmtTags.ERROR).semantics {
                    liveRegion = LiveRegionMode.Assertive
                    channelMutationReason = reason.name
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // CREATE form.
        OutlinedTextField(
            value = newId,
            onValueChange = { newId = it },
            enabled = operator,
            label = { Text(stringResource(Res.string.channel_mgmt_create_id)) },
            modifier = Modifier.fillMaxWidth().testTag(ChannelMgmtTags.CREATE_ID),
        )
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            enabled = operator,
            label = { Text(stringResource(Res.string.channel_mgmt_create_name)) },
            modifier = Modifier.fillMaxWidth().testTag(ChannelMgmtTags.CREATE_NAME),
        )
        // Kind selector — CREATABLE_KINDS only (DIRECT/GROUP); HUB is never offered (isCreateValid also fail-closes it).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CREATABLE_KINDS.forEach { k ->
                Text(
                    (if (newKind == k) "● " else "○ ") + k.name,
                    modifier = Modifier
                        .testTag(ChannelMgmtTags.createKind(k.name))
                        .selectable(selected = newKind == k, enabled = operator, role = Role.RadioButton) { newKind = k },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        // Candidate members (membership IS the ACL, memberGrant read+write).
        agentIds.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = a in newMembers,
                    onCheckedChange = { on -> if (on) newMembers.add(a) else newMembers.remove(a) },
                    enabled = operator,
                    modifier = Modifier.testTag(ChannelMgmtTags.createMember(a)),
                )
                Text(a, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(
            onClick = { create() },
            enabled = canCreate,
            modifier = Modifier.testTag(ChannelMgmtTags.CREATE_SUBMIT),
        ) { Text(stringResource(Res.string.channel_mgmt_create_submit)) }

        // MANAGE existing.
        visible.forEach { c ->
            Row(
                modifier = Modifier.fillMaxWidth().testTag(ChannelMgmtTags.row(c.id)).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${c.name} (${c.kind.name})",
                    modifier = Modifier.weight(1f, fill = false),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = renameDraft[c.id] ?: "",
                    onValueChange = { renameDraft[c.id] = it },
                    enabled = operator,
                    label = { Text(stringResource(Res.string.channel_mgmt_rename_placeholder)) },
                    modifier = Modifier.testTag(ChannelMgmtTags.rename(c.id)),
                )
                Button(
                    onClick = { rename(c) },
                    enabled = operator,
                    modifier = Modifier.testTag(ChannelMgmtTags.renameSubmit(c.id)),
                ) { Text(stringResource(Res.string.channel_mgmt_rename_submit)) }
                // HUB is protected → archive disabled (client hint); the server 409 is the authoritative guard.
                Button(
                    onClick = { archive(c) },
                    enabled = operator && isArchivable(c),
                    modifier = Modifier.testTag(ChannelMgmtTags.archive(c.id)),
                ) { Text(stringResource(Res.string.channel_mgmt_archive)) }
            }
        }
    }
}
