# Build and verification

## Reproducible checks

Use JDK 21 for the Gradle/Kotlin toolchain and the tested IDE's Java 25 runtime for platform-dependent Java compilation/checks. Node.js is required for router regressions.

`build-on-windows.ps1 -JavaHome <JDK-21> -IdePath <IDEA>` runs `check buildPlugin`. Optional `-AiAssistantPath` and `-AirPluginPath` select installed dependencies; otherwise the build resolves pinned Assistant 262.10968.223 and Air 262.8665.497. Configure Gradle Java toolchain discovery for both JDKs. Use `--offline` only after dependencies are cached.

Run `tools/VerifyClientPreferences.ps1` with explicit IDE, Assistant, Air and Java paths for native checks. Keep both clients enabled for a shared/Air change; `-AirVisible -FoldedAgent` adds visible-control and native folded-agent coverage. Use synthetic providers and isolated homes, settings, registrations, histories and projects. Never copy personal authentication or conversation stores into a sandbox.

Review package/router source parity and deployment queue preservation where relevant. A documentation-only edit requires link/privacy/diff checks, not a new plugin build or installation.

## Last checked artifact

Development **0.2.3-dev.2**, SHA-256 `F2923116E1B58FA9FB49374662DAE196EEA10C47D479FC761897893252B55B33`:

- `check buildPlugin --offline --max-workers=2` passed, including 18 custom regression suites. The conventional JUnit task has no source tests.
- Combined native Assistant/Air checks passed with controlled agents on the 262 hosts: native stores, profile/agent/client isolation, class identity and wrong-owner rejection, real ACP v1 initialization metadata, drafts, native visible model/reasoning controls, prepared-handle reuse, first prompt, cancellation and unchanged project default.
- A public editor-document candidate returned a different Document instance from the native input model and was rejected; no production substitution remains.
- Plugin Verifier 1.410 ran without suppression/excluded packages and with both declared host dependencies resolved.

| IDE | Native dependencies | Binary result | Internal findings / distinct elements | Experimental | Deprecated + removal |
| --- | --- | --- | --- | ---: | --- |
| IU-262.10968.63 | Assistant 262.10968.223, Air 262.8665.497 | Compatible | 373 / 183 | 19 | 14 + 1 |
| IU-263.7031.23 | Assistant/Air 263.7031.23 | Compatible | 373 / 183 | 8 | 15 + 1 |

Eleven Internal findings removed and zero added compared with uploaded 0.2.2 including its ignored findings. An intermediate report missing Air dependencies is incomplete and is not counted as a passing full check. See [replacement assessment and exact inventory](public-api-recheck-2026-10-18.md).

## Limits and review checklist

- Compatible is a binary result, not Marketplace approval of Internal API usage. Existing reflective/private host adapters are not exhaustively represented in the bytecode-visible count.
- Native runtime coverage uses pinned 262 hosts and controlled agents. Full EAP UI, remote frontend/backend, every real provider and real-account restoration are not established by these checks.
- A `startedNewTurn` completion gap is reproducible directly and through the router. Native task state can remain active; this is not fixed. Do not synthesize completion from text or timers.
- Native attachment caches and host presentation/lifecycle internals can differ across clients and versions. Do not infer UI parity from protocol tests alone.
- Resource-lifecycle, bounded communication queues, timed-out/late RPC cleanup, Windows descendant containment, setup cleanup, abandoned preparations, native synthetic-cache eviction, canonical profile-home isolation and durable lease maintenance need explicit regression/soak coverage. Earlier point-in-time findings are not a claim that every defect persists unchanged in this artifact.
- Profile environment overrides are local configuration, not a password vault. Providers using system credential stores/fixed paths may need specific isolation support. Local account metadata is not verified login.
- Windows symlink-path checks could not be fully exercised on the verification host. Keep aliases/junctions and manually shared authentication homes in the isolation review scope.

See the [native adapter review checklist](native-client-audit-2026-10-17.md) and [protocol contract](proxy-transparency.md). No test result promises future-host compatibility or Marketplace approval.
