# MovieBox Android Integration & Security Configuration

Laterbox integrates with the standalone MovieBox FastAPI backend (`Adetisola/Laterbox-MovieBox-API`) as the primary Movies & TV catalogue, search, episode metadata, stream resolver, and subtitle provider.

## Default Backend Endpoint

- **Production Endpoint**: `https://laterbox-moviebox-api.vercel.app/` (Vercel `sin1` / Singapore)
- **Alternative Providers Preserved**: Internet Archive + TVMaze + optional TMDB remain selectable in **Movies -> Data Sources**, alongside **Offline Sample Fixture Mode**.

The endpoint URL is centralized in `BuildConfig.MOVIEBOX_API_BASE_URL` (defaulting to the production HTTPS URL above) and `MovieBoxEndpointConfig` in [`UserPreferencesRepository.kt`](../app/src/main/java/com/laterbox/app/data/preferences/UserPreferencesRepository.kt).

## Authentication & Secret Isolation

### Debug Builds (Local Development & Testing)
In `debug` builds only, `app/build.gradle.kts` reads an optional `MOVIEBOX_API_KEY` from:
1. Gradle property (`-PMOVIEBOX_API_KEY=...`)
2. Environment variable (`MOVIEBOX_API_KEY`)
3. Untracked local `.env` or `local.properties`

Neither `.env` nor `local.properties` is tracked by Git (`.gitignore` excludes both). Never commit any real key to version control.

### Release Builds (Strict Zero-Bundled-Secret Guarantee)
In `release` builds, `app/build.gradle.kts` explicitly sets:
- `MOVIEBOX_API_BASE_URL` = `https://laterbox-moviebox-api.vercel.app/` (or an explicit HTTPS override)
- `MOVIEBOX_API_KEY` = `""`

Release APKs **never** inherit a developer's local `.env` or `local.properties` API key. Cleartext HTTP base URLs are also rejected in non-debug builds (`MovieBoxEndpointConfig.isValidBaseUrl` and `MovieBoxProvider.requireBaseHttpUrl`).

### Runtime Configuration (Data Sources Dialog)
Inside **Movies -> Data Sources**, testers can:
- Switch between `MovieBox Live` and `Open Archive & TVMaze`.
- Override the MovieBox server URL (with `https://` enforcement in release builds, or loopback `http://10.0.2.2` / `http://localhost` allowed in debug builds).
- Enter or clear an `X-API-Key` locally in device preferences (masked input with Show/Hide toggle).
- Toggle **Offline Sample Fixture Mode**.

## Required Changes Before Public Distribution

Using a static `X-API-Key` for developer/tester access is a temporary safeguard while the backend is under single-tenant evaluation:
1. **Do not distribute a shared static API key inside public APKs.** Any secret embedded in an APK can be extracted by decompilation.
2. **Adopt per-user or device-attested auth before public release**:
   - Issue short-lived user session tokens/JWTs after account login, or
   - Front the FastAPI service with an authenticated gateway validating Firebase App Check / Play Integrity attestation tokens and per-user rate limits.
3. **Never log** `X-API-Key` headers, signed CDN stream URLs, query signatures, or `Cookie` headers (enforced in `MovieBoxProvider.executeGet`).
