# Product decisions

## 2026-10-18 - Proprietary EULA for Marketplace

The Marketplace listing uses a custom EULA, not an open-source license from the upload list. The agreement lets users install and use the free plugin at their own risk and, to the extent permitted by law, declines liability for claims about how the plugin works and for any damage arising from its use. Public source still grants no right to copy, modify, or redistribute the plugin. The licensor is the sole trader operating as boyko.dev. The text is [EULA.md](../EULA.md).

## 2026-10-18 - Describe personal model-list management

The public description must explain that users can hide old or unused models and set a custom order, including placing their most-used model first. It must also mention Air's account information identifying the account used by the selected profile's agent. This documents existing profile model inclusion/order controls; configured filters and ordering remain policy over the bound agent's own catalog. No runtime or lifecycle behavior changes.


## 2026-10-18 - Recreate the public repository from a clean snapshot

The user will delete the original GitHub repository and requests recreation under the same personal owner/name. Prepare a fresh single-root Git history from the audited current sources; never push the previous history into the replacement repository. Preserve previous local Git metadata only in a private backup outside the working tree. Recreate the public repository only after its prior instance has been deleted. Existing IDE/profile/session data remains untouched.


## 2026-10-18 - Neutral public profile examples

Use only neutral Personal/Work profile labels, synthetic account identities and generic directory names in public documentation, fixtures, screenshots and Git history. Organization-specific labels are confidential and must not be published, including encoded copies. Live user profiles and the original private checkout are not renamed.

The unused pre-profile home service and its unused mismatch helper had no callers and embedded private defaults; retire that dead code rather than rename or encode its serialized fields. Its old XML remains untouched on disk. Active project/profile/session state components retain their names and files. Legacy CPS cleanup derives profile names from its existing saved homes map and still removes only exact proven-owned registrations. Full `check buildPlugin` passed after cleanup, including a new regression for arbitrary legacy profile names and preservation of manually changed registrations. The user subsequently chose repository recreation with a fresh initial commit instead of rewriting the old published history.


## 2026-10-18 - Short public README and illustrated quick start

The user requests a brief, plain-language explanation of the plugin and a very short illustrated manual. Keep README focused on separate AI profiles/accounts, project Default Profile and switching chats. Preserve the previous extensive description in [the detailed guide](detailed-guide.md), including developer notes and compatibility limitations. Publish only visually reviewed native UI screenshots with synthetic data; unreviewed local screenshots remain excluded. The user requests both Personal and Work in the settings illustration and Work selected in the chat illustration. This is a documentation change only: no runtime behavior, Marketplace metadata or deployment changes.


## 2026-10-18 ? Public repository and Marketplace identity

The user explicitly approves Plugin ID `dev.boyko.ai-multi-profiles`, Gradle root/repository `ai-multi-profiles`, and a new working copy at `C:\projects\ai-multi-profiles`. This supersedes earlier instructions to preserve the old Plugin ID only. ACP ID `acp.ai-multi`, name **AI Multi**, Kotlin/Java packages, persistent state and storage identities stay unchanged. Native-client architecture and Default Profile semantics remain binding.

Publish sources to the personal public GitHub repository `mboyko1979/ai-multi-profiles`; license selection is pending. Preserve 0.2.0, beta, since-build 262.10968 and no until-build. Do not deploy, uninstall, upgrade, restart the primary IDEA, touch live data/authentication/chats, or publish to Marketplace during this migration. Keep the original working directory. This task-specific prohibition overrides prior automatic deployment authorization, including the unchanged AGENTS.md. See [migration and compatibility plan](plugin-id-migration-2026-10-18.md).

## 2026-10-18 — Beta channel, current IDEA and newer

0.2.0 is the first stable product version and is distributed on the Marketplace `beta` channel, not the default channel. Compatibility starts at IDEA build 262.10968 and has no until-build, so newer IDE releases can install it. The same version number cannot be uploaded again to the stable channel; a public release needs a newer number. Adapters remain verified against AI Assistant 262.10968.223.

## 2026-10-17 — Manual parameter values survive Refresh Models

Refresh Models preserves explicit per-model choices such as Medium when the agent reports no reasoning recommendation. Missing-value warnings and Apply validation concern only enabled, available models. Update warnings immediately after parameter selection, model inclusion/exclusion and reset; identify the affected model and parameter. Saving remains an explicit Apply action.

Dev.96 implements this in the shared Settings editor for Assistant and Air. See [implementation and verification](defaults-refresh-warning-2026-10-17.md).

Actual Settings UI checks, full build/shared checks and native Assistant/Air regressions passed. Dev.96 is queued for the next primary IDEA restart, with the existing plugin backed up and unrelated updates preserved.

## 2026-10-17 — Model exclusions update open chats in both native clients

Excluding a model filters future choices; it does not replace an already accepted current model in an existing chat. Keep that current model visible until the user changes it. Restoring a chat does not reapply today's profile defaults. Real model unavailability remains owned by the bound agent/native client, including their fallback or error.

Dev.95 refreshes the existing config-option catalog when saved list filters change, using the shared routing layer for AI Assistant and Air. Re-enabling a model restores its agent-sourced entry without discovery, and accepted settings/session identity remain unchanged. The defect was reproduced in both native clients with controlled providers. See [implementation and verification](live-model-filters-2026-10-17.md).

Shared checks, `check buildPlugin` and actual native Assistant/Air regressions passed. Dev.95 is queued for the next primary IDEA restart with a backup and unrelated queued updates preserved.

## 2026-10-17 — Session-list icons identify AI Multi ownership

The user reaffirmed that a Personal/Codex session launched through AI Multi must display the AI Multi icon in Air's session list. The same rule applies to every bound agent. A directly launched agent retains its native icon.

Dev.94 scopes icon resolution to the actual row's `agentSessionId` in the AI Multi virtual-session namespace. A pinned Air 262 adapter decorates only the native renderer in `air.threads` and delegates rendering unchanged, supplying a temporary row-local icon-resolution context to the existing presentation service. This covers saved sessions without rewriting their source IDs, launch routes, histories or catalog/cache keys. Native progress/status badges and row actions remain delegated. No additional UI test runs were requested or launched; visual confirmation is manual.

Standard build/shared checks passed. Dev.94 is queued for the next primary IDEA restart with a verified backup and unrelated updates preserved. See [implementation and verification limits](session-list-icons-2026-10-17.md).

## 2026-10-17 — Account caption in an existing Air chat

Account presentation applies to already acquired AI Multi chats as well as New Session, for all bound agents. Dev.93 extends the account decorator's scope to existing AI Multi runtime bindings when tab metadata names the bound agent. It adds no session acquisition or agent-specific branch. At the user's request, repeated native tests were stopped; final confirmation of the reported chat is manual. See [evidence and limitations](acquired-account-caption-2026-10-17.md).

One final `check buildPlugin` passed. Dev.93 is queued for the next primary IDEA restart, with the installed plugin backed up and unrelated queued updates preserved.

## 2026-10-17 — Agent-independent defaults loading

Defaults collection and validation must use the same policy for every agent. Do not branch on agent identity or read vendor-specific model catalogs to recover missing ACP recommendations. Request supported recommendation metadata uniformly; keep reported current values distinct from model recommendations. This also applies to Grok and Codex.

Dev.92 loads the advertised catalog even when parameter recommendations are absent. Missing values require explicit selection (or disabling the affected model) before Apply. Refresh preserves existing choices; reset leaves unknown defaults unselected. The isolated settings probe advertises recommendation support to every agent. No vendor catalog adapter was added.

Build, shared regressions and native Assistant/Air checks passed. Dev.92 is queued for the next primary IDEA restart, with a backup and unrelated pending updates preserved. See [verification](defaults-loading-2026-10-17.md).

## 2026-10-17 — Reuse real prepared sessions before the first message

Keep the same empty native chat/composer and draft while choosing profiles. Native preparation may create real sessions before sending anything. Remember those sessions per draft: Personal/Codex → Grok → Personal/Codex must reuse the original Codex session ID and native handle. On the first prompt, retire unused prepared sessions; release them when the draft is abandoned. Do not send messages or load/resume merely to select a profile. Project Default Profile stays unchanged.

Implementation: dev.89 retains Assistant SDK bindings and Air's native preparation leases, with shared router cleanup. This supersedes dev.85's recreation of an already-bound empty Assistant chat. Initial Unassigned setup still uses the existing native initial-launch path. No metadata cache is added. Verification was reduced at the user's request; final Air behavior requires manual verification. See [implementation and verification limits](prepared-profile-sessions-2026-10-17.md).

## 2026-10-17 — Agent-owned cache only

Never add a plugin metadata cache and never wrap Air's `AirSessionPreferencesMetadataCache` with a profile UUID. Catalog isolation is the bound agent's native cache: different bound agents already have different caches. Grok profiles use Grok's cache and icon; Codex profiles use Codex's. Two profiles of the same bound agent share that agent's cache. Visible agent row stays `AI Multi → profile → native models`; launch still routes through AI Multi with `--initial-profile`.

Implementation status: dev.88 seeds the New Session launch with the bound `agentId`/`launchTargetId`, keeps the process as AI Multi via the composer lease, forces the agent chip to AI Multi, and shows local profile account email when native chip text is empty. Dev.91 makes that Grok footer chip visible: native Grok has no `_auth/status_update`, so Air hides the account control; the overlay now forces visibility, reads email synchronously from the profile `auth.json`, and drops blank native `SEGMENTS` so the caption can paint. Queued for the next primary IDEA restart; the IDE was not restarted.

## 2026-10-17 — Follow installed agent updates

Dev.87 fixes stale launch snapshots after native agent updates: shared route publication resolves the current installed configuration, preserving manual advanced overrides, profile identity/settings and project Default Profile. Native catalog changes and both clients' explicit launch boundaries refresh routes without acquiring or restarting agent sessions. See [cause and verification](installed-agent-launch-2026-10-17.md).

This is the current contract. Historical investigation and release notes do not override it.
Update this file when the user changes a decision; distinguish implemented behavior from pending work.

## 2026-10-17 — Acquired Air chat retains its agent's login

The native account control in an acquired AI Multi chat must receive the real account identity of that chat's committed profile/agent segment, independently of project Default Profile and other composer selections. Preserve the agent's actual capabilities, notification payload and unknown fields; do not synthesize an account status.

Implementation status: dev.86 forwards selected-backend connection notifications, including one-time auth pushes outside pending requests, and restores the retained source's last real auth push after a cancelled handoff. Air uses an icon-only account control in acquired chats, so a caption decorator in the existing preferences group also displays the native account presentation's chip text, scoped by stored acquired AI Multi tab coordinates. The button, popup and account lifecycle remain native; no live session is required for caption presentation. The acquired profile tooltip now matches Assistant's explanation that switching is available after the response finishes. The initial routing-only build passed full regressions. The user subsequently stopped automated tests and requested manual verification: final caption/tooltip changes compiled and packaged without another automated test run. Dev.86 is queued for the next primary IDEA restart with a hash-verified backup and unrelated updates preserved; the IDE was not restarted. See [cause, checks and limits](air-account-identity-2026-10-17.md).

## 2026-10-17 — One transparent profile architecture for every IDE client

The user explicitly confirmed that Air, AI Assistant and future IDE clients must share the same architecture. AI Multi manages profiles and indirectly selects the bound agent/environment; thereafter the native client handles that agent as directly as its APIs allow. The second shared policy layer applies configured defaults and model filters over the agent's real catalog. Native controls, caching and preparation/task lifecycle stay client-owned. Reuse profile/routing/settings policy across clients; isolate host bindings and remembered native preferences. Air improvements must not regress AI Assistant.

Implementation status: contract recorded in [architecture](native-client-architecture.md) and AGENTS.md. Dev.85 implements the launch-selection step with shared `ProfileLaunch` resolution. Assistant empty-chat selection uses native creation/staged initial routing; Air selection uses composer-local state and a launch binding scoped to that composer, without waiting for a live session or switching its virtual conversation. The global Air factory replacement is abandoned. Native UI proof covers immediate selection during delayed preparation, one-segment launch, native models/defaults, unchanged default/composer and independent chat. Wrapping Air's metadata cache is abandoned; see the agent-owned cache decision. Other [audit](native-client-audit-2026-10-17.md) items remain. Both-client native verification and all build/regression checks passed. Dev.85 is queued for the next primary IDEA restart with a verified backup and unrelated updates preserved; the IDE was not restarted.

## 2026-10-17 — Native client owns cached startup and preparation

Air owns model/settings rendering, loading, metadata caching and deferred/live preparation. AI Multi is a profile routing/default/filter layer over the bound agent's real catalog. Reuse native cached startup instead of requiring a live session to display known models; do not implement a second composer or preparation lifecycle. Preserve per-profile/agent isolation and project Default Profile. See the [architecture contract](native-client-architecture.md).

Implementation status: the [architecture audit](air-native-cache-audit-2026-10-17.md) confirms a persistent metadata cache, cached preferences source and deferred-start path. Dev.85 removes dev.83–84's live-binding prerequisite/fresh conversation switch for New Session profile choice. Its selector stays available during native preparation. The application-wide factory override was rejected and abandoned; only a particular AI Multi composer's launch binding is used. Per-profile wrapping of that cache is rejected; isolation is the bound agent's native cache (dev.88). Prior latency explanations describe the old implementation, not an Air/ACP requirement. Missing defaults do not justify a live prerequisite; explicit defaults can still require agent setters. Cache hits alone do not guarantee no native session start.

## 2026-10-17 — Air profile switching feedback

For an acquired conversation's actual profile transfer, show an animated indicator and target profile; guard overlapping transfers and clear pending state on completion/cancellation/failure. For New Session launch selection, dev.85 supersedes the old disabled-loader behavior: the choice completes immediately and stays available while Air owns agent preparation/loading. The project Default Profile remains unchanged.

Implementation status: dev.84 adds a native animated icon and `Switching to …` text scoped to the Air runtime, plus an atomic guard against overlapping selections. Initial native session preparation also shows a spinner. All build/regression checks and native UI verification with a delayed backend passed. The update is queued for the next primary IDEA restart, with a verified backup and unrelated updates preserved. See [verification and latency explanation](air-switch-loading-2026-10-17.md).

## 2026-10-17 — Minimal Air integration and stable New Session composer

Choosing an AI Multi profile in New Session must retain Air's native composer, draft, layout and tab. Do not open an acquired-chat view or create an extra tab merely to select a profile. The row stays `AI Multi → profile → native model/settings`; the underlying provider does not replace the AI Multi agent selection. Existing acquired chats retain their native layout; when Air omits its agent selector, place the profile before native model/settings. Add only the profile control to the native toolbar and preserve its other actions.

AI Multi's icon must appear in Air's session list, including chats created by earlier development versions. Preserve stored identities and histories, plugin/ACP registration IDs and the project Default Profile.

Implementation status: dev.83 switches the current composer's native prepared session and removes the separate-chat creator. It places the acquired-chat profile first and resolves icons from both launch targets and session descriptors. A visibility-only catalog alias supports the older Air source identifier without adding another launchable agent, changing stored sessions or adding session sources. Native checks and all build/regression checks passed. Dev.83 is queued for the next primary IDEA restart, with a verified backup and unrelated updates preserved. See [verification](air-native-composer-2026-10-17.md).

## 2026-10-17 — Air profile selector beside the agent selector

Show the AI Multi profile dropdown below the prompt immediately to the right of Air's agent selector, before the model and other settings, both in New Session and an acquired AI Multi chat. Use the native borderless selector style, with the profile name and profile icon. Do not duplicate the dropdown in the upper launch settings row. Selection keeps its existing chat-local routing behavior and preserves the project's Default Profile. Air must display the same AI Multi agent icon as AI Assistant.

Implementation status: dev.80 moves New Session selection into `Air.Prompt.Preferences`, the native bottom row containing the agent selector, and uses that same placement for acquired chats. Native UI verification passed; the update is queued for the next primary IDEA restart. See [verification](air-profile-placement-2026-10-17.md).

Dev.82 follow-up: the profile chooser belongs only to AI Multi. Selecting standard Codex or another agent hides it immediately. A profile-created chat must finish Air's native preparation lifecycle before opening, exposing the agent's real model/settings controls without an initial prompt. Native UI checks and the full build/regression checks passed; deployment is queued for the next primary IDEA restart with a verified backup. See [investigation and verification](air-preparation-2026-10-17.md).

Dev.81 follow-up: exact ordering, native compact button styling and Air icon resolution are implemented. Build/regression checks and native popup, ordering and icon checks passed; deployment is queued for the next primary IDEA restart, with a backup and unrelated updates preserved. The acquired fixture remains in Air's existing Preparing state, so its fully initialized selector row is not visually verified. See [verification and limitations](air-selector-style-2026-10-17.md).

## 2026-10-17 — Air folded agent launch identity

Dev.90 fixes the Personal/Codex New Session “No agents are available” error by using Air's own folded catalog mapping instead of inventing a dynamic catalog ID for Codex. The ACP target retains the bound registry identity. Native cross-client checks also found an Assistant first-prompt `IllegalAccessError`; its pinned callback now passes the real host result through an erased boundary. Build/check, visible folded-Codex Air verification and the combined native Assistant/Air suite passed. Dev.90 is queued for the next primary IDEA restart with a backup and unrelated updates preserved. See [implementation and verification](air-folded-agent-launch-2026-10-17.md).

## 2026-10-17 — Air profile selection before the first message

Air must expose AI Multi profiles on New Session as well as in an acquired AI Multi chat. Choosing or switching a profile preserves the project's Default Profile and other chats. Profiles remain inside AI Multi, with the existing plugin and ACP registration IDs. The earlier separate-chat-on-selection behavior is superseded by the minimal native-composer decision above.

Implementation status: dev.79 adds the chooser to Air's native launch toolbar. A connection-local initial routing argument selects the profile before session creation, without locking subsequent profile switches or overriding a restored committed segment. See [verification](air-profiles-2026-10-17.md).

## 2026-10-16 — Defaults and client preferences

- AI Assistant, Air and future clients follow the same policy. Client adapters implement native storage and lifecycle integration; they do not define precedence.
- Every new chat applies every explicitly configured default for that profile and client. Missing parameters come from that client's remembered preferences; absent or unavailable remembered values leave the agent's real initial value intact.
- Remembered preferences use the stable pair `[profile UUID, bound agent ID]`, shared across projects, independently for each client. Renaming and reauthorization preserve the key; switching agents changes it.
- Use the client's native store. Do not seed a profile's preferences from common AI Multi or original-agent preferences, and do not create a parallel preference store. Adapters expose only preferences their native store supports.
- Session acquisition and profile-default application do not overwrite remembered preferences. Only successful explicit client selections are remembered.
- Restoring a chat preserves the last committed segment's accepted state. Today's profile defaults and remembered preferences are not reapplied on load/resume.
- Profile selection in a chat never changes the project's Default Profile. Internal preference keys never become ACP routing or registration IDs.
- Missing defaults do not trigger automatic capture into the profile. Loading agent defaults is an explicit Settings action.

Implementation status: implemented in dev.78. Both native client stores and actual Assistant/Air sessions were verified in an isolated IDEA; see [implementation and verification](client-preferences-2026-10-17.md). Air deliberately excludes Plan from its native remembered parameters; explicitly configured profile Plan still applies, while an absent Plan preference leaves the agent's value intact.

## 2026-10-16 — Hanging turn investigation

The affected Personal/Codex chat completed its last response at 23:15:29 local time, but IDEA still displayed Thinking/Waiting. No question form was emitted. An earlier cancellation and subsequent steering request are confirmed. A native `startedNewTurn` completion gap was reproduced both through AI Multi and directly without its router; the exact missing message in the original production transport was not captured. This upstream issue is not fixed in dev.78. See [reproduction](steering-completion-2026-10-17.md). Do not fabricate ACP completion from text or a timer, restart the primary IDE, or interrupt active chats to conceal the problem.
