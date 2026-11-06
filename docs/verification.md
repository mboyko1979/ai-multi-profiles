# Build and verification

## Reproducible checks

Use JDK 21 for the Gradle/Kotlin toolchain and the tested IDE's Java 25 runtime for platform-dependent Java compilation/checks. Node.js is required for router regressions.

`build-on-windows.ps1 -JavaHome <JDK-21> -IdePath <IDEA>` runs `check buildPlugin`. Optional `-AiAssistantPath` and `-AirPluginPath` select installed dependencies; otherwise the build resolves pinned Assistant 262.10968.223 and Air 262.8665.497. Configure Gradle Java toolchain discovery for both JDKs. Use `--offline` only after dependencies are cached.

Run `tools/VerifyClientPreferences.ps1` with explicit IDE, Assistant, Air and Java paths for native checks. Keep both clients enabled for a shared/Air change; `-AirVisible -FoldedAgent` adds visible-control and native folded-agent coverage. Use synthetic providers and isolated homes, settings, registrations, histories and projects. Never copy personal authentication or conversation stores into a sandbox.

The native ACP registry stores are shared across `runIde` homes by default. The verification agent must set both hosts' TestOnly binary/data path overrides **before** creating installer fixtures. Those APIs are test infrastructure only. The native updater probe supplies loopback ZIP distributions and controlled native consent; executables are inert and never launched. The folded-route probe also controls the native registry getter/installed overlay and restores the original flow afterward, so it does not resolve real vendor packages.

During the initial installer-probe development on 2026-10-27, missing path overrides created only the synthetic `review-codex`, `review-grok` and `review-unrelated` artifacts under the primary binary cache. The six named agent/download directories were verified, backed up under `build/installer-fixture-cleanup` and removed; native synthetic metadata was uninstalled. Real agent binaries were not replaced. Subsequent probes redirect both metadata and binaries to their sandbox. Do not describe that initial attempt as having made no primary filesystem writes.

Review package/router source parity and deployment queue preservation where relevant. A documentation-only edit requires link/privacy/diff checks, not a new plugin build or installation.

## Last checked artifact

Development **0.2.3-dev.15**, SHA-256 `C39E34C844E23E4430185E61F910275668D3E7FD479CCC7242F3F564AB5E9F23` (2026-11-03):

- Fixes a prepared Air profile chat retaining a direct-provider route and namespace alongside an AI Multi virtual ID. Native first-prompt admission now commits the transport/identity association without changing the composer?s bound-agent catalog or project Default Profile.
- A failed cold restore uses a conversation-scoped router bootstrap to initialize the last committed agent, including its original working directory. The existing native recovery adapter admits the actual session/load or session/resume. It never creates a replacement session; changed-agent consent remains required. The pinned 497/521 native request, event and identity-association differences are documented in the architecture.
- `check buildPlugin --offline --max-workers=2` passed (`build/restore-release-final-check.log`). All six packaged router modules match both source copies. Router regressions cover initialization from a different process directory without an Assistant execution UID, a changed Default Profile, last-agent metadata, exact resume and extension forwarding, rejection of replacement creation, and changed-agent consent/refusal. The ambiguous-account notification fixture now establishes both sessions before scheduling the push, removing its timer/startup race.
- Combined native Assistant/Air 497 regression passed (`build/restore497-final-v4`): Assistant draft/handle preservation, Plan, cancellation and handoffs; Air native visible model/profile switching, prepared handles, first prompt, and cold restoration followed by an admitted/completed native continuation. The Air history-bearing profile-handoff subsection was excluded by ComposerOnly.
- Targeted native Air 262.8665.521 on IDEA 2026.2.4 passed against the final compiled code (`build/restore521-release`): the same virtual/provider session and committed profile survive native runtime release/reopen, old route repair is idempotent and preserves unknown fields, native resume creates no new provider session, and a subsequent native prompt completes with ready/connected/acceptsPrompt all true. This targeted run excludes the full visible model/profile-switch suite; no final real-account or new Plugin Verifier result is claimed.
- Development test attempts first exposed the missing transport bootstrap and native API differences; partial runs are not counted as passes. Synthetic native tests can also trigger the host?s background Junie download in their redirected registry storage, as in earlier verification. The primary IDE was not restarted and its active chats were not interrupted.
- Queued for native installation at the next IDEA restart. Installed dev.14 was backed up under `deployment-backups/20261103-161538-0.2.3-dev.15`; the native installer helper preserved unrelated queued updates. Automatic approval initially rejected staging for lack of current-session authorization; re-reading the existing private standing authorization allowed the same deployment action on review. The staged archive hash matches the build above.

## Previous development artifact

Development **0.2.3-dev.10**, SHA-256 `96A3ECD371D8AC686C1FC694FCF8D0F807BD5472ECDFB6F50315E3D13FBC12E6` (2026-11-02):

- Air 262.8665.521 `AgentPromptLaunchDraftState.pick` takes `(profile, origin, seedKind, boolean)`. dev.9 still passed Air 497's trailing nullable `Long`, so `call` found no method and threw `NoSuchElementException` from the composer toolbar update.
- `call` still prefers an exact arity. Only a trailing run of nulls can bind a shorter host method. A miss reports the host type, arguments and candidate signatures. Toolbar binding logs that incompatibility once; an explicit profile choice still rolls back.
- `nativeCallCompatibilityTest` covers the 497 five-argument shape, the 521 four-argument shape, exact-name preference, and a non-null tail that must not be dropped. `check buildPlugin` passed. No new full Air 521 composer sandbox or Plugin Verifier run is claimed.
- GitHub Actions `Air pick` nightly compares `AgentPromptLaunchDraftState.pick` on the Air `262.*` release line and on the newest stable Marketplace build with the Air 497 and 521 descriptors. A new shape opens an issue and does not change or publish the plugin.
- Queued for native installation at the next IDEA restart. Installed dev.9 is backed up under `deployment-backups/20261102-134551-0.2.3-dev.10`. The primary IDE was not restarted.

## Previous development artifact

Development **0.2.3-dev.9**, SHA-256 `C678686ED90143B01886D9CD520B73943233EC5B5FAE6AF2836226CD2AE816F6` (2026-10-28):

- Fixes the Air `.512` composer `AcpSessionRuntimeAdapter` constructor linkage failure. The scoped runtime and prepared backend preserve all running-host constructor defaults and replace only their routing resolvers.
- `check buildPlugin --offline --max-workers=2` passed against `.497` (`build/runtime9-check.log`). Combined isolated Assistant/Air `.497` verification passed (`build/runtime497-native`), including native visible controls, profile switches, prepared-handle reuse, first prompt, cancellation and unchanged project Default Profile.
- The same `.497`-compiled code passed runtime/backend constructor-default checks on `.512`. Assistant `.512` native launch, Plan, handoff/update and cancellation checks also completed in `build/runtime512-composer` before its Air fixture stopped at the obsolete Effort action assertion. The targeted composer check requires the native model control; it does not claim standalone reasoning-control parity when this fixture lacks `reasoningEffortSupported` metadata.
- Broader `.512` fixture attempts stopped first at the old `AcpLaunchFacts` setup constructor, then at `AirProfileBridge.connect`'s old `AcpConnectionRequest` binding for a manually prepared chat (`build/runtime512-native`, `build/runtime512-native-v2`). These are not passing full `.512` handoff suites. The composer-only check excludes that separate connection/recovery path. No real Grok account or fresh Plugin Verifier result is claimed.
- The `.512` model-switch fixture uses distinct native agent IDs and visibly different Codex/Grok model labels. `build/runtime512-model-switch` completed the cross-agent composer switch, displayed `Model B Grok`, reused both prepared sessions, admitted the first native prompt and closed the unused preparation. Its outer harness then incorrectly required a low-reasoning write from the excluded handoff scenario; that assertion now stays in the full suite. The probe also waits for the native composer binding between agent choices and avoids unsupported `getChildren(null)` calls.
- Final targeted `.512` verification passed (`build/runtime512-model-switch-final`), including native model presentation, distinct bound agents, project-default preservation, prepared-handle reuse, first prompt admission and unused preparation cleanup. Combined Assistant/Air `.497` regressions remain the full-suite result above; the targeted `.512` run used AirOnly after the preceding Assistant checks.
- Some `.512` runs triggered the host's background Junie preparation/download in addition to controlled providers. Registry binaries were redirected to the sandbox; one native process reported a log under the real user's `.junie/logs`. Do not claim that every provider execution or write in these runs was synthetic or isolated solely by `user.home`.
- Queued for native installation in the primary IDE, with the installed `dev.8` backed up under `deployment-backups/20261028-205901-0.2.3-dev.9`. The native deployment helper verified the queue and preserved unrelated updates. The primary IDE was not restarted.

## Previous development artifact

Development **0.2.3-dev.8**, SHA-256 `A0D3777BA42FAE12827B6A239ADF33F9986150054540EEB4A8252DC7FAEF3E2C` (2026-10-28):

- Composer launch seeds use the runtime Air copy signature, preserving native fields including `.512` fast mode and the draft prompt. This removes the `.497` `copy-Paq9AVk$default` linkage dependency reported on `.512`.
- Account captions use the lane's ACP agent identity (`agentAuthIdentityState` on `.497`, `agentFactsState` on `.512`), then the selected profile's local hint, or `Account unknown`. Generic IDE-source account captions and stale native text segments cannot override it.
- Combined isolated Assistant/Air native verification passed on `.497` (`build/launch-account497-v3`), including visible account controls, profile switches, prepared handles, model/settings defaults, continuation, cancellation and unchanged project Default Profile. An earlier mock context used the wrong DataKey comparison; a subsequent updater probe hit a transient null installed version, and its wait now handles that state.
- Final `check buildPlugin` passed (`build/launch-account8-final-check.log`). The final `.497`-compiled JAR passed `.512` startup/catalog/preferences and both account schema adapter checks (`build/launch-account512-final`), including fast mode/draft preservation. This is not a full `.512` native task suite or real Grok account verification. No new Plugin Verifier result is claimed.
- Queued for native installation in the primary IDE, superseding the verified pending `dev.7` installation. The deployment helper verified the queue, preserved unrelated updates and backed up the installed plugin. The primary IDE was not restarted.

## Previous development artifact

Development **0.2.3-dev.6**, SHA-256 `F6FC9D172953A8AE02AD41D8D474BF8152E230B07965EAC7A87F7569207B7078` (2026-10-27):

- Fixes Air 262.8665.512 profile button construction by invoking the native style's optional-foreground default bridge; retains the one-argument host API.
- `check buildPlugin` passed. The combined native Assistant/Air suite passed on the pinned .497 host (`build/button497-combined`), including visible controls. The .512 startup probe constructed the profile button successfully (`build/button512-verification`), and the combined .512 run completed Assistant checks but stopped in the Air fixture at its outdated `AcpLaunchFacts` constructor (`build/button512-combined`).
- The native probes ran against the same compiled code before the version-only repackaging. Automatic approval review rejected staging the update in the primary IDE; no deployment is claimed.

## Previous development artifact

Development **0.2.3-dev.5**, SHA-256 `4033E223DB687DF986538D8613DC10357F9B8162DAAF84B53E9B2E6CAF904CF1` (2026-10-27):

- Fixes Air 262.8665.512 startup failure in the legacy presentation alias: use the registered native contributor and its cold declaration path, with a runtime descriptor proxy instead of methods referencing removed .497 types.
- Adapts removed launch APIs, the Eel-aware client-info factory and native preference copies preserving `fastMode`.
- Full `check buildPlugin` passed against .497 before the final descriptor proxy adjustment (`build/air512-fix-final-check.log`). The final artifact was repackaged without repeating broad suites (`build/air512-package.log`).
- The final .497-compiled binary passed a short isolated .512 startup/catalog/native preference check, including preservation of a non-default fast mode (`build/air512-final-startup-v3`). One preceding sandbox attempt crashed in JBR's compiled VarHandle code; the fresh sandbox passed.
- Intermediate combined .512 checks passed Assistant native stores, launch, Plan and history-bearing profile handoffs. Full Air launch/task checks were not completed: the broad fixture needed adaptation to the newer catalog. No full final native suite or Plugin Verifier result is claimed. Later checks were narrowed at the user's request.

## Previous development artifact

Development **0.2.3-dev.4**, SHA-256 `83D7EC591E23FCB5F9F63F3596DB3116F76DB3BEBE58D2950490E3957C0891AC` (2026-10-27):

- Full `check buildPlugin --offline --max-workers=2` passed (`build/acp-release-check.log`). All six packaged router modules match both source copies byte for byte; the four new native binding/update adapters are present in the main plugin JAR.
- Combined isolated Assistant/Air verification passed (`build/acp-release-native`), including visible folded-agent controls, native preference stores, prepared handles, defaults/filters, drafts, cancellation and unchanged project Default Profile.
- Plan survives repeated native model setters and a native update reload/resubmission with conflicting profile defaults. The accepted-selection snapshot belongs to the native chat lifecycle and is discarded after restoration or disposal.
- Both clients switched a history-bearing chat in both capability directions, rebound actual SDK capabilities and completed native continuations. Air additionally checked its native steering snapshot. Shared router tests cover rollback, exact load/resume, original MCP, replacement consent and unknown protocol fields.
- Air's real updater/coordinator downloaded, hash-checked and installed two distinct synthetic distributions from 1.0.0 to 2.0.0 through loopback ZIP fixtures. Bound usage was recorded and an unrelated candidate stayed at 1.0.0. No real vendor executable was run. Assistant exercised its real updater's NoUpdate result plus the successful-install reload callback; a real Assistant distribution download is not claimed.
- Revised bound-agent terms reached Assistant's real terms service and chooser; a controlled native cancellation declined admission and left terms pending. This is a native service/channel test, not a real-user acceptance of vendor terms.
- Shared transport acceptance covers complete initialization errors including extensions, both cancellation spellings, reverse cancellation, original pending owner after a profile switch, late cancellation, true session notifications and unassigned setup without starting providers or aggregate capabilities.
- No fresh Plugin Verifier, 263 runtime or real-account distribution installation is claimed. Pinned private adapters and the directly reproducible native steering-completion limitation remain documented below and in the [architecture](native-client-architecture.md).

## Previous development artifact

Development **0.2.3-dev.3**, SHA-256 `142F93C8D0E94C88DF7CA8E0AA92560F5267361D5375840BC084ADDCA01FFA30` (2026-10-27):

- `check buildPlugin --offline --max-workers=2` passed. Targeted concrete-default and shared Assistant/Air preference tests also passed after the final regression additions. Packaged router bytes match the source and the native updater adapter is present.
- Reproduced Plan reverting to Default when the client reasserted the current model under a concrete profile preset; the regression now passes. Repeated config and legacy model setters retain manual choices, while actual model changes still apply their defaults.
- Combined isolated native Assistant/Air verification passed (`build/plan-update-final-native`), including visible/folded Air controls, launch selection, defaults, profile isolation, cancellation and unchanged project default.
- Native Assistant Plan survives same-model prompt preparation, cancellation, reload and a subsequent completed response. The bound update callback receives the committed registry agent once per segment, refreshes routes and excludes changed identities and advanced custom launches. The real native update service/chooser was exercised with a missing fixture distribution returning NoUpdate. A simulated successful-install callback exercised native reload/resubmission and provider cleanup; no registry distribution was downloaded by that fixture.
- No new Plugin Verifier run or real-account update installation is claimed for this artifact. The pinned update adapter adds native API dependencies documented in the [architecture](native-client-architecture.md).

## Previous verifier baseline

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
