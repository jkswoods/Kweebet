# Building and releasing Kweebet

The default support branch is `hytale0.7`, with no `main` branch.
The mod version is `0.1.1`;
its declared Hytale compatibility is `>=0.7.4 <0.8.0`. This range is the
intended supported family, not a claim that every future patch has been tested.
Compilation uses the pinned official Hytale 0.7.4 SDK and Java 25.

`src/main/resources/manifest.json` is authoritative for the mod version and manifest constraint.
`build-target.json` is authoritative for the exact SDK/Java pins. `release.json` references
those files and defines artifact naming, the existing CurseForge project and channel.
The build and uploader validate agreement; they do not widen support based on a store tag.

## Build

From the repository root, set `JAVA_HOME` to a Java 25 JDK and `HYTALE_SERVER_JAR` to
the official SDK with the checksum in `build-target.json`, then run:

```bash
bash tools/ci-build.sh
```

Alternatively, `bash tools/ci-fetch-sdk.sh` downloads the exact pinned official Maven
coordinate and prints its path. `python3 tools/release_pipeline.py verify-sdk PATH`
checks its SHA-256. Game/server binaries are external build inputs and are not bundled.
The installable file is named `Kweebet-hytale0.7-0.1.1.jar`.
Builds/tests do not install the mod or access a native client.

## GitHub Actions and CurseForge

Pushes and pull requests to `hytale0.7` build a candidate; only a successful push-build
candidate for the exact release commit can be published. Download the candidate and
complete the required gameplay, save, visual/listening and other release checks on
that exact JAR before publishing any release. Preserve existing acceptance limits.

Use a tag such as `hytale0.7-v0.1.1` at the tested source commit.
Copy the single `hytale-candidate` marker from its build summary into the release notes.
The marker identifies the run, attempt and tested checksum; it does not certify native
acceptance. The publish workflow downloads that candidate, verifies its source, SDK,
manifest and checksum, attaches the same JAR to GitHub and submits those bytes to
CurseForge with the `0.7` store tag. Set GitHub prerelease for Alpha/Beta channels.

Publishing remains disabled until the existing project ID in `release.json` is correct,
`CURSEFORGE_API_TOKEN` is stored as an Actions secret, the verified game author-upload API
origin is configured as `CURSEFORGE_API_BASE`, and `ENABLE_CURSEFORGE_PUBLISH=true` is
set as a repository variable. Do not put tokens in source. Missing project IDs stay
unresolved rather than creating a new listing. No mod release is published by this
configuration change.

The store's `0.7` tag does not itself promise every 0.7 patch. State the precise range
and any known exceptions in player release notes. Keep prior tags/downloads immutable;
this source version is a new candidate, not a replacement for a prior published JAR.

An upload is attempted once. A rerun stops if the binary is already attached to the
GitHub release. Inspect both GitHub and CurseForge before reconciling a failed or
unknown submission; do not remove/reupload files or retry blindly. Retain the returned
CurseForge file ID and distinguish submission/moderation from a public download.

When beginning a 0.8 port, create `hytale0.8` from the intended commit and update its
SDK pin and manifest constraint. When adopting that support line, make it the GitHub
default and align Pages source/environment rules and branch links. Retain `hytale0.7`
for selected backports. Documentation deploys only from the default branch, preserving
this repository's manual or automatic publication trigger.
