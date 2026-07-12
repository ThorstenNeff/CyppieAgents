package com.tneff.cyppieagents.controlplane

import com.tneff.cyppieagents.model.HubDescriptor

/**
 * CYP-481 (Epic CYP-427 Phase-2) — map a Control-Plane [RegisteredHub] to the client-facing [HubDescriptor] wire DTO
 * (the `GET /hubs` projection, S-1 / S-J). The registry is **zero-knowledge** — it stores only identity + routing
 * ([RegisteredHub] has no presence) — so **presence** ([online] / [lastSeen], advisory H1) is supplied by the caller
 * (the Connector-WS presence, when the live endpoint is wired with CP activation). [dhPubKey] flows **straight
 * through** unchanged: it is the value the client TOFU-pins (CYP-478), so it must be the hub's exact published static.
 *
 * The live `GET /hubs` endpoint is deliberately NOT wired here (the CP hub registry is INERT until the Phase-2-Remote
 * GO); this pure mapping is the contract the endpoint will use, and it unblocks the client pin + the TS regen now.
 */
fun RegisteredHub.toDescriptor(online: Boolean, lastSeen: Long): HubDescriptor = HubDescriptor(
    hubId = hubId,
    name = name,
    online = online,
    defaultPort = defaultPort,
    lastSeen = lastSeen,
    dhPubKey = dhPubKey,
)
