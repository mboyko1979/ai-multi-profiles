# AI Multi — ACP lifecycle and memory audit

Audit date: 14 October 2026. Source version: `0.2.0-dev.40`.

Historical audit of dev.40. Subsequent authorized lifecycle fixes and validation are documented in [the dev.41 verification entry](verification.md#020-dev41--owned-acp-lifecycle-and-temporary-session-cleanup); findings below describe the audited implementation, not the later fixed version.

**Conclusion:** AI Multi has confirmed backend retention and failed-start cleanup defects that can increase process counts and system commit. The available evidence does **not** establish that AI Multi caused the IDEA crash. A Windows low-memory event at the exact incident time independently confirms system-wide commit exhaustion and identifies substantial consumers outside the plugin.

No implementation, registration, authentication, or stored-session changes were made. No primary IDEA restart or active-chat interruption was performed. This report is the only repository addition. Temporary mock fixtures were removed, and all processes launched by the audit were verified stopped.

## Evidence and limits

- Inspected the Router, virtual-session and settings modules, Kotlin process/setup paths, registration, profile routing, services, listeners, coroutine scopes and presentation adapters.
- SHA-256 hashes of all four deployed modules under `%USERPROFILE%\.jetbrains\ai-agent-project-switcher` match both `router/` and `src/main/resources/`. References below use `router/`; fixes must preserve bundled parity.
- Inspected installed AI Assistant classes using `javap`, including `AcpProcessHandlerService`, `AcpLocalSessionLifecycleManager`, `AcpSessionLifecycleManager`, and `AcpServerProcessHandlerImpl`. Inspected installed Codex ACP JavaScript and the Codex executable wrapper.
- Ran a temporary mock-only process harness against the actual Router. It used isolated configuration/history, no authentication, no provider prompts, and no production agent executable.
- Read Windows System event 2004, record 50446, timestamp `2026-10-14T12:37:58.3000275Z` / 14:37:58 local.
- Did not reproduce the JVM crash, obtain a pre-crash process inventory, or independently inspect the fatal JVM report. JVM figures in the request remain user-supplied evidence. No JVM debugger or heap/native-memory capture was taken. The existing complete test suite was inspected, not rerun.

## A. Architecture overview

### Initialization and ownership

`ProjectGuardActivity.execute()` requests application registration reconciliation, publishes the project's saved Default Profile, and calls `GenericAutoRegistration.ensureAsync()`. `ensure()` copies the Router and dependency modules into the user data directory and registers the Node command in `.jetbrains/acp.json`. It does not launch a persistent singleton Router itself.

`updateRoutes()` publishes `virtualSessions: true`, profile launch specifications, and project-root assignments. A file lock serializes configuration writes across IDE processes; atomic replacement limits partial reads. `ProfileRegistrations.reconcile()` additionally publishes fixed-profile entries such as `AI Multi · Personal`, each invoking the same script with `--profile <id>`. These registrations are independent host entry points, not one shared backend pool.

The installed AI Assistant's **project-scoped** `AcpProcessHandlerService` caches processes using `ProcessKey(agentId, chatUid)`. A newly acquired chat normally gets its own Router transport; reuse concerns that chat's process. Merely creating an unfocused chat need not immediately launch ACP, but focusing/initializing it can do so before the first prompt. `ProfileStartupStatus.observe()` explicitly calls native `ensureSessionReady()` on the supplied UI scope.

Ownership boundaries:

| Resource | Lifecycle owner |
|---|---|
| Native chat, task runner, ACP client, terminals/tool requests | AI Assistant; Air has its own host lifecycle |
| Router process launched from AI Multi registration | Host launches/stops it; AI Multi supplies its behavior |
| Profile backend launched by `backendFor()` | AI Multi Router |
| Backend's Codex app-server, executable and tool descendants | Agent implementation directly; AI Multi needs containment for the tree it launches |
| Settings/setup Router and explicit login handler | AI Multi Kotlin setup dialog/probe |
| Profile homes, owner records, conversation history | Durable user data; process cleanup must preserve it |
| Native Codex/Grok launched through another registration or external tool | Outside AI Multi ownership |

An executable name or common profile directory alone is insufficient ownership proof. Track launch ancestry and creation identity. Current inspection found two Router roots directly parented by the primary IDEA, demonstrating multiple transports, not a singleton. This post-restart snapshot cannot reconstruct the incident.

### Router and backend creation

`backendFor(profile, spec, isolationKey)` caches a backend by `profile + launch fingerprint + isolationKey`. The fingerprint covers executable, arguments, launch cwd, effective environment and authorization revision. The map is local to one Router. Identical profiles in different chats/projects therefore legitimately launch separate instances.

On Windows, `directLaunch()` replaces `npx`/`npm` command shims with a managed `node.exe` and `npx-cli.js`/`npm-cli.js`, with `shell:false`. This avoids an initial command shell; npm can still launch additional wrappers. For the installed Codex ACP, `startCodexConnection()` starts `node codex.js app-server`, and `codex.js` starts the native `codex.exe`, passing through the environment. Agent-internal recovery can restart an app-server without the Router creating another backend.

Router `initialize` normally starts one backend with an empty isolation key to negotiate capabilities/authentication. A virtual `session/new` then calls `VirtualSessions.createBackend()`, which uses a new segment UUID as its isolation key and starts a **second** backend. Thus a fresh initialized virtual chat normally has both a capability/authentication backend and a session backend. Restored chats with saved initialization/client metadata can avoid the first launch; this is not a universal two-process rule.

When no project profile is assigned, `initialize` starts **all configured profiles** to intersect their capabilities even though it presents a local setup chat.

### Sessions, switching and restoration

Virtual sessions expose an `aiaps-chat-*` ID to IDEA. Each provider segment has a separate UUID, profile fingerprint and provider session ID. Immutable owner files prevent reassignment of native session IDs across profiles; virtual conversation files retain profile attribution, public exchanges, session options and handoff state. Directory leases exclude another Router from concurrently opening the same virtual conversation.

Switching profiles creates a fresh backend and provider session, commits a cloned conversation record, and optionally prepares a consented history transfer. It does not restart or repurpose the old backend. Each switch back to Personal or Work creates another segment, rather than reusing the previous segment. `attachment-preview` creates an additional cached backend per target profile/fingerprint within that Router, even if the switch dialog is cancelled.

`session/cancel` forwards cancellation of the current task; it is not session disposal. `session/close` can be forwarded to the current provider but does not release virtual active state, leases or backend processes. The Router does not advertise virtual close capability. Hiding a chat panel, disposing a native chat, and deleting stored history are separate host operations; they must not be treated as equivalent.

### Project closure and IDE shutdown

The installed native lifecycle invalidation calls `AcpLocalSessionLifecycleManager.cleanupAfterSessionInvalidation()`, which stops the process for that agent/chat. `stopSourceAndAwait()` uses `stopProcessHandlerAndAwait()`. Project service disposal cancels its cleanup job and stops all registered transports. The process handler delegates termination to EEL; its existence does not prove how every Windows descendant is contained.

AI Multi's registration service removes its listeners on disposal and intentionally preserves registrations during normal shutdown. It does not maintain a Kotlin registry of ordinary chat Router PIDs. Correct project/chat cleanup therefore depends on the host reaching its disposal paths and the Router cleaning up children.

Router stdin EOF, SIGINT and SIGTERM call `shutdown()`. It releases virtual leases, clears request timers and kills immediate backend children. It does **not** use its Windows tree-kill helper there. Abrupt Windows termination cannot be assumed to execute JavaScript signal handlers. A fatal IDEA crash cannot be assumed to execute IntelliJ disposers.

### Profile isolation and Session Defaults

The current published routes contain distinct homes:

| Profile | Explicit directory variable | Directory below `profiles/` |
|---|---|---|
| Personal | `CODEX_HOME` | `codex_personal` |
| Work | `CODEX_HOME` | `codex_work` |
| Grok | `GROK_HOME` | `grok_personal` |

`ProfileHomes.environment()` supplies vendor-specific variables, and those generated values override native/environment-editor entries when routes are published. Router spawning merges inherited environment first and explicit profile values last. The installed Codex ACP and Codex JS wrapper preserve this environment down to the executable. These traced launch paths do not accidentally substitute `%USERPROFILE%\.codex` for the two current managed Codex homes. The native executable's actual file accesses were not monitored, and custom commands can ignore environment variables.

Separate backend processes isolate in-memory provider session state. Multiple projects using one profile intentionally share that profile's authentication/configuration/session directory. Different managed profiles currently have separate directories. The abstraction itself is sound; configuration validation gaps are listed below.

`profile-settings.apply()` applies agent-matched defaults using session setters, skips already accepted values, and bounds its reconsideration passes. It does not spawn processes. Restoration reacquires accepted provider options instead of reapplying creation defaults. `ProfileAgentPreferences.synchronize()` pins the live session's accepted values before native preferences can replace them. Native saved preferences still use the underlying agent ID, so Personal and Work share that native preference namespace; this is distinct from authentication or session-storage sharing.

The Router launch fingerprint excludes Session Defaults and profile instruction fields. Saving those fields alone therefore does not create a new backend cache key or require an active process restart. New segments apply the current defaults; existing segments retain their accepted settings and frozen instructions. Explicit model/catalog refresh does launch a separate discovery backend, whose failed-initialization cleanup is covered by F2.

The audited chat-local paths (`CurrentChatProfileSwitch.choose()` and `ProfileChatCreator.create()/prepare()`) preserve Default Profile. The separate project-selection action intentionally changes the project assignment.

## B. Findings

No Critical finding is established. Severity reflects plausible operational impact; “confirmed” means source behavior and/or the controlled test establish the defect, not incident causation.

### F1 — High: old segment backends accumulate after profile switches

**Source:** [virtual-sessions.js:566](../router/virtual-sessions.js#L566), `control()` switch/abort branches; `createBackend()` at line 127; Router `backendFor()` at line 263.

**Status:** Confirmed, reproduced. Successful switches replace `active` with the new segment but never stop/remove the old backend. Aborting a prepared handoff similarly leaves the discarded target alive. Older segments are not selected for subsequent switch-back reuse. Each old `backend.virtual.state` can also retain its previous complete transcript snapshot.

**Reproduction:** Initialize one virtual chat, alternate two profiles six times while it is empty. The harness counted **2 → 8 live backends**. Completed-task switches have the same retention path and may additionally create preview instances.

**Impact:** Process trees, native memory, threads, pipes and watchers grow with switch count. Historical transcript copies can multiply Router heap consumption. This is unbounded retention, not a demonstrated bounded reuse policy.

### F2 — High: failed startup lacks transactional rollback

**Source:** [cps-router.js:263](../router/cps-router.js#L263), `backendFor()` and `backend.ready`; [virtual-sessions.js:127](../router/virtual-sessions.js#L127), `createBackend()`, `newSession()`, `ensureBackend()`, and `control()` refresh/switch.

**Status:** Confirmed; initialization timeout reproduced. A rejected/timed-out initialize promise remains cached, without stopping the child or marking initialization failure through a unified cleanup path. Session creation, preset application, ownership publication and persistence can throw after a backend starts, without retiring it. New-session failure can leave a lease whose session ID was never returned.

**Reproduction:** Backend accepts launch but never answers initialize. With a 200 ms request timeout, the Router returned an error and **one stalled backend remained alive**. Repeat a refresh whose unique `catalog:<UUID>` backend fails initialization: assignment to `discovery` never completes, so its `finally` cannot stop that instance. Retrying creates new keys.

**Impact:** Orphaned logical instances inside a live Router; potentially growing process trees on unique-key retries. Repeated use of an identical cached key instead returns its failed promise: that case is a recovery failure, not repeated spawning.

### F3 — High: descendant cleanup is not guaranteed on Windows

**Source:** [cps-router.js:605](../router/cps-router.js#L605), `shutdown()`; `stopProcess()` at line 29; login branch starting line 79.

**Status:** Confirmed implementation gap; actual orphan survival remains a hypothesis. Normal shutdown uses `backend.proc.kill()` despite an existing `/PID … /T /F` helper. Signal paths immediately call `process.exit()`, with no wait for descendants. Login mode has no equivalent EOF/signal cleanup. `stopProcess()` returns once the immediate parent's `exitCode` is non-null, so it cannot recover descendants after that parent has already died.

**Reproduction:** Stop a Router/backend while it owns a descendant that does not voluntarily exit on pipe closure; repeat with abrupt parent termination. The audit's EOF mock test left **zero** backend or grandchild processes alive, so it did not reproduce this failure. Host/OS containment or pipe behavior can mask it. The underlying Windows EEL containment was not established.

**Impact:** Surviving descendant trees across reconnects, restarts or crashes can consume system commit beyond the host's tracked process count. Node's child `kill()` is not a portable process-tree ownership guarantee. See [Node child-process documentation](https://nodejs.org/api/child_process.html).

### F4 — Medium: backend failure leaves stale objects and may leave a live process

**Source:** [cps-router.js:244](../router/cps-router.js#L244), `backendFailed()`; error/exit listeners in `backendFor()`; `request()`.

**Status:** Confirmed. Failure rejects pending RPCs and removes upstream mappings, but neither evicts the backend nor disposes its virtual/auth/session references. Input-pipe failure can mark a backend failed while its process is still running. A timed-out create request can later create a provider session whose response is discarded as unmatched.

**Reproduction:** Close backend stdin while keeping the process alive; crash a segment; delay `session/new` past timeout. Existing same-key calls reuse the stale entry rather than a healthy backend.

**Impact:** Retained objects/pipes and potentially a useless live tree; inability to recover without a new Router. Eviction alone is insufficient: dedicated live trees must also be stopped.

### F5 — Medium: capability negotiation and previews retain additional instances

**Source:** [cps-router.js:448](../router/cps-router.js#L448), `initialize`; its unassigned branch at line 465; [virtual-sessions.js:501](../router/virtual-sessions.js#L501), `targetCapabilities()`; Kotlin `CurrentChatProfileSwitch.choose()` estimate loop.

**Status:** Confirmed behavior; partly intentional caching. New virtual chats retain both negotiation and segment backends. Preview caches are bounded by target profiles/fingerprints per Router, but have no expiry. Unassigned initialization starts every configured profile for a local setup chat.

**Reproduction:** Acquire new chats; open/cancel switch previews; acquire an unassigned connection. The single-chat harness observed two backends before any switch.

**Impact:** Multiplies baseline process/native-resource cost across chats/projects. Repeated estimates with the same preview key reuse one backend, so 21 estimates do not imply 21 launches. These caches are not all leaks, but their lifetime and budget are excessive/unexplained.

### F6 — Medium: session close does not release Router state

**Source:** [virtual-sessions.js:713](../router/virtual-sessions.js#L713), `handle()`; `close()` at line 778; [cps-router.js:564](../router/cps-router.js#L564), generic session forwarding.

**Status:** Confirmed Router behavior; ordinary UI-close retention depends on the host. There is no per-session release of `active`, `sessions`, `leases`, authentication bindings or unused backends. Forwarded provider `session/close` acknowledgements do not change that. `VirtualSessions.close()` only removes lease directories at Router shutdown.

**Reproduction:** Send `session/close` after switches while leaving the transport alive. The harness received success and still counted **8 backends**. This does not prove the host sends close when hiding a panel; native disposal can instead stop the whole Router.

**Impact:** Whole conversation history and backend trees persist until transport termination, especially in a host that reuses a transport for several sessions.

### F7 — Medium: unbounded histories and repeated full snapshots

**Source:** [virtual-sessions.js:92](../router/virtual-sessions.js#L92), `save()`; `fromBackend()` at line 313; `prompt()` at line 668; `restore()` and `control()` clone branches.

**Status:** Confirmed growth policy; workload-dependent exhaustion. All public reply chunks and supported attachment data remain in `exchanges`. Each changed streamed update can stringify, synchronously write and fsync the entire conversation. Switching deep-clones it, while old backends retain prior snapshots. Transfer limits bound the outgoing handoff, not stored history.

**Reproduction:** Long replies, repeated tool updates or large image/audio attachments over many turns/switches.

**Impact:** Growing Node heap/private commit, transient serialization copies, increasing disk writes and event-loop stalls. Durable user history is intentional; multiple live copies and full rewrites per chunk are avoidable amplification. No history should be deleted to solve it.

### F8 — Medium: communication buffers and outstanding RPCs are unbounded

**Source:** [cps-router.js:14](../router/cps-router.js#L14), `send()`; `request()` at line 233; `fromBackend()` upstream handling; readline interfaces at lines 284/612; [virtual-sessions.js:313](../router/virtual-sessions.js#L313), suppressed updates.

**Status:** Confirmed absence of bounds; memory exhaustion is conditional. Stream writes ignore backpressure. JSON lines have no size limit. Suppressed startup notifications have no byte/count cap. Prompt/auth/extension requests can be untimed, and upstream client-request mappings have no deadline. A provider that remains alive but stops responding can retain these indefinitely. Cancel does not independently complete a stuck prompt.

**Reproduction:** Slow/stalled IDEA reader; oversized unterminated line; provider floods notifications during startup; unanswered provider requests; nonresponding prompt/cancellation.

**Impact:** Node buffers, retained request closures/transcripts and stuck busy state. The Router forwards stderr rather than retaining a full transcript, but its writable buffer can still grow. Proper producers must respect `write()`/`drain`; see [Node streams](https://nodejs.org/api/stream.html).

### F9 — Medium: login spawn error leaves an active interval

**Source:** [cps-router.js:109](../router/cps-router.js#L109), profile-login child error handler and monitor.

**Status:** Confirmed. Spawn error sets `exitCode`, while the referenced 100 ms interval is cleared only on `exit`. Failed spawning need not emit `exit`. The wrapper can remain alive doing revision reads.

**Reproduction:** Terminal login executable/cwd becomes unavailable after validation.

**Impact:** Retained Node wrapper, handles and repeated filesystem activity per failed login. Monitor-based tree termination also launches asynchronous `taskkill` without an in-progress stop guard.

### F10 — Medium: Kotlin setup cleanup has exceptional/race gaps

**Source:** [ProfileDefaultsProbe.kt:27](../src/main/kotlin/dev/boyko/aiagentswitcher/ProfileDefaultsProbe.kt#L27), `Connection.init`, `close()`; [ProfileSetupDialog.kt:76](../src/main/kotlin/dev/boyko/aiagentswitcher/ProfileSetupDialog.kt#L76), `authorizeNow()` and `dispose()`.

**Status:** Confirmed missing guarantees; leaked process/thread outcome is a hypothesis. Probe construction catches exceptions by deleting temporary files without stopping an already-started child. `close()` enumerates descendants once, then kills the Router: descendants launched after enumeration can escape. Stream closure is not protected by one outer cleanup `finally`. The common-pool reader blocks in `readLine`; cancelling a `CompletableFuture.runAsync` does not itself establish that the reader has terminated. The size check happens after reading the complete line.

Dialog disposal can race with terminal handler creation/publication or a replacement connection. `start()` has a closed-after-publication check, but the terminal/replacement branches do not provide the same final ownership handoff guarantee.

**Reproduction:** Failure after process start; cancel/close authorization at the exact terminal or replacement-connection publication boundary; child creates another descendant during cleanup.

**Impact:** Setup/login trees or blocked reader tasks can survive a disposed dialog. Normal `use`/dialog cleanup paths do attempt explicit descendant termination and should be preserved.

### F11 — Medium: unlimited JVM presentation event queue

**Source:** [ProfileResponseHeaderProvider.kt:63](../src/main/kotlin/dev/boyko/aiagentswitcher/compat/ProfileResponseHeaderProvider.kt#L63), `createComponent()`.

**Status:** Confirmed unbounded queue; runaway growth is a hypothesis. Producer subscription starts before waiting for the AI Multi catalog entry. `Channel.UNLIMITED` permits unlimited events while that prerequisite never arrives or EDT processing falls behind.

**Reproduction:** Missing/stalled catalog entry while events arrive, or sustained heavy event traffic while EDT is blocked.

**Impact:** JVM heap retention and additional committed heap capacity. The channel and coroutines do use the supplied scope; there is no evidence they create an unbounded number of native threads. This is not evidence for the incident's `Chunk::new` failure.

### F12 — Medium: synthetic per-chat toolbar cache entries lack release

**Source:** [ProfileToolbarIsolation.kt:104](../src/main/kotlin/dev/boyko/aiagentswitcher/compat/ProfileToolbarIsolation.kt#L104), `cacheId()`/`scoped()`; [ProfileCatalogPresentation.kt:16](../src/main/kotlin/dev/boyko/aiagentswitcher/compat/ProfileCatalogPresentation.kt#L16), `publishLegacy()`.

**Status:** Confirmed publication without AI Multi eviction; retained contents require runtime sizing. Each chat publishes `acp.ai-multi-chat-<uid>` into native project configuration/model/mode managers. Inspected managers contain ID-keyed maps; no corresponding synthetic-entry removal appears in the plugin. Native session override clearing is a separate cleanup path.

**Reproduction:** Open/close many distinct chats in one long-lived project. Multiple calls to `scoped()` also create duplicate collectors polling every 100 ms within their supplied scopes.

**Impact:** Project-lifetime JVM metadata/catalog growth and redundant polling. It is not necessarily a retained backend process. A fix must preserve chat-local presentation isolation and remove only AI Multi synthetic entries.

### F13 — Medium: profile home isolation is not enforced for every configuration

**Source:** [ProfileHomes.kt:26](../src/main/kotlin/dev/boyko/aiagentswitcher/ProfileHomes.kt#L26), `environment()`; [ProfileAssignments.kt:30](../src/main/kotlin/dev/boyko/aiagentswitcher/ProfileAssignments.kt#L30), `validateProfile()`; `AgentProfiles.replace()`; `GenericAutoRegistration.saveProfiles()`.

**Status:** Confirmed validation gap; cross-account contamination is conditional and not observed in current routes. Agent type is inferred from agent ID/name. Blank-home custom/legacy configurations do not get a managed variable and may inherit `CODEX_HOME` or use global defaults. Two persisted/advanced profiles can point to the same directory; validation does not check canonical uniqueness, junction/symlink aliases or an explicit global-home selection.

**Reproduction:** Custom Codex command lacking a recognized agent identity and explicit `CODEX_HOME`, or two entries configured with the same effective home.

**Impact:** Authentication/configuration/session-store sharing; potentially conflicting mutable state. Current Personal/Work routes pass the inspected separation checks. Grok's variable is correctly passed, but provider adherence was not monitored. Profile-specific native preference isolation is also incomplete because `ProfileAgentPreferences` intentionally persists via the underlying agent ID; creation-time explicit pins currently mitigate selection crossover.

### F14 — Low: one plugin coroutine scope outlives its intended owner

**Source:** [ChatProfiles.kt:76](../src/main/kotlin/dev/boyko/aiagentswitcher/ChatProfiles.kt#L76), `ChatProfileSwitch.scope`/`launch()`; `GenericAutoRegistration.background()`.

**Status:** Confirmed unmanaged scope; sustained leak is a hypothesis. The singleton `SupervisorJob` is not attached to a service/project disposer. Pooled registration work is finite but similarly not explicitly cancelled when its originating project closes. Some callbacks check project disposal, rather than cancelling the work itself.

**Reproduction:** Close a project or unload the plugin while a project-selection/chat-open operation waits.

**Impact:** Retained project/classloader references and possible delayed UI/configuration work. Most other inspected UI coroutines correctly use supplied scopes.

### F15 — Low: durable registries and lease recovery have no maintenance policy

**Source:** `ProjectRoutes` in [AutoAgent.kt](../src/main/kotlin/dev/boyko/aiagentswitcher/AutoAgent.kt), `ResponseProfiles.remember()/recover()`, Router owner publication, `VirtualSessions.acquire()`/`handle(session/list)`.

**Status:** Confirmed durable growth; mostly intentional storage. Root assignments, response attribution, session-owner files and conversation files are not removed on chat close. Listing/restoring repeatedly scans and parses stored conversations. A crash between lock-directory creation and writing `owner.json` leaves an unreadable lease that later acquisition refuses. A stale lease uses PID liveness alone, so PID reuse can also block recovery.

**Reproduction:** Long-lived history/project collection; interrupt lock creation; stale PID later belongs to another process.

**Impact:** Disk growth, temporary parsing allocations, slower listing and blocked restoration. This does not establish a live process leak. Maintenance must preserve history/credentials and distinguish durable user data from ephemeral leases.

### F16 — Low: ownership diagnostics cannot explain incident process totals

**Source:** Router `log()`, `backendFor()`, `backendFailed()`, `shutdown()`; Kotlin probe error redirection.

**Status:** Confirmed observability gap. No structured create/stop records correlate PID/parent PID, profile, agent, session/segment and reason. Setup stderr is discarded. Router passes raw provider stderr through, so adding unrestricted stderr logging would risk recording authentication material.

**Reproduction:** Any reconnect, switch or crash investigation.

**Impact:** Cannot reliably separate legitimate transports, retained segments, agent recovery, or unrelated Codex tools after the fact. Missing metrics hinder proving fixes and attributing commit consumption.

### Resources that appear correctly managed

Application registration listeners use a disposable bus connection and explicitly remove static listeners. `ChatSwitchUi` registers panel/project disposers and closes its overlays. Settings disposal stops the preset timer and cancels its loading work. Native toolbar extension masking is tied to its disposable service. Profile preference/startup maps use weak keys. Many filesystem channels/streams use `use`/`finally`.

The Router's authorization monitor is `unref()`'d and cleared during normal shutdown. It still performs synchronous revision checks every 100 ms per cached backend, so stale backend growth also increases I/O/CPU work. No plugin-created unbounded executor/thread pool, TCP listener or socket registry was found. Ordinary ACP transport uses stdio; tool/MCP connections are delegated to host/provider code. No evidence supports attributing all 137 JVM threads to this plugin.

### External agent cleanup caveat

Installed `@agentclientprotocol/codex-acp/dist/index.js`, `startCodexConnection()` around line 27850 and `CodexAppServerSupervisor` around lines 38036–38224, performs its own app-server recovery. Its termination sequence ends stdin, later signals the immediate process, and can eventually abandon waiting/close pipes if it still has not exited. ACP stdin close calls supervisor shutdown near line 45684. The Codex JS wrapper forwards signals to its native child.

These are useful cooperative cleanup mechanisms, not AI Multi-owned implementation defects. They do not establish hard Windows tree containment when an intermediate wrapper is forcibly killed. Recovery/restart overlap is an additional provider-dependent hypothesis to test; do not count every app-server launch as a Router duplication.

## C. Incident assessment

### Independent Windows evidence

Event 2004 at **14:37:58 local** reports:

| Metric | Event value |
|---|---:|
| System commit limit | 137,186,222,080 bytes (127.76 GiB) |
| System commit charge | 137,086,205,952 bytes (127.67 GiB) |
| Remaining commit | 100,016,128 bytes (95.38 MiB) |
| Physical memory size | 34,107,006,976 bytes |
| Physical memory usage | 28,267,139,072 bytes |
| Reported process commit charge | 32,837,234,688 bytes (30.58 GiB) |
| Paged / nonpaged pool usage | 2,206,912,512 / 1,999,319,040 bytes |

The event snapshot's headroom differs from the request's approximately 66 MB; these are different readings near a changing exhaustion boundary. The event lists MySQL PID 6040 at 4.37 GiB, IDEA PID 31960 at 3.17 GiB, Oracle PID 6304 at 2.00 GiB, DWM at 1.74 GiB and WebView2 at 1.35 GiB. Node/Codex do not appear among those five **individual** processes. Their aggregate contribution could still matter.

The large difference between reported system and process commit means the listed application processes are not a sufficient accounting of the system total. It warrants collecting system/kernel/shared commit data; it does not identify a particular driver or other culprit by itself.

Windows commit capacity and available physical RAM are separate measurements. Allocation can fail near the commit limit while physical RAM remains available. See [Microsoft's commit/page-file guidance](https://learn.microsoft.com/en-us/troubleshoot/windows-client/performance/how-to-determine-the-appropriate-page-file-size-for-64-bit-versions-of-windows).

### Attribution

**Supported:** The crash is consistent with native/system allocation failure rather than conventional Java heap exhaustion. Confirmed retention of AI Multi-launched trees and transcript copies can consume system commit and plausibly amplify such pressure. Native resources of a Node/Rust child need not appear in IDEA's Java heap. Restarting IDEA can eliminate both host-owned and plugin-associated resources, so the reduction is compatible with several explanations.

**Not supported:** A measured pre-crash aggregate commit footprint for AI Multi, a proven surviving orphan population, monotonic growth attributable to this plugin during the incident, or identification of the JVM allocator subsystem from `Chunk::new` alone. The 1.13 GB heap and thread count do not identify the responsible consumer. MySQL/Oracle being named also does not prove they caused the exhaustion.

**Assessment:** AI Multi is a plausible contributing factor with independently fixable lifecycle defects. It is not an established root cause. The near-saturated system commit, event's accounting discrepancy and other applications require investigation alongside the plugin. Increasing JVM `-Xmx` would not address these cleanup defects and could consume more commit.

## D. Proposed fixes

Preserve plugin ID `dev.boyko.ai-agent-project-switcher`, ACP ID `acp.ai-multi`, the independent profile abstraction, chat-local profile switching and project Default Profile semantics. Preserve all authentication and durable sessions.

| Priority | Change | Complexity | Expected benefit |
|---|---|---|---|
| 1 | Transactional backend acquisition/retirement; rollback every failed start/create/restore/preset/save | Medium | Stops stalled unique-key retries and failed provisional sessions |
| 1 | Retire obsolete segments after switch commitment; bounded previous-segment retention only while rollback remains possible | Medium | Removes growth proportional to historical switches |
| 1 | Unified awaited cleanup for EOF, errors, revocation, close and shutdown | Medium | Clears maps, pending RPCs, readline/streams and verifies owned tree exit |
| 1 | Windows hard containment for each owned backend tree | Higher | Survives intermediate-parent death and abrupt Router termination |
| 2 | Reuse/release negotiation backends safely; expire previews; local unassigned initialization without launching every profile | Medium | Reduces per-chat baseline and cancelled-preview cost |
| 2 | Explicit virtual session release independent of durable-history deletion | Medium | Stops retention when a host keeps its transport alive |
| 2 | Stream backpressure, bounded framing/queues, request deadlines and cancellation escalation | Medium | Prevents runaway buffers and permanently retained operations |
| 2 | Append/incremental history persistence with bounded in-memory working set | Higher | Reduces transcript copies, serialization spikes and fsync per chunk |
| 2 | Setup/login cleanup guards, scope-bound readers and close-race handling | Medium | Removes failed-login wrappers and setup cancellation races |
| 2 | Validate effective profile homes and aliasing; verify native preference pins under concurrency | Low–Medium | Preserves auth/session/default isolation for advanced configurations |
| 3 | Scope-bound Kotlin operations and synthetic toolbar-cache eviction | Medium | Bounds JVM metadata and removes redundant collectors |
| 3 | Robust lease identity/publication and non-destructive maintenance/indexing | Medium | Reliable crash recovery and scalable history discovery |
| Cross-cutting | Structured ownership diagnostics and commit sampling | Low–Medium | Makes attribution and long-run regression checks possible |

Backend retirement should be idempotent and use an explicit state machine (`starting`, `ready`, `stopping`, `stopped`, `failed`). Reject pending RPCs, detach obsolete authentication/upstream/session references, clear timers, close communication interfaces and remove only the retiring entry. Retained history should reference identifiers/snapshots on disk, not an obsolete live backend object. Store provisional cleanup handles before awaiting initialization, so refresh failure cannot bypass `finally`.

For switching, keep the old segment available only until the new mapping is durably committed and any prepared-handoff rollback requirement is resolved. On rollback, stop the discarded target. On successful continuation, retire the old tree. For providers unable to restore sessions, define a bounded documented retention exception instead of silently retaining every segment. Do not resume/retry a prompt automatically: interrupted tools may already have changed the project.

### Windows containment

A Windows Job Object with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` can terminate member processes when its final handle closes, including default-inherited descendants. Use a non-inherited controlling handle and assign the child before it can spawn descendants; a suspended native launch followed by assignment/resume avoids that race. Verify nested-job compatibility with EEL/IDEA and disallow unintended breakaway. See [Microsoft Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects).

A per-backend job owned by a live Router addresses Router death. Protecting against IDEA death additionally requires a verified host-owned job or a launcher/supervisor whose handle closes when the host transport/parent disappears. A Router holding its own job handle while surviving IDEA is not enough. Establish current EEL behavior before adding overlapping mechanisms.

As an interim measure, use the existing PID-targeted tree helper consistently, await completion with a bounded deadline, check failures, and guard repeated stop attempts. This is weaker than job containment after the root exits or PID reuse occurs. Never terminate by executable name, all processes sharing `CODEX_HOME`, or stale PID alone. Some agent tools intentionally spawn external applications; define their ownership/detachment contract before placing all descendants in a kill-on-close job.

## E. Test plan

### Existing coverage and gaps

Gradle wires Router, virtual sessions, profile settings/setup, host profiles, conversation storage, credentials, presets and registration cleanup suites into `check`/`buildPlugin`. `test-generic-router.js` delegates to `test-router.js`.

Tests cover routing/fingerprints, fail-closed ownership, profile defaults, history transfer, interrupted-conversation restoration, concurrent Router lease exclusion, image capability checks, fixed-profile host behavior, revocation and storage sharing violations. These are valuable correctness tests. Their mock backends generally do not model persistent descendant trees, private commit, explicit release/expiry or long-duration workloads. Several suite cleanup paths kill the Router directly; the crash-recovery test proves recovery of history, not absence of surviving descendants.

### Audit harness results

| Scenario | Observed |
|---|---|
| Initialize and create one virtual chat | 2 live mock backends |
| Six alternating fresh-profile switches | 8 live mock backends |
| Forward and acknowledge `session/close` | Still 8 live backends |
| Close Router stdin | Router, backends and mock grandchildren all exited |
| Initialization timeout | Error returned; stalled backend remained alive |
| Final harness cleanup | 0 audit-owned PIDs alive |

These tests establish F1, F2 and F6 while deliberately limiting claims about F3. They used small mocks and do not quantify production Codex memory costs.

### Reproducible validation matrix

Run first with an isolated `runIde`, isolated user/config/system/profile homes, mock agents and a dedicated process containment harness. Test actual installed providers afterward with independent test homes. Never operate on primary-IDE active sessions or unrelated agent processes.

| Scenario | Procedure | Required result after fixes |
|---|---|---|
| 1. Open/close chats | 100 acquired chats; separately hide panel, dispose chat, reopen, and delete test history | Native disposal releases that chat's owned tree; hiding behavior follows documented reuse policy; counts return to defined baseline |
| 2. Switch Personal/Work | 100 alternations in one chat, both empty and completed-task cases; cancel previews/handoffs | Active trees bounded independently of switch count; rollback works; Default Profile unchanged |
| 3. Concurrent projects | 3–5 projects using same/different profiles; close one while others prompt | Only closed-project transports stop; no cross-profile events/state; same-profile home sharing remains deliberate |
| 4. Restart Router | Graceful EOF, native stop/reconnect, root-only abrupt kill, interrupted handoff | Old owned trees stop before replacement or are contained; restore history/settings without duplicate prompt execution |
| 5. ACP failures | Spawn failure, initialize timeout/error, provider exit, stdin failure, late `session/new`, preset/save failure, failed refresh, stalled cancel | Provisional instances/leases released; retry bounded; no accumulating `catalog:<UUID>` trees |
| 6. Close IDEA with sessions | Isolated normal shutdown during idle/prompt/auth/setup | All harness-owned processes, jobs, pipes and reader tasks reach zero after a defined deadline |
| 7. Force IDEA termination | Kill isolated IDEA only; also kill intermediate npm/ACP wrapper | Descendants reliably contained; stale leases recover; no prompt automatically resubmitted |
| 8. Commit/process monitoring | Sample every second throughout each scenario; repeat 30–60 minutes and an overnight soak | Stable retained-process envelope and stable fixed-workload private-commit baseline after warm-up |

Additionally test oversized lines, blocked readers, notification floods, unavailable native catalog, repeated UI creation, profile reauthorization and authorization revision change during active work. Exercise default setters and restoration concurrently across Personal/Work. Verify actual effective home/config/session locations through a test agent or sanitized provider response; use marker files in isolated homes, never inspect or log real token contents. Assert same native session remains active when changing Session Defaults/options.

### Diagnostics design

Emit structured lifecycle events with UTC timestamp, IDE instance/project identifier, Router instance UUID, PID/parent PID/process creation time, profile ID, agent type, backend instance/isolation purpose, virtual session ID, segment ID, provider session ID when available, lifecycle state, termination reason, exit code/signal, owned active/starting/stopping counts and cleanup outcome. Record launch purpose (`negotiate`, `session`, `preview`, `catalog`, `setup`, `login`) so intentional reuse is distinguishable from abandoned work.

Track pending/upstream RPC counts, byte-sized queued output/startup notifications, conversation working-set bytes, Node RSS/heap/external/array-buffer memory, process private committed bytes, handles and thread counts. For Windows process sampling use PID **plus creation time**, and include wrapper `cmd.exe` processes where present. Collect global `Memory\Committed Bytes`, `Commit Limit`, available physical memory and paged/nonpaged pool data. Sum private bytes only for proven AI Multi-owned trees; this sum does not represent all system commit or all JVM native memory.

For an isolated JVM, enable Native Memory Tracking at startup and compare summaries with process private commit; NMT does not account for external child processes or every third-party native allocation. Capture Event 2004 and the complete fatal JVM report for any repeat incident. Do not run a primary JVM diagnostic requiring restart without separate authorization.

Use an allowlist for diagnostic fields. Never log OAuth/API tokens, full environment maps, prompt/history content, terminal-auth metadata, proxy headers or unrestricted command arguments/stderr. Redact provider error details. Rotate diagnostic files with a size/retention cap. Keep ownership metrics separate from durable conversation storage.

Acceptance gates: zero audit-owned descendants after disposal/shutdown; no growth proportional to historical switches/failed refreshes; explicit limits for any retained preview/rollback instance; no stale provisional leases or pending RPCs; unchanged project Default Profile; separate effective Personal/Work homes; stable fixed-workload memory after warm-up. A growing transcript may legitimately grow durable storage, so test idle/disposed memory separately from deliberate history growth.
