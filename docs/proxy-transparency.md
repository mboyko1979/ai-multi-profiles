# ACP proxy transparency — 0.2.0-dev.57

Transparency is a foundational rule. AI Assistant and Air own their chat UI and protocol decisions; the selected profile's agent owns its capabilities and provider behavior. AI Multi adapts routing identifiers, configured profile settings and the explicitly approved cross-agent history transfer.

## Protocol and restoration

- Negotiate ACP v1, including when a client offers v2. V2 is deferred to a separate future opt-in.
- Initialize the actual last active agent and forward its capabilities and extension metadata. Do not manufacture image, load, resume, list, fork or close support from persisted snapshots.
- Forward the method selected by the client: load remains load and resume remains resume. Preserve provider errors and error data; never substitute load for resume.
- Restore only the last committed conversation segment. Translate its logical session ID to the original agent session ID. Forward the agent's complete replay, including user messages and unknown update variants; do not replay an additional router transcript.
- Forward ordinary prompts, steering, future content types, extensions and provider limits to the agent. History-transfer budgets apply only to AI Multi's explicit transfer workflow.
- Delegate listing and pagination to the selected agent, map known current session IDs, and hide retired internal segments. Delegate fork, close and delete and preserve their outcomes. Ambiguous shared connections require a routing context.

## Immutable identity and changed agents

Each segment records its original profile and agent identity, launch fingerprint and provider session ID. Selecting a profile in a chat never changes the project's Default Profile.

If a profile now selects another agent, do not send the previous agent's session ID to it. The shared transfer dialog shows From and To with the same profile and the old/new agents, explains why automatic restoration is unavailable, and offers a new empty session or selected recent history with explicit consent. Cancellation or a configuration change during the dialog leaves the original conversation untouched. Replacement commits only after creating the new session successfully.

The explicit transfer uses the existing native task runner and progress, tools and cancellation UI. The selected history is sent once, either with an already waiting user task or through the native continuation adapter. Hidden thoughts, raw tool payloads and authentication data are excluded from transferred history; live protocol events remain transparent.

## Native responsibilities and limits

| Behavior | Owner |
| --- | --- |
| Agent discovery and runtime preparation | Installed native ACP catalogue and launch configuration |
| Rendering, tools, permissions, cancellation and authentication UI | Native AI Assistant / Air |
| Capabilities, configuration options and accepted values | Active agent |
| Remembering configuration options | Native ACP settings service |
| Logical IDs, immutable attribution and approved cross-agent transfer | AI Multi |

Native adapters are verified against IDEA/AI Assistant 262.10968.223 and Air 262.8665.497. Internal API changes require renewed verification. The isolated native probe verifies the real recovery dialog and continuation adapter construction/signatures; it does not exercise a production-provider conversation. Extremely old native-only records without transferable router history cannot invent that history, and clients that refuse restoration before issuing any request retain their native limitations.

Regression coverage includes exact load/resume delegation, real replay, unknown metadata/content, provider errors and pagination, fork lifecycle, changed-agent cancellation/consent, stale approval, original MCP servers, one-time transfer and restoration of only the last agent.
