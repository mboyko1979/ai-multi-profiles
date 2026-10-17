# AI Assistant steering completion reproduction

Status: reproduced in the pinned native client/adapter contract; not fixed by dev.78. No report has been sent externally.

The original Personal chat completed its Codex answer at 23:15:29 local time on 2026-10-16 while IDEA continued displaying Thinking/Waiting. The preceding messages included cancellation and native steering. Its rollout contains a normal final answer and `task_complete`; no question form was emitted. That alone does not prove which ACP boundary failed in the original chat.

## Isolated reproduction

`tools/ClientPreferencesProbe.java` and `tools/preferences-fixture.js` exercise the actual AI Assistant session lifecycle with a synthetic ACP v1 agent:

1. Start a normal `session/prompt` and retain its response while the task is active.
2. Submit a follow-up through native `onMessageSent`. The client selects `_session/steering`.
3. The agent replies with `outcome: startedNewTurn`, finishes the original prompt with its real `end_turn` response, then emits the new answer and `session_info_update` with `_meta.codex.threadStatus.type: idle`.
4. The native client retains an active task after receiving the completed text/status. There is no second `session/prompt` request to receive a matching response.
5. Repeat with the fixture connected directly to AI Assistant, without the AI Multi router. The same active-task state remains.

The probe records both `routed-steering-repro.txt` and `direct-steering-repro.txt`. Ordinary cancel → new `session/prompt` completes normally in the same native probe. Only synthetic sessions are cancelled or disposed; the primary IDE and its chats are untouched.

## Installed implementation evidence

Inspected locally: AI Assistant `262.10968.223` and `@agentclientprotocol/codex-acp` `2.2.2`.

- The adapter's `startNewTurnFromSteering` returns `startedNewTurn` after starting an external prompt; `startNewTurnFromExternalPrompt` consumes that prompt's eventual result internally.
- Its notification conversion maps `turn/completed` to no ACP update. Thread status is forwarded under Codex metadata.
- The pinned Assistant converter completes normal tasks from prompt-response/local-terminal events. The direct fixture demonstrates that text plus this Codex idle metadata does not complete the adopted steering task.

This proves an upstream compatibility gap for the reproduced sequence. The original production transport was not instrumented, so its exact missing message cannot be asserted from this fixture alone.

## Required upstream correction

The native client and adapter need a mutually understood, attributable terminal event for externally started steering turns, or a corrected native lifecycle that retains a request capable of delivering their completion. Preserve the real outcome/error and cancellation semantics. Do not infer success from text, manufacture an ACP prompt response, disable an advertised capability, or replace the client's steering request inside the router.

Reproduce locally with `./tools/VerifyClientPreferences.ps1`; inspect its output directory under `build`. The fixture deliberately verifies the currently broken native sequence, alongside passing preferences and ordinary-turn scenarios.
