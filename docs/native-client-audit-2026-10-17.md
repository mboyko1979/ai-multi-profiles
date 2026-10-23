# Native client integration review checklist

This checklist records ongoing adapter risks and required evidence under the [shared architecture](native-client-architecture.md). It does not claim that all historical implementation concerns remain defects in the current build.

## Shared boundaries

`ProfileLaunch.kt` resolves profile/agent launch metadata independently of a live session. `router/profile-settings.js` owns defaults, remembered-preference precedence and filtering; `ClientPreferences.kt` binds separate native client stores. Routing/virtual-session modules preserve real negotiation and the last committed segment.

Keep profile UUID, bound agent ID, native chat ID, native catalog-cache identity, remembered-preference identity and ACP registration ID distinct. Catalogs stay agent-owned; preferences remain profile/agent/client-scoped. Filtering preserves unknown fields/groups and accepted excluded current models. Presentation must not become catalog discovery or another default policy engine.

## Pinned host adapters

| Area | Required review |
| --- | --- |
| Composer launch | Choice must remain local and available before readiness. Bind routing on the AI Multi composer before native preparation; do not replace application-wide factories or add readiness polling/state machines. |
| Prepared drafts | Reuse actual native handles on switch-back, preserve draft/composer/default, and retire unused preparations after admission or abandonment. |
| Assistant startup | Authentication/before-prompt hooks must be idempotent and must not acquire extra sessions merely for rendering. |
| Native controls | Toolbar masking/decorators and synthetic presentation identities must delegate unrelated agents/chats, preserve native ownership/order, and release subscriptions/cache entries. Prefer supported scoped APIs where available. |
| Continuation and recovery | Private task/mutex/state bindings must retain real native preparation, tools, consent, cancellation and completion. Changing cached capability flags alone does not prove equivalence. |
| Loading and attribution | Scope loading overlays and response/session decorators to the operation/chat; preserve accessibility, native layout, status badges and unrelated chats. |
| Discovery and authentication | Separate controlled discovery/setup from live chat state. Clean up temporary authorization, pending requests and spawned descendants on success, error, cancellation and timeout. |

## Regression contract

Compare direct native agent selection with the same agent through a profile without explicit overrides. Cover cold/warm caches, first routing, partial/full defaults, accepted manual choices, filters/new models, account isolation, prepared-session reuse, native prompts/tools/cancel/completion, unchanged drafts/project defaults and exact restoration/consented transfer. Include standard agents and unrelated chats, and run both Assistant and Air for shared or Air lifecycle changes.

The last combined native suite passes on pinned 262 hosts with controlled providers. That does not establish every cold/warm-cache/provider scenario or EAP UI behavior. The native steering-completion gap remains reproducible directly and through the router. See [verification](verification.md) and [remaining Internal API requirements](public-api-recheck-2026-10-18.md).
