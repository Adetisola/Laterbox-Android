# Laterbox Distribution Migration & Cutover Plan

## 1. Target Repository Architecture

| Repository | Visibility | Purpose |
| --- | --- | --- |
| `Adetisola/Laterbox-Android` | Private | Android application source code, build configuration, tests, sanitized `release-notes/*.md`, and release workflows |
| `Adetisola/Laterbox` | Public | Dedicated artifact-only distribution repository: APKs, `.patch` files, `delta-manifest.json`, `checksums.sha256`, sanitized release notes, and minimal `README.md` |
| `Adetisola/Laterbox-Website` | Private | Official Next.js download & release-notes website deployed on Vercel |
| `Adetisola/Laterbox-MovieBox-API` | Private | Existing FastAPI backend providing `/updates/laterbox/latest` and `/updates/laterbox/assets/{id}/download-url` |

---

## 2. Android Updater Compatibility Matrix

| Version Range | Update Discovery Mechanism | Asset & Patch Resolution | Post-Cutover Compatibility Requirements |
| --- | --- | --- | --- |
| `v1.0.0` – `v1.0.17` | No `GitHubUpdateChecker` | Manual update only | Works via official website or GitHub Releases (`app-release.apk` migrated) |
| `v1.0.18` – `v1.0.46` | Direct `GET https://api.github.com/repos/Adetisola/Laterbox/releases/latest` | Reads `assets[].browser_download_url`; `v1.0.35+` also reads `delta-manifest.json` | Requires `Adetisola/Laterbox` to remain **public** at the exact original slug |
| `v1.0.47` – `v1.0.53` | Direct `GET https://github.com/Adetisola/Laterbox/releases/latest/download/delta-manifest.json` | Constructs `https://github.com/Adetisola/Laterbox/releases/download/v{ver}/{targetApkFilename}` from `manifest.patches[]` | Requires `Adetisola/Laterbox` to be **public**, latest release must include `delta-manifest.json` with non-empty `patches[]` covering ARM32, ARM64, and Universal |
| `v1.0.54` – `v1.0.57` | Backend only: `GET https://laterbox-moviebox-api.vercel.app/updates/laterbox/latest` | Resolves signed URLs via `/updates/laterbox/assets/{asset_id}/download-url` | Requires `LATERBOX_GITHUB_REPO=Adetisola/Laterbox`, valid backend GitHub access on the new repo, and cache invalidation on cutover |
| `v1.0.58` – `v1.0.61` | Backend `/updates/laterbox/latest` primary; falls back to `api.github.com/repos/Adetisola/Laterbox/releases/latest` on DNS/connect/timeout failure | Uses backend signed URLs or direct GitHub `browser_download_url` in fallback | Both backend and public `Adetisola/Laterbox` fallback work seamlessly once cutover completes |

---

## 3. Required GitHub App Permissions & Secrets

### A. Release Publishing App (in `Adetisola/Laterbox-Android`)
Create (or scope) a GitHub App installed **only** on `Adetisola/Laterbox` with minimum permissions:
- **Repository permissions → Contents**: `Read & write` (required to create artifact-only tags, create releases, and upload APKs/patches/manifests)
- **Repository permissions → Metadata**: `Read-only` (default)
- No organization-wide permissions.

Store in `Adetisola/Laterbox-Android` → Settings → Secrets and variables → Actions:
- Secret `DISTRIBUTION_APP_ID`: Numeric GitHub App ID
- Secret `DISTRIBUTION_APP_PRIVATE_KEY`: Full PEM private key for the GitHub App
- Variable `DISTRIBUTION_REPO` (optional, defaults to `Adetisola/Laterbox`)
- Optional cache invalidation:
  - Variable `UPDATE_CACHE_INVALIDATE_URL`: `https://laterbox-moviebox-api.vercel.app/updates/laterbox/cache/invalidate`
  - Secret `UPDATE_ADMIN_TOKEN`: Matches backend `MOVIEBOX_ADMIN_TOKEN` / `MOVIEBOX_API_KEY`

### B. Backend Read App (in `laterbox-moviebox-api` Vercel Project)
Already configured in Vercel (`LATERBOX_GITHUB_REPO=Adetisola/Laterbox`, `LATERBOX_ALLOW_UNAUTHENTICATED_GITHUB=0`, `GITHUB_APP_ID`, `GITHUB_APP_INSTALLATION_ID`, `GITHUB_APP_PRIVATE_KEY`, `LATERBOX_GITHUB_WEBHOOK_SECRET`):
- **Critical**: At cutover, grant this GitHub App installation access to the **newly created** `Adetisola/Laterbox` repository (and configure the webhook on `Adetisola/Laterbox` if used), OR temporarily set `LATERBOX_ALLOW_UNAUTHENTICATED_GITHUB=1` while reconfiguring the installation.

### C. Website (`Laterbox-Website` Vercel Project)
- `GITHUB_DISTRIBUTION_REPO=Adetisola/Laterbox`
- `NEXT_PUBLIC_DISTRIBUTION_REPO=Adetisola/Laterbox`
- Optional `GITHUB_RELEASE_READ_TOKEN` (server-side only; never `NEXT_PUBLIC_`)
- `NEXT_PUBLIC_SITE_URL` (set only when final custom domain is attached)

---

## 4. Final Cutover Checklist (Execute Only With Explicit Approval)

1. **Full Backup**: Run `python3 tools/distribution/backup_releases.py --source-repo Adetisola/Laterbox --backup-dir <backup_path>` on a machine with full GitHub CLI access to archive all 60 releases (`v1.0.0`–`v1.0.61`), metadata, and SHA-256 verified assets.
2. **Verify Source Readiness**: Confirm `Adetisola/Laterbox` `main` has the cross-repo workflows (`release.yml`, `backfill-release-deltas.yml`, `migrate-releases.yml`), `tools/distribution/`, `tools/release/`, and `release-notes/`.
3. **Verify Backup Completeness**: Inspect `backup-inventory.json` and confirm zero warnings across all 60 releases and consistent signing certificate (`67388d12f96d7b15179ef02354842faabfaff944a49c466ad3f539d2a2063e32`).
4. **Verify Staging / Cleanliness**: Confirm `migrate_releases.py` refuses any repository containing Android source files and creates tags only against the artifact-only `README.md` commit.
5. **Verify Release Workflow**: Confirm `DISTRIBUTION_APP_ID` and `DISTRIBUTION_APP_PRIVATE_KEY` secrets are ready to add to `Laterbox-Android`.
6. **Verify Backend Config**: Confirm `laterbox-moviebox-api` Vercel env has `LATERBOX_GITHUB_REPO=Adetisola/Laterbox`.
7. **Verify Website Config**: Confirm `Laterbox-Website` Vercel env has `GITHUB_DISTRIBUTION_REPO=Adetisola/Laterbox`.
8. **Rename Source Repository**: Make `Adetisola/Laterbox` **private** and rename it to `Adetisola/Laterbox-Android` (do **not** delete it).
9. **Create Public Distribution Repository**: Immediately create a new **public** repository `Adetisola/Laterbox` initialized with `README.md` only.
10. **Migrate Historical Releases**: Run `migrate_releases.py` (locally or via `.github/workflows/migrate-releases.yml`) in chronological order (`v1.0.0` → `v1.0.61`) into `Adetisola/Laterbox` with sanitized notes, `checksums.sha256`, and verified assets.
11. **Grant Backend GitHub App Access & Webhook**: Add the new `Adetisola/Laterbox` repository to the backend's GitHub App installation (and add the webhook pointing to `/updates/laterbox/cache/invalidate`).
12. **Confirm Public URLs**: Verify `https://github.com/Adetisola/Laterbox/releases/latest` and `https://github.com/Adetisola/Laterbox/releases/latest/download/delta-manifest.json` resolve publicly with `v1.0.61`.
13. **Refresh Backend Cache**: Call `POST https://laterbox-moviebox-api.vercel.app/updates/laterbox/cache/invalidate` and redeploy `laterbox-moviebox-api` on Vercel so every serverless instance clears old `asset_id`s.
14. **Verify Updater Flows**: Test `/updates/laterbox/latest` (`v1.0.54`–`v1.0.61`), direct `delta-manifest.json` (`v1.0.47`–`v1.0.53`), and direct GitHub API (`v1.0.18`–`v1.0.46`).
15. **Verify Website**: Confirm `Laterbox-Website` displays `v1.0.61` with sanitized notes, working Universal/ARM64/ARM32 downloads, and accurate SHA-256 hashes.
16. **Verify Next Release Pipeline**: Run a `workflow_dispatch` dry-run (and tag `v1.0.62` when ready) from `Adetisola/Laterbox-Android` to confirm end-to-end publishing to `Adetisola/Laterbox`.
