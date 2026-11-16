# 07 — Assumptions, Hypotheses & Open Questions (Phase 5 Checkpoint)

## 1. Confirmed Facts vs. Product Hypotheses

To maintain strict intellectual honesty, we separate **Confirmed Codebase Facts** from **Product Hypotheses**:

### Confirmed Codebase Facts (100% Verified in Source Code)
1. **[`LaterboxBottomBar.kt`](file:///home/antigravity/Adetisola/Laterbox/app/src/main/java/com/laterbox/app/ui/components/LaterboxBottomBar.kt#L59) is unused dead code**: Handset navigation relies exclusively on a top-left `ModalNavigationDrawer` while `FloatingChatComposer` permanently occupies the bottom of `InboxScreen`.
2. **Smart Digest notifications silently fail without a Gemini API key or when offline**: Both [`SmartReminderWorker.kt`](file:///home/antigravity/Adetisola/Laterbox/app/src/main/java/com/laterbox/app/reminders/smart/SmartReminderWorker.kt#L106-L122) and [`ReminderReceiver.kt`](file:///home/antigravity/Adetisola/Laterbox/app/src/main/java/com/laterbox/app/reminders/ReminderReceiver.kt#L166-L175) skip notification delivery when `SmartDigestEngine.generateDigestCopy()` returns `AiCopyResult.Failure`, instead of falling back to deterministic copy like manual reminders do.
3. **`POST_NOTIFICATIONS` permission is never requested during onboarding or when setting reminders**: On Android 13–15, runtime notification permission is only requested inside [`MediaSection.kt`](file:///home/antigravity/Adetisola/Laterbox/app/src/main/java/com/laterbox/app/media/ui/linkdetail/MediaSection.kt#L125) when downloading media.
4. **No In-App Article Reader exists**: Links only open in external Custom Tabs/browsers via `BrowserLauncher.kt`.
5. **Search, Multi-Collection, Backup, and ViewMode gaps**:
   - `SavedLinkDao.searchLinks()` omits `description` and `site_name`.
   - `SavedLinkCard.kt` only displays the first collection name even when a link belongs to multiple collections.
   - `CollectionDetailScreen.kt` omits swipe gestures, `viewMode`, and `hasDownloadedMedia` on `SavedLinkCard`.
   - `BackupManager.kt` omits `reminderAt` during JSON export/import.
   - `InboxViewMode.EDITORIAL` is implemented in `SavedLinkCard.kt` but unreachable in `InboxViewModel.onToggleViewMode()`.
6. **Cold-start overhead**: `LaterboxApplication.onCreate()` launches 8 background tasks immediately on cold start (including duplicate `reconcileInspections()` calls), and `LaterboxNavGraph.kt` eagerly instantiates all 11 ViewModels at root.

### Product Hypotheses (To Be Validated via Local Metrics & User Usage)
1. **Hypothesis H1 (Interactive Starter Link in Onboarding)**: Seeding an optional interactive starter link (and 1-tap clipboard save) during onboarding will reduce Day-1 blank-screen abandonment and increase discovery of Media Downloads and YouTube Transcripts.
2. **Hypothesis H2 (Consumption-First Detail Sheet + In-App Reader Mode)**: Presenting an editorial overview with an In-App Article Reader and above-the-fold Media/Transcript actions (while tucking Title/Note `OutlinedTextField`s behind an `Edit` toggle) will increase the Save-to-Consume ratio.
3. **Hypothesis H3 (Bottom Navigation + Compact Assistant Dock)**: Activating `LaterboxBottomBar` and replacing the full-width `FloatingChatComposer` with a compact `[✨ Ask Assistant] [+ Save]` dock will increase navigation into `Collections` and `Downloads` without reducing intentional Assistant usage.

---

## 2. Key Open Questions for User Approval (Phase 5 Checkpoint)

Before we begin writing code in Phase 1 of the implementation roadmap, please review and confirm your preferences on these **4 design & product decisions**:

1. **Handset Bottom Navigation vs. Floating Chat Bar (`P0-3`)**:
   - *Recommended*: Activate [`LaterboxBottomBar`](file:///home/antigravity/Adetisola/Laterbox/app/src/main/java/com/laterbox/app/ui/components/LaterboxBottomBar.kt) (`Inbox`, `Collections`, `Downloads`, `Archive`) at the bottom of handset screens, and replace the permanently expanded `FloatingChatComposer` on the Inbox with a compact floating dock (`✨ Ask Laterbox` pill + `+ Save` button) that expands the full chat/voice composer when tapped.
   - *Alternative*: Keep the full-width `FloatingChatComposer` pinned on the Inbox and place `LaterboxBottomBar` below it (or keep top-bar tabs).

2. **Default Card Tap Action & `SavedItemDetailSheet` Layout (`P0-4`, `P1-3`)**:
   - *Recommended*: Keep card tap opening `SavedItemDetailSheet`, **redesigning `SavedItemDetailSheet` into a Consumption-First Hub** (clean title/note display, primary **`Read in Laterbox`** / **`Open Web ↗`** / **`▶ Play Offline`** buttons, and above-the-fold Media & YouTube Transcript cards, with an `Edit` pencil icon to edit title/notes) + make the default card tap action configurable in `Settings → Reading & Behaviour`.
   - *Alternative*: Make tapping a card open the link/reader directly, and add a `•••` button on the card to open `SavedItemDetailSheet`.

3. **In-App Article Reader Mode Scope (`P1-3`)**:
   - *Recommended*: Implement a clean, local-first Jsoup-based **Distraction-Free Reader Sheet (`ArticleReaderSheet`)** that extracts article headings, paragraphs, quotes, and lead image, respects `LaterboxTheme` and `fontSizeScale`, caches extracted text for offline reading, and provides a 1-tap fallback to Chrome Custom Tabs (`Open Original Web Page ↗`).
   - *Alternative*: Skip the in-app article reader for now and rely solely on Chrome Custom Tabs.

4. **Execution Scope After Approval**:
   - Would you like us to implement **all 5 phases sequentially** (verifying unit tests and build health at each phase), or start with **Phase 1 + Phase 2** first and pause for a midpoint review?
