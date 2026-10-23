# Product decisions

## Profiles and native clients

- The plugin and Settings entry are **AI Multi Profiles**; the ACP agent is **AI Multi**. Plugin ID is `dev.boyko.ai-multi-profiles`; ACP registration is `acp.ai-multi`. Preserve persistent storage and protocol identities.
- **Default Profile** belongs to the project. Choosing a profile in a composer or existing chat never changes it.
- Initialize a new composer's local selection from the project default. Subsequent choices need no live session, discovery or conversation switch. Retain the native composer, unsent draft and real prepared handles; returning to a prepared profile reuses its session. Retire unused preparations on admission or abandonment.
- AI Multi owns profile storage, account/environment routing and configured defaults/filters. The native client owns controls, caches, preparation and task lifecycle. Shared policy applies to Assistant, Air and future clients; adapters bind host APIs. See [architecture](native-client-architecture.md).
- No plugin metadata cache, profile-UUID wrapper around native caches, duplicated native controls or parallel preparation state machine. Agent-owned native catalog identity is separate from profile-scoped preferences. Standard agents and unrelated chats must remain unaffected.

## Protocol, restoration and consent

- Negotiate ACP v1; v2 is not supported. Forward real agent capabilities, client-selected methods, unknown fields, notifications and errors. Translate only routing IDs and configured settings.
- Restore the last committed profile/agent segment and its accepted configuration, using the client's exact load/resume method. Do not reapply today's defaults or invent replay/capabilities.
- Changed agent identity requires explicit recovery consent. Never send a predecessor's session ID to its replacement. History transfer is explicit and bounded; hidden reasoning and raw tool payloads are excluded. Native clients retain the transcript.
- Profile deletion allows local read-only history or consented selection of another profile. Local viewing does not start a provider.

## Defaults and presentation

- Defaults are independent for Assistant and Air. Explicit profile defaults precede that client's native remembered preferences; missing values preserve real agent values. Remembered preferences are scoped to profile UUID and bound agent identity, independently per client. Only accepted explicit client choices are remembered.
- Load Agent Defaults is explicit. Refresh preserves existing model order, enabled state and manual values, adds new models, and flags unavailable settings. Enabled available models need valid values before Apply. Missing recommendations are not invented or borrowed from another model.
- Filters affect future choices without replacing an accepted current model. Restore preserves accepted state. Unknown agent metadata/groups remain intact.
- Native Air selectors place the AI Multi profile beside the agent and before model/settings controls, only for AI Multi. Account attribution comes from the bound profile; AI Multi-owned session rows retain AI Multi attribution. Local identity metadata is not proof of a verified login.

## Compatibility and publication

The distribution uses the beta channel with minimum build 262.10968 and no upper build bound. This does not guarantee compatibility with future hosts. Pinned native adapters require renewed checks after host updates.

Use behavior-preserving public API replacements. Do not conceal calls through reflection or warning suppression, use TestOnly APIs as production substitutes, or remove features solely to obtain Marketplace approval. Remaining private integrations require supported replacements or explicit JetBrains guidance; binary compatibility and lack of a known alternative are not permission. See [API assessment](public-api-recheck-2026-10-18.md).

The plugin is source-available under [EULA.md](../EULA.md), with free personal/commercial use and no open-source license grant.

## Current implementation status

Development 0.2.3-dev.2 restores native Air dynamic model/settings actions and replaces internal disabled-state queries and plugin/module lookup with public APIs. Combined Assistant/Air checks pass on the pinned 262 hosts. Both full verifier targets report 373 Internal API findings across 183 distinct elements; publication remains subject to JetBrains review. A native steering-completion gap remains reproducible directly and through AI Multi. See [verification and limitations](verification.md).
