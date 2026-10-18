# Build repair and old checkout retirement — 2026-10-18

The user authorized discarding old builds and caches, temporarily archiving the remaining old project files, repairing the current build, and installing the latest build for manual verification. The primary IDEA was not restarted.

## Retired directories

Both old project roots are gone from their original locations. Remaining files are in `C:\Projects\ai-multi-profiles-backups\pending-deletion-20261018-182403`: 29 files from CodexProjectSwitcher and 205 from AIAgentProjectSwitcher. Every moved file passed SHA-256 verification. Including inventories and the deletion reminder, the temporary archive is 2,382,733 bytes. Delete it after manual verification; it is not a permanent backup requirement.

Removed generated `build`, `.gradle`, `.intellijPlatform`, `.kotlin` and old deployment archives. Three orphaned Codex processes from the settings53 test sandbox, started on October 15 with their parent already gone, held an empty sandbox directory open; only that confirmed test chain was stopped. Primary IDEA processes and production agents were left running. Removed obsolete 0.2.0/0.2.1 distributions and 96 old development installation ZIPs (52,569,465 bytes), preserving the current archive and native installation queue.

## Build failure and repair

The ordinary build omitted Air unless `airPluginPath` was passed, although the source compiles against Air classes. It also could not discover Java 25 for Java compilation/platform verification; the Gradle runtime and Kotlin toolchain use JDK 21. The shell had no JAVA_HOME or Java on PATH.

The build now resolves pinned Air 262.8665.497 alongside AI Assistant 262.10968.223 and retains explicit local-plugin overrides. The Windows script accepts JavaHome and AirPluginPath, includes the selected IDE runtime in toolchain discovery, and runs both check and buildPlugin. JDK 21 and the installed IDEA's JBR 25 were registered in the machine's Gradle user properties, outside Git, so ordinary IDE Gradle builds find both toolchains. Runtime optional dependencies and plugin behavior are unchanged.

`check buildPlugin --max-workers=2` passed with the default pinned dependencies and no local dependency/toolchain flags. All routing, lifecycle, profile storage, Assistant/Air defaults, restoration and transparent ACP regression tasks passed. An earlier run during mass filesystem cleanup exceeded a fixture's 400 ms backend timeout; standalone retry and the final complete build passed without changing production timeouts or weakening tests. Evidence: `build/build-diagnosis.log`, `build/build-fixed.log`, `build/build-default.log`.

## Installation queued

The installed old identity was verified from its descriptor as `dev.boyko.ai-agent-project-switcher` in AIMulti. Explicit `-MigrateLegacyPlugin` queues deletion of that directory before installing `dev.boyko.ai-multi-profiles`; it refuses an ambiguous dual installation or a queued legacy update. Native queue regression checks passed for migration ordering, unrelated-command preservation, incomplete update rejection and ordinary updates. No installer command ran during those tests.

Version 0.2.2 is queued for the next user restart. The native queue was read back and contains old-directory deletion, target-directory deletion and extraction of the new archive. Built, staged and recorded SHA-256 match: `9b735a410f059d172030397b6deb5ea74c1de1c5fe62ea842c8e0f80fbe9e43d`.

Private local rollback artifacts, excluded from Git:

- `deployment-backups/20261018-183508-0.2.2/ai-multi-profiles-before-update.zip`: installed old plugin, ZIP integrity checked.
- `deployment-backups/native-state-20261018-183354/native-state-before-installation.zip`: 814 native settings/history/ownership files, ZIP and individual SHA-256 checked. Profile credential homes were not modified or copied.

Activation and primary-IDE manual UI verification await the user's restart. Existing session ownership remains tied to the original project paths; no history transfer, profile reassignment or session-ID migration was performed.
