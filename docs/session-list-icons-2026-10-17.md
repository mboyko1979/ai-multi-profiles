# AI Multi session-row icon — dev.94

Air's native preparation uses the bound agent's catalog ID and launch target so its model metadata remains native. Its session row also uses that agent ID when resolving icons. The existing icon service could recognize AI Multi registration IDs, but its icon method receives no session identity, so a routed Codex row appeared as an ordinary Codex row.

The adapter is restricted to the `air.threads` tool window and its known Air 262 `ThreadTreeCellRendererWithSeparators`. It observes that tool window's lazily added components without polling. During a native row render, the existing node resolver supplies its `SessionSummary`. Only rows whose actual `agentSessionId` belongs to AI Multi's virtual-session namespace supply an AI Multi icon-resolution context. The context is removed in `finally`; direct-agent rows retain native icon resolution.

The original renderer still creates the actual component and retains its status/progress/automation badges, separators, row actions, accessibility and other presentation. No persisted sessions, descriptors, project defaults, route IDs, native model controls or caches are changed. Unsupported renderer bindings retain native rendering and log once. The native renderer/node-resolver binding is pinned to the installed Air 262 implementation.

At the user's request, verification is limited to the standard build/shared checks, with output in `build/icon94-build.log`. No isolated UI sessions were launched for this change. The actual icon in the user's existing session list requires manual confirmation after activation.

`check buildPlugin --offline` passed. Dev.94 was queued for the next primary IDEA restart, superseding the verified dev.93 queue entry. Backup: `deployment-backups/20261017-224007-0.2.0-dev.94/AIMulti-before-update.zip`. Unrelated queued updates were preserved, and the primary IDE was not restarted.
