# AI Multi Profiles artwork

Original SVG paths created for this project: an AI sparkle and overlapping profile silhouettes. No third-party logo or icon paths were copied. The previous robot was AI Assistant's fallback icon for custom ACP agents.

- [Color logo](ai-multi.svg), [dark-background color logo](ai-multi-dark.svg): scalable web artwork and the same 40 × 40 artwork packaged as `META-INF/pluginIcon.svg` / `pluginIcon_dark.svg`.
- [Monochrome agent icon](ai-multi-monochrome.svg), [dark theme variant](ai-multi-monochrome-dark.svg): 16 × 16 native dropdown assets.
- Single-profile silhouettes and the middle-dot separator are packaged under `src/main/resources/icons/`.

Each chat profile choice uses a native composite icon: profile silhouette, middle dot, then the chosen installed AI agent's own icon; the user's profile name follows. Icons are resolved by the saved agent ID from AI Assistant's native agent extension list. The code is provider-independent. Unavailable/custom agents without an icon use the generic AI Multi mark.

Design references: [Lucide Sparkles](https://lucide.dev/icons/sparkles), [Lucide Users Round](https://lucide.dev/icons/users-round). Packaging, size and theme requirements: [JetBrains plugin logo guide](https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html), [working with icons](https://plugins.jetbrains.com/docs/intellij/icons.html).
