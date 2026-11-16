# Laterbox

> *"Find something interesting. Save it. Get back to it later."*

Laterbox is a calm, local-first native Android application for saving and organising links encountered online—articles, YouTube videos, Reddit discussions, reference websites, tutorials, and inspiration.

Instead of leaving dozens of tabs open or scattering bookmarks across messaging apps and notes, Laterbox provides one quiet, thoughtfully crafted place to keep them.

---

## Product Philosophy & Design

Laterbox is conceived as a **personal digital library**, not a task manager, corporate dashboard, or social network. There are no overdue badges, streaks, or noisy notifications.

- **Warm Editorial Aesthetic**: Built on a warm ivory canvas (`#F8F7F3`) in light mode and deep charcoal (`#141414`) in dark mode, with crisp typography and subtle hairline borders instead of drop shadows.
- **Expressive Typography**: Pairs an editorial serif (**Newsreader**) for contemplative titles and empty states with a modern sans-serif (**Inter**) for dense UI legibility and a monospace face (**JetBrains Mono**) for metadata.
- **Adaptive Link Cards**: Seamlessly displays rich preview images when available, while treating text-only cards as first-class editorial citizens when sites lack Open Graph images.
- **Local-First & Private**: All data is stored on-device in a SQLite/Room database. No user accounts, no cloud telemetry, no tracking SDKs, and no external metadata proxy servers.
- **Consumption-First Experience**: Handset bottom navigation (`Inbox`, `Collections`, `Downloads`, `Archive`), a distraction-free local Jsoup **Article Reader**, 3 card layout modes (`Hero`, `Compact`, `Editorial`), and a configurable card tap action.

---

## Technical Architecture

Laterbox is built as a 100% native Android application:

- **Language & Toolchain**: Kotlin 2.0 with Kotlin Symbol Processing (KSP).
- **UI Framework**: Jetpack Compose with custom Material 3 token-driven theming and responsive handset/tablet layouts (`LaterboxBottomBar` on handsets, persistent navigation rail/drawer on wide screens).
- **Local Persistence**: Room database (`FTS4` full-text search across titles, notes, descriptions, and site names) with reactive Kotlin Coroutines and `Flow`.
- **User Preferences**: Jetpack DataStore (Preferences).
- **In-App Reader & Metadata Extraction**: Jsoup-based HTML extraction with local 7-day reader disk cache (`cacheDir/reader_cache/`) and Chrome Custom Tabs fallback.
- **Offline Media & Transcripts**: On-device media inspection and downloading (`yt-dlp`, QuickJS, FFmpeg) plus YouTube video & playlist caption extraction/export (`MD`, `TXT`, `PDF`).
- **Image Loading**: Coil Compose for memory-efficient favicon and thumbnail caching.
- **Sharing Integration**: Native Android `ACTION_SEND`, `ACTION_SEND_MULTIPLE`, `ACTION_PROCESS_TEXT`, and Quick Settings tile integration (`ShareReceiverActivity`, `QuickSaveTileService`).

---

## Project Structure

The repository follows the standard Android Gradle project hierarchy:

```text
Laterbox/
├── app/                        # Android application module
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/laterbox/app/
│   │   │   │   ├── ai/         # Optional Gemini digest & assistant client (with local fallbacks)
│   │   │   │   ├── capture/    # Clipboard suggestion & accessibility text capture
│   │   │   │   ├── data/       # Room database, DAOs, repositories, reader cache, DataStore, backups
│   │   │   │   ├── diagnostics/# Local app health checker & on-device metrics
│   │   │   │   ├── domain/     # Models, URL normalization, shared text parser
│   │   │   │   ├── media/      # Media engine: extraction adapters, downloads library, viewers
│   │   │   │   ├── metadata/   # Jsoup extractor & offline worker
│   │   │   │   ├── reminders/  # Scheduled & smart reminders, notification history
│   │   │   │   ├── sharing/    # ShareReceiverActivity & intent handling
│   │   │   │   ├── transcript/ # YouTube caption extractor & MD/TXT/PDF exporters
│   │   │   │   └── ui/         # Compose screens, Reader sheet, theme tokens, components
│   │   │   ├── res/            # Vector drawables, fonts, strings
│   │   │   └── AndroidManifest.xml
│   │   └── test/               # Unit, Room, and Robolectric test suites
│   └── build.gradle.kts
├── docs/                       # Architectural specifications & CI/CD guides
├── .github/                    # GitHub Actions workflows
├── build.gradle.kts            # Root build script
├── settings.gradle.kts         # Module inclusion & plugin repositories
└── gradle/                     # Gradle wrapper & version catalogs
```

---

## Continuous Integration & Release Strategy

Laterbox uses **GitHub Actions** for automated build and release verification:

- **CI Pipeline**: On every push and pull request to `main`, workflows validate the Gradle wrapper, run Android Lint, execute the complete unit and Robolectric test suite, compile the debug APK, and upload the build artifact.
- **Release Distribution**: Pushing a version tag (`v*`) triggers an automated build that signs the release APK via secure GitHub Actions Secrets and attaches multi-ABI APKs (`Laterbox-ARM32.apk`, `Laterbox-ARM64.apk`, `Laterbox-Universal.apk`) directly to a GitHub Release.
- Detailed pipeline specifications and secret configurations are documented in [docs/ci-cd-architecture.md](docs/ci-cd-architecture.md).

---

## Adaptive Layout Status

- **Design System Standardisation**: All screens, dialogs, buttons, cards, and input fields use Laterbox tokens (`LaterboxTheme.colors`, `spacing`, `typography`, `LaterboxShapeTokens`, and standardized components) with `48.dp` accessible touch targets.
- **Responsive Handset & Wide-Screen Navigation**: Handset screens present a persistent 4-destination `LaterboxBottomBar` plus a compact expandable `InboxActionDock` (`✨ Ask Laterbox` + `+ Save`). Wide tablet screens (`>= 840.dp`) switch to `LaterboxPersistentSidePanel`.

---

## Git Workflow & Contributing

- The `main` branch always reflects the verified, stable state of the application.
- All new features and fixes are developed in isolated topic branches (`feature/*`, `fix/*`) and verified against tests and lint before merging.
- Commits follow the [Conventional Commits](https://www.conventionalcommits.org/) specification.
- For complete branching, style, and verification guidelines, see [CONTRIBUTING.md](CONTRIBUTING.md).

---

## Public Repository Security & Privacy

This is a public repository:
- Machine-specific files (`local.properties`), keystores (`*.jks`, `*.keystore`), and credential files are strictly excluded via `.gitignore`.
- Release signing secrets are managed solely via GitHub Secrets.
- Sample data and automated test fixtures use strictly synthetic, public domains (`example.com`, `wikipedia.org`) with zero private user information.

### Media Library, Privacy & Optional AI Services

Laterbox includes an on-device Media Library for discovering and downloading media directly into local app storage:
- **Zero Third-Party Relays**: All media downloads and byte transfers occur strictly on-device directly between your phone and the original content servers. Links, cookies, and downloaded files are never proxied or forwarded to intermediate servers.
- **Local yt-dlp & QuickJS Engine**: The media extraction pipeline runs locally on your device using a bundled Python runtime with QuickJS for JavaScript player signature deciphering.
- **Public X/Twitter Metadata (FxEmbed)**: For X (formerly Twitter) links only, Laterbox queries `api.fxtwitter.com` strictly to resolve public post metadata and media URLs when saved, without requiring accounts, API keys, or personal identifiers.
- **yt-dlp Extractor Engine Updates**: To keep extractors functional when platforms update their web players, Laterbox periodically queries the official `yt-dlp` GitHub releases repository (checked at most once every 24 hours on app launch, or manually via Settings). This request contacts GitHub solely to check for binary releases—no user links, history, or identifiers are transmitted.
- **Optional Google AI Studio (Smart Reminders & Assistant)**: Both Smart Digests and the Laterbox Assistant operate cleanly with deterministic on-device fallbacks. If you opt in and configure a personal Google AI Studio API key in Settings, only the relevant prompt and local library context are sent directly to Google AI Studio when requested.

---

## Licensing Notice

Source code in this repository is currently publicly visible with **All Rights Reserved**. 

*Note for Review*: While the code is hosted publicly on GitHub, making a repository public does not automatically make it open source. Selecting a formal open-source license (such as **Apache 2.0**, **MIT**, or **GPLv3**) or maintaining proprietary copyright is a distinct decision reserved for the repository owner.
