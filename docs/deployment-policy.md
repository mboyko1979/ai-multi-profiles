# Development deployment policy

Deployment must follow authorization in the current work session or private local operating instructions. Public repository access alone is not permission to modify a running IDE or publish a release.

- Build the intended development artifact and pass required shared and native checks before deployment. Air changes require Assistant regression checks too.
- Back up the installed plugin, verify the exact target identity and archive, and preserve unrelated pending plugin updates. A native queued installation for the next restart is acceptable.
- Do not restart the primary IDE or interrupt active chats without separate explicit confirmation. Use isolated sandboxes for runtime verification.
- Never enable overlapping legacy and current plugin identities together. The deployment tool requires explicit migration selection and rejects ambiguous identities or conflicting queued updates.
- Marketplace uploads, support messages and repository history rewrites require authorization for those actions.

The local ignored file `.private-docs/CURRENT-INSTRUCTIONS.md`, when present, retains project-specific operating permissions. It does not replace the architecture, session ownership or verification requirements.
