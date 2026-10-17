# Chat startup and restoration (dev.66)

Native sandbox reproduction found two problems after a successful ACP load:

- AI Assistant resets its ACP converter's step-to-event mapping during history replay. Our local authentication preparation and its completion can consequently have the same step ID but different native event IDs. The response view kept two rows, leaving the original Preparing row animated. The AI Multi view now uses one presentation event ID per local authentication step, including cached reloads. Native history and provider notifications are unchanged; provider tools and other authentication steps are excluded.
- A valid restore response can omit configOptions even if session/new supplied them. ProfileAgentPreferences read the optional flow before checking configOptionsSupported, causing `Config options are not provided by the agent`. The guard now precedes access, including the before-prompt hook. Successful acquisition also completes its status before optional presentation synchronization.

New Chat previously bypassed the defaults preflight used by profile selection. Startup now checks the pending chat profile or project default before creating a new session, opens AI Multi Profiles Settings with that profile selected, and shows the Refresh agents defaults / Apply instruction. Explicit chat-local creation also checks before creating the chat. A stored native session bypasses this creation check; restoration does not require recapturing defaults or apply new-session defaults.

The primary Work profile had no defaultsAgentId or defaultsObservedAt; Personal had a snapshot for its selected Codex agent. This is missing saved configuration, not a time-based expiry or a requirement to refresh for every chat. No primary profile configuration or credentials were changed by diagnosis.

## Verification

`check buildPlugin --offline --max-workers=1` passed against the installed IDEA, AI Assistant and Air, with the installed JBR supplied as the Java 25 toolchain. Evidence: `build/restore66/build-release.log`. Native installer queue checks passed in `build/restore66/queue-checks.log`.

The native probe uses a synthetic local ACP process and native chat serialization, history storage, composer and response models. It creates a chat, then restores its serialized native chat in a later isolated run. Its load response omits optional configOptions; the probe checks that the acquired session reports no support, sends through that chat's actual associated input, and checks the native view's authentication blocks are COMPLETED and converter-reset event IDs do not create duplicate rows. It also opens a native New Chat with a missing defaults snapshot, verifies the Settings profile selection and confirms no client session was acquired before refresh. It cancels Settings without refreshing or contacting a real provider.

Sources: `tools/ChatStartupProbe.java`, `tools/ChatStartupSupport.java`, `tools/chat-startup-fixture.js`. Compile the Java sources with `tools/SettingsRefreshProbe.java`, package a javaagent with `Premain-Class: ChatStartupProbe`, and run through the existing runIde `uiProbeAgent` option. The probe directory must be a direct child of build; sandboxHome must equal its home subdirectory. Copy the fixture to backend.js there. The first invocation seeds native-state.xml; subsequent invocations restore it. The probe intentionally refuses the primary IDE's home.

The IDE debugger had no active session and exposed only a ProfilePresetsChecks launch configuration, so runtime evidence was collected through the isolated instrumentation probe rather than attachment to the primary IDE. No primary restart, provider prompt or active-chat interruption was performed. Successful synthetic reproduction does not establish the exact optional-options response received by the user's particular production chat.

The final native run passed (`build/restore66/native-final.log`, `result.txt`, `refresh-result.txt`): six converter-reset status updates merged, all displayed authentication blocks completed, a prompt sent after restore without optional config options, and New Chat opened the correct Settings profile without acquiring a session. The final probe produced no error.txt. Packaged native classes and all six router resources were checked; diagnostic classes are excluded.

Dev.66 is queued through the primary IDE's native installer. Backup: `deployment-backups/20261016-095602-0.2.0-dev.66/AIMulti-before-update.zip`. Distribution, staged archive and deployment record SHA-256: `52EEE18941AC4506E31962244E5F04D657766B69693E7B90EB2C041DBFC75E28`. Unrelated queued updates were preserved. Activation awaits the user's next IDEA restart.
