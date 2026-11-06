# ACP and native-client transparency review, 2026-10-27

Original review scope: 0.2.3-dev.3, ACP v1, Assistant 262.10968.223 and Air 262.8665.497. The findings below describe that snapshot. The subsequent dev.4 corrections are listed here; historical observations are retained for traceability.

## Corrections in dev.4

- Shared transport now translates cancellation IDs in both directions to the pending request's owning backend, supports both native spellings, cancels timed-out requests, preserves genuine notification semantics and the complete initialization error, including extension fields.
- Unassigned initialization stays in the local setup router without starting providers or synthesizing their capability intersection.
- Both native clients rebind the target's actual initialization/session metadata during an in-chat handoff. Agent notifications remain buffered until native binding is accepted. Rollback restores the source metadata too.
- Assistant delegates pending terms to the bound agent's native terms service and chooser before its update check. Its native update reload preserves accepted choices in an in-flight snapshot, validates them against the updated agent, and clears that snapshot after restoration or chat disposal.
- Air records native usage and invokes its own updater for the selected managed agent before resolving the launch. Explicit in-chat switches use the same path and native provisioning verdict; pending admission keeps the source binding. Composer launch metadata delegates the real agent's native origin/admission. Native installer policy, consent, progress and per-agent coordinator remain authoritative. Custom command overrides are excluded.
- `tools/review-acp-transparency.js` now runs the passing `router/test-protocol-transparency.js` acceptance suite, also registered in Gradle `check`; it no longer asserts that the original defects persist.

Verification artifacts and remaining native limitations are recorded in [verification](verification.md). The original review's Air/update boundaries below apply to dev.3 only.

## Update scope at the dev.3 review baseline

`compat/ProfileAgentUpdates.kt:24–55` uses the committed segment's `agentIdentity.agentId`; it contains no Codex/Grok/vendor allowlist. Any managed registry agent supported by Assistant's native updater follows this path. Distribution type, available version, installation policy, progress and update consent remain native responsibilities.

Important boundaries:

- The new adapter is for Assistant. Air continues using its own native background updater; the adapter is not a shared updater for every host.
- Assistant checks before its first response, once per committed segment. This follows the pinned native lifecycle; it is not a background polling service for old chats.
- Advanced custom command launches are excluded. A locally configured CLI does not acquire a registry update mechanism merely by being selected through a profile.
- A changed agent identity is excluded until explicit recovery. Updating a replacement must never reuse the predecessor's session ID.
- The native tests exercise a non-Codex registry fixture, native NoUpdate, callback routing, reload/resubmission and cleanup. They do not download real Codex, Grok or other distributions. Cross-vendor real-install parity is not established.

Auto-update is a native IDE lifecycle feature, not an ACP method or an agent-pushed ACP update notification.

## Confirmed with executable probes

Run from the repository root:

```powershell
& 'C:/Program Files/nodejs/node.exe' tools/review-acp-transparency.js
```

The current command runs the dev.4 acceptance regressions with synthetic agents in an ephemeral temporary directory, removed afterward. The original audit asserted the dev.3 gaps and retained `results.json` and per-scenario traces in `build/acp-review-2026-10-27`. No account or real provider is used by these transport probes.

### P1: Request cancellation does not translate request IDs in either direction

Locations: `router/cps-router.js:386–394`, `649–652`, `815–819`, `850–853`; `router/virtual-sessions.js:1096`.

The router maps a client's RPC ID to `aiaps:*` and an agent's upstream ID to `aiaps-up:*`. It does not keep/use the corresponding original-ID mapping for `$/cancel_request.params.requestId`. Forwarding that notification unchanged cannot cancel the remapped request. The pinned SDK spelling `$/cancelRequest` has the same problem.

Observed client → agent: actual request ID `aiaps:3`, cancellation ID `client-3`. Observed agent → client: actual request ID `aiaps-up:8`, cancellation ID `native-upstream`. Both ends implement cancellation in the probe; the router breaks the association. This is separate from `session/cancel`, whose ordinary prompt path already has regression coverage.

Impact: cancelled RPCs or nested client requests can continue running/waiting. A shared connection also needs cancellation routed by the pending request's actual owner, not whichever profile is current later. Timeout handling currently removes `pending` without notifying the agent, so it does not solve this gap.

Fix direction: maintain bidirectional pending-ID/owner mappings, translate cancellation references in both directions, clear them on completion/failure, and test profile changes, concurrent chats and late responses. Preserve the native cancellation response and error.

### P1: Session-scoped extension notifications become requests

Locations: `router/virtual-sessions.js:1054–1057`, `1096`, `1180–1182`.

A client notification with a virtual `params.sessionId` enters `VirtualSessions.handle`, then goes through `api.request`. That allocates an RPC ID even though the client supplied none. The probe sends `_audit/notification` without `id`; the backend receives `id: "aiaps:9"`.

Impact: notification/request semantics change; an agent that correctly sends no notification response leaves a synthetic pending request until timeout. Extension methods do not need individual implementations, but their message kind must be preserved.

Fix direction: translate routing fields and write genuine notifications directly to the bound transport. Reserve `api.request` for real requests; retain the intentional request-only checks for actual request methods.

### P1: Backend initialization errors lose their native code and data

Location: `router/cps-router.js:494–500`.

`backend.ready` replaces every `initialize` error with a new generic `Error`. The probe's `{code:-32077, message:"Native initialization refused", data:{retryAfter:12}}` reaches the client as `{code:-32602, message:"ACP initialization failed for profile p"}`.

Impact: native error handling and diagnostic/recovery information are lost at the first protocol boundary, even though errors later in normal requests are forwarded.

Fix direction: retain the agent's complete error when initialization fails. Separate malformed/missing initialization responses from actual provider errors and preserve cleanup without replacing the error.

### P1: An unassigned project starts all agents and synthesizes capabilities

Location: `router/cps-router.js:685–701`.

Without a project assignment or explicit initial profile, initialization starts every configured agent using `Promise.allSettled`, computes a capability intersection, removes metadata and advertises synthetic agent information. The probe configured two agents and observed both initialized; image support from agent `p` disappeared because agent `q` did not support it.

Impact: opening an unassigned composer starts unrelated providers and presents a capability set belonging to no actual bound agent. This conflicts with session-independent launch selection and the foundational capability-transparency contract.

Fix direction: keep the unassigned/setup presentation local, resolve the chosen/default profile before native launch, and negotiate only the bound agent's real capabilities. Do not manufacture an aggregate agent from configured profiles.

## Native integration gaps found by static review

These are distinct from the executable transport findings above. They still require focused native UI reproductions before claiming an observed user-visible failure.

### P1: A live history handoff does not rebind the complete native capability set

Locations: `router/virtual-sessions.js:992–993`; `compat/CurrentChatProfileSwitch.kt:102–103`; `compat/ChatHandoffClient.kt`, `updateSteeringSupport`.

For a history-bearing handoff, the switch response supplies `supportsSteering`; a full `initialize` payload is supplied only for retained prepared drafts. Assistant updates its cached steering flag, while retaining the original initialized native client. The target can have different image/audio/embedded-context, authentication and optional-session capabilities. The native client's initial immutable capability state is not comprehensively rebound by this path.

Impact to verify: after switching agents in an existing chat, attachment controls or optional-method choices can still reflect the source agent. Passing arbitrary payloads through the router does not by itself repair the native client's earlier decisions.

Fix direction: use a supported native client/connection rebind for the target while preserving the logical conversation and consented transfer. Do not advertise a capability union or synthesize success. Test both directions of differing capabilities in both clients, including rollback and restoration.

### P2: The bound agent's native terms check is not delegated like its update check

Locations: `compat/ProfileAgentPreferences.kt:77–101`; `compat/ProfileAgentUpdates.kt:50–54`; `compat/InstalledAgents.kt:34–56`.

Assistant's pinned native `AcpSessionLifecycleManager.ensureTermsOfServiceAcceptedInChat` passes its own `agentId` to `AcpTermsOfService.ensureAcceptedInChat`. For this client that ID is AI Multi's local registration. The plugin adds a bound-agent update call but contains no corresponding delegation to the bound agent's terms service.

This was checked against the installed native class bytecode, not inferred from absence of an ACP method. Terms are native registry metadata, not an ACP message. A profile whose actual installed agent has pending revised terms needs a native reproduction with an update-free version and a synthetic chooser; no assumption is made that every installed provider currently has pending terms.

Fix direction: route the native admission checks as a coherent bound-agent operation, preserving the native chooser, decline behavior, ordering and errors. Avoid vendor-specific exceptions or another preparation state machine.

## Coverage gaps and intentional limits

| Area | Assessment |
| --- | --- |
| Basic prompt, normal cancellation, exact load/resume, tool/permission requests, unknown updates and extension request forwarding | Existing router regressions cover these surfaces. No missing per-method switch case is implied: generic forwarding is intentional. |
| Slash commands, agent plans, usage and newly added agent update variants | Generally pass through as agent notifications; a separate AI Multi implementation is not needed when the native client supports them. This review did not establish full native UI coverage of every variant. |
| Logout and future unscoped methods | Generic bound-backend routing exists. Do not label logout unsupported merely because it has no dedicated branch. Multi-chat ambiguity and profile-switch cases need specific tests. |
| Air auto-update | Native policy filters candidates by recent usage and accepted consent. The composer adapter overrides the requested ID but delegates the rest of AI Multi's local catalog entry. Correct recording of registry-agent usage/origin and a positive install through new/legacy profile routes remain unverified; no confirmed Air installation failure is claimed. |
| Native API compatibility | New update bindings are pinned host internals. dev.3 has native 262 checks, but no fresh Plugin Verifier or 263 runtime pass. |
| Steering completion | Already reproduced directly and through Multi in native probes. This is a host/agent lifecycle limitation, not evidence that the router must manufacture completion. |
| ACP v2 | Intentionally unsupported by the agreed project contract. Continue negotiating v1 until a separate v2 implementation is authorized and verified. |
| Non-stdio remote agents | Launch resolution currently requires a local executable/configuration. Streamable HTTP is documented as a draft transport, not a missing mandatory v1 stdio feature. |

## Recommended order

1. Preserve transport semantics: cancellation ID/owner routing, true notifications and initialization errors. Make fixes shared by all agents and clients.
2. Remove unassigned-project fanout and capability synthesis through the native launch boundary.
3. Verify and repair complete native rebind/admission behavior: capabilities, terms and update eligibility/usage, across Assistant and Air.
4. Add direct-agent versus routed-agent contract checks with several synthetic vendor IDs, differing capabilities and real native installer fixtures. Do not make personal account downloads part of routine CI.

## Protocol sources

- [ACP v1 cancellation](https://agentclientprotocol.com/protocol/v1/cancellation): `$/cancel_request` references the ID of the original request.
- [ACP v1 extensibility](https://agentclientprotocol.com/protocol/v1/extensibility): extension requests and notifications retain their respective JSON-RPC semantics.
- [ACP v1 schema](https://agentclientprotocol.com/protocol/v1/schema): current request/capability definitions, including optional authentication, terminal and session surfaces.
- [ACP v1 transports](https://agentclientprotocol.com/protocol/v1/transports): stdio and draft transport status.

Native lifecycle conclusions use the locally installed host classes and the repository's adapters, not the ACP spec as a substitute for native implementation evidence.
