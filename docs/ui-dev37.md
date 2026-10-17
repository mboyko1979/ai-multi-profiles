# Profile UI verification — 0.2.0-dev.37

`check buildPlugin --offline` passed against installed IDEA 2026.2.3 and AI Assistant 262.10968.223, including routing, virtual sessions, storage, presets, credentials and registration checks. Native deployment queue tests passed for preserving unrelated updates and replacing only verified development updates.

Isolated `runIde` with mock ACP providers verified whole-profile initialization, immediate cached reopening, retained per-profile draft overrides, spacing between Agent and Session defaults, removal of Refresh options, Advanced after Default Profile, and full separation of Authorization from the options panel. Final screenshots and assertions are `build/ui37/settings37.png`, `initialization37.png` and `checks37.txt`. Earlier failed layout/count assertions are retained as previous diagnostics; the final layout assertions passed on 2026-10-13 at 02:41.

Native toolbar flows were checked with a mock provider exposing both legacy modes and a config option with category `mode`, plus a separate collaboration option. The legacy mode/model flows were empty; config controls retained mode, collaboration and model. Effort belongs to the native thought-level control. Evidence is in `build/ui37/controls37-*` and `pass37-*`. The final layout changes do not alter toolbar code. No real provider prompts or account authentication were used.

Installed Air preset-editor bytecode uses a preferences lane with a metadata cache and reactive catalog. Inspection output is `build/ui37/air-preset-dialog.txt` and `air-catalog.txt`. AI Multi preserves its independently discovered profile defaults and caches untouched successful results for five minutes.

Native installation was queued with backup `deployment-backups/20261013-024137-0.2.0-dev.37/AIMulti-before-update.zip`. The staged ZIP hash matches the checked build. Other pending plugin updates were preserved. The primary IDEA was not restarted and its active chats were not interrupted; dev.37 activates on the user's next restart.
