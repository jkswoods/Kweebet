---
title: "Build from source"
---

# Build from source {#build-from-source}

You need Python 3.9 or newer, a Java 25 **JDK**, and your own supported Hytale 0.7.4 server JAR. The repository does not redistribute Hytale's binaries or assets.

Set `HYTALE_SERVER_JAR` to that server JAR and `JAVA_HOME` to the JDK directory. `build-target.json` records the exact server checksum expected by this version. Then run:

```sh
python3 tools/build.py
python3 tools/test.py
```

The output is `build/libs/Kweebet-0.1.0.jar`. The build compiles the plugin, includes its original assets and recipes, and writes deterministic archive entries. Tests cover item accounting, saved outcomes and display-state geometry using JVM fixtures; they do not substitute for native gameplay or multiplayer observation.

## Build the guide {#build-the-guide}

```sh
python3 -m venv .venv-docs
.venv-docs/bin/python -m pip install -r requirements-docs.txt
.venv-docs/bin/python -m mkdocs build --strict
```

On Windows use the virtual environment's Scripts directory. Generated build/site output is excluded from Git.
