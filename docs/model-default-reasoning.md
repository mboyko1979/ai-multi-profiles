# Model default reasoning — settings refresh

Verified with installed `@agentclientprotocol/codex-acp` 2.2.2, AI Assistant 262.10968.223 and Air 262.8665.497.

## Source of truth

In the installed Codex bundle, `createSessionConfigOptions` passes `currentModel.defaultReasoningEffort` to `createReasoningEffortConfigOption` when the client opted into `recommendedValue`. That function independently exposes the session's effort as `currentValue` and the supported model recommendation as `_meta.jetbrains.air.recommendedValue`.

The supplied upstream documentation URL returned 404 during this investigation. The installed package version, actual source functions and live results were checked directly; the example values in the request were not used as defaults.

Previously, refresh saved session `currentValue` as an inherited profile default. That cannot establish a model's original reasoning recommendation. The model setter can retain the prior effort, so its accepted current value cannot establish a default either.

## Collection and display

The existing profile setup connection accepts the native client's initialize parameters for an isolated refresh. Native capability factories are called separately for AI Assistant and Air, including their current runtime flags. For Codex metadata collection only, the settings client adds the `recommendedValue` feature to the existing extension object and preserves all other native capabilities. Ordinary chat initialize is unchanged.

A clean temporary profile home contains copied account authorization only. Original config, sessions and model cache are excluded. The installed launch directory is retained. The agent must populate a fresh model catalog. No prompts, tools or production sessions are used.

Refresh collects the initial model's recommendation and then each advertised model's options in that temporary session. It validates the model acknowledgment and that each recommendation belongs to the option's advertised values. `currentValue`, `modelDefault` and `defaultSource` remain separate. There is no fallback from session values, config.toml, saved profile preferences, model names, other models or a hardcoded reasoning level. A missing/invalid recommendation is unknown.

`AI Assistant defaults` and `Air defaults` tabs have separate metadata caches and per-model maps. Changing the selected model changes its displayed reasoning recommendation. The existing explicitly selected Profile Default is preserved. No new persisted profile-setting fields were introduced; the existing Profile Default is shared by the two metadata views. Refresh updates both views before committing results. The current existing profile preset format receives an inherited snapshot for the selected model; runtime application code is unchanged.

Profile metadata caches include the launch configuration in their keys. Per-client metadata files are separate from catalogs updated by live chats. Model recommendations, model IDs, actual session values and their provenance are persisted separately. Existing catalogs without a recommendation cannot be mistaken for an authoritative default.

Diagnostics use only public agent/version/model/option IDs, supported option values, recommendation presence, source and capability opt-in status. Raw provider stderr, environment, authorization and proxy headers are not logged.

## Evidence

- `router/test-model-defaults.js`: High current versus Medium recommendation, initial-model handling, independent model defaults, absent/invalid recommendation, no fallback, repeat stability, model acknowledgment, diagnostics and immutable input.
- `ProfilePresetsChecks.java`: metadata parsing/cache round trip, independent client caches, model map, unchanged explicit profile choices and inherited defaults derived from recommendations.
- `tools/ModelDefaultsProbe.java`: isolated native capability factories and serialization; both-client refresh; native settings tabs, model changes, actual refresh handler and progress; explicit High preserved; unknown; different original config values; repeat/profile independence and failed-refresh preservation.
- `build/defaults61/live-result.json`: real installed Codex ACP in isolated authorized homes for Personal/Work and both native client capability sets. Each selected model recommendation matches the freshly fetched provider catalog. Metadata hashes and Codex catalog client version are recorded without account identity.

On the real accounts, `gpt-6.1-sol` reports Low for Personal and Medium for Work, through both clients. The fresh model objects themselves have different `default_reasoning_level` and different hashes. They are not identical metadata, so the plugin must retain the difference. Identical metadata produces identical recommendations regardless of session effort or profile choice, as verified with controlled fixtures. Why the service assigns those different model metadata values is not established by this change.

Direct endpoint provenance was subsequently established in docs/catalog-origin-2026-10-15.md: repeated HTTP 200 bodies themselves contain the account-scoped difference, and fresh native Codex 0.160.1 caches with no overrides carry the matching server ETags. The internal service policy choosing those values remains unknown.
