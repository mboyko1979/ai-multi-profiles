# Air profile placement — dev.80

The profile dropdown now appears below the prompt, in the same native `Air.Prompt.Preferences` toolbar as the agent selector. The upper `Air.Prompt.Launch` row no longer contains it. New Session creation and existing chat switching retain their existing behavior, including project Default Profile isolation.

`check buildPlugin --offline --max-workers=1` passed all 33 tasks; see `build/air80-build.log`. The check invocation explicitly supplies the installed IDEA JBR as the Java 25 test toolchain. Early build attempts failed due to overlapping incremental compiler caches and missing Java 25 discovery; they are not counted as passing checks.

`tools/VerifyClientPreferences.ps1 -AirOnly -AirVisible -OutputDirectory build/air80-bottom-native2` passed native New Session selection, acquired chat profile visibility in the bottom toolbar, absence from the upper toolbar, initial profile routing, subsequent switching, preserved original composer, independent existing chat, unchanged project Default Profile and no provider prompt. `new-session.png` shows the profile dropdown beside the agent below the prompt; `bottom-profile.png` shows the acquired chat selector below its prompt. Fixtures run in an isolated IDEA. Earlier placement/probe attempts are not counted as passing verification.

The probe now expands registered groups with an action event, avoiding the host's prohibited `getChildren(null)` path and resolving action stubs before comparing actions.

Native installer queue regression checks passed in `build/air80-queue`. Dev.80 supersedes the verified queued dev.79 update, preserving unrelated pending updates. The installed plugin is backed up at `deployment-backups/20261017-085809-0.2.0-dev.80/AIMulti-before-update.zip`. Installation is queued for the next primary IDEA restart; the primary IDE and active chats were not restarted or interrupted.
