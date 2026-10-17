# Rename and publication verification ? 2026-10-18

## Result and identity

New working copy: `C:\projects\ai-multi-profiles`; Gradle root `ai-multi-profiles`; Plugin ID `dev.boyko.ai-multi-profiles`; plugin name **AI Multi Profiles**. ACP registration remains `acp.ai-multi`, title **AI Multi**, protocol v1. Packages and serialized identities remain unchanged. See the [migration plan](plugin-id-migration-2026-10-18.md) and [complete remaining-name inventory](legacy-name-inventory.txt).

Repository: https://github.com/mboyko1979/ai-multi-profiles ? confirmed personal account `mboyko1979`, public, empty before the first push; initial branch `main`. Commit authorship uses GitHub noreply instead of the workstation's private email. No Release, Marketplace upload or binary publication is part of this task.

## Build and package

Final `check buildPlugin --offline --max-workers=1` passed: 34 tasks, 28 executed, six up to date. Compilation and the 18 custom regression suites passed; the conventional JUnit `test` task has no source tests. SDK: installed IDEA **2026.2.3**, build **262.10968.63**, AI Assistant **262.10968.223**, Air **262.8665.497**. The installed directory has a historical version label, so product-info.json was checked instead of inferring its version from that label. JDK 21 compiles the project; Java 25 from the IDE JBR runs the relevant verification suites. No SDK or dependency versions were changed.

Archive: `build/distributions/ai-multi-profiles-0.2.0.zip`. SHA-256: `3b2648c529869d9268b0db7391eaa6d97ad1bed1289a0a057c57e74a02d5db7c`. Recursive inspection of 323 ZIP/JAR entries found no old checkout absolute path. The descriptor contains the new Plugin ID, AI Multi Profiles, 0.2.0 and since-build 262.10968, with no until-build. Both optional host descriptors are present; all six packaged router modules match their tested sources byte for byte. Channel remains beta.

A clean compilation exposed an existing private Air AgentId constructor call; the public factory fixes it without changing the ID. Searchable-options generation now has its own plugin/user home, in addition to Gradle's separate IDE config/system sandbox. The native verification script accepts explicit SDK paths, resolves its fixture from this checkout and handles javac warnings correctly in Windows PowerShell. PowerShell syntax checks passed.

## Isolated native verification

`VerifyClientPreferences.ps1 -AirVisible -OutputDirectory build/migration-native` passed with explicit SDK/JDK paths. Only controlled synthetic providers were used. The test process had private home, IDE config/system, ACP registration, profiles and histories. HOME, USERPROFILE, APPDATA, LOCALAPPDATA, XDG and provider authorization homes pointed inside the test tree; token/API-key/password/secret/credential environment variables were removed from that process. No personal authorization was copied.

Verified native Assistant and Air preference stores and profile/agent/client isolation; current installed launch configuration; Assistant profile selection preserving the same chat/draft and prepared SDK handles; cancellation and subsequent prompts; live model filters without session replacement; actual Air ACP-v1 admission under acp.ai-multi; native model/default controls; unchanged project Default Profile and composer/tabs; immediate selection during delayed native preparation; reuse of prepared sessions and retirement of unused ones; account attribution from the fixture agent's actual notification.

A separate `LocalChatRestorationProbe` in `build/restore65` passed: native Assistant/Air history reads, deleted-profile actions, empty-profile local view, disabled read-only composer, retained binding/history, target staging/input restoration and saved model without reapplying new defaults. It starts no real provider.

`DeploymentQueueChecks` passed against temporary files under `build/migration-queue`: serialization, preservation of unrelated commands, rejection of incomplete replacement pairs and unchanged original test queue. Installer commands were never executed.

Evidence remains local under ignored build directories, including `migration-build-final.log`, `migration-native/native.log`, `migration-native/*result.txt` and `restore65/result.txt`. Diagnostic logs/screenshots are not source artifacts.

## Security and compatibility limits

Initial source inventory: 205 files, including private IDE state/screenshots. A separate ZIP and SHA-256 inventory were verified outside the new repository. Generated caches, the roughly 177 GiB original build tree and prior deployment archives remain in the original directory; they were not duplicated. Source hashes outside .idea remained unchanged throughout migration. Unsaved editor buffers cannot be inspected from the filesystem.

The public candidate/staged file set was inspected using git status, git ls-files and the complete git diff --cached, plus secret signatures, credential-assignment review, email/path/name checks and binary allowlisting. No .env, local.properties, credential/certificate files or actual authorization/profile configuration were copied. gradle.properties contains only JVM/encoding and Kotlin-style settings. All found email/account fixtures are synthetic example.com/example.test/demo values. The sole intentional binary source artifact is the unchanged Gradle Wrapper JAR. IDE state, build output, logs/dumps, deployment backups, screenshots and the preview ZIP are excluded. AGENTS.md is byte-identical to its source. No project license was selected.

No persistent-state names, XML storage filenames, profile UUIDs, authentication homes, native preferences/history keys, session IDs or ownership files were migrated. The old shared storage directory and notification group stay as compatibility identifiers. No metadata cache or alternative native task/preparation lifecycle was added. Existing native steering-completion behavior was reproduced both directly and through the router; this pre-existing pinned-host issue is unchanged.

The credential suite could not exercise symlink creation because this Windows host lacks that permission. Standalone JetBrains Plugin Verifier and other IDE/AI client versions were not run; validation is against the pinned SDK and actual isolated clients. Sandbox startup/shutdown logs contain host warnings and disposal diagnostics; successful probe assertions and exit codes establish only the checks listed above. No real-account login, real-account chat restoration or primary-IDE upgrade was tested, to preserve active work.

The primary IDE was not restarted, closed, upgraded, uninstalled or deployed to. Its active sessions and native update queue were not modified. Global ACP registration/manifest/routes timestamps remained unchanged across the isolated native checks. The old checkout remains in place without a Git repository.

## Before manual Marketplace publication

Resolve the project license/EULA decision without inventing vendor or email data; set the Marketplace source link to the repository above; review the separately consented old-ID migration plan; optionally run standalone Plugin Verifier and broader host-version checks; manually upload the checked 0.2.0 ZIP to beta. No automatic publication or GitHub Release is configured by this migration.

## Subsequent public-data cleanup

Public examples and fixtures now use neutral Personal/Work labels. The unused pre-profile home service was retired without touching its old XML; no active profile or session storage was migrated. The current tree is sanitized. The user subsequently chose to delete the original remote and recreate it from a fresh single-root commit, excluding the previous Git history. Original migration checks above describe the migration baseline; follow-up cleanup checks are recorded separately in the decision log.
