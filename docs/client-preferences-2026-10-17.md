# Shared client preferences — dev.78

Contract: [product decisions](decisions.md).

The router resolves new-session settings for both AI Assistant and Air using the same `profile-settings.js` policy. It applies configured profile defaults, fills missing settings from the current client's native store, checks remembered values against the real catalog, and reasserts explicit defaults if a remembered model resets a dependent parameter. Load/resume never reads or reapplies these preferences.

`NativeClientPreferenceStore` separates native storage from that policy. AI Assistant uses `AcpAgentSettingsService`; Air uses `AgentSessionLastUsedStoreImpl`. Both use `acp.ai-multi-preferences-<SHA-256(JSON([profile UUID, bound agent ID]))>`. These are storage keys, not registered/routed agents. Preferences are shared across projects within a client, independently for each profile/agent. Existing common Codex and AI Multi preferences are not migrated or modified by the adapters.

Air's native store supports model, reasoning effort and session mode, rather than an arbitrary ACP option dictionary. Its native write and load normalization deliberately clear `planMode`; Plan is not a remembered preference in that store. The adapter uses Air's own reasoning conversion functions and exposes only genuinely retained fields. Unsupported remembered fields remain absent; profile defaults, including Plan, still use the common ACP application policy for every supported select/boolean option. No additional preference database is introduced. Future clients implement the store interface and client identification without duplicating precedence/application logic.

The IDE and router exchange sanitized option metadata through temporary request/response files. Only the IDE owning the router process ancestry processes its requests, so a second running IDE cannot answer from a different native store. Files are removed after each operation. No history, credentials or opaque metadata enters this exchange.

Air's in-process facade only registers a finite set of vendor methods. AI Multi's own `_ai_multi/*` controls use the admitted native connection's existing pool lease and vendor-request API. This preserves method, session ownership and the original response, without adding agent capabilities or opening another connection. Standard ACP configuration still follows the native facade.

Only an accepted client setter that actually changes the selected value is remembered. Rejected/coerced selections, initial catalogs, unsolicited updates, profile application and the native client's repeated before-prompt setters do not overwrite preferences. The Assistant toolbar announces accepted values without invoking native registration helpers that send additional setters. Existing chats take identity from their committed segment, even if the profile's agent is later changed.

## Verification

- `router/test-client-preferences.js`: real router subprocesses with both native client identities, full/partial/absent defaults, dependent-value precedence, repeated unchanged selections, rejected values with unchanged error data, restore without store operations, profile/agent isolation and cancel followed by a new prompt. Included in `check` and `buildPlugin`.
- `tools/VerifyClientPreferences.ps1`: isolated IDEA with synthetic agents. Exercises actual native stores and Air state serialization/normalization, an actual Assistant chat, cancellation and a new prompt, and actual Air session admission/selection. The steering reproduction is recorded separately and is not an assertion that the upstream bug is fixed.
- Final `check buildPlugin --rerun-tasks --offline --max-workers=1` passed: all 33 tasks executed successfully (`build/preferences78-release-verified.log`). Native verification passed in `build/preferences78-native-release/`, including independent stores, Air serialization/normalization, native session/default selection, ordinary cancellation and both steering reproductions.

## Deployment

Dev.78 was queued through IDEA's native startup installer on 2026-10-17. Installed-plugin backup: `deployment-backups/20261017-003940-0.2.0-dev.78/AIMulti-before-update.zip`. Queue serialization checks passed; existing unrelated update commands were preserved. All six bundled router modules match source/test copies; diagnostic probe classes are absent. Built, staged and recorded SHA-256: `963783B530C1AEDE4D8C10DD84ABD7BA81BBDF37A757DCCEA4BA39DA3150AEAC`.

The primary IDEA was not restarted and its active chats were not interrupted. Activation waits for the user's next normal restart.

## Known upstream issue

The `startedNewTurn` steering completion issue remains separate from defaults. See [reproduction and evidence](steering-completion-2026-10-17.md). This release does not fabricate a prompt response or alter the client's selected ACP method to conceal it.
