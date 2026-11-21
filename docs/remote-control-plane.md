# Laterbox Android Remote Control Plane Client

## Core Principles

- **Local-First Guaranteed**: Remote configuration cannot break local Inbox, Reader, Collections, or offline downloaded media playback.
- **No User Accounts Required**: Android authenticates requests using Firebase App Check (`X-Firebase-AppCheck`), never a user login.
- **Strict Capability & Schema Allowlists**: Unknown feature flags, unknown UI component types, unsafe markup, and unallowlisted destinations are rejected or ignored safely (`RemoteConfigValidator`).
- **Non-blocking Startup & Offline Resilience**: Cached DataStore state (`RemoteConfigCache`) hydrates immediately in background while `GET /config/v1/published` refreshes with `ETag` (`304 Not Modified`) and interval throttling (`minRefreshIntervalSeconds`) after the first frame settles.

## Key Modules (`com.laterbox.app.remoteconfig`)

- `RemoteConfigModels.kt`: Immutable v1 schema models (`RemoteConfigSnapshot`, `RemoteVersionPolicy`, `RemoteMaintenanceConfig`, `RemoteUiComponent`).
- `AppCapabilityRegistry.kt` (`RemoteCapabilities`): Declares all 9 allowlisted feature keys and 4 native UI component types sent on fetch.
- `RemoteConfigValidator.kt`: Validates `schemaVersion == 1`, expiry timestamps, plain text sanitization, version bounds, and destination allowlists (`movies`, `downloads`, `collections`, `inbox`, `archive`, `settings`, or `https://`).
- `RemoteConfigCache.kt`: Jetpack DataStore Preferences persistence + dismissed announcement tracking + stale-cache expiry fallback.
- `RemoteConfigClient.kt`, `RemoteConfigRepository.kt`, `RemoteConfigManager.kt`: Single-flight refresh, `If-None-Match` conditional requests, and lifecycle hooks (`LaterboxApplication`).
- `RemoteConfigLayoutHelper.kt` & `RemoteUiComponents.kt`: Safe compose rendering for `announcement_banner`, `hero_carousel`, `content_rail`, `featured_callout_card`, maintenance notices, recommended update banner, and enforced minimum-version blocking dialog (`RemoteForcedUpdateDialog`).
