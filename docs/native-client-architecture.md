# Native client and profile responsibilities

User-confirmed contract, 2026-10-17, including the follow-up covering AI Assistant and future IDE clients. This supersedes historical implementation notes where they conflict.

The same architecture applies to Air, AI Assistant and future IDE clients. Selecting a profile indirectly selects an agent and its profile-specific environment. After that resolution, the client should work with the bound agent as it does after selecting that same agent directly, wherever native integration permits. Explicit profile settings and required profile identity/routing are the intentional differences; a separate client behavior or lifecycle is not a product goal.

| Responsibility | Owner |
| --- | --- |
| Profile CRUD, credentials/environment isolation, project Default Profile, per-chat/composer selection and routing | AI Multi shared profile layer |
| Catalog contents, model IDs, capabilities, protocol behavior, accepted values, notifications and errors | Bound agent, through the native client/transport |
| Configured default selections and model inclusion/exclusion over agent-sourced metadata | AI Multi shared policy layer |
| Composer/chat layout, native model/settings controls, loading, metadata caches, preparation, prompting, tools, cancellation and task completion | Native client |

The two execution-policy layers are profile/agent resolution and explicit default/filter application. Profile administration and existing consented history transfer remain AI Multi features. Profile attribution UI must stay a narrow addition. The agent supplies catalog metadata; the client renders the dropdowns. Profile selection never changes the project's Default Profile.

Clarification after the original adapter proposal: selecting a launch profile requires no live session, cache lookup, agent discovery or protocol request. Initialize the composer's own selection from the project Default Profile. Subsequent choices belong to that composer. Agent metadata can still be loading while this selection is already complete. A global Air factory replacement is abandoned; it is not a prerequisite for this behavior. Any unavoidable native binding must be scoped to the AI Multi composer and justified separately by the need to carry its launch routing, not by profile choice itself.

## Shared implementation and host adapters

Profile resolution should produce a client-independent launch description: stable profile UUID, bound agent identity, executable/arguments/environment and explicit settings policy. Keep profile identity and launch preparation separate from a live session. Share policy for defaults, remembered-preference precedence, filtering, cache identity/invalidation and consented routing; preserve existing protocol transparency and restoration rules.

Host adapters translate that description into native launch/selection APIs, bind remembered-preference storage, and pass agent-sourced metadata through the shared overlay. They expose only functionality the client actually supports. They must not independently decide precedence, reconstruct the agent's catalog, choose substitute models, replicate a preparation/task runner, or wrap the client's metadata cache. A future client adds native bindings and contract tests, rather than another copy of profile policy. Client-specific remembered preferences remain independent.

Current shared pieces are `router/profile-settings.js`, routing/virtual-session modules and `ClientPreferences.kt`/`NativeClientPreferenceStore`. Keep them shared. `ProfileLaunch.kt` provides shared launch resolution used by both clients and explicit startup staging for AI Assistant. Air binds the selected launch on its own composer instance. Cache/presentation integration is not yet unified: see the [cross-client audit](native-client-audit-2026-10-17.md) and [implementation boundaries](shared-launch-implementation-2026-10-17.md).

## Native startup and catalog overlays

For an already prepared, unsent draft, retain the native chat/composer and the real prepared sessions across profile choices. Returning to a profile reuses its native handle and original session ID. Release unused preparations after the first message is accepted, or when the draft is abandoned. This supersedes recreating an empty Assistant chat merely to select another prepared profile; it adds no metadata cache or requirement to acquire a session before recording a local choice. See [dev.89 host bindings and verification limits](prepared-profile-sessions-2026-10-17.md).

For Air New Session or AI Assistant New Chat, resolve the selected profile and its bound agent before the native client acquires the initial agent session where its APIs permit. Supply profile-scoped native launch metadata and seed selections. The common filter removes only configured exclusions from the real catalog and preserves unknown fields/groups; it never fills the list from a hand-maintained replacement. Keep newly reported agent models available unless an explicit policy excludes them. Explicit defaults are selections, not a source of models or evidence that a session is ready.

Let each client render its native controls and decide between supported cached/deferred/live preparation paths. Do not add an AI Multi requirement for synchronous session creation merely to show an accepted cached catalog. Air may still prepare in the background or start after a native interaction despite a cache hit; AI Assistant may have a different native policy. Do not force both clients to copy Air's lifecycle or promise that cache hits never start a session. An unavailable cached/configured value must be resolved against the real agent rather than invented. A display cache is not a live ACP session and must never authorize sending an old agent's session ID to another agent.

Do not build replacement windows, parallel model/settings dropdowns, a parallel UI cache or a second session-preparation state machine. Avoid manually calling connection executors, rehydrating ownership, polling readiness or opening another view solely to select a new launch profile. A narrow host adapter may bind profile identity, launch arguments, native preference seeds and agent-catalog overlays; it must delegate rendering and lifecycle to the client, preserve unknown native actions/fields and document its pinned API dependencies. A global factory/provider replacement is not justified merely because the shared architecture is approved; its scope and unrelated-client behavior require concrete review and verification.

Do not add a plugin metadata cache and do not wrap or suffix the client's cache key with a profile UUID. Catalog isolation is the bound agent's native cache: a Grok profile uses Grok's Air cache, a Codex profile uses Codex's. Two profiles of the same bound agent share that agent's cache. Keep plugin/ACP registration IDs stable, do not add duplicate user-visible agents to obtain separate cache keys, and never mutate the project's Default Profile during chat selection. Explicit defaults precede that client's profile/agent-scoped remembered preferences; absent selections preserve the real agent's values.

Existing conversations retain their committed profile/agent segment and accepted settings. A deliberate switch in a conversation with history remains a consented routing/history operation, distinct from choosing a New Session launch profile. Preserve native session admission and completion; do not synthesize ACP capabilities or success.

## Verification across clients

Every change to shared routing/default/filter code must pass both clients' contract tests. An Air-specific lifecycle/cache change must also pass AI Assistant regression verification, and conversely. Cover direct agent selection versus the same agent through a profile with no explicit overrides, cold/warm cache, partial/complete defaults, model filters and new agent models, independent account catalogs/preferences, unchanged draft/layout/default profile, first native prompt, tools, cancellation, completion, restoration and consented history transfer. Compare observable behavior and preserve unknown protocol fields/errors; stable routing IDs and authorized profile overlays are expected differences.

Use native verification for both clients in an isolated IDE; do not infer AI Assistant safety from an Air-only run. Keep standard agents and unrelated chats in the regression scope. Timing claims require measurements; existence of a cache alone does not prove which path a particular production session used.
