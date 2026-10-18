# Development deployment policy

## 2026-10-18 explicit installation authorization

After the published-source migration, the user explicitly requested repairing the current build and installing its latest build in the primary IDEA for verification. That request authorizes this deployment and the old-to-new Plugin ID installation transition. Use `deploy-to-idea.ps1 -MigrateLegacyPlugin` for the proven installed AIMulti identity. Back up the old plugin and native state, preserve unrelated updates, and queue legacy removal before the new installation. Do not restart the primary IDEA or interrupt live chats. This supersedes the earlier migration-only installation prohibition below for this requested deployment.

## 2026-10-18 migration override

Automatic deployment is prohibited for the rename/GitHub migration. Do not install, uninstall, upgrade or restart the primary IDEA or modify its update queue. The new Plugin ID is a distinct installation; follow the separately approved [migration plan](plugin-id-migration-2026-10-18.md) before any future deployment. Earlier authorization below is historical and does not authorize this migration. AGENTS.md is intentionally preserved verbatim.


User authorization recorded on 2026-10-11 for this project:

- After building a new plugin version and passing the required checks, automatically install that version into the user's primary IntelliJ IDEA for verification.
- Preserve a backup of the previously installed plugin when deploying an update.
- Installing the plugin is authorized; restarting IntelliJ IDEA still requires separate user confirmation. Do not interrupt active chats or restart the IDE automatically.
- Plugin name and Settings entry: **AI Multi Profiles** (Settings name updated at the user's request on 2026-10-12). Keep the agent name **AI Multi**, the project setting **Default Profile**, the plugin ID and the agent registration ID unchanged.

This authorization applies to this project's development versions. Sandbox `runIde` verification and building installation ZIPs remain authorized.
