# Marketplace 0.2.2 Internal API investigation — 2026-10-18

Plugin 35238, beta update 1197250, support request 9266991. This investigation does not authorize publishing or sending a support response. Internal API usage remains a publication blocker pending JetBrains' response or supported replacements.

**Follow-up correction:** the [deeper public API recheck](public-api-recheck-2026-10-18.md) found a documented replacement for plugin/module loader discovery. It removes both remaining `PluginManagerCore` calls and the existing reflective module scan from discovery initialization. The classloader row below records the original dev.1 assessment and is superseded by that recheck. Other candidate replacements were inspected rather than inferred from absence in the migration table.

## Evidence and grouping

Fetched the update metadata, both verification-result records and both complete `verifier.json` reports using GET requests. Credentials were supplied only in the Authorization header to plugins.jetbrains.com; no credential is included in the evidence or repository.

| Target | Result | Active rows | Ignored rows | Unique active API elements |
| --- | --- | ---: | ---: | ---: |
| IU-262.10968.63 / 2026.2.3 | 6007581 | 382 | 2 | 185 |
| IU-263.7031.23 / 2026.3 EAP | 6007582 | 382 | 2 | 185 |

The verdict's “384 usages” includes the two ignored rows. Do not add those two again. The downloaded JSON has 15/16 deprecated entries, respectively; one in each is scheduled for removal, explaining the verdict's separate 14/15 deprecated + 1 removal counters. “Compatible” describes binary compatibility, not approval of private API use.

Both targets have identical normalized Internal API findings: parameter debug names (`arg0` versus `ref`, for example) differ, but API signatures, usage locations and kinds do not. Original report SHA-256 values and the exact per-target totals are in [the generated summary](marketplace-0.2.2/internal-api-summary.json).

| Integration | Active rows | Unique API elements | Reason |
| --- | ---: | ---: | --- |
| Air | 335 | 154 | Native launch binding, prepared leases, delegated GUI protocol, catalog identity and presentation |
| AI Assistant | 22 | 17 | Setup/authentication UI, draft protection and startup/native toolbar contracts |
| Assistant split UI / RPC | 6 | 5 | Profile attribution in the native response header |
| Assistant toolbar provider binding | 2 | 2 | Existing native control-provider scoping |
| Plugin enable/disable lifecycle | 16 | 6 | Registration cleanup and re-enable on disable-state changes |
| Plugin/module loader discovery | 1 | 1 | Locating native recovery implementations across content modules |

The two ignored entries are both `PluginManagerCore.getPlugin(PluginId)`, in `InstalledAgentsKt.mlPluginClassLoader` and `NativeRecoveryContinuation.type`.

The [grouped inventory](marketplace-0.2.2/internal-api-grouped.json) lists every unique element, every bytecode location and its containing source file. The [CSV](marketplace-0.2.2/internal-api-locations.csv) contains all 768 rows across both targets, including ignored rows. Generated delegated methods and coroutine/bridge classes are mapped to their containing source, not presented as independent handwritten methods. For example, `AirComposerProfiles.kt` accounts for 209 rows and `AirLegacyProfilePresentation.kt` for 101. `ThreadGuiApi` alone appears 34 times as a type, plus individual delegated method references/overrides.

Reproduce the grouping:

```text
node tools/summarize-marketplace-verifier.js build/marketplace-audit/verifier-262.10968.63.json build/marketplace-audit/verifier-263.7031.23.json docs/marketplace-0.2.2
```

## Supported replacements applied

1. `RegistrationLifecycle`: replace both `DisabledPluginsState.getDisabledIds()` queries with `PluginDetailsService.isDisabled(PluginId)`. Use the same public service for the loaded-state query. The service is public, marked Experimental in 262 and no longer Experimental in 263. It explicitly exposes the user's disabled flag. Retain the original listener because observing pending disable/re-enable changes is a separate requirement.
2. `InstalledAgentsKt.mlPluginClassLoader`: replace ignored `PluginManagerCore.getPlugin(...).pluginClassLoader` with `AcpAgentId::class.java.classLoader`. This public Assistant ACP type identifies its actual native content module and preserves SDK class identity. The old root plugin loader cannot load `AcpAgentInstallationState` on the installed 262 host, causing an empty installed-agent list. Existing version-specific native reflection is unchanged; no new reflective dispatch was introduced.
3. `AirProfilePreferencesGroup`: use public `AnActionEvent.updateSession.children(group)` to expand native dynamic root and nested action groups. Remove reflective `ActionGroup.getChildren(AnActionEvent)` dispatch. Outside an update session enumerate only static children. The 0.2.2 switch to `DefaultActionGroup.getChildren(ActionManager)` dropped Air's dynamic model/settings roots: a reproduced New Session showed only the profile. This is a behavior fix and removal of an existing reflective override-only call, not a reduction of a reported Internal API element.

Official source checked at both exact target tags:

- [262 PluginDetailsService](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/platform/core-impl/src/com/intellij/ide/plugins/PluginDetails.kt), [263 PluginDetailsService](https://github.com/JetBrains/intellij-community/blob/idea/263.7031.23/platform/core-impl/src/com/intellij/ide/plugins/PluginDetails.kt).
- [262 UpdateSession](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/platform/editor-ui-api/src/com/intellij/openapi/actionSystem/UpdateSession.java), [263 UpdateSession](https://github.com/JetBrains/intellij-community/blob/idea/263.7031.23/platform/editor-ui-api/src/com/intellij/openapi/actionSystem/UpdateSession.java).
- [DefaultActionGroup](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/platform/platform-api/src/com/intellij/openapi/actionSystem/DefaultActionGroup.java): the ActionManager overload reads stored children; it cannot replace a subclass's dynamic event-based expansion.

The installed Air 262.8665.497 `AirPromptSelectorToolbarGroup` bytecode confirms its event overload reads `AirPromptActionsKeys.MODEL`, obtains native view-model roots and resolves their actions. The static overload bypasses this code. Runtime evidence includes the empty and restored toolbar screenshots; this was not inferred from verifier success.

## Remaining integrations and replacement assessment

No behavior-preserving public replacement was identified in the reviewed target contracts for the following groups. This is a scoped finding, not proof that JetBrains has no unpublished alternative, and not permission to ship them. Exact method signatures and all locations are in the inventory.

| APIs | Plugin source | Requirement / requested extension point |
| --- | --- | --- |
| `ThreadGuiPreparedSessionLeaseService`, `ThreadGuiPreparedSessionLeaseServiceImpl`, `PreparedSessionLease`, `ThreadGuiPreparedSessionRequest`; `BackendThreadGuiPreparedSessionApi`, `AgentSessionRuntimeAdapters`; `ThreadGuiApi`, `ThreadGuiRef` and related requests/results | `air/AirComposerProfiles.kt` | Bind a composer-local resolved launch to the native lease, preserving admission, cleanup, tools and unknown GUI operations. Request a scoped launch-config decorator. Kotlin `by` delegation generates many reported pass-through methods; replacing it with a second lifecycle would violate the architecture. |
| `AcpAgentsCatalog`, `AcpCatalogEntry`, `AcpAgentId`, `AcpAgentStartConfig.withAcpArgs`, `acpCatalogAgentId`, `acpAgentLaunchTargetId` | `air/AirComposerProfiles.kt` | Preserve the real agent's catalog/cache identity (including folded Codex identity), changing only router process arguments and the explicit initial profile. Request native catalog-to-launch mapping plus a process launch hook. Assistant's similarly named ACP ID type is a different, public API. |
| `AgentDescriptor`, inherited `AgentDeclaration` members, `AcpAgentCatalogContributor` | `air/AirLegacyProfilePresentation.kt` | Presentation of already stored AI Multi session identities without creating replacement sessions or adding duplicate launchable agents. Request a session-presentation alias/attribution hook. |
| `AgentLaunchTargetPresentationServiceImpl`, launch-target conversion helpers; `RowPresentation` | `air/AirAgentPresentationService.kt`, `air/AirSessionRowIcons.kt` | AI Multi's icon in native launch/session rows, delegate all unrelated agent presentation. Request an icon contributor with native descriptor/session context. Existing service override is a pinned adapter, not newly broadened here. |
| `AirPromptSelectorId.actionId`, `CompactAwareActionButtonWithText` | `air/AirProfileControls.kt` | Profile control beside the native agent selector with native compact behavior. `ActionButtonWithText` alone does not implement Air's responsive selector contract. Request an ordered native composer action contribution API. |
| `AIAssistantChatPanelComponentProvider`, `AIAssistantChatPanelComponent`, `AgentAuthorizationRowProvider` | `compat/AiProfileSetupPanelProvider.kt`, `compat/ProfileAuthorizationRowProvider.kt` | Profile setup/authorization in native client surfaces. Request public narrow UI contribution points. |
| `AcpEvent`, `AcpEvent.CustomAgentEvent`, `AcpUserResponse.RequestResponse` | `compat/ProfileAuthenticationChooser.kt` | Render the native agent authentication request and return the actual chosen response. Request public authentication UI delegation. |
| `AIInput`, `AIInput.getDocument`, `AgentSession`, `AIAssistantChatCustomNotificationViewModel` | `compat/UnsentPromptGuard.kt`, `compat/LocalHistoryPresentation.kt`, `compat/ProfileStartupStatus.kt`, `compat/ProfileToolbarIsolation.kt` | Unsent-draft protection, read-only retained history, native status and delegated view-model contracts. A Swing Document alone is public but does not supply the native chat input/lifecycle binding. Request public input/status access. |
| `SplitComponentProvider`, `SplitComponentBinding`, RPC `Id`, `ChatSessionId.findValue` | `compat/ProfileResponseHeaderProvider.kt` | Profile attribution within native response cards without replacing their transcript/content lifecycle. Request a header attribution contributor. |
| `ExtensionPointImpl`, `ExtensionPointImpl.maskAll` | `compat/ProfileToolbarIsolation.kt` | Existing native model/mode/config provider scoping. Public registration overloads are TestOnly; registering additional providers would duplicate controls instead of decorating them. Request a scoped native toolbar decorator. Do not change this to test-only APIs or silently remove controls. |
| `DisabledPluginsState.addDisablePluginListener/removeDisablePluginListener` and companion/type references | `RegistrationLifecycle.kt` | Clean owned ACP registration when disabling/uninstalling and restore on re-enable, including restart-required changes. `DynamicPluginListener` observes unloads, not all pending disabled-state changes. `PluginEnabler` is also Internal; it is not a public replacement. Request a public disabled-state topic. |
| `PluginManagerCore.getPluginSet`, ignored `PluginManagerCore.getPlugin` | `NativeRecoveryContinuation.kt` | Find existing native host implementations across enabled content-module loaders for consented recovery. `PluginDetailsService` exposes metadata/module names, not loaders or recovery entry points. Request native recovery API or public class resolution for declared optional dependencies. |

The [migration guidance](https://plugins.jetbrains.com/docs/intellij/api-internal.html) contains no listed replacements for the Air/Assistant integrations above. Source inspection of `ExtensionPoint`, `PluginEnabler`, `DisabledPluginsState`, `PluginManagerCore` and `PluginDetailsService` distinguishes public metadata queries from internal lifecycle/module mutation. Full Air/Assistant source was unavailable in the reviewed intellij-community paths. Their exact-target verifier annotations/signatures and installed pinned-host bytecode were used rather than inventing public SDK contracts.

Important scope limit: the verifier inventory covers bytecode-visible findings in the uploaded 0.2.2 archive. Existing reflective/private host bindings in launch/preferences/authentication/recovery are **additional unsupported dependencies**, not automatically safe because they are absent from this count. None were introduced to conceal a finding. The support draft discloses this and requests supported boundaries for the whole integration.

## Validation and deployment

Evidence is local under `build/marketplace-audit` and the isolated `build/marketplace-*` sandboxes. Baseline native verification failed in `installedLaunchRefresh` because `InstalledAgents.all()` was empty. After the ACP loader fix it progressed but reproduced the empty Air toolbar/account-control failure. After the action expansion fix the combined native suite passed with visible agent/profile/model/reasoning/access/account controls, retained draft/native handles, correct profile defaults/preferences, native cancellation and unchanged project Default Profile. Production accounts/chats were not used by these fixtures.

Development version: **0.2.3-dev.1**. `check buildPlugin --offline --max-workers=2` passed all 18 regression suites; `git diff --check` passed. Installer queue regression checks passed, including preservation, incomplete-pair rejection and ordinary/migration installation ordering. Native verification was rerun on the final development build with both hosts, `-AirVisible -FoldedAgent`, without `-AirOnly`; it passed the explicit visible model/reasoning regression as well as the existing cross-client lifecycle/defaults checks. Native fixture instrumentation and the analysis tool are outside plugin sources.

Plugin Verifier **1.410**, without suppression, ignore rules or external-package exclusions:

| Target / dependencies | Binary result | Internal rows | Experimental | Deprecated + removal |
| --- | --- | ---: | ---: | --- |
| IU-262.10968.63; Assistant 262.10968.223, Air 262.8665.497 | Compatible | 375 | 19 | 14 + 1 |
| IU-263.7031.23; Assistant 263.7031.23, bundled Air 263.7031.23 | Compatible | 375 | 8 | 15 + 1 |

The public Experimental service adds 11 experimental rows on 262. On 263 it is no longer Experimental. Both complete reports remove exactly nine original Internal rows and add zero: eight type/companion/field/method references associated with disabled-ID queries, plus the ignored root-loader lookup. There are 185 remaining distinct elements counting the one previously ignored `getPlugin` element. Local CLI reports all 375; it does not apply Marketplace's two historical ignored findings. No future Marketplace ignore configuration or approval is assumed.

The [development inventory and exact delta](marketplace-0.2.2/development-internal-api.json) lists every remaining API/location and all nine removals for each target. Reproduce by appending these two plain-text reports to the grouping command above:

```text
build/marketplace-verifier-262/IU-262.10968.63/plugins/dev.boyko.ai-multi-profiles/0.2.3-dev.1/internal-api-usages.txt
build/marketplace-verifier-263-complete/IU-263.7031.23/plugins/dev.boyko.ai-multi-profiles/0.2.3-dev.1/internal-api-usages.txt
```

The first offline EAP attempt lacked the exact optional Assistant dependency and reported four unresolved-package groups. It is retained under `build/marketplace-verifier-263` as incomplete evidence, not counted as a successful check. Downloaded official EAP Windows ZIP and verified its published SHA-256 before extraction. Adding Marketplace Assistant update 1194604, version 263.7031.23 (the same dependency as the original server report), resolved those groups in the complete run. No IDE or plugin was installed from that EAP distribution. Static verification is not full EAP UI/runtime coverage.

Local evidence:

- `build/marketplace-audit/build-dev1.log`, `deployment-checks.log`, `verifier-dev1-262.log`, `verifier-dev1-263-complete.log`, `inventory-generation.log`.
- `build/marketplace-baseline/error.txt`: original installed-agent discovery failure.
- `build/marketplace-fixed/new-session.png`: empty native Air controls after fixing only the loader; `error.txt` records the account-control failure.
- `build/marketplace-dev1/result.txt`, `visible-model-settings.txt`, `new-session.png`, `assistant-launch-result.txt`, `assistant-live-filters.txt`, `air-result.txt`, native stores and direct/routed steering artifacts.

Existing native `startedNewTurn` completion behavior is still reproduced through the router and directly; this investigation does not claim to fix it. The controlled native suite is not exhaustive production-provider or cold/warm-cache coverage. Primary IDEA UI verification awaits activation.

**Deployment:** queued 0.2.3-dev.1 for the next primary IDEA restart using `deploy-to-idea.ps1`, without migration (the new plugin identity is already installed). Backup: `deployment-backups/20261018-192158-0.2.3-dev.1/ai-multi-profiles-before-update.zip`. Built ZIP, staged ZIP and deployment record SHA-256: `439480C305E91929D6CC42F946C4092368A46E6762E7660EACF30DBA34EABAB4`. Native queue was read back; backup entries were read successfully. The deployment preserves unrelated queued updates. The primary IDEA was not restarted and active chats were not interrupted.

**Publication remains blocked:** obtain JetBrains guidance or explicit permitted-use conditions for remaining APIs, or implement supported equivalents without removing agreed behavior; validate any resulting changes and repeat verifier/native gates. Only then prepare a release artifact and upload it when separately instructed. The support draft remains unsent and no new Marketplace version has been uploaded.

See the [unsent support draft](marketplace-support-draft-9266991.md). No Marketplace upload or support message is authorized by this document.
