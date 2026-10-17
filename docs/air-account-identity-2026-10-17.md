# Air account identity in an acquired chat — dev.86

## Grok New Session footer account — dev.91

Grok's New Session composer row still omitted the right-aligned account email that Codex shows (`AI Multi | Grok | Grok 4.6 | Medium | Standard Access`). Codex publishes `_auth/status_update`, so native Air paints `chipText` with `TITLE_AND_ICON`. Grok's binary ACP does not, so Air's account state is Hidden and the native `AiSource` control stays invisible.

Dev.91 keeps the native account action, button, tooltip and menu. The overlay now:

- forces `isVisible`/`isEnabled` when a caption exists
- uses synchronous `ProfileAccounts.discover()` when the async `request()` cache is empty
- reads the profile email from local `auth.json` (including nested `issuer::client` maps)
- resolves acquired chats through `AirProfileBridge.record`
- clears blank native `SEGMENTS` so `presentation.text` can paint

The email is the profile's stored local identity, not a synthesized `_auth/status_update`. Native Grok still does not publish auth status. UI verification is after the next user-initiated IDEA restart.

The user reported that the native account control showed a login on New Session but lost it in an acquired AI Multi chat. The account must belong to that chat's committed profile/agent segment, independently of the project's Default Profile or another composer's selection.

## Cause and implementation

An agent can publish `_auth/status_update` once, immediately after `initialize`, with no session ID and no pending request. The router's previous request/session checks could discard this connection-level notification. Native Air consequently received the agent's auth-status capability but no account identity.

The virtual-session router now forwards connection notifications from the connection's selected backend. Notifications received during initialization are held until the client's initialize response has been sent. New-session, restore, fork, close/delete and profile-switch boundaries flush the selected backend's pending notifications. Forks sharing one backend remain unambiguous; notifications from several different active backends cannot be attributed to one connection identity.

Catalog/preview backends and retained handoff sources cannot overwrite the selected account. Cancelling a handoff republishes only the retained source's last actual auth-status notification, kept in memory. Stopping a backend clears this state. Payloads, unknown extension fields and the agent's real capabilities are preserved. The legacy direct-routing path retains its existing request-scoped behavior.

No account identity is inferred or fabricated. An agent that does not publish auth status supplies no new account information through this fix. Per-profile native model metadata cache isolation remains unresolved, as documented in the [launch implementation](shared-launch-implementation-2026-10-17.md).

Air explicitly uses `ICON_ONLY` for acquired-chat account controls and `TITLE_AND_ICON` on New Session. Its acquired tooltip is generic, so forwarding account status alone does not display the inline login. The existing `AirProfilePreferencesGroup` now decorates only the acquired AI Multi account action's caption with the same native source presentation's `chipText`. Native action updates, button, tooltip, popup and lifecycle delegate unchanged. Scope comes from stored tab coordinates, not a live session or profile account files. Other agents and New Session retain their native actions. The pinned Air 262 adapter reads the native selector command service's lane and account state; unsupported APIs retain the native control and log once. It introduces no replacement factory, cache, account identity or preparation lifecycle.

The acquired profile tooltip also uses Assistant's wording: switch after the response finishes, with the project's Default Profile unchanged.

## Verification

The initial routing-only `check buildPlugin` run passed all 33 actionable tasks (`build/account86-build-final.log`), before the caption decorator was added. That early package differed from dev.85 only in two router modules and version metadata. This result does not validate the final caption change.

`router/test-transparent-proxy.js` covers exact pre/post-initialize auth pushes, notification ordering and unknown fields; restore of the last committed agent; preview and retained-source isolation; cancellation/rollback; and shared-backend versus ambiguous connection attribution. The remaining shared/Assistant/Air regression suites passed in the full build.

The first native UI run (`build/account86-native`) passed native account-identity assertions but could not find a visible account control. Its mock agent omitted provider discovery. The pinned Air implementation hides this control when the agent exposes no supported provider APIs. The mock now advertises `providers` and returns a local `providers/list` fixture, alongside the original one-time auth push. This change belongs only to the diagnostic probe, which is excluded from the plugin.

The next native run (`build/account86-native-final`) passed visible composer account changes but failed its acquired-chat check. Diagnostic evidence (`build/account86-native-diagnostic/account-runtimes.txt`) confirmed that the original manager still held the correct chat account. The synthetic runtime had incorrectly used `local` as its launch target. Air's acquired GUI resolves the runtime adapter from the projection's launch target before reading auth identity, so the fixture supplied no adapter. Initial preparation, acquired OpenSpec and launch profile now use Air's canonical `acpAgentLaunchTargetId`; no production UI workaround was added. Path separators were not the cause: Air normalizes the project directory when constructing its SessionRef.

Further runs confirmed that native acquired metadata contains the correct account independently of the default/composer (`build/account86-native-complete/account-metadata-diagnostic.txt`). Empty acquired button text was expected native style, not evidence that account metadata was absent. Automated attempts asserting inline text/tooltip before the caption adjustment are failed runs, not completed UI verification.

The user explicitly stopped automated tests and preferred manual checking. The isolated `build/account86-caption-native` JVM was stopped; the primary IDEA and its chats were not interrupted. Its completed portions covered Assistant and composer account changes, but the final acquired caption check did not finish. No more automated tests are to be run for this update. The final caption and tooltip changes compiled and packaged successfully with all test tasks excluded (`build/account86-manual-build.log`, 12 actionable build tasks). This is a manual-verification build, not a claim of completed final UI/regression verification.

Dev.86 is queued for the primary IDEA's next user-initiated restart. The existing installed plugin was backed up and all file hashes verified. The native queue preserves unrelated pending updates and supersedes the verified dev.85 pair. The backup and deployment record are in [deployment-backups/20261017-164036-0.2.0-dev.86](../deployment-backups/20261017-164036-0.2.0-dev.86/deployment.json); the record explicitly identifies manual verification. The primary IDEA was not restarted.
