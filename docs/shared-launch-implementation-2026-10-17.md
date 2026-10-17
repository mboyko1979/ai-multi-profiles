# Session-independent launch profile selection — dev.85

This is the launch-selection step, not completion of the cache/internal-adapter audit. Profile choice requires no live session. Initialize a new composer from the project's Default Profile; subsequent choices belong to that composer and never change the project default.

## Shared and Assistant implementation

`ProfileLaunch.kt` resolves a profile snapshot independently of any client session. Assistant startup staging retains the existing router contract: native chat UID, selected profile UUID, project directory, canonical route-spec hash and creation time. Publication is atomic. The client creates/acquires the chat; the router validates its configuration. No capabilities, metadata or success events are fabricated.

`ProfileChatCreator` uses this boundary before focusing a native chat. `CurrentChatProfileSwitch` selects an empty chat's profile through native creation without acquiring/describing the old provider solely to perform a fresh virtual handoff. A confirmed-empty fallback uses the same path. There is no Assistant `_ai_multi/switch(fresh=true)` branch. History retains draft protection and consented transfer/recovery.

The Assistant probe selects grok and then codex through the actual empty-chat action: each focused native chat starts with the selected profile and one segment, sends no prompt, and leaves the original conversation and project default unchanged.

## Air binding and internal dependency

`AirComposerProfiles` stores selection in a weak map keyed by the native draft. Choice does not use `AirProfileBridge.binding`, `preparedThread`, `connect`, `_ai_multi/describe` or `_ai_multi/switch`. The chooser stays available during preparation. Air owns model/settings controls, preparation, loading and lease cleanup. Acquired-chat/history handling remains separate.

The launch hook is installed only on this AI Multi composer's `AgentPromptPreferencesChildServiceImpl.leaseService` instance. Other agents' requests pass unchanged to its original instance. For AI Multi, Air's own `ThreadGuiPreparedSessionLeaseServiceImpl` and `BackendThreadGuiPreparedSessionApi` run with an injected ACP catalog-entry resolver that appends `--initial-profile <UUID>` to the original arguments. Other entry properties/start configuration delegate unchanged. Air's original ownership services remain in use. This path has no custom readiness event, polling runner or manual connection executor.

Native `draft.pick` uses a composer-local profile preset origin and `persist=false`. Agent, launch target, registration and route IDs remain unchanged. A distinct preset origin lets Air invalidate the old preferences child: keeping the original origin did not restart it despite a changed seed revision. This preset identity is not an ACP agent ID. Air's popup may have no matching stock preset row for the custom origin; its displayed agent remains AI Multi. Unsupported APIs disable this binding explicitly instead of silently reverting to a live conversation switch. Platform cancellation/control-flow exceptions propagate.

This remains a pinned Air 262 adapter using a private instance field and internal constructors. Its scope is one AI Multi composer, not a stable public extension API. Local verification does not establish remote frontend/backend support. Air updates require compatibility checks.

## Abandoned global proposal

The application-wide `AirSessionPreferencesFactory` replacement was rejected by automatic approval review and then rejected as an integration direction by the user. It is abandoned, not awaiting renewed approval. There is no such class, registration or service replacement. Profile choice never justified that mechanism.

Wrapping `AirSessionPreferencesMetadataCache` or suffixing its key with a profile UUID is also abandoned. The user requires agent-owned caches only: different bound agents already have different native caches.

## Remaining cache/audit work

The native persistent cache and deferred path are confirmed in the [cache audit](air-native-cache-audit-2026-10-17.md). A cache hit can coexist with native live preparation. Dev.88 seeds the composer launch with the bound agent's `agentId`/`launchTargetId` so Air looks up that agent's native cache; the process still starts AI Multi with `--initial-profile`. A cache miss shows native Updating… instead of the previous agent's models. Two profiles of the same bound agent share that agent's cache. Do not promise instant model readiness from profile defaults.

Other internal adapters in the [cross-client audit](native-client-audit-2026-10-17.md) remain review items. Acquired-chat transfer/recovery is preserved, not redesigned here.

## Verification

`build/composer85-native11` passed the isolated Air UI suite: real popup callback, initial project default, enabled immediate choice during a delayed response, selected-profile native launch with one segment, native models/configured defaults, unchanged composer coordinates/tabs/default, no prompt on selection and independent acquired chat. The probe follows replacement native leases rather than expecting released empty prepared conversations to survive cleanup.

The independent shared/Assistant changes passed `check` and both-client native verification (`build/shared-launch-check.log`, `build/shared-launch-native-final`). Final dev.85 verification passed both clients in `build/composer85-native-final`, including the Assistant launch assertions, actual native preference stores, cancellation and the documented routed/direct upstream steering reproduction. `check buildPlugin --offline --max-workers=1` passed all 33 actionable tasks (`build/composer85-build.log`). The upstream steering-completion issue remains known; this change does not fix it.

Dev.85 is queued for the primary IDEA's next restart. Its verified backup and deployment record are in `deployment-backups/20261017-145140-0.2.0-dev.85`. All installed plugin files were backed up and hash-verified; native queue regression checks passed and unrelated pending updates were preserved. The primary IDE was not restarted and active chats were not interrupted.
