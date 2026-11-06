# Product decisions

## Profiles and native clients

- The plugin and Settings entry are **AI Multi Profiles**; the ACP agent is **AI Multi**. Plugin ID is `dev.boyko.ai-multi-profiles`; ACP registration is `acp.ai-multi`. Preserve persistent storage and protocol identities.
- **Default Profile** belongs to the project. Choosing a profile in a composer or existing chat never changes it.
- Initialize a new composer's local selection from the project default. Subsequent choices need no live session, discovery or conversation switch. Retain the native composer, unsent draft and real prepared handles; returning to a prepared profile reuses its session. Retire unused preparations on admission or abandonment.
- AI Multi owns profile storage, account/environment routing and configured defaults/filters. The native client owns controls, caches, preparation and task lifecycle. Shared policy applies to Assistant, Air and future clients; adapters bind host APIs. See [architecture](native-client-architecture.md).
- No plugin metadata cache, profile-UUID wrapper around native caches, duplicated native controls or parallel preparation state machine. Agent-owned native catalog identity is separate from profile-scoped preferences. Standard agents and unrelated chats must remain unaffected.
- Air's legacy `acp.ai-multi` presentation alias uses Air's registered ACP catalog contributor, including the native cold declaration API where available. Do not construct a second contributor or rely on Kotlin default-constructor masks across host updates (262.8665.512 startup regression, 2026-10-27).
- Scoped Air composer runtime/backend construction must preserve all running-host defaults when binding the profile launch resolver. Verify the actual profile preparation/switch path across pinned hosts; startup/catalog checks alone do not cover it (262.8665.512 constructor regression, 2026-10-28).

## Protocol, restoration and consent

- Negotiate ACP v1; v2 is not supported. Forward real agent capabilities, client-selected methods, unknown fields, notifications and errors. Translate only routing IDs and configured settings.
- Restore the last committed profile/agent segment and its accepted configuration, using the client's exact load/resume method. Do not reapply today's defaults or invent replay/capabilities.
- Changed agent identity requires explicit recovery consent. Never send a predecessor's session ID to its replacement. History transfer is explicit and bounded; hidden reasoning and raw tool payloads are excluded. Native clients retain the transcript.
- Profile deletion allows local read-only history or consented selection of another profile. Local viewing does not start a provider.

## Defaults and presentation

- Defaults are independent for Assistant and Air. Explicit profile defaults precede that client's native remembered preferences; missing values preserve real agent values. Remembered preferences are scoped to profile UUID and bound agent identity, independently per client. Only accepted explicit client choices are remembered.
- Load Agent Defaults is explicit. Refresh preserves existing model order, enabled state and manual values, adds new models, and flags unavailable settings. Enabled available models need valid values before Apply. Missing recommendations are not invented or borrowed from another model.
- Filters affect future choices without replacing an accepted current model. Restore preserves accepted state. Unknown agent metadata/groups remain intact.
- Reasserting the current model (including native prompt preparation) preserves manually selected settings such as Plan mode. Apply per-model defaults on an actual model change, not on a repeated setter (2026-10-27).
- Registry agent update checks belong to the native client and target the committed bound agent in both Assistant and Air, including through AI Multi. Keep native update policy, admission/terms, progress/consent UI and errors; refresh launch routes after installation. Preserve accepted selections across a native pre-prompt update. Advanced custom launches and replacement identities are excluded. Air records bound usage and joins its native updater/coordinator before resolving a managed launch, including an explicit in-chat switch; its background updater remains native (2026-10-27).
- Native Air selectors place the AI Multi profile beside the agent and before model/settings controls, only for AI Multi. Account attribution comes from the bound profile; AI Multi-owned session rows retain AI Multi attribution. Local identity metadata is not proof of a verified login.
- Air account captions use the current lane's agent identity, then the selected profile's local identity hint. A generic IDE/JCP/BYOK source account must never be attributed to an ACP profile; absent identity shows `Account unknown`. Clear native text segments when overriding the caption so stale account text cannot win (2026-10-28).

## Compatibility and publication

The distribution uses the beta channel with minimum build 262.10968 and no upper build bound. This does not guarantee compatibility with future hosts. Pinned native adapters require renewed checks after host updates.

Use behavior-preserving public API replacements. Do not conceal calls through reflection or warning suppression, use TestOnly APIs as production substitutes, or remove features solely to obtain Marketplace approval. Remaining private integrations require supported replacements or explicit JetBrains guidance; binary compatibility and lack of a known alternative are not permission. See [API assessment](public-api-recheck-2026-10-18.md).

The plugin is source-available under [EULA.md](../EULA.md), with free personal/commercial use and no open-source license grant.

## Current implementation status

Air profile chats must persist AI Multi as the transport owner of a virtual session ID after native admission, while the composer keeps the bound agent's native catalog and controls. Existing wrong transport bindings and native namespace associations are repaired after proving local conversation ownership. On a failed native restore, bind the saved conversation before ACP initialization and reuse the existing native recovery adapter; retain the same session/profile/history, exact load/resume method and changed-agent consent. Do not interrupt a live owner or synthesize readiness (2026-11-03, dev.15).

Development 0.2.3-dev.15 repairs Air?s persisted transport/namespace and restores the last committed profile before ACP initialization, including when Air launches outside the project directory. Shared checks, combined native Assistant/Air 497 regression and targeted native Air 521 cold restoration/continuation passed; dev.15 is queued for the next normal IDE restart. See [verification](verification.md) for the exact scope and limitations.

Earlier documented baseline: development 0.2.3-dev.10 binds an Air composer profile when `pick` no longer accepts the optional Long removed in Air 262.8665.521. A removed nullable tail still matches the shorter host method, and a signature miss names the candidates instead of throwing `NoSuchElementException` on the EDT. It retains dev.9's scoped composer runtime/backend construction on Air 262.8665.512. It retains dev.4's bound-agent native update/admission integration for both clients, manual Plan preservation, shared ACP transport corrections and native capability rebinding on a profile handoff. See the [review corrections](acp-transparency-review-2026-10-27.md) and [verification and limitations](verification.md) for exact coverage. The previous dev.2 verifier baseline reported 373 Internal API findings across 183 distinct elements; dev.10 has not been reverified with Plugin Verifier and publication remains subject to JetBrains review. A native steering-completion gap remains reproducible directly and through AI Multi.
