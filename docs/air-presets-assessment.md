# Preset UI investigation — 2026-10-12

Scope: read-only inspection of the installed Air plugin and official documentation; HTML proposal only. No primary sessions or credentials accessed.

## Confirmed in installed Air

Inspected modules under `air-plugin/lib/modules` with JDK 21 `javap`:

- `intellij.air.frontend.launch.presets.jar`: `AgentSessionPreset` contains `sessionParameters` and a separate string `prePrompt`. The preset editor has a pre-prompt listener. This is additional preset text, not an AGENTS.md or SKILL.md storage reference.
- `intellij.air.backend.acp.jar`: `AcpGenerationSettingsKt` discovers options from session controls, including models and reasoning efforts. `discoverLivePlanControl` supports a configuration option or a session mode. `discoverPlanModeConfig` uses command action metadata and also recognizes `collaboration_mode` with `plan` and `default`. These are Air implementation details, not a universal ACP promise or confirmation of the installed Grok's descriptors.
- `intellij.air.backend.prompt.api.jar`: `AgentPromptPlanModeKt` distinguishes plan session modes and a `plan_mode` option. Plan behavior is not synonymous with file/network permission policy.

Official sources:

- [Air presets](https://www.jetbrains.com/help/air-ides/air-configure-presets.html): injected instructions are delivered before the first prompt separately from visible conversation; available preset fields depend on the agent.
- [Codex AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md): global guidance is read from CODEX_HOME, preferring AGENTS.override.md over AGENTS.md; project guidance is also discovered. This provides a native path for profile-specific rules because AI Multi already isolates CODEX_HOME.
- [Skills](https://learn.chatgpt.com/docs/build-skills): SKILL.md describes an invocable workflow with discovery metadata, unlike always-loaded AGENTS.md guidance.

## Existing AI Multi architecture

- `AgentProfiles.Entry`: stable identifiers, app XML persistence, launch fields and agent/profile-home identity. `ProjectSelection` remains project-owned; chats are independently bound via ChatProfiles and virtual router records.
- `InstalledAgents.resolve`: installed ACP launch config is resolved on a saving worker. `ProfileHomes.environment` supplies profile-specific vendor directories. Keep these launch fields internally; do not add a launch-settings form.
- Router/virtual sessions already forward ACP options, modes, model changes and dynamic notifications, retaining authoritative session snapshots. `ProfileAgentPreferences` currently bridges native preferences shared by agent ID, so profile defaults must avoid overwriting accepted/restored session values.
- Extend the current split list/editor in place. Keep native profile renderer, icons and selector. Put identity by Authorization and in the selector tooltip only. Settings title requested by the user: AI Multi Profiles.
- Project default UI: a checkbox on the selected profile labeled `Default Profile (project-specific)` replaces the shared assignment dropdown. Only the assigned profile is checked. Checking another profile replaces the project assignment; unchecking the default makes the project unassigned. In the settings profile list, its name is bold with `Default for this project` in its hover tooltip only. This is project selection state, never a global boolean on the profile. Apply/Reset handles the draft project assignment independently of profile data; the chat dropdown remains unchanged.

## Incremental implementation plan

1. Add flexible per-agent saved session values with backwards-compatible empty defaults and deep draft copies. Preserve unsupported values across editing and profile switches. Distinguish launch fingerprint from saved session defaults to avoid invalidating running sessions merely when presets change.
2. Cache descriptors from actual session initialization and accepted option updates, keyed by resolved launch environment and agent binary/version where detectable. Opening Settings must not launch an agent. Cached values describe available controls, not confirmed current-session state.
3. Apply saved defaults only to newly created backend sessions through the actual advertised ACP operations. Revalidate after each accepted configuration response because changing a model can alter effort choices. Retain rejected/unavailable values and explain them without restarting or altering active sessions.
4. Account provider abstraction: narrowly scoped Codex identity discovery using the effective profile-specific home. Local JWT metadata is discovered identity, not verified login. No credentials in cache, persistence, logs or errors. Unsupported providers return unknown. Duplicate-account warning requires verified provider-scoped ID evidence.
5. For Codex native profile rules, offer an editor link to its effective AGENTS.override.md / AGENTS.md, explaining project-rule precedence. Do not overwrite existing files or also inject the same file. Investigate adapter behavior before claiming a native rules change affects a running session.
6. For optional generic extra instructions, offer mutually exclusive text or Markdown source and an editor link. Do not rename arbitrary Markdown as an automatically discovered AGENTS file or skill. Determine delivery support per provider; ACP does not guarantee a universal hidden pre-prompt/system-instruction setting. Any fallback context delivery must have explicit semantics and preserve first-task/session lifecycle behavior. Freeze instructions for the session and read file content afresh only for new sessions.
7. Regression checks: migration, unknown agent options, dependent/dynamic values, profile-specific homes and identities, missing/malformed credentials, verified duplicate IDs, effective state, profile switches/default invariance, edits versus running sessions, and both hosts. Build and deploy under existing policy after required checks; no primary restart.

Remaining uncertainty: the exact live Grok option descriptors and initial-instruction delivery support have not been sampled. Mockup Grok fields are explicitly illustrative. No Air internal preset storage integration is proposed.
