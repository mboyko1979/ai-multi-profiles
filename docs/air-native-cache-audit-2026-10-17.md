# Air native cache and profile architecture audit

This is an investigation, not a shipped implementation. Production sources and version remain dev.84. The native plugin examined is the installed Air 262 build. Decompiled code and bytecode are local primary evidence in `build/air85-decompiled` and `build/air85-*.txt`.

## What Air already does

`AirSessionPreferencesMetadataCacheImpl` is a persistent native cache (`$CACHE_FILE$`). Its key contains `sourceConfigurationId` and `agentId`; a common AI Multi registration alone does not distinguish its profiles/accounts. `get` rejects unreadable entries and invalid model presentations. A model catalog saved by AI Multi's Settings page is a different artifact and is not automatically a native Air cache hit.

`AirSessionPreferencesHostViewModelImpl.runChild` first tries a cached child, emits native `AirSessionPreferencesState.Deferred` with cached controls when accepted, and decides whether to defer starting a live child. `CachedMetadataPreferencesSource` cannot send a prompt or apply a setting: both operations return false. Cache rendering and live readiness are separate native concepts.

`AgentPromptPreferencesChildServiceImpl.createFromCache` checks cached metadata against native remembered selections and the seeded launch profile. A missing seeded value or a conflicting native last-used selection can force live preparation. `AgentPromptPreferencesLaneBinding` supplies `deferChildStartWhenCached`. The host also checks explicit child-start requests and the cached configurable-provider state before postponing creation. Opening the native Agent popup can itself request child start. Therefore “a cache exists, so a session is created only on a cache miss” is not a universal Air rule. Let Air make that decision.

`AirSessionPreferencesSourceResolver` distinguishes a direct catalog path from a live preparation path using the native launch route and capabilities. Different built-in and ACP integrations can take different routes. The user's observation that standard Codex opens quickly is consistent with native cache/catalog paths; this audit does not claim to have timed the user's standard Codex process or proved that a particular opening had no backend process.

Native audit: `tools/VerifyClientPreferences.ps1 -AirOnly -AirVisible -OutputDirectory build/air85-cache-audit2` passed using only the controlled ACP fixture. Returning from Junie to AI Multi reported `nativeCacheHit=true`, `nativeCachedChildAccepted=true`, `deferChildStartWhenCached=true` and `cachedConfigurableProvidersPresent=true`, but also `childStartRequested=true`. Air therefore finished in `Ready` and issued one new `session/new` despite accepting cached metadata. This verifies that an accepted cache and a live session can coexist normally; it does not prove a production startup performance defect. Evidence: `build/air85-cache-audit2/cache-audit.txt`, `result.txt`, `native-warm-cache.png`. The first, less detailed audit in `build/air85-cache-audit` also completed successfully. No production accounts, primary IDE sessions or settings were changed.

## Where dev.84 differs

The model/settings controls are native Air controls, not replacement AI Multi dropdowns. The profile button and its placement adapter are ours. Earlier separate-chat opening was removed in dev.83.

However, `AirProfileBridge.preparedThread` accepts only native `Ready` preferences backed by `LiveThreadPreferencesSource`. It ignores native `Deferred` cached preferences. The selector disables itself when it cannot find that live binding. This unnecessarily couples profile selection to live preparation even though Air can already present cached controls.

New Session profile selection currently calls `AirProfileBridge.choose`, then `_ai_multi/switch` on the prepared virtual conversation. That creates another target segment/process/session instead of selecting the native launch configuration first. `connect` also manually enters Air's connection executor, rehydrates ownership and polls readiness when required. Those are the implementation choices behind the previous latency explanation, not necessary requirements for displaying a known catalog.

Missing profile defaults do not trigger automatic Settings discovery in dev.78 onward. Explicit defaults can add accepted setters after native initialization. Neither fact justifies gating cached presentation on live session creation.

## Required rework and historical review boundary

Dev.85 follow-up: launch profile selection is now composer-local and independent of a live binding, using an instance-scoped native launch hook. The global factory proposal below is abandoned following the user's clarification; it is not awaiting approval. Wrapping Air's metadata cache with a profile UUID is also abandoned: isolation is the bound agent's native cache (dev.88). See [current implementation boundaries](shared-launch-implementation-2026-10-17.md). The cache audit findings remain valid.

1. Keep profile identity, Default Profile and per-composer selected profile in AI Multi. Change the native draft's launch seed without changing tabs, layout, text or the project default.
2. Bind the selected profile to the initial launch arguments before Air starts its native ACP preparation. Do not start the project-default agent and then switch it merely to show another profile's settings.
3. Use the bound agent's native Air cache and remembered-preference store. Do not wrap the metadata cache or invent a plugin catalog. Never borrow a different agent's catalog to make the toolbar appear ready.
4. Overlay only explicit defaults and model filters on agent-sourced metadata. Air continues to render native controls and owns cached/deferred/live states and submission.
5. Validate cold/warm paths, absent/defaulted values, account isolation, native first prompt, stale catalogs, independent existing chats and standard agents before deployment.

The investigated integration point was `AirSessionPreferencesFactory`: delegate to Air's real factory, scope its cache/state/lease factories to AI Multi contexts, and use native draft selection and native backend preparation with profile-specific launch arguments. Native constructors expose injected launch-entry/adapter resolution, potentially avoiding a parallel preparation lifecycle. This is a pinned-host integration proposal, not proof of safety or compatibility, and frontend/backend separation must be considered.

An isolated prototype must prove: ordinary agents delegate to the original factory unchanged; profile routing is attached before native initialization; the first provider belongs to the selected profile; native cached controls survive without a live binding; namespaces cannot mix accounts; accepted native UI selections and explicit profile defaults retain their precedence; native lease cleanup and initial prompt run without synthetic readiness events. A successful prototype is not authorization to install an unverified factory replacement into the primary IDE.

Automatic approval review rejected the proposed source patch because overriding an application-wide factory and injecting internal runtime/cache adapters could disrupt native preparation and unrelated chats. The patch was not applied: no factory override, production adapter, version bump or deployment was made. The next reviewable step is an isolated sandbox prototype and explicit standard-agent delegation tests; applying that rejected factory change requires user approval. Investigation tools and architecture documentation are independent of that blocked change.
