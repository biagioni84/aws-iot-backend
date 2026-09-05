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
- [2026-09-04] [DOC] `ECR-Deployment.md` is stale vs. actual prod setup — references `/home/bitnami/` paths, `pid: host`, `cap_add: KILL`, a `tunneluser` volume and `config/application.properties` mount that don't match the current `docker-compose.prod.yml` (`/home/ubuntu/`, no pid/cap_add, `.aws` mounted to `/home/spring/.aws:ro`, `application.properties` mounted directly). Rewrite once CI/CD pipeline work settles.
- [2026-09-04] [DEBT] CI/CD hardening deferred while solo-maintainer (no external contributors yet): (1) enable "Require approval for all outside collaborators" under repo Settings > Actions > General, so fork PRs never auto-run workflows; (2) pin third-party GitHub Actions (`appleboy/ssh-action`, `appleboy/scp-action`) to a commit SHA instead of a version tag, to survive a tag-repoint supply-chain attack like the 2025 tj-actions/changed-files incident; (3) enable Dependabot alerts. Revisit before accepting outside PRs.
- [2026-09-04] [BUG] `.githooks/check_backlog.py`'s trigger-phrase scan false-positives on the Spanish word meaning "everything/all" (four letters: t-o-d-o), matching it as if it were the English pending-work marker. Fired repeatedly during a Spanish-language session even after explicit "nothing to capture" replies. Fix the regex to require a word-boundary match against the actual English trigger phrases, not a bare substring.

## Done

(archive items here as they're closed; prune older than ~3 months)
