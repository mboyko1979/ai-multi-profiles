# Public API replacement recheck — 2026-10-18

Assessment of public alternatives for the 0.2.3-dev.2 integration boundaries, against the exact targets below.

## Method and scope

The [Internal API Migration guide](https://plugins.jetbrains.com/docs/intellij/api-internal.html) explicitly says its replacement and exception tables are incomplete and directs authors to code documentation and current sources. A missing table row therefore does **not** establish that no public replacement exists.

Reviewed the guide's replacement/exception tables against the complete remaining inventory, then followed:

- [Explore the IntelliJ Platform API](https://plugins.jetbrains.com/docs/intellij/explore-api.html): locate extension contracts and their real consumers; inspect annotations rather than treating an `api` package or an implementation example as permission.
- [Platform extension points and listeners](https://plugins.jetbrains.com/docs/intellij/intellij-platform-extension-point-list.html), [plugin extension points](https://plugins.jetbrains.com/docs/intellij/intellij-community-plugins-extension-point-list.html), [listeners](https://plugins.jetbrains.com/docs/intellij/plugin-listeners.html), and [extensions](https://plugins.jetbrains.com/docs/intellij/plugin-extensions.html).
- [Class loaders](https://plugins.jetbrains.com/docs/intellij/plugin-class-loaders.html), [plugin dependencies](https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html), and [modular plugins](https://plugins.jetbrains.com/docs/intellij/modular-plugins.html), especially dependency-based visibility of content modules to a classic plugin.
- [2026 API changes](https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html), exact platform tags `idea/262.10968.63` and `idea/263.7031.23`, and current `master` for the relevant lifecycle/classloading contracts. Newer source is supporting research, not proof that an API exists at our compatibility floor.

The generic SDK lists do not enumerate Air/Assistant's contracts. Inspected their actual distributed plugin/module XML declarations, API class annotations and candidate consumers at these exact versions:

| Host | Version | Unique declared EP/contract pairs inspected | Contracts with class-level Internal annotation |
| --- | --- | ---: | ---: |
| Air | 262.8665.497 | 78 | 51 |
| Air | 263.7031.23 | 78 | 51 |
| Assistant | 262.10968.223 | 170 | 42 |
| Assistant | 263.7031.23 | 151 | 38 |

See the [complete derived EP status inventory](marketplace-0.2.2/public-extension-point-review.json). These are declared extension points of the scanned artifacts, not all possible public methods/services. An unmarked interface is only a candidate: member/parameter visibility, compatibility and actual call path still matter. Irrelevant language/model/completion/Junie/Matterhorn contracts were not claimed as alternatives to profile routing.

## Additional supported replacement implemented

### Native class resolution: use declared dependencies

`NativeRecoveryContinuation.type` previously called `PluginManagerCore.getPlugin` and `getPluginSet`, then reflectively enumerated enabled module descriptors/loaders. This is unnecessary for this classic plugin's declared optional dependencies. The modular-plugin guide documents that a classic plugin depending on a modular plugin receives that plugin's module loaders as parents.

The replacement uses the plugin's own `ClassLoader.loadClass`, checks the resolved native class's owning plugin through public `PluginAwareClassLoader.pluginId`, and initializes that same class through its defining loader. A missing class or wrong owner returns `null`. Native host classes, class identity and the requested host remain authoritative; no new loader or SDK copy is created.

The guide also recommends `PluginAwareClassLoader` instead of the internal concrete `PluginClassLoader`. Checked both exact target sources: [`262`](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/platform/extensions/src/com/intellij/ide/plugins/cl/PluginAwareClassLoader.java), [`263`](https://github.com/JetBrains/intellij-community/blob/idea/263.7031.23/platform/extensions/src/com/intellij/ide/plugins/cl/PluginAwareClassLoader.java). The interface and `getPluginId` are public. Its `tryLoadingClass`, `loadClassInsideSelf`, module-state and file-enumeration methods are Internal and are **not** used.

Applied the same resolution to `DefaultsClients.initialize(Air)`, removing its existing reflective `getPluginSet`/module scan as well. Its private native capability factory access remains a separate unsupported dependency; resolving its class publicly does not make that factory public. No reported call was moved into reflection. Removed the now-unneeded `INVISIBLE_REFERENCE`/`INVISIBLE_MEMBER` suppression from `NativeRecoveryContinuation`.

Native tests compare resolved Air/Assistant classes with the actual host class identities, reject a wrong plugin ID and a missing class, and exercise both clients' real ACP v1 initialization metadata. Existing combined composer/prepared-session/defaults/cancellation checks cover the changed path. Production sessions are not used.

## Candidates examined and reasons they do not establish a replacement

| Current requirement | Public/nearby candidate checked | Finding |
| --- | --- | --- |
| Composer-local ACP process launch before native preparation | Air `threadLaunchSpecAugmenter` / `AgentThreadLaunchSpecAugmenter`, `threadLaunchContributor` / `AgentThreadLaunchContributor` | These two interfaces lack an Internal class annotation in both artifacts and compile without invisible-member suppression. They are real candidates, not dismissed merely because they are Air APIs. Their consumers decorate `AgentThreadLaunchSpec` in `AgentThreadLaunchPlannerImpl`. The prepared ACP path instead runs `BackendThreadGuiPreparedSessionApi.lease` → `AgentSessionRuntimeAdapter.prepareLaunch` → `AcpSessionRuntimeAdapter` → `AcpSessionManager.prepare`. Both exact targets show this separate path. Launch-spec decoration alone does not supply the ACP start config, composer-selected profile or the native preparation lease. No behavior-preserving replacement of that binding was established. |
| ACP-specific launch/environment customization | `com.intellij.air.acp.agentStartConfigOverrideProvider` / `AcpAgentStartConfigOverrideProvider` | Semantically closer, but the interface itself is explicitly `ApiStatus.Internal` on both targets. Its `resolveOverride(Project, AcpAgentId)` uses Air's internal ACP ID/start-config types. A registered extension point is not automatically public. |
| Other ACP preparation/admission hooks | `SessionPromptPreparationProvider`, `AgentSessionSurfaceSwitchBridge`, `AgentLaunchTargetExecutor`, `AgentCatalogContributor`, presentation-service interface | Relevant contracts inspected; each is itself Internal in both targets. Changing from an Internal implementation to an Internal interface does not solve the violation. |
| Native input document for read-only retained history and draft protection | Public `AIAssistantInput.editorTextField` → platform `EditorTextField.getDocument()` | The accessor is public in both targets, but the native 262 composer test returned a **different Document instance** from `AIInput.document`, despite equal text at that instant. A proposed substitution was reverted. Equal current text does not prove read-only state, replacement, listeners or future edits affect the same model. This remains an unproven candidate, not a claimed universal impossibility. |
| Profile attribution in the native ACP response header | Public Assistant `ChatMessageViewProvider`, `MessagePartViewExtension`, `ChatMessageHandler` | Inspected signatures and consumers. They handle legacy `ChatMessage` views, Swing text/HTML parts or answer-message handling. The ACP native header uses `ChatSessionMessageEvent`/Compose view models via the split-component path. None of these contracts exposes a narrow header-author decorator for that path. Replacing the whole view or taking over answer generation would change the agreed client-owned rendering/lifecycle. The SDK explicitly marks `com.intellij.frontend.splitComponentProvider` Internal. |
| Existing native model/mode/config control decoration | Public `AiChatToolbarToggleProvider` | It contributes controls (`getControl`, `getControls`, `getControlsFlow`); it does not decorate/suppress another provider's controls. An additional provider would duplicate the native selectors. Public `ExtensionPoint` registration overloads are TestOnly or deprecated toward test APIs; unregister/re-register also loses native ownership/order/lifecycle metadata. No supported scoped decorator was identified. |
| AI Multi icon in Air's native session/launch rows | Assistant `AgentIconService`, `AcpChatAgentExtension.getIcon`, generic platform icon mapping | Assistant's public agent icon extension is already used there. It does not govern Air's independent session-row/launch presentation. Air's corresponding presentation interface is Internal. Resource-name icon mapping does not supply the bound/legacy session context required for these rows. |
| Profile launch environment through Assistant | Public `AcpChatAgentExtension.contributeProcessEnv` in 262 | Method is absent in the target 263 interface. Its 262 contract is agent-scoped and has no independent composer/profile context. It is not a cross-target replacement for per-composer routing and Air preparation. Existing public icon/description contribution remains. |
| Profile button matching native responsive Air selectors | Platform `ActionButtonWithText` | Public base, but Air adds `FORCE_COMPACT`, `MAX_TEXT_CHARS` and tooltip behavior through native keys/style. Merely changing the superclass loses that behavior; accessing private keys via strings/reflection would conceal the dependency. A behavior-preserving public equivalent was not established. |
| Disable/re-enable cleanup, including restart-required pending state | Public `DynamicPluginListener`, `PluginStateListener`, `PluginDetailsService` | Already used where applicable. Dynamic listener is load/unload; state listener has install/uninstall; details service supplies snapshot queries. Neither exact target nor reviewed current sources exposes a disabled-state change topic through these contracts. A file watcher or delayed shutdown-only cleanup is not an equivalent semantic event. `PluginEnabler` is itself Internal. |
| Setup/authentication UI delegation | `AIAssistantChatPanelComponentProvider`, `AgentAuthorizationRowProvider`, generic dialog/action APIs | Native contribution interfaces themselves are Internal. Generic dialogs/actions do not publicly delegate the native authentication request/response flow or supply the native panel lifecycle. Reimplementing that UI/task flow would not preserve the current architecture. |

The remaining API inventory still supplies the exact individual signatures and source locations. The matrix above groups those elements by the integration requirement and records the strongest candidates actually investigated. Most Air pass-through findings belong to one Internal delegated protocol rather than independent replacement opportunities.

## Evidence, checks and limits

Research inspected distributed extension declarations, class annotations, candidate signatures/consumers, preparation bytecode and exact platform source tags.

`tools/ClientPreferencesProbe.java` now records:

- `public-classloading.txt`: matching actual native class identity for both hosts, wrong-owner and missing-class rejection.
- `public-editor-document.txt`: `sameDocument=false`, both types `DocumentImpl`, `sameText=true` in the actual 262 composer. The initial equality assertion rejected the proposed document change; production code retains the original native document.
- Both clients' real initialization metadata must still contain protocol version 1, client capabilities and implementation information.

Native verification: `build/public-api-review/native-dev2-final-confirmed` passed both clients, visible Air model/reasoning/account controls, native document/draft handling, prepared session reuse and default isolation. The added tests check native class identities, wrong-owner/missing-class behavior and actual client initialization metadata. `check buildPlugin --offline --max-workers=2` passed on the final sources; whitespace checks passed. The previously passing native installer-queue regression suite remains applicable: no installer/helper code changed in this recheck.

Final artifact **0.2.3-dev.2**, SHA-256 `F2923116E1B58FA9FB49374662DAE196EEA10C47D479FC761897893252B55B33`, checked with Plugin Verifier 1.410, without suppression or excluded packages:

| Target | Exact native dependencies | Result | Internal rows / distinct APIs | Experimental | Deprecated + removal |
| --- | --- | --- | --- | ---: | --- |
| IU-262.10968.63 | Assistant 262.10968.223, Air 262.8665.497 | Compatible | 373 / 183 | 19 | 14 + 1 |
| IU-263.7031.23 | Assistant/Air 263.7031.23 | Compatible | 373 / 183 | 8 | 15 + 1 |

Compared with uploaded 0.2.2 including its ignored entries: **11 removed, zero added**. Compared with dev.1: two further bytecode findings removed, and the existing reflective module scan in discovery initialization removed outside the verifier's bytecode-visible count. See [every remaining signature/location and all removals](marketplace-0.2.2/development-internal-api-0.2.3-dev.2.json).

Final complete reports: `build/public-api-review/verifier-final-262` and `verifier-final-263`, with isolated dependency caches and both declared hosts resolved. An earlier intermediate 262 report contained only 38 findings because optional Air was unavailable; it is **incomplete**, not a successful full check. Other failed/intermediate candidate runs are retained as research evidence and are not counted as passing gates. Static EAP verification does not claim full EAP native UI coverage; the combined native run uses installed 262 hosts and controlled agents.


**A public classloading replacement was found and implemented.** For the remaining native integrations, no equivalent public contract was established by the reviewed guides, exact-target interfaces, extension declarations and candidate consumers. This is not proof of nonexistence in all JetBrains code, nor permission to use Internal API. The concrete requirements above need supported extension points or explicit guidance on permitted usage; Air/Assistant APIs are not uniformly private.
