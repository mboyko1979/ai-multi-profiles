# Air folded agent launch — dev.90

The primary Air New Session composer displayed “No agents are available” with Personal selected. Personal is bound to `acp.registry.codex-acp`. Air's `AcpAgentCatalogContributor` uses its own `FOLDED_ACP_AGENTS` mapping to expose this entry as `codex`; it does not publish the synthesized dynamic ACP catalog ID used by the plugin's launch seed. The custom ACP fixture used in earlier verification was not folded, so it did not cover this case.

The composer seed and its idempotence check now use Air's actual folded mapping, falling back to Air's dynamic ACP identifier for ordinary agents. The ACP launch target still identifies the real bound ACP agent. AI Multi registration, profile routing, native catalog/cache ownership and composer-local selection remain unchanged. This is a pinned Air 262 host binding to the same mapping used by the host contributor, with no plugin cache or copied agent list.

`VerifyClientPreferences.ps1 -FoldedAgent` runs the existing native Air lifecycle/preferences/composer checks with profiles bound to the actual Codex registry identity and an isolated controlled backend. No production account is contacted by that backend. The test checks the native `codex` catalog ID and ACP registry launch target, preparation, defaults, account attribution, profile switching, prepared-session reuse and first prompt admission.

Cross-client verification also exposed an existing Assistant first-prompt failure: `ProfileAgentPreferences` referenced the package-private host `BeforePromptResult` class across plugin classloaders. The JVM threw `IllegalAccessError` before sending any ACP prompt. The adapter now returns the host's real result singleton through an erased callback, using the existing narrow reflective binding to `beforePromptActions`. Native validation/actions retain their order and result semantics. This is another pinned host adapter; it does not synthesize protocol success or capabilities.

Verification:

- `check buildPlugin --offline --max-workers=1` passed; archive: `build/distributions/AIMulti-0.2.0-dev.90.zip`. Java 25 verification tasks used the installed IDE's JBR via `org.gradle.java.installations.paths`.
- `build/startup90-air-repro` passed the existing custom-agent native Air suite before the folded mapping change.
- `build/startup90-folded-final` passed the native visible Air suite with the Codex registry identity, including explicit catalog/launch-target assertions, profile menu, native model/settings/account controls, unchanged default and draft, retained native session IDs and first prompt admission. `new-session.png` shows the native composer without the reported error.
- `build/startup90-final` verified the repaired Assistant first prompt, cancel and prepared-profile reuse, then failed a popup-focus assertion while another visible sandbox was starting. The subsequent Air-only visible run passed sequentially. The existing direct/routed steering-completion gap remains reproducible and outside this fix.

- `build/startup90-cross-client-final` passed the complete combined native Assistant/Air suite, including the repaired first prompt, cancel/retry, prepared-session retention, native preference stores, actual Air admission, defaults and profile switching with Codex's registry identity. This run was sequential and did not use the visible-popup audit.

Deployment: dev.90 was queued through the native installer for the next primary IDEA restart. The installer verified preservation of unrelated queued updates. Previous plugin backup: `deployment-backups/20261017-210137-0.2.0-dev.90/AIMulti-before-update.zip`. The primary IDE was not restarted and its active chats were not interrupted. The screenshot scenario was verified with the isolated controlled backend; the production Personal account still requires user verification after restart.
