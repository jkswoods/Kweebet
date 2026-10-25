# Building and releasing Kweebet

The default branch is `hytale0.7`, with no `main` branch. The current mod version
is `0.1.1` and its manifest declares `>=0.7.4 <0.8.0`.
The family label does not certify every future game patch. Preserve required native,
save, visual/listening and multiplayer acceptance before releasing selected source.

## Build a candidate

Use the pinned Java/SDK inputs in `build-target.json`. Set `JAVA_HOME` to
the required JDK and `HYTALE_SERVER_JAR` to the matching SDK, then run:

```bash
bash tools/ci-build.sh
```

`bash tools/ci-fetch-sdk.sh` downloads the exact pinned official Maven coordinate;
`python3 tools/release_pipeline.py verify-sdk PATH` checks its checksum. Ordinary CI
pushes and pull requests build/test candidates without publishing a mod release.
Game binaries, live saves and private acceptance records stay outside the repository.

## Publish manually

Open **Actions → Publish mod release → Run workflow**:

| Control | Choices | Default |
|---|---|---|
| Branch | GitHub's built-in selector; choose `hytale0.7` | Repository default |
| Version bump | Patch, Minor, Major | Patch |
| Release type | Release, Beta, Alpha | Release |

A manual run is an instruction to publish that branch's selected source. The workflow
checks the destination, computes the highest published numeric mod version for that
Hytale family, applies the bump, stamps the manifest/channel and builds/tests once.
For a family's first release, its current manifest version supplies the numeric base;
legacy unqualified tags and other Hytale families do not change that base. Patch adds
one patch; Minor resets patch to zero; Major resets minor and patch to zero.

The workflow commits the version to the selected branch, creates a family-qualified
tag and stages a draft GitHub release. It attaches the exact built JAR/checksum receipt,
uploads those same bytes to CurseForge with the `0.7` game tag and chosen channel,
then publishes GitHub after a confirmed CurseForge file ID. Alpha/Beta become GitHub
prereleases. Release type does not add a suffix to the numeric version.

For example: tag `hytale0.7-v1.3.40`, title
`Kweebet 1.3.40 — Hytale 0.7`, file `Kweebet-hytale0.7-1.3.40.jar`.
Other Hytale families can independently release `1.3.40` under their own tags/titles.
Stable backports do not replace the current default family's GitHub Latest release.

`src/main/resources/manifest.json` is authoritative for version/compatibility and
`build-target.json` for SDK/Java pins. `release.json` references those
sources and the existing CurseForge project. Publishing requires a real `projectId`
and the repository Actions secret `CURSEFORGE_API_TOKEN`. No enable variable or custom
API-host variable is needed. The API origin is `https://www.curseforge.com`, with the
Hytale catalog at `/api/game/hytale/versions`. The `hytale.curseforge.com` landing site
is not the author API. Trusted candidate builds check the token/catalog read-only;
missing listings are reported without blocking candidate builds. Publishing refuses
missing listings before changing remote versions. A catalog check does not prove
upload permission or a successful store submission.

## Retry and recovery

Rerun the same workflow run to keep its saved version plan and binary. Do not start a
fresh dispatch to retry a partial release. Existing tags/assets must match; no binary
is overwritten. A confirmed CurseForge receipt lets GitHub finalization finish without
another upload. A saved upload intent without a confirmed file ID stops: inspect the
store before reconciling an unknown outcome. HTTP uploads are attempted once.

The run's plan/candidate are retained for 30 days. Missing/expired state or an advanced
source branch fails explicitly rather than selecting another version or new bytes.
Submission may still await CurseForge moderation; a file ID is not proof of approval.

When adopting `hytale0.8`, update SDK/compatibility, make it the GitHub default and
align Pages rules/links. Keep workflow/helper fixes synchronized across every supported
`hytaleX.Y` branch while preserving each branch's source, SDK and compatibility data.
