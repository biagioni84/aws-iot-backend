# Backlog

Append-only log of items discovered during work that aren't done yet.
Triage periodically: promote real ones to issues, prune dead ones into `## Done`.

## Format
`- [YYYY-MM-DD] [TYPE] title — one-line context, optional file:line`

**Types**: `BUG` `FEAT` `DEBT` `DOC` `INVEST` (investigation/spike)

---

## Active

- [2026-05-23] [INVEST] gateway pubkeys in DB stored without `ssh-rsa` prefix — backend now prepends the type in `LightsailRemoteAccess.buildKeyLine`, but other code paths reading `public_key` may still hit the bare base64. Check usages.
- [2026-05-23] [DEBT] gateway-side: connects as `iot_<serial>` and sets `StrictHostKeyChecking=yes` — should be `tunneluser` + `StrictHostKeyChecking=no`, and `-R 0.0.0.0:port:...` for permitlisten to match. Tracked in gateway repo.
- [2026-05-23] [DEBT] `generate_context.py` uses regex for Java parsing — fragile against unusual formatting. Consider tree-sitter or javalang if false positives recur.
- [2026-05-23] [FEAT] Gateway self-registration endpoint already on CLAUDE.md roadmap (#4) — but no `POST /api/v1/gateways` handler validates pubkey format (no normalization to `ssh-rsa AAAA...`). Add validation at intake to avoid the bug above.

## Done

(archive items here as they're closed; prune older than ~3 months)
