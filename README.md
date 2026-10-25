# Kweebet versioned player guides

The `docs` branch is the documentation source. [Read the published guides](https://jkswoods.github.io/Kweebet/).
The mod's default remains its latest supported Hytale branch.

Edit the affected `versions/hytaleX.Y/` pages and its `navigation.json`. Keep shared
media isolated in `assets/hytaleX.Y/`; maintain `versions.json`, the root `docs/index.md`
and `docmd.config.json` when adding a supported family. Maintainers port code and docs
forward/back together. See [Contributing](CONTRIBUTING.md).

Use Node 22 (docmd requires Node 20+) and Python 3:

```bash
npm ci --ignore-scripts
npm run build
npm run check
npm run dev
```

`@docmd/core` is pinned to 0.9.8 with a lockfile. The build includes the version index
and every registered guide; the compiled audit checks links, fragments and assets for
all versions. AI assistants, semantic downloads and analytics are disabled. Images
and existing compatibility/native-acceptance wording are preserved from the source.
Deploy through Actions → Versioned player guides, selecting branch `docs`. The Pages
environment accepts only `docs`; ordinary builds validate without publishing unless
the repository's existing automatic-docs preference is enabled.
