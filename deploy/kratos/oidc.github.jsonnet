// CYP-183 / P4 — GitHub OIDC claims → identity traits mapper.
//
// Maps GitHub's user claims to our identity schema. `user:email` yields the account's primary email; the
// spike (P4.3) settles whether Kratos v1.3.0 lands it as a VERIFIED address (→ P1 guard admits) and — the
// HARD S1 gate — whether it links/verifies ONLY on GitHub's verified primary email (never an unverified
// one, which would enable account takeover). This mapper only shapes the traits; the linking/verify safety
// is Kratos's behavior, proven by the spike, not asserted here.
local claims = std.extVar('claims');
{
  identity: {
    traits: {
      email: claims.email,
    },
  },
}
