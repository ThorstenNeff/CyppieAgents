package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Message
import org.slf4j.LoggerFactory

/**
 * Structured audit log for send/deny/ACL events (Spec 02 §15 observability). Every value that
 * could carry a secret is run through [SecretMasker] before it reaches a log line (Gate #3:
 * logs are an egress too).
 */
class Audit(private val masker: (String) -> String = SecretMasker::mask) {
    private val log = LoggerFactory.getLogger("comm.audit")

    fun posted(message: Message) {
        log.info("posted id={} channel={} from={} body={}", message.id, message.channelId, message.from, masker(message.body))
    }

    fun denied(agentId: String, channelId: String, reason: String) {
        // Fail-closed denials are security-relevant: log at WARN so they are easy to find.
        log.warn("denied agent={} channel={} reason={}", agentId, channelId, reason)
    }

    fun aclChanged(channelId: String, agentId: String, canRead: Boolean, canWrite: Boolean, by: String) {
        log.info("acl-set channel={} agent={} canRead={} canWrite={} by={}", channelId, agentId, canRead, canWrite, by)
    }

    fun aclDenied(channelId: String, agentId: String, by: String, reason: String) {
        // Rejected ACL change (e.g. PO-lockout guardrail, CYP-49): security-relevant → WARN.
        log.warn("acl-denied channel={} agent={} by={} reason={}", channelId, agentId, by, reason)
    }
}
