# Plugin identity migration ? 2026-10-18

## Scope and safety

Old checkout: `C:\projects\aiagentprojectswitcher`; new checkout: `C:\projects\ai-multi-profiles`. The original remains intact. A separate timestamped source backup and SHA-256 inventory are stored outside both repositories in `C:\projects\ai-multi-profiles-backups`. Generated build/tool caches and prior deployment archives are not duplicated; they remain in the original directory. Unsaved editor buffers cannot be inspected from the filesystem; no editor or process was closed. On-disk sources were backed up before editing.

Old Plugin ID: `dev.boyko.ai-agent-project-switcher`; new: `dev.boyko.ai-multi-profiles`. Gradle root changes from `AIMulti` to `ai-multi-profiles`. Plugin name remains **AI Multi Profiles**, ACP registration `acp.ai-multi`, agent title **AI Multi**. The router's existing ACP `agentInfo.name` is also preserved. Version 0.2.0, beta, since-build 262.10968, no until-build.

## Compatibility inventory

Active persistent components and serialized classes were not renamed. The unused pre-profile home service was subsequently retired for public-data hygiene; its old XML is left untouched and is no longer registered by this version. Application states remain `AcpSwitcherAgentProfiles` / `AcpSwitcherAgentProfiles.xml` and `AiMultiResponseProfiles` / `AiMultiResponseProfiles.xml`. Project states remain `AcpSwitcherProject` in workspace storage and `AcpSwitcherProjectRoutes` / `AcpSwitcherRoutes.xml`. Kotlin/Java packages, settings/action IDs and notification group ID `AI Agent Project Switcher` are retained. They are independent of the Marketplace Plugin ID.

`~/.jetbrains/ai-agent-project-switcher` remains the shared storage root for credentials, routes, immutable session owners, conversation links and recovery rendezvous. Profile UUIDs, launch fingerprints, agent IDs and native client history/preference keys do not change. The project path in an ownership record remains an ownership boundary: opening this new checkout does not authorize adopting sessions bound to the old project directory. No automatic rewrite or transfer is introduced. A changed agent still requires explicit consent; old session IDs never go to a replacement.

The lifecycle listener now watches the new Plugin ID, and native probes look up its new classloader. Deployment archive/folder checks use the new Gradle name. The deployment script refuses an existing `AIMulti` installation; it was not executed. No additional catalog cache or client preparation/task lifecycle was added.

## Future migration, requires separate approval

1. Finish active chats and agree on an IDE maintenance window. Back up native settings, project workspace state, ACP registration/ownership manifest, profile storage and native histories privately. Never put these backups in Git.
2. Never enable both Plugin IDs together: their stable component, action and ACP registrations overlap. Installing the new ID is not an in-place upgrade. Review old-plugin cleanup behavior before disabling/removing it; it can remove manifest-owned ACP registration, while leaving credentials and history.
3. During the approved maintenance window, disable the old identity and install only the new one. A restart may be required, and needs explicit consent. Do not copy chat history, rewrite profile UUIDs or change authentication homes.
4. Verify retained application profiles, project Default Profile in the original project, native client preferences and restoration against the existing storage. For this new checkout, select its own project Default Profile explicitly if needed. A project path change must not bypass session ownership checks.
5. If validation fails, disable the new identity and restore the backed-up old installation/settings under the same maintenance consent. Do not run both plugins as a rollback strategy.

No live migration step was performed in this task.

## Remaining historical names

A complete line-level inventory is in [legacy-name-inventory.txt](legacy-name-inventory.txt). Compatibility entries retain the storage directory, cleanup ownership suffix, notification group and router agentInfo identity in source and fixtures. Historical entries retain prior decisions, verification evidence and this migration explanation. AGENTS.md is copied byte for byte as requested; its earlier Plugin ID preservation/deployment wording is superseded only by the explicit 2026-10-18 task and current decision entry. No operational path points at the old checkout.

## Public repository hygiene

Only source/configuration/docs and the Gradle Wrapper are eligible for the first commit. Exclude IDE state, generated caches, build output, deployment backups, authentication, local profiles, environment files, credentials/certificates, logs/dumps and unreviewed screenshots/archive previews. The project gradle.properties contains only JVM memory/encoding and Kotlin style. Email/account values in tests and the HTML mockup are synthetic example.com/demo fixtures. Local user paths in scripts were replaced with explicit parameters; a historical cache path was made home-relative. No license is selected; public availability grants no license.

## Clean compilation adjustment

The first clean Kotlin compilation exposed an existing private Air `AgentId` constructor call in `AirLegacyProfilePresentation`. Kotlin's daemon reported a synthetic-accessor lowering assertion; its fallback compiler continued. The new copy uses Air's existing public `AgentId.from` factory for the same `acp.ai-multi` value. The pinned SDK bytecode confirms that factory validates syntax and calls its own constructor. This changes no stored identity, catalog source, cache or native-client architecture.
