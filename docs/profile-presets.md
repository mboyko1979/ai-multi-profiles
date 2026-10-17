# Profile presets and account identity — 0.2.0-dev.74

For current precedence, client parity and preference identity, see [product decisions](decisions.md). Both AI Assistant and Air follow that contract.

## Current model defaults contract (dev.74)

AI Assistant and Air have independent client configurations. With no configuration, Settings shows **Load Agent Defaults** and new chats use the client's and agent's ordinary behavior. Explicit loading creates concrete settings for every model, with a single default model and an ordered enabled-model list. There is no `Use agent default` choice or hidden frozen-value fallback in this editor. Existing legacy snapshots are migrated literally, including incompatible values that require correction.

**Refresh Models** discovers a fresh catalog without resetting existing choices. New models are prepended enabled; existing order and enabled state are retained. Invalid values remain visible in red as **Deprecated** and block Apply for enabled available models. Explicit refresh resets a disappeared default model to the agent's newly reported default and reports the change. **Reset Default Model** only uses the last loaded agent default model; **Reset defaults for this model** resets its concrete parameters from that load. **Remove Defaults** removes all model settings and presentation policy for just the selected client. Unavailable model recommendations leave the parameter unselected; select a value or disable the affected model before Apply. Loading the remaining catalog succeeds, and another model's inherited current value is never used as a recommendation.

At startup the router validates the selected model and its concrete settings against the real session result, applies the model first, and revalidates dependent options. Boolean setters include the ACP boolean discriminator. A deprecated/rejected setting opens the correct profile, client and model with the exact option/value and available values. The original new-session request stays pending while Settings is closed or edited. Apply continues configuration on the same backend session. Removing defaults during correction rolls back partial setters to that session's original reported values. Authentication, transport, launch and unrelated provider errors remain real errors.

Model list filtering and ordering preserve provider option fields and extensions. A disabled current model remains displayed until the user selects another model. Explicit model selection applies that model's concrete parameters captured with the segment; changing today's profile configuration does not rewrite an old segment's parameters. Load/resume never reapplies profile defaults or substitutes new-session creation. A changed agent requires consented recovery and never receives the old agent's session ID.

Host selection recognizes both native Assistant client names (`JetBrains.IntelliJ IDEA` and `JetBrains IntelliJ IDEA`) and Air's own identity. Settings probes and runtime therefore choose the same independent defaults. New chat toolbars wait for accepted runtime state; they no longer seed stale discovery defaults as if those values had already been accepted.

Verification: `concreteDefaultsTest` covers real router pending creation/correction on the same session, model policy, per-model selection, native client identity, boolean typing, removal rollback and restore without setters. `concreteDefaultsPersistenceTest` covers XML/deep copies, model recommendations, refresh reconciliation, deprecated values and independent removal. `tools/ConcreteDefaultsProbe.java` exercises the native editor and startup lifecycle in an isolated runIde sandbox with synthetic credentials and an ACP fixture. The historical sections below document previous versions and are superseded by this contract.

## Implemented UI and persistence

Settings is **AI Multi Profiles**. The existing list and chat dropdown keep their structure and icons. **Default Profile (project-specific)** edits the selected profile's draft project assignment. Checking another profile replaces the default; unchecking it makes the project unassigned. Apply commits the change and Reset restores it. The default's name is bold; **Default for this project** appears only in its tooltip. Chat-local selections keep the project default unchanged.

Email, account ID, discovery status and Refresh account info appear in the **Account info** popup beside Authorization. The popup resizes after asynchronous identity discovery. The compact chat selector tooltip uses the active segment's identity; profile option descriptions include their own account email. The native toolbar takes its hover description from `popupDescription`, so that property supplies the full hint too. Existing reactive native model/effort/mode controls display confirmed session values, rather than duplicating a settings snapshot in the profile hint. Saved presets are never presented as confirmed runtime values; email is not a permanent toolbar label or an extra dropdown row.

`AgentProfiles.Entry` retains existing identifiers and launch fields. New settings are an extensible list scoped by agent ID, operation and option ID. Legacy XML defaults to an empty list and no additional instructions. Settings drafts and persistent state use deep copies of mutable selections. Unsupported values and settings for a previously selected agent remain saved. The separate launch-settings form was not added.

## ACP capabilities and application

`profile-settings.js` reads actual session responses and accepted updates. It supports select/boolean options, nested groups, and legacy models/modes through their own ACP operations. Future unsupported types stay agent-owned. No universal model catalog, reasoning scale, access semantics or Plan toggle is invented. A plan/collaboration control appears when the agent reports it, using its names and values.

New profiles start with **Select Agent** and do not start an agent or show editable session defaults when an agent is selected. **Authorize…** opens an isolated ACP connection and displays the advertised login methods on the profile page. Protocol login supports URL/form elicitation; terminal login launches the advertised command with the profile's environment in an embedded process console. A durable profile home is reserved before login and retained across retries. No conversation, prompt, tools, or MCP servers are created for sign-in. After login, an empty `session/new` supplies the authorized defaults and catalog; the sign-in window closes after those options are available.

The per-profile catalog is persisted in `catalogs/<profile-id-digest>.json`, scoped by provider, home, launch environment and authorization revision. Reopening Settings uses that catalog without starting ACP. **Refresh session options** explicitly resolves the installed provider and creates an independent discovery session. Successful real chat creation refreshes provider defaults before applying profile overrides. Accepted updates refresh catalogs while retaining those independent defaults; live chat selections do not become project or profile defaults. Revocation makes the cached catalog unusable.

Chat controls seed their native presentation from the authorized profile catalog before ACP is ready, then replace it with the live session's state. **Refresh models…** is an action in the native model menu. It first offers same-value model/config setters to the existing provider, then discovers fresh options on a separate connection and publishes catalogs with the active chat's selections retained. Codex's legacy setter performs `model/list` refresh internally, including the exact current effort. The active provider still validates every subsequent selection: ACP has no universal force-refresh request, and independent discovery does not guarantee that every provider can use a newly listed model in an existing session. Refresh is refused during an active task; discovery processes are cleaned up independently of the live chat.

Session defaults appear immediately after the compact provider selector and before Authorization. Refresh agents and the information button share the provider row. A choice such as **Low (Agent default)** inherits the provider's default; choosing **Low** explicitly stores a profile override even when it equals the current provider default. Returning to Agent default removes that override.

New sessions apply model choices first, then revalidate other choices against each accepted response. Application uses `session/set_config_option`, `session/set_model` or `session/set_mode` as advertised. Unavailable/rejected values produce notices without being deleted or claimed as effective. Editing a preset never applies it to an already running/restored session or silently replaces that session.

Effective values stay in the existing virtual-session snapshots and change with accepted setters and notifications, including legacy current-mode updates. The AI Assistant preferences adapter supplies all accepted ACP values explicitly during initial registration, including provider defaults when the profile has no overrides. Saved last-chat preferences cannot replace those values. Subsequent native user selections remain runtime session changes.

## Account discovery and security

`AgentAccountIdentityProvider` isolates vendor discovery. The Codex provider recognizes registered Codex ACP profiles and reads bounded metadata from the exact effective `CODEX_HOME` used by that launch. Environment inheritance and profile-home overrides follow the launcher. It does not fall back to the global Codex home or use the JetBrains account.

Only sanitized email/account identifiers and discovery status leave the provider. Raw authentication tokens are not persisted in settings, capability catalogs or identity snapshots. Parsing exceptions are neither logged nor shown. API-key environments, missing/malformed/inaccessible metadata and conflicting IDs produce neutral identity data. Expired identity metadata is marked expired. Local JWT claims never establish verified login. No account-display network request is made.

The session identity snapshot is captured after creation from its actual launch specification. Editing the saved profile therefore cannot make an old chat display the newly configured account. Refresh account info rereads local profile metadata; running sessions retain their startup identity snapshot. Start a new session after changing the signed-in account.

Other agents currently show unknown/unavailable identity: no generic ACP authenticated-email operation is assumed. Duplicate warnings require a verified, provider-scoped account ID; matching email/display names or unsigned claims do not qualify. The Codex fallback alone cannot trigger a verified duplicate warning. The detector and UI support future providers with reliable identity evidence.

## Instructions

The initially collapsed **Advanced** group contains additional instructions: None, Text or Markdown file. They are frozen when a new backend segment is created and included as additional user context with its first prompt, without creating a separate provider turn or changing the task title. They are not system instructions, a skill, or an assertion of Air's hidden pre-prompt behavior. They stay out of the public handoff history. Restored sessions do not reread or reinject them.

For Codex, the editor link opens the effective non-empty `AGENTS.override.md`, or `AGENTS.md`, in this profile's `CODEX_HOME`. Clicking it creates a minimal `AGENTS.md` only if absent; existing rules are not overwritten. Project guidance remains independent. Selecting these native home rules as the additional Markdown source does not also inject the same file. Additional text/files are limited to 64 KiB; unavailable files give a clear new-session error.

## Components and compatibility

Modified: `AgentProfiles`, `GenericAutoRegistration`, `SwitcherConfigurable`, `ProfileAgentPreferences`, `AiProfileToggleProvider`, plugin metadata, router and virtual-session resources. Added: `ProfileConfiguration`, `ProfileAccounts`, `ProfilePresetPanel`, `profile-settings.js`, the separate runtime `codex-account.js` provider, `ProfilePresetsChecks` and `test-profile-settings.js`. Runtime dependencies are published before the router entry point.

Plugin ID `dev.boyko.ai-agent-project-switcher`, registration title **AI Multi**, and ACP ID `acp.ai-multi` are unchanged. The shared router applies presets independently of the host. There is no Air dependency or Air internal preset-store synchronization. Native UI was tested in AI Assistant; Air retains the same ACP discovery/transport and needs manual UI confirmation.

## Checks and manual verification

`check buildPlugin --offline` passed against installed IDEA 2026.2.3 and AI Assistant 262.10968.223. Router, virtual-session, conversation-storage, credential deletion and registration-cleanup regressions passed. New tests cover legacy XML migration/roundtrip and stable IDs; deep-copy drafts; different/absent option sets; dependent model/effort choices; booleans, grouped options and legacy setters; rejected/unsupported saved values; dynamic effective values; edits versus running sessions; restore without preset replay; environment inheritance and isolated homes; two Codex accounts; missing/malformed/expired/conflicting metadata; token exclusion; verified duplicate IDs; Markdown snapshots and native-rule deduplication.

Isolated runIde with mock ACPs and synthetic accounts verified native cached controls, checkbox/bold/tooltip behavior, draft Apply/Reset, settings/instructions across profile switches, first-prompt delivery, the account section, independent High/Low presets for two profiles using the same Codex agent ID, the actual Work selector tooltip, and unchanged project default after opening that Work chat. The primary IDE and chats were not restarted or interrupted, and no production-provider prompt was sent. The workspace's original project assignment was verified and the sandbox's temporary assignment was restored before closing. Evidence is in `build/presets31`; probes are not packaged.

The XML test uses Java 25 for the installed IDEA interfaces. On this machine supply `-Porg.gradle.java.installations.paths="C:/Program Files/JetBrains/IntelliJ IDEA 2025.3.1.1/jbr"` with the existing idePath/aiAssistantPath properties. Plugin sources retain the existing Java 21 target.

After activating the build: confirm real Codex account hints, select a provider, configure the actual agent-reported model/access/plan/effort values, then open new chats through AI Assistant and Air. Check runtime selections and confirm that preset edits leave the running chat and project default unchanged. Real provider catalogs and verified account identity are not claimed by the mock checks.

## Development version 0.2.0-dev.37

The provider information icon is a small gray outline with light/dark variants and a compact multiline tooltip. Session defaults has a small top gap and no Refresh options link. Advanced follows the Default Profile checkbox inside the same scrolling form. Explicit UI DSL gaps separate nested panels from Authorization.

Defaults discovery still opens an independent ACP session on its first request. While loading, the entire profile form is replaced by a spinner and initialization text. Successful untouched discovery results are cached in memory for five minutes, keyed by project, profile ID and launch/environment fingerprint; credentials revision changes invalidate that key. Live-chat selections and profile overrides never enter this defaults cache. Reopening a cached profile renders immediately and reapplies its own draft selections. A changed installed provider launch may remain cached until the five-minute expiry; failures are not cached.

The legacy mode toolbar control is suppressed for AI Multi when its chat's config options include category `mode`. Collaboration mode remains separate, and legacy modes remain available for providers without that category. Other agents retain native behavior. Installed Air preset-editor bytecode uses its existing preferences lane, metadata cache and reactive catalog rather than this plugin's separate discovery session; inspection evidence is under `build/ui37`.

## Deployment

Dev.34 moves the Settings entry directly under **Tools**, aligns Refresh agents with the installation hint, moves account details to a popup, includes email in native hover/dropdown descriptions, and collapses instructions under Advanced. The missing-options hint explicitly refers to the selected profile's cache. On 2026-10-13 the user requested an immediate refresh: empty ACP sessions retrieved real catalogs for Personal and Work (Mode, Collaboration mode, Model, Reasoning effort, Fast mode) and Grok (Model, Reasoning Effort). Only capability caches were updated; no model prompt, active-chat change or project-default change was made. The diagnostic helper is under `build/refresh-options34.js` and is not packaged.

`check buildPlugin --offline` passed. Isolated native IDEA checks verified cached defaults, initially hidden instructions, Account info popup and asynchronous sizing, and account email in the native hover description and both dropdown profile descriptions. Synthetic accounts and mock backends were used for UI checks; artifacts are under `build/ui34`. The sandbox was closed without restarting the primary IDE.

Dev.34 was queued for native installation on 2026-10-13. Backup: `deployment-backups/20261013-005248-0.2.0-dev.34/AIMulti-before-update.zip`. The native installer verified preservation of unrelated pending updates; built and staged archive hashes match the deployment record. Activation awaits the user's next IDEA restart.

Dev.33 adds **Set as Default for Current Project** to the settings profile list's right-click menu. It selects the clicked row, updates the default checkbox and bold marker in the editor draft, and commits only through Apply. The existing default's action is disabled; right-clicking empty space does not select the nearest row. Cancel/Reset and active-chat behavior remain unchanged. `check buildPlugin --offline` is the required verification for this incremental UI change; a native right-click smoke check remains after activation.

Dev.33 passed `check buildPlugin --offline` and was queued on 2026-10-13, replacing only the verified pending dev.32 installation. Unrelated queued updates were preserved and built/staged SHA-256 checksums match. Backup: `deployment-backups/20261013-000401-0.2.0-dev.33/AIMulti-before-update.zip`. The primary IDE was not restarted.

The final dev.32 archive was queued for native installation on 2026-10-12, replacing only the verified pending dev.31 installation. The installer verified that unrelated pending updates were preserved. Backup: `deployment-backups/20261012-231423-0.2.0-dev.32/AIMulti-before-update.zip`. The checked archive, staged archive and deployment-record SHA-256 match. The primary project's persisted default was verified unchanged. Activation awaits the user's next IDEA restart; no primary restart was performed.


## Development version 0.2.0-dev.72

A profile with no saved agent defaults starts new sessions using the agent's ordinary behavior. Session creation and profile handoff do not bootstrap a defaults refresh or open Settings merely because defaults are absent. Explicit profile selections still apply; previously saved defaults remain frozen and restore does not reapply presets.

New Chat Defaults only displays cached option rows after defaults have been captured in the profile draft or committed profile. Metadata left behind by a failed or cancelled refresh cannot masquerade as saved defaults. Explicit saved selections remain visible even if metadata is unavailable. Refresh discovers defaults in an isolated session; Apply commits the resulting draft. Resolving the installed agent deep-copies mutable profile selections so discovery cannot mutate the expected original and falsely fail its commit check.

Regression verification covers native defaults without setter/bootstrap requests, explicit choices, saved defaults, exact agent errors, restoration without setters, and unchanged project assignment.

## Development version 0.2.0-dev.73

Defaults and their visibility are evaluated separately for AI Assistant and Air. A client with no saved defaults shows a Refresh instruction instead of option dropdowns. The native AI Assistant preferences bridge now protects only explicitly configured profile options in newly created ACP segments; other options use the host's ordinary preference policy, including remembered selections. Restored segments and legacy records preserve their accepted session state. Router records carry the configured option IDs for this purpose without adding ACP protocol fields.

Remove defaults clears both captured defaults and explicit selections for the selected client in the Settings draft. Apply commits the removal; Cancel discards it. Legacy shared settings are split first so the other client's settings survive. Help icons beside Refresh and Remove explain discovery, saving, automatic chat preferences and application to every new chat or profile switch. Current chat settings remain unchanged.
