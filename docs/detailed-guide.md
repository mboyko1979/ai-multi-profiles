# AI Multi Profiles: detailed guide and developer reference

[Back to the quick start](../README.md)

AI Multi Profiles provides project defaults and chat-local profile selection in AI Assistant and Air. New Session keeps the native composer and draft while selecting profiles; returning to a prepared profile reuses its native session. The project's **Default Profile** stays unchanged. See the [current architecture](native-client-architecture.md) and [decision log](decisions.md).

Development build **0.2.0-dev.65** uses AI Assistant/Air's native local history. AI Multi stores profile/agent identities, provider session IDs, native history references, switching boundaries and configuration/status metadata, without a second message archive. Transfers read selected native history on demand. A deleted profile offers another-profile selection or local read-only viewing; a changed agent requires explicit recovery consent. Existing sessions retain provider-owned settings. See [native history and restoration](native-history-storage.md).

Development build **0.2.0-dev.74** provides independent **AI Assistant** and **Air** defaults. A new profile has no saved defaults: the client and agent choose their normal session settings. **Load Agent Defaults** explicitly loads concrete values for every model. The model list supports ordering, enable/disable and a single default model; disabled models stay visible in Settings, grey and struck through, and are omitted from chat choices. Selecting a model in Settings edits its parameters without making it the default. Hide old or unused models from the picker and arrange the enabled models in your preferred order, for example placing your most-used model first.

**Refresh Models** preserves existing order, enablement and chosen values, prepends new enabled models, and marks unavailable values **Deprecated**. Apply rejects invalid enabled model settings. If the saved default model disappeared, explicit Refresh selects the agent's current default model and reports the change. **Reset Default Model** and **Reset defaults for this model** use the last explicitly loaded agent defaults without refreshing. **Remove Defaults** clears only the selected client, returning to Load Agent Defaults. A deprecated startup setting opens the affected profile/client/model with its exact cause and holds that chat's creation pending. Apply continues the same backend session; closing Settings does not create a replacement or silently fall back. Removing defaults during correction rolls back partial profile setters to that session's original values.

Old chats restore their last committed profile/agent segment with the client's exact load/resume method. Today's profile defaults are never reapplied during restore. The configured model list controls presentation without replacing a disabled current model. Agent changes require explicit recovery consent; original session IDs never go to a replacement agent. Choosing a profile in chat keeps the project Default Profile unchanged.

Refresh starts a separate ACP connection in a temporary clean home with account authorization only, excluding existing model caches, configuration and chat history. No prompt or MCP connection is sent. The common refresh mechanism uses no agent IDs: it requires `auth.json` authorization and a newly fetched JSON model catalog with `fetched_at` and nonempty `models`. Agents with other credential or catalog formats need additional refresh support. If freshness cannot be confirmed, refresh reports an error and retains the previous profile defaults. Unsupported custom provider configuration is also reported rather than silently changing providers. Temporary authorization is removed after the probe.

Optional instructions can be entered as text or loaded from Markdown for the new session's first prompt. Codex profiles also offer an editor link to their native `AGENTS.override.md` / `AGENTS.md`. Codex account identity is discovered from the profile's actual `CODEX_HOME` and explicitly distinguished from verified login. See [implementation and verification](profile-presets.md).

Independent IntelliJ Platform plugin for project defaults and switching ACP profiles within an AI Multi conversation.

Development build **0.2.0-dev.12** preserves the original chat title during profile continuation and repairs old `AI_MULTI_HANDOFF:` titles when those chats open. Conversation saves retry transient Windows sharing violations; a persistent write failure preserves the committed history and the latest pending snapshot and reports the error without crashing the router.

**To enable automatic project-based routing, select “AI Multi” in the JetBrains AI Chat agent dropdown.** Selecting Codex or another agent directly bypasses automatic routing. The project assignment is the default profile for new chats.

## Setup

Requires IntelliJ IDEA build 262.10968 or newer, AI Assistant or Air, Node.js and ACP v1-compatible agent executables. The first publication is planned for the Marketplace `beta` channel. Adapters were checked against AI Assistant 262.10968.223; a newer IDE can change those internal APIs.

1. Install the ZIP using Settings → Plugins → Install Plugin from Disk. Updating the plugin may require restarting IDEA; finish active work first.
2. Open Settings → Tools → AI Assistant → AI Multi Profiles.
3. Install your agent in AI Assistant → Agents. Add a profile with a name and choose the installed agent. Use **Refresh agents** after installing or enabling an agent.
4. Set **Default Profile** and Apply. Apply does not open a chat.
5. Select **AI Multi** in AI Chat and start a new session.

For example, create **Codex Personal**, choose **Codex**, and click **Authorize…** on the profile page. The sign-in window lists the agent's advertised login methods and has no chat prompt input. After authorization, optionally use **Load Agent Defaults** in the desired client tab, then **Apply**. Create another profile for your company account and authorize it separately. **Refresh models…** in the chat model menu reloads the available options without changing saved profile defaults.

The agent dropdown calls the same row provider as Settings → AI Assistant → Agents and includes only rows marked installed, in the screen's order. It does not add ACP registrations or profiles from CodexProjectSwitcher. Custom commands remain available through Custom ACP (Advanced). Apply uses the selected installed agent's native launch configuration to obtain the executable, arguments and runtime environment. Uninstalled or disabled agents are excluded; the list is not hardcoded. Grok receives its own `GROK_HOME`, matching the installed Grok launcher's home-directory mechanism.

On Apply, the plugin creates a separate directory under `~/.jetbrains/ai-agent-project-switcher/profiles/`. **Codex Personal** becomes `codex_personal`; existing directories cause an atomic reservation of `codex_personal_1`, etc. Renaming retains the existing directory, duplicating creates a new one, and removing a profile retains its files. Installation binaries are shared with AI Assistant; authentication files are not copied from existing accounts. Codex receives the dedicated `CODEX_HOME`. Claude receives `CLAUDE_CONFIG_DIR`; home, XDG and Windows user-data environment paths are isolated for other local agents. Agents that use a system credential store or their own fixed paths may need agent-specific isolation support; Grok receives a dedicated GROK_HOME; its ACP initialization was checked with an empty profile, while completed account login remains unverified.

**Advanced** contains the storage path and optional launch overrides. Existing custom-command profiles remain supported there. Installed-agent profiles resolve their native launch configuration again on Apply. Native terminal login is routed to the same profile executable and isolated environment; stale requests are refused after changing the project assignment or profile configuration. Authentication is interactive and is not performed during Settings Apply.

Arguments use normal command-line quoting in Settings. Existing arguments stored as one argument per line retain that interpretation. Launching uses an argument array with no shell. On Windows, select an executable such as `node.exe` with the adapter script as an argument, rather than a `.cmd` wrapper.

## Selecting profiles

Settings → AI Multi Profiles reads the current native ACP installation store when refreshing agents, including installations and removals made through Air after AI Assistant started. A saved profile whose agent was removed stays editable and shows a warning to refresh; authorization is disabled until the agent is available. Refresh agents displays progress and a completion count. Registration Refresh and Repair Registration are mutually exclusive while running; Repair finishes only after the registration operation completes.

The AI Assistant compatibility adapter uses `com.intellij.aiChatToggleProvider` and its native `AiChatToolbarControl.Selector`, with bottom placement. The inspected AI Assistant **262.10968.223** builds this area in the order agent → regular selectors → model. The adapter is isolated in `compat/`; this is an internal API, not a stable public JetBrains contract. See [development verification](verification.md) for actual runtime validation and remaining limits.

The chat menu contains configured profiles and **Manage Profiles…**. In an existing AI Multi chat it shows **Current Chat Profile**. Choosing another profile opens **Switch AI Profile** with **Continue Current Chat** or **Start a New Chat**. Both choices leave the project's **Default Profile** unchanged. Ordinary new chats still use that default. Settings Apply and the Tools → **AI Multi: <profile>** project menu control the default.

The profile selector is disabled while a native task is active. Wait for its response to finish, or use the chat's Stop button before changing profiles. The switch dialog does not cancel tasks. Cancelling the dialog leaves the current profile and model unchanged. Native model and configuration controls use a separate presentation cache for each AI Multi chat.

Before opening another chat from an AI Multi chat with no submitted messages and a nonempty prompt, **Unsent prompt — AI Multi** offers **Stay in This Chat** (default) or **Open New Chat**. Staying preserves the chat and its draft; opening proceeds only after that explicit choice. Nothing is sent automatically. Empty prompts and chats with submitted messages do not trigger this warning. This protects the native new-chat selector, its agent choices, the older new-chat actions, and AI Multi's profile-specific chat creation. The compatibility adapter targets AI Assistant 262.10968.223.

AI Multi shows the native Preparing and configured-authentication status at startup for any supported local ACP profile. The startup card and response cards show AI Multi, followed by separate profile/provider icons and the profile name. There is no permanent profile banner above the input.

Start a New Chat creates an unfocused native chat, records a short-lived profile request bound to its exact chat UID/project/configuration hash, and then focuses it. ACP initialization uses that chosen profile from the start. This also works when the project default is unassigned or its agent cannot create more sessions. The one-time request is consumed only after the new virtual session is saved.

Continue selects 0–20 recent user messages (5 by default), capped at the saved history count. Messages injected during an active task count separately. The native count spinner and compact JBSlider stay synchronized; the usage slider shows the context level actually attainable with the available history. The first public agent response and complete final response are retained, with intermediate public updates omitted when their boundaries are known. Legacy answers without reliable boundaries remain complete. Hidden reasoning is excluded. Optional **Additional instructions** become an ordinary user message. Leaving the field empty continues the latest task without adding a user message. The router sends its handoff immediately through AI Assistant's native task runner: progress, replies, tool permissions and cancellation stay in the visible chat. The internal transcript/prompt is not displayed as another message. Explicit confirmation is required to share history and project data with the new provider.

The dialog estimates context size conservatively and validates it before sending. It offers up to 20 native user turns and retained native attachment references accepted by the target. Public text links remain text; unavailable attachment payloads are not fabricated. Hidden reasoning and raw tool output are excluded. The complete local transcript remains owned by AI Assistant/Air.

Reviewed screenshots are included in the [quick start](../README.md#quick-start). Other local development screenshots remain withheld pending privacy review.

New chats have a stable virtual ACP session ID while successive backend sessions retain immutable profile/account ownership. The conversation's current profile persists across reopening and restarting IDEA. Previously saved native chats stay in legacy mode: their owner is preserved and the dialog offers a separate new chat when no complete router transcript exists. The previous agent title **AI Auto (Project Switcher)** is renamed to **AI Multi** on startup, and existing chats that used it are retargeted to `acp.ai-multi`.

## Session safety and limitations

The router persists immutable ownership records separately for each session under `~/.jetbrains/ai-agent-project-switcher/session-owners/`. Atomic creation avoids different IDE processes overwriting ownership. Records bind session ID, project directory, profile ID and launch-configuration fingerprint, including the effective environment and launch directory. Secrets are hashed, not copied into ownership records.

Virtual session links are saved under `conversations/` using flushed temporary files and atomic replacement. An exclusive process lease prevents two routers from rewriting one binding. Version 2 stores profile/agent/session links, native source boundaries, configuration and task status. It stores no message or attachment payloads, including in failed-write snapshots. Legacy records are compacted on successful update. Native history stays in AI Assistant/Air. An undelivered transfer is cancelled after disconnection and needs fresh selection/consent; no prompt is automatically repeated.

Unknown sessions, conflicting IDs, removed profiles and changed launch configurations are refused. **Pre-0.2.0 session maps do not prove the launch configuration and are not automatically trusted. Start a new chat for those sessions.** Existing files and the older CodexProjectSwitcher plugin are left unchanged.

Keep separate authentication directories for separate accounts. The switcher cannot detect a manual login/account replacement inside an unchanged agent authentication directory. Do not change accounts in a profile's existing home directory while retaining chats; create a separate profile and home instead.

For an assigned new chat, initialization retains the selected profile's actual capabilities, agent information and extensions. Reopened chats use the last committed profile/agent segment. Target prompts are validated against the actual target attachment capabilities. Without an assignment, the local setup connection uses conservative shared capabilities. Initialization/session setup has a bounded timeout; normal prompt requests may run longer.

The router negotiates ACP v1 and preserves the native client's selected load/resume method, provider replay, capabilities, extensions and errors. Local combined history remains in AI Assistant/Air. The last committed profile and agent identify the session to restore; changed settings or environment do not replace a registered agent's identity. Defaults apply to new provider sessions. An unavailable model is resolved by the provider/native client rather than substituted by AI Multi. Provider-supported list/fork/delete keep their actual capabilities and ownership checks. A v2-only agent is unsupported.

AI Assistant 262.10968.223 caches attachment support globally by agent ID using `computeIfAbsent`. Restart IDEA after updating to clear AI Multi's old cached capabilities. The router's capabilities now match the selected profile, but this JetBrains cache can still show the first initialized AI Multi profile's attachment choices when profiles with different image support share one IDE process. Clipboard insertion and complete UI parity must be validated in the running IDE; protocol verification alone does not establish them.

When no profile is assigned, AI Chat shows ordinary explanatory text with a clickable **Open Settings** link. The link is supplied by the native IDE chat panel extension, not by ACP. The router opens a local setup session that forwards no prompts, attachments or tool requests to an account. Selecting a profile does not convert that setup session: **start a new chat** to use the selected profile. Setup sessions remain local when restored. Real startup, configuration and ownership failures still produce errors.

Environment variables are stored in local IDE settings and router configuration, not a password vault. Avoid storing secrets in the repository. The environment dialog's inherit-parent-environment choice is honored.

## Registration and troubleshooting

Since **0.2.0-dev.11**, disabling or uninstalling AI Multi removes its unchanged, manifest-owned registrations, including its previous AI Auto name. Re-enabling restores AI Multi when a project is available. Ordinary IDE shutdown and plugin updates keep registration. Cleanup saves an exact backup of `acp.json`, uses the same configuration lock and atomic replacement as registration, and leaves credentials, profiles, routes and conversation history intact. Manually modified entries are preserved.

AI Multi also cleans up unchanged **Codex - Personal (CPS)**, **Codex - Work (CPS)** and **Codex - Auto (CPS experimental)** registrations after the older **Codex Project Switcher** is disabled, unloaded or uninstalled, or when opening a project while CPS is absent/disabled. It compares them with CPS's saved router command and profile homes; their names alone do not prove ownership. Keep AI Multi enabled for this legacy cleanup. If both plugins have already been removed, back up `~/.jetbrains/acp.json` and remove the obsolete entries manually. Authentication folders do not need to be deleted.

The plugin maintains one **AI Multi** entry in `~/.jetbrains/acp.json`, shared by AI Assistant and Air. Profiles are internal AI Multi settings and are not published as separate agents. AI Chat provides the profile dropdown; selecting a chat profile leaves **Default Profile** unchanged. Startup removes old **AI Multi · <profile>** aliases only when they exactly match the saved ownership manifest. Unrelated agents and manually changed entries are preserved. A previous plugin-owned **AI Auto (Project Switcher)** entry is renamed. Outdated owned entries can be repaired automatically; conflicting or manually changed shared entries require confirmation through **Repair Registration…**. Writes are serialized, backed up and atomic.

Changing a project profile only updates routing configuration; it does not re-register the ACP agent. The plugin requests a VFS refresh, which AI Assistant's inspected configuration service observes. Reopen the agent menu to check the result. Restart only if the running AI Assistant still does not pick up the registration; a refresh request alone does not prove an in-memory reload.

Multiple project windows in one IDEA process were tested. Independent IDEA processes serialize file writes and merge project roots; simultaneous global profile editing across processes has not been validated.

If no profile is selected, choose one; the plugin never silently selects a default. If an executable fails, check its path, arguments, working directory, inherited PATH and Node.js availability. Diagnostics go to IDEA notifications/log and ACP stderr; stdout is protocol-only.

## Development

Use JDK 21 and the Gradle wrapper:

```powershell
$env:JAVA_HOME = 'C:\jdks\jdk-21.0.4.7-hotspot'
.\gradlew.bat buildPlugin -PnodeExecutable='C:\Program Files\nodejs\node.exe'
```

The Java verification suites also use Java 25 from the tested IDE runtime. For offline verification, provide `-PidePath=<IDEA-2026.2.3>` and `-Porg.gradle.java.installations.paths=<IDEA-2026.2.3>/jbr`, then run `check buildPlugin`. The distribution is `build/distributions/ai-multi-profiles-0.2.0.zip`.

To compile against an installed AI Assistant, add `-PaiAssistantPath='C:\path\to\plugins\ml-llm'`. Otherwise Gradle resolves the pinned plugin from Marketplace. Router regression tests are required by both `check` and `buildPlugin`; run them separately with `node router/test-router.js`.

`runIde` uses an isolated `build/sandbox-home` for both the IDE home and switcher files. It does not install the ZIP into your working IDEA. Avoid copying personal or company authentication data into a test sandbox.

This is an independent community plugin by boyko.dev, not an official JetBrains or OpenAI product.

AI Multi has original SVG artwork: an AI sparkle beside overlapping profile silhouettes. The plugin page uses a color logo; the agent dropdown uses a monochrome variant with light/dark theme support. Profile choices show **profile icon · installed agent icon · profile name**. Provider icons come from AI Assistant's installed agent entries by ID, without a hardcoded provider list; Custom ACP without its own icon uses the generic AI Multi icon. See [branding assets](branding/README.md).

Deployment is manual while the Plugin ID migration is pending; see the [migration plan](plugin-id-migration-2026-10-18.md). Do not install both identities together.

## Source, identity and license

Source: https://github.com/mboyko1979/ai-multi-profiles

Marketplace Plugin ID: `dev.boyko.ai-multi-profiles`. ACP registration remains `acp.ai-multi` (**AI Multi**). Version **0.2.0**, channel **beta**. The new Plugin ID is a separate plugin to IDEA; existing development installations need a separately approved [migration](plugin-id-migration-2026-10-18.md).

Local checkout: `C:\projects\ai-multi-profiles`. Gradle root project: `ai-multi-profiles`. Existing packages, persistent state names and user storage paths are preserved for compatibility.

License decision pending. No open-source license has been granted. Public source availability alone does not grant permission to copy, modify or redistribute it. Existing third-party notices, including those for the Gradle Wrapper, retain their own terms.

For isolated native checks, run `tools/VerifyClientPreferences.ps1` with explicit `-IdePath`, `-AiAssistantPath`, `-AirPluginPath` and `-JavaHome`. Use IDEA 2026.2.3 and AI Assistant 262.10968.223; the sandbox uses synthetic providers and its own home, project, registration and histories.
