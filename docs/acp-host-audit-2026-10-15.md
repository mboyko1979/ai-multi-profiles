# ACP host audit — 2026-10-15

Inspected installed AI Assistant 262.10968.223 and Air 262.8665.497. No primary IDE restart, attachment, session request, or provider prompt was performed. Decompiled evidence and the standalone Air policy probe are in `build/acp-host-audit/`.

## AI Assistant

`AcpSessionLifecycleManager.initializeOrReuseSession` constructs the v1 SDK `ClientInfo` using its default protocol-version parameter. The SDK default is 1. The main chat path uses `com.agentclientprotocol.client.Client`; bundled v2 schema classes do not establish v2 support in that path.

`startSessionByCapabilities` checks the stored session ID. If it exists and load is supported, it calls `loadSession`; otherwise it calls `resumeSession` if supported; otherwise it creates a new session. This is the normal capability-selection path, not a guarantee about every separate recovery branch or a proof that a particular user's chat restored successfully.

Evidence: `ai-AcpSessionLifecycleManager.txt`, `ai-ClientInfo.txt`, `ai-Client.txt`, `ai-AcpSessionLifecycleManager$initializeOrReuseSession$2$sessionAction$1.txt`.

## Air

`AcpProtocolOffer.Companion.capture` selects V2 only when registry key `air.acp.v2.enabled` is true. The installed plugin declares this key with `defaultValue="false"`, `restartRequired="false"`. No persisted override for this key was found in the primary IDE options. This establishes the default and disk configuration; the primary process's current in-memory registry value was not queried.

`AcpInitializedClientFactory` always supplies a V1ClientConfig to ClientNegotiator and supplies V2ClientConfig only when V2 is offered. Air therefore has a v1 default with optional v2 negotiation, rather than v2-only transport. AI Multi itself negotiates v1, so enabling the host's v2 offer would not convert this connection into v2.

The actual installed `AcpProtocolSessionKt.decideContinuation(storedSessionId, supportsLoadSession, supportsResumeSession, importing, rebuildingStaleHistory)` was invoked in a standalone JVM, without starting an IDE or agent. Results:

- Existing ID, both methods, ordinary cached transcript: ResumeExisting.
- Existing ID, load only: LoadExisting.
- Importing external session with load available: ImportExisting.
- Rebuilding stale transcript with load available: RebuildExisting.
- Existing ID, neither method: Refuse.

`AcpConnectionOperation.openStoredSession` routes resumed continuation to facade resumeSession, and load/import/rebuild to facade loadSession. Separate AcpV2ConnectionOperation and AcpV2ClientSessions implement the v2 surface.

Evidence: `AcpProtocolOffer$Companion.txt`, `AcpInitializedClientFactory.txt`, `AcpProtocolSessionKt.txt`, `AcpConnectionOperation.txt`, `air-continuation-result.txt`, `HostContinuationAudit.java`.

## Protocol status and recommendation

The official specification labels v1 as Latest and v2 as Draft. Its migration guide explicitly says the entire v2 protocol remains draft and recommends version negotiation plus feature flags, retaining v1 support. V2 removes session/load and uses session/resume with optional replayFrom. It also changes initialization capabilities, prompt acceptance/completion, event updates, authentication, and client tool APIs. A version-number change alone is not an implementation.

Sources: https://agentclientprotocol.com/protocol/v2/migration and https://github.com/agentclientprotocol/agent-client-protocol .

Keep the plugin's v1 path while diagnosing historical chat restoration. Any future v2 support should be additive and negotiated per connection. The dev.56 legacy resume-to-load compatibility regression is real and tested, but this audit does not establish it as the cause of the reported AI Assistant history failure: the normal AI Assistant path already prefers load when both methods are advertised.
