# Defaults loading — dev.92

Loading previously required a model recommendation for every reasoning parameter and rejected the entire catalog when one was absent. The recommendation capability was also requested only for one agent identity.

The isolated Settings consumer now advertises its recommendation support uniformly. Sources are labelled `ACP recommendedValue`. Missing recommendations leave the parameter unselected while loading the catalog and confirmed values. The editor explains the missing values; Apply requires a supported explicit choice for every enabled model. Refresh preserves existing choices; resetting a model leaves unknown parameters unselected. Current effort inherited from another model never supplies its recommendation. No agent-name branch or vendor model-file adapter was added.

`check buildPlugin --offline` passed (`build/dev92-check.log`). Expanded `ConcreteDefaultsChecks` verifies unknown recommendations, confirmed values, Apply validation, manual correction, refresh preservation and reset for Assistant and Air. Existing protocol, recommendation, client preference, restoration and lifecycle suites passed. Native client verification passed for both clients (`build/dev92-native/result.txt`, `assistant-launch-result.txt`, `air-result.txt`). The native suite exercises controlled providers; this change was not verified against a live Grok account.

Dev.92 was queued for the next primary IDEA restart. Backup: `deployment-backups/20261017-221403-0.2.0-dev.92/AIMulti-before-update.zip`. The installer verified preservation of unrelated queued updates. The primary IDE was not restarted and active chats were not interrupted.
