# Catalog origin investigation — 2026-10-15

## Finding

The same exact slug, `gpt-6.1-sol`, is returned with `default_reasoning_level: low` for Personal and `medium` for Work by direct authenticated HTTP responses from `https://chatgpt.com/backend-api/codex/models?client_version=0.160.1`. This difference is present before Codex ACP, AI Multi, local configuration, cache parsing or fallback can affect it.

The experiment establishes an account-scoped difference in the endpoint responses. It does not identify the service's internal policy that chooses these values, or establish whether that policy depends on plan, experiments or stored server-side preferences. No account settings or chat reasoning were changed to investigate that policy.

## Direct HTTP evidence

Two repeated request pairs were run at 21:03:30–21:03:31 UTC and 21:06:06 UTC. Within each pair the URL, client version, originator, user agent and cache controls were identical; OAuth credentials and ChatGPT account scope differed. Both account headers were present. Credential/account values are neither printed nor stored in diagnostic output.

| Observation | Personal | Work |
| --- | --- | --- |
| Exact slug | `gpt-6.1-sol` | `gpt-6.1-sol` |
| HTTP status | 200 | 200 |
| `default_reasoning_level` in response body | `low` | `medium` |
| Response ETag | `W/"0c92229fcc7eab8f4f18cc752fb1da1b"` | `W/"94062d57d4bdcd4c1c241a31c321fe1e"` |
| `Cache-Control` response | `private, no-store` | `private, no-store` |
| `CF-Cache-Status` | `DYNAMIC` | `DYNAMIC` |
| Model count | 9 | 9 |

The requests sent `Cache-Control: no-cache` and `Pragma: no-cache`; no `If-None-Match` or other conditional cache header was sent. The diagnostic performs an HTTP GET directly, without launching Codex or consulting any local model catalog. Both runs returned the same respective defaults and ETags.

Evidence: `build/catalog-origin/direct-http-first.json`, `build/catalog-origin/direct-http.json`; reproducible diagnostic `build/catalog-origin/direct-http.js`. Only selected public model metadata and allowlisted response headers are saved.

## Native Codex source and fresh-cache evidence

The exact runtime packaged with Codex ACP 2.2.2 is `@openai/codex` 0.160.1. A separate `app-server` process for each profile was launched from the same binary with:

- A new temporary CODEX_HOME and working directory.
- Copied account authorization only.
- No existing models_cache.json or copied config.toml.
- A minimal environment containing Windows process prerequisites and the isolated home; no inherited CODEX_CONFIG, model/provider/catalog overrides, proxy variables or API keys.
- `config/read` with layers and `model/list`; no session/new or prompt.

Both processes reported an empty effective override for `model`, `model_provider`, `model_reasoning_effort` and `model_catalog_json`; the base URL was `https://chatgpt.com`. User/system config layers supplied none of those overrides. Native model-cache logs confirmed no usable cache entry. Neither fetch failure nor fallback was observed.

| Fresh native cache | Personal | Work |
| --- | --- | --- |
| Cache existed before launch | No | No |
| `fetched_at` UTC | `2026-10-15T21:05:25.070447700Z` | `2026-10-15T21:05:25.822951800Z` |
| Cache `client_version` | `0.160.1` | `0.160.1` |
| Default in cache | `low` | `medium` |
| `model/list.defaultReasoningEffort` | `low` | `medium` |
| ETag matches direct server response | Yes | Yes |

The official source at `rust-v0.160.1` uses OnlineIfUncached by trying the cache, then fetching on a miss. `fetch_and_update_models` constructs the saved entry from the fetched models/ETag and writes it before applying the remote catalog. The endpoint client makes GET models with the client_version query and decodes the response body. Sources:

- https://github.com/openai/codex/blob/rust-v0.160.1/codex-rs/models-manager/src/manager.rs
- https://github.com/openai/codex/blob/rust-v0.160.1/codex-rs/model-provider/src/models_endpoint.rs
- https://github.com/openai/codex/blob/rust-v0.160.1/codex-rs/codex-api/src/endpoint/models.rs

Evidence: `build/catalog-origin/clean-appserver.json`; diagnostic `build/catalog-origin/clean-appserver.js`. Temporary homes were deleted after the diagnostic-owned processes exited.

## Original profile caches and overrides

Original caches are under `~/.jetbrains/ai-agent-project-switcher/profiles/`:

| Original profile file | Original `fetched_at` UTC | Default |
| --- | --- | --- |
| `codex_personal/models_cache.json` | `2026-10-15T20:58:28.039858Z` | `low` |
| `codex_work/models_cache.json` | `2026-10-15T20:20:36.078631100Z` | `medium` |

Their ETags match the corresponding direct HTTP responses and new isolated caches. Both use client_version 0.160.1 and the same exact slug. The corresponding cached model objects differ in `default_reasoning_level` and `priority` (Personal 1, Work 0).

At inspection time neither profile home contained config.toml. Both profiles use the same installed ACP 2.2.2 command and working directory; their only explicit environment entry is CODEX_HOME. The diagnostic host had CODEX_CONFIG containing only developer_instructions, without model/catalog/provider keys; no relevant endpoint/provider/proxy/API-key override was present. The clean native experiment additionally removed that inherited context altogether and reproduced the difference.

Evidence: `build/catalog-origin/environment-audit.json`. This investigation changed no implementation code, profile preferences, model caches in the original homes, authentication or active chats.
