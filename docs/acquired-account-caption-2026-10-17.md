# Acquired account caption — dev.93

The user reported a missing account in an existing Air chat, including Codex. The primary IDE log identifies the active plugin as dev.91; dev.92 was queued.

The account decorator previously recognized acquired chats only when their stored tab coordinates used an AI Multi registration ID. It now also accepts an existing runtime binding whose session ID belongs to AI Multi's virtual-session namespace. Native bound-agent launch metadata therefore does not exclude the account decorator. This uses the existing runtime lookup without acquiring, describing, switching or creating a session. Account content, popup and button still come from the existing presentation path. There are no provider-specific conditions.

The initial dev.92 native baseline passed Assistant and Air, and its screenshot shows an account in the ordinary acquired fixture (`build/account93-before/visible.png`). This did not reproduce the user's particular chat. Earlier assertions incorrectly accepted tooltip-only email; the probe now checks the button's painted text, allowing native truncation. One early run also exposed a fixed-delay UI race; the probe now waits for the actual profile-control visibility state. Follow-up diagnostic attempts stopped before proving the bound-agent-coordinate case due to probe API mismatches. Those unsuccessful diagnostics were removed.

The user then requested fewer costly tests. No additional native IDE runs were launched after that instruction. The final source change requires manual confirmation in the user's affected acquired chat; successful compilation/shared regressions must not be presented as proof that this precise UI symptom is resolved. Final build results are in `build/account93-build.log`.

`check buildPlugin --offline` passed in one final run. Dev.93 was queued for the next primary IDEA restart, superseding the verified queued dev.92 update. Backup: `deployment-backups/20261017-223017-0.2.0-dev.93/AIMulti-before-update.zip`. Unrelated updates were preserved; the primary IDE and active chats were not restarted or interrupted.
