package com.tneff.cyppieagents.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentAvatar
import com.tneff.cyppieagents.model.Role

/** Stable test tags for the shared agent avatar (CYP-216). */
object AvatarTags {
    fun avatar(id: String): String = "avatar.$id"
}

/**
 * The authenticated Coil [ImageLoader] used for avatar image stages (1/2). Provided by the shell over the SHARED
 * authed Ktor client (the serve endpoint `GET /api/agents/{id}/avatar` is participant-gated), so image GETs carry
 * the same session/operator credential as every other call. `null` (default / tests / non-shell renders) → the
 * avatar renders stages 3-4 only (initials → colour), never a broken image.
 */
val LocalAvatarImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

/** The HTTP base (e.g. `http://localhost:8787`) for building the avatar serve-URL. `null` → no image stage. */
val LocalAvatarBaseUrl = staticCompositionLocalOf<String?> { null }

/** The cache-bust `?v=` token: an upload's server-minted ref (changes per re-upload) or the preset's style-seed. */
private fun AgentAvatar.versionToken(): String = when (this) {
    is AgentAvatar.Upload -> ref
    is AgentAvatar.Preset -> "$style-$seed"
}

/**
 * CYP-216 — the ONE shared agent avatar (anti-divergence foundation for Epic CYP-212). Every identity avatar (comm
 * message rows, event log, titlebar, …) renders through this, so the look + the full fallback chain (§4) live in a
 * single place.
 *
 * **Fallback chain (§4), fail-closed:** custom-upload → preset → **initials → colour**. The image stages (1/2) load
 * the participant-gated serve-URL via the authed [LocalAvatarImageLoader]; on load/error/404 (or no [avatar], or no
 * loader) the initials/colour disc beneath shows through — never a broken image. The CYP-209 contrast-safe colour
 * RING wraps every stage (identity anchor). Colour is identity, never status (§1); a11y-decorative (the name is
 * shown adjacently → no double-announce).
 *
 * **[tintedBar] (§5.1 inverted disc):** when non-null (the themed titlebar), the avatar INVERTS so it does not blur
 * into the already-tinted bar — disc = [TitleBarColors.content], figure (initials) = [TitleBarColors.background] (the
 * agent colour, ≥4.5:1 vs the disc), ring = 1.5.dp [TitleBarColors.content] (NOT the surface-derived borderColor).
 * `null` = the normal path (Comm / Event-Log): disc = avatarFill, figure = onAvatar, ring = borderColor.
 */
@Composable
fun AgentAvatarView(
    id: String,
    size: Dp,
    modifier: Modifier = Modifier,
    displayName: String = id,
    role: Role? = null,
    colorHex: String? = null,
    avatar: AgentAvatar? = null,
    tintedBar: TitleBarColors? = null,
) {
    val color = SenderPalette.forAgent(id, role, colorHex)
    // Normal path vs §5.1 inverted titlebar disc.
    val discFill: Color = tintedBar?.content ?: color.avatarFill
    val figure: Color = tintedBar?.background ?: color.onAvatar
    val ringColor: Color = tintedBar?.content ?: color.borderColor
    val ringWidth: Dp = if (tintedBar != null) 1.5.dp else (size.value * 0.06f).dp.coerceAtLeast(1.dp)

    val loader = LocalAvatarImageLoader.current
    val base = LocalAvatarBaseUrl.current
    // Image stage (1/2) only when there's an avatar override AND an authed loader + base to fetch it.
    // SECURITY (CYP-216 §2): the loader ONLY ever gets `<apiBase>/api/agents/{id}/avatar?v=<token>` — the same-origin
    // API serve-URL, NEVER a raw/dicebear URL. style/seed/ref never choose host/scheme/path; id + token are
    // URL-encoded (no `../`, no `@userinfo`, no scheme injection), so no `file://`/`data:`/`content:` is reachable.
    val model: String? = if (avatar != null && loader != null && base != null) {
        "$base/api/agents/${id.encodeURLPathPart()}/avatar?v=${avatar.versionToken().encodeURLParameter()}"
    } else {
        null
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(discFill)
            .border(BorderStroke(ringWidth, ringColor), CircleShape)
            .testTag(AvatarTags.avatar(id)),
        contentAlignment = Alignment.Center,
    ) {
        // Stages 3-4 base (always present): initials on the disc — the fallback that shows through on image error.
        Text(
            text = initialsOf(displayName.ifBlank { id }),
            color = figure,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        // Stages 1-2 overlay: the served PNG on top; on load/error/404 it draws nothing → the disc shows (§4).
        if (model != null && loader != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                imageLoader = loader,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Convenience overload for a resolved [Agent] — pulls id/name/role/colour/avatar off the model (CYP-210/215 seam). */
@Composable
fun AgentAvatarView(agent: Agent, size: Dp, modifier: Modifier = Modifier, tintedBar: TitleBarColors? = null) =
    AgentAvatarView(
        id = agent.id,
        size = size,
        modifier = modifier,
        displayName = agent.name,
        role = agent.role,
        colorHex = agent.color,
        avatar = agent.avatar,
        tintedBar = tintedBar,
    )
