# Contributing

Thank you for contributing. Choose the pull request's **base branch** according to
the change:

| Change | Pull request target |
|---|---|
| New feature or enhancement | The latest supported Hytale release branch, which is this repository's default branch |
| Bug fix | The supported `hytaleX.Y` branch for the Hytale version where you reproduce the issue |

For example, if `hytale0.7` is the default, propose features against `hytale0.7`.
A bug reproduced on a supported `hytale0.6` version can be fixed against `hytale0.6`.
Check the branch selector for the current default and available support branches.
Create your working branch or fork from the intended target. Mod repositories use
Hytale support branches instead of `main` or `master`.

Maintainers are responsible for forward-porting and backporting fixes and features
across the appropriate supported Hytale branches. Contributors only need to open one
PR against the appropriate target; maintainers coordinate any additional ports.

Describe the change, the affected Hytale version, reproduction steps for a bug and
how you checked the result. Use the target branch's pinned SDK and build instructions.
Keep each PR focused and preserve existing saves, public IDs and compatibility claims.
Include only mod source, assets, build/tests, licenses and curated documentation.
Credentials, game binaries, player saves and private logs do not belong in a PR.

Documentation lives on the separate `docs` branch. Open documentation PRs against
`docs`, updating only the affected `versions/hytaleX.Y/` guide and its navigation.
Features and bug fixes with player-facing changes need a linked documentation update
for that Hytale family. Keep the root version index current; preserve other versions.

Contributor changes to every `hytale*` support branch and the `docs` branch require
an approving review from the designated maintainer in `.github/CODEOWNERS`. New
reviewable commits dismiss previous approvals. Resolve review conversations before
merging. Contributors cannot push directly or force-push support/docs branches.

The repository owner's own work may be pushed directly. This owner-only exception
does not extend to contributors or release automation; contributor PRs still need the
owner's review. Maintainers handle forward/back ports and corresponding versioned docs.
