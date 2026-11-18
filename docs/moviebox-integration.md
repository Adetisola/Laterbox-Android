# MovieBox Android Integration & Security Configuration

Laterbox integrates with the standalone MovieBox FastAPI backend (`Adetisola/Laterbox-MovieBox-API`) as the primary Movies & TV catalogue, search, episode metadata, stream resolver, and subtitle provider, unified via `MediaResolver` with Internet Archive and TVMaze automatic fallback.

## Default Backend Endpoint

- **Production Endpoint**: `https://laterbox-moviebox-api.vercel.app/` (Vercel `sin1` / Singapore)
- **Fallback Providers**: Internet Archive and TVMaze provide automatic catalogue/metadata enrichment and fallback when MovieBox is unavailable, alongside debug-only **Offline Sample Fixture Mode**.

The endpoint URL is centralized in `BuildConfig.MOVIEBOX_API_BASE_URL` (defaulting to the production HTTPS URL above) and `MovieBoxEndpointConfig` in [`UserPreferencesRepository.kt`](../app/src/main/java/com/laterbox/app/data/preferences/UserPreferencesRepository.kt).

## Authentication & Secret Isolation

### Firebase App Check (Production & Debug)
Requests to the MovieBox backend are authenticated with **Firebase App Check** (`X-Firebase-AppCheck` header):
- **Release builds** use `PlayIntegrityAppCheckProviderFactory` (configured in [`LaterboxApplication.kt`](../app/src/main/java/com/laterbox/app/LaterboxApplication.kt)).
- **Debug builds** use `DebugAppCheckProviderFactory`.
- On HTTP `401` / `403`, [`MovieBoxProvider`](../app/src/main/java/com/laterbox/app/movies/data/provider/MovieBoxProvider.kt) refreshes the App Check token once before surfacing an auth failure.
- No `X-API-Key` or static backend secret is bundled in APKs, stored in `UserPreferences`, or exposed in the UI. Legacy `moviebox_api_key` DataStore entries from earlier builds are purged automatically on startup.

### Runtime Configuration (Data Sources Dialog)
Inside **Movies -> Data Sources**, users/testers can:
- Override the MovieBox server base URL (with `https://` enforcement in release builds, or loopback `http://10.0.2.2` / `http://localhost` allowed in debug builds).
- In debug builds only, toggle **Offline Sample Fixture Mode**.

### Logging & Privacy Rules
- **Never log** `X-Firebase-AppCheck` tokens, signed CDN stream URLs, query signatures, or `Cookie` headers (enforced in `MovieBoxProvider.executeGet`).
