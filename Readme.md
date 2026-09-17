# FHIR Package Snapshot Tool (Java 21)

[![Build](https://github.com/Gefyra/fhir-pkg-tool/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Gefyra/fhir-pkg-tool/actions/workflows/ci.yml)
[![Release Workflow](https://github.com/Gefyra/fhir-pkg-tool/actions/workflows/release.yml/badge.svg)](https://github.com/Gefyra/fhir-pkg-tool/actions/workflows/release.yml)
[![Latest Release](https://img.shields.io/github/v/release/Gefyra/fhir-pkg-tool?logo=github&label=Latest%20Release)](https://github.com/Gefyra/fhir-pkg-tool/releases/latest)
![Dependabot](https://img.shields.io/badge/dependabot-enabled-blue?logo=dependabot)
[![License](https://img.shields.io/github/license/Gefyra/fhir-pkg-tool?logo=opensource&label=License)](LICENSE)
![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![FHIR](https://img.shields.io/badge/FHIR-R4%20%7C%20R4B%20%7C%20R5-red?logo=hl7&logoColor=white)

A small CLI tool that downloads FHIR NPM packages from the registry, resolves recursive dependencies, and generates snapshots for `StructureDefinition`s.

## Contents

- [Features](#features)
- [Install](#install)
- [Examples](#examples)
  - [Multiple packages](#multiple-packages)
  - [Dependencies from a Sushi file](#dependencies-from-a-sushi-file)
  - [Dependencies from inline YAML](#dependencies-from-inline-yaml)
  - [Dependencies from package.json](#dependencies-from-packagejson)
  - [Install a package from a local tarball](#install-a-package-from-a-local-tarball)
  - [Always rebuild snapshots](#always-rebuild-snapshots)
  - [Only root packages](#only-root-packages)
  - [Local profiles from a folder](#local-profiles-from-a-folder)
  - [Reinstall a republished package](#reinstall-a-republished-package)
- [Use in a GitHub Actions pipeline](#use-in-a-github-actions-pipeline)
  - [Action inputs](#action-inputs)
  - [Notes for CI](#notes-for-ci)
  - [Without the action](#without-the-action)
- [CLI Options](#cli-options)
- [Exit Codes](#exit-codes)
- [Lock Files](#lock-files)
- [Version Handling](#version-handling)
- [Output Layout](#output-layout)

## Features
- Multiple packages via `-p` (also comma-separated)
- Local package tarballs via `--package-file` (equivalent to `fhir install <file.tgz> --file`)
- Reinstall of already cached packages via `--force-install`, from the registry or from a local tarball
- Dependencies from `sushi-config.yaml` (file or YAML string) – supports both Sushi structures
- Dependencies from `package.json` via `dependencies` (ignores `devDependencies`)
- Automatic FHIR context detection (R4, R4B, R5; DSTU3 fallback)
- Snapshot generation (optional `--force-snapshot`)
- If the same package ID is provided multiple times with different versions, the newest SemVer is selected
- Output layout: one subfolder per package in `--out`, named `<packageId>#<version>` (e.g. `hl7.fhir.us.core#6.1.0`). The complete package is copied there; only `StructureDefinition` JSON files are parsed and (re)written with snapshots.
- Local profiles folder via `--profiles-dir` (recursively loads JSON `StructureDefinition`s and writes snapshots under `--out/local`)

## Install

Every release ships a fat jar and native binaries for Linux, macOS and Windows:
<https://github.com/Gefyra/fhir-pkg-tool/releases/latest>

```bash
# Native binary (no JVM required), e.g. Linux x64
curl -sSL -o fhir-pkg-tool \
  https://github.com/Gefyra/fhir-pkg-tool/releases/download/v0.5.0/fhir-pkg-tool-linux-x64
chmod +x fhir-pkg-tool
./fhir-pkg-tool --help
```

Asset names per platform: `fhir-pkg-tool-linux-x64`, `fhir-pkg-tool-linux-arm64`,
`fhir-pkg-tool-macos-x64`, `fhir-pkg-tool-macos-arm64`, `fhir-pkg-tool-windows-x64.exe`,
plus `fhir-pkg-tool.jar` (needs Java 21).

> **Use v0.5.0 or newer for the native binaries.** Up to and including v0.4.0 they were built
> without three data resources the HL7 core library reads when it constructs a FHIR worker
> context, so every run that got past `--help` ended in a `NullPointerException`. The jar of those
> releases is unaffected.

Build from source:

```bash
mvn -B -DskipTests package          # -> target/fhir-pkg-tool.jar
mvn -B -DskipTests -Pnative verify  # -> target/fhir-pkg-tool (GraalVM 21)
```

The version reported by `--version` comes from the Maven `revision` property, which the release
workflow sets to the git tag. A local build reports `0.0.0-SNAPSHOT` unless you pass it yourself:
`mvn -DskipTests -Drevision=0.5.0 package`.

All examples below use `java -jar target/fhir-pkg-tool.jar`; with a native binary simply
replace that with `./fhir-pkg-tool`.

## Examples

### Multiple packages
```bash
java -jar target/fhir-pkg-tool.jar   -p hl7.fhir.r4.core@4.0.1   -p hl7.fhir.us.core@6.1.0,hl7.fhir.au.core@5.0.0   -o ./out/mix
```

### Dependencies from a Sushi file
```bash
java -jar target/fhir-pkg-tool.jar   --sushi-deps-file ./sushi-config.yaml   -o ./out/from-sushi
```

### Dependencies from inline YAML
```bash
java -jar target/fhir-pkg-tool.jar   --sushi-deps-str "$(cat <<'YAML' 
dependencies:
  hl7.fhir.us.core: 3.1.0
  hl7.fhir.us.mcode:
    id: mcode
    uri: http://hl7.org/fhir/us/mcode/ImplementationGuide/hl7.fhir.us.mcode
    version: 1.0.0
YAML
)"   -o ./out/from-inline
```

### Dependencies from package.json
```bash
java -jar target/fhir-pkg-tool.jar   --package-json-file ./package.json   -o ./out/from-package-json
```

### Install a package from a local tarball

Equivalent to `fhir install <file.tgz> --file`.

```bash
java -jar target/fhir-pkg-tool.jar   --package-file ./packages/molit-service.fhir.vitu-0.1.20.tgz
```
Notes:
- Package id and version are taken from the tarball's own `package.json`; the package is installed into the cache as `<packageId>#<version>`.
- Can be repeated and combined with `-p`/`--sushi-deps-*`; a local tarball wins over a registry package with the same id.
- If `<packageId>#<version>` is already in the cache, the cached copy is kept and the tarball is ignored (the tool says so). Use `--force-install` to drop the cached copy and install the tarball instead.
- Dependencies of the local package are resolved from the registry unless `--skip-deps` is given.

### Always rebuild snapshots
```bash
java -jar target/fhir-pkg-tool.jar   --sushi-deps-file ./sushi-config.yaml   --force-snapshot
```

### Only root packages
```bash
java -jar target/fhir-pkg-tool.jar   -p hl7.fhir.r5.core@5.0.0 -p hl7.fhir.uv.tools@current   --skip-deps
```

### Local profiles from a folder
```bash
java -jar target/fhir-pkg-tool.jar \
  -p hl7.fhir.r4.core@4.0.1 \
  --profiles-dir ./profiles \
  -o ./out
```
Notes:
- The folder is scanned recursively for `*.json` files with `resourceType: StructureDefinition`.
- Only local outputs are produced; packages are NOT copied or snapshotted when `--profiles-dir` is used.
- Outputs mirror the input structure under `./out/local/...`.
- Snapshot generation respects `--force-snapshot`, `--overwrite`, and `--pretty`.
- With `--sushi-deps-file`, the `fhirVersion` declared in that file determines the context; without a Sushi file, the first loaded package decides, and the tool falls back to R5.

### Reinstall a republished package
```bash
java -jar target/fhir-pkg-tool.jar   -p de.medizininformatikinitiative.kerndatensatz.labor@2027.0.0-ballot.rc1   --force-install
```
The cache keys packages by `<packageId>#<version>`, so a package that is rebuilt without a version bump (a ballot RC, a nightly IG build) is never picked up again: the cached copy wins and the tool reports that it ignored the newer one. `--force-install` drops the cached copy first.

Notes:
- Works for both sources: a registry package (`-p`) is downloaded again, a local tarball (`--package-file`) is extracted again.
- Applies only to the packages you ask for. Dependencies and the auto-loaded FHIR core package keep using the cache, so a forced run does not re-download the whole tree.
- Needs an explicit version (`name@version`); without one there is no single cache entry to drop and the tool says so.
- `current` and `dev` are always refreshed anyway, with or without the flag.
- For `-p`, the cached copy is deleted before the package is fetched again — if that download fails, the package stays uninstalled until the next successful run. `--package-file` has no such risk, the tarball is local.
- Not to be confused with `--force-snapshot`, which regenerates snapshots for packages that are already installed.

## Use in a GitHub Actions pipeline

This repository ships a composite action, so a pipeline does not have to download and wire up the
binary itself:

```yaml
      - name: Install FHIR packages and generate snapshots
        uses: Gefyra/fhir-pkg-tool@v0.5.0
        with:
          sushi-deps-file: sushi-config.yaml
```

By default the action writes into the **standard FHIR package cache** (`~/.fhir/packages`). Steps
in the same job share the runner's filesystem, so SUSHI, the IG Publisher or the validator running
in a later step find the packages — now carrying snapshots — exactly where they look for them. Only
across *jobs* is the cache gone; that is what the `cache` input is for.

A fuller example, warming the cache for a SUSHI build. This is what the action is for: the packages
land in `~/.fhir/packages` before SUSHI looks for them there, carrying snapshots, so the build does
not download the dependency tree itself.

```yaml
name: IG build

on:
  push:
    branches: [ main ]
  pull_request:

env:
  SUSHI_VERSION: "3.20.1"

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6

      - uses: actions/setup-node@v7
        with:
          node-version: "22"

      - name: Install FHIR packages and generate snapshots
        uses: Gefyra/fhir-pkg-tool@v0.5.0
        with:
          sushi-deps-file: sushi-config.yaml

      - name: Run SUSHI
        run: |
          npm install --global "fsh-sushi@${SUSHI_VERSION}"
          sushi .

      - name: Upload the generated resources
        uses: actions/upload-artifact@v7
        with:
          name: fsh-generated
          path: fsh-generated/resources
          if-no-files-found: error
```

The same pattern works for the HL7 IG Publisher, which reads the same cache. Two things are worth
knowing about it:

- **A dependency without a pinned version is still resolved over the network.** The action installs
  what `sushi-config.yaml` names; for an entry that says `latest`, or none at all, SUSHI still asks
  the registry which version that is.

### Action inputs

| Input | Default | Description |
| ----- | ------- | ----------- |
| `packages` | – | Coordinates (`name@version`), comma- or newline-separated. Blank lines and `#` comments are ignored. |
| `package-files` | – | Local `*.tgz` tarballs, newline-separated. |
| `sushi-deps-file` | – | Path to a `sushi-config.yaml`. |
| `sushi-deps-str` | – | Inline YAML block with a `dependencies:` map. |
| `package-json-file` | – | Path to a `package.json` (only `dependencies`). |
| `profiles-dir` | – | Directory with local StructureDefinition JSONs. |
| `out` | standard cache | Output directory, which doubles as the package cache. |
| `version` | the tag the action is used at | Release of the tool to run. |
| `binary` | – | Path to an existing binary. Nothing is downloaded and `version` is ignored - for runners without access to github.com, or to test an unreleased build. |
| `cache` | `true` | Cache the output/cache directory via `actions/cache`. |
| `cache-key-files` | `sushi-config.yaml`, `package.json` | Glob patterns hashed into the cache key. |
| `repair-lock-files` | `true` | Remove orphaned `*.lock` files before loading. See the note below. |
| `skip-deps`, `no-auto-core`, `force-snapshot`, `force-install`, `ignore-snapshot-errors`, `overwrite`, `debug` | `false` | The matching CLI flags. |
| `registry` | `https://packages.fhir.org` | Package registry URL. |
| `extra-args` | – | Additional arguments passed through verbatim (quoting is honoured). |

At least one of `packages`, `package-files`, `sushi-deps-file`, `sushi-deps-str`,
`package-json-file` or `profiles-dir` must be set.

Outputs: `out-dir` (where packages and snapshots landed), `version` (the release that ran) and
`cache-hit`.

`version` deserves a note: left empty, the action runs the release it is referenced by, so
`uses: Gefyra/fhir-pkg-tool@v0.5.0` fetches the `v0.5.0` binaries. Referenced by a branch
(`@main`) there is no tag to derive, and the action falls back to the release pinned in
`action.yml`. Set the input explicitly to decouple the two.

### Notes for CI

- **Pin the action**, by tag or by commit SHA. The action downloads a release binary, so an
  unpinned reference changes what actually runs.
- **`repair-lock-files` only removes orphans.** A lock left behind by a cancelled or killed run is
  cleaned up, while a lock some process is holding right now is kept and the run still aborts (see
  [Lock files](#lock-files)). That makes it safe on a self-hosted runner with a shared home
  directory. Turn it off for a cache mounted over NFS or SMB, where the lock check cannot be
  trusted.
- **Snapshot failures fail the step** with exit code 6. Set `ignore-snapshot-errors: 'true'` to
  only log warnings.
- **Cache metadata version.** The tool insists on `[cache] version = 4` in `packages.ini` and
  aborts with exit code 4 otherwise. When it shares the standard cache with an IG Publisher that
  writes a newer format, both have to move together.
- **Cache size.** The cached directory holds the full content of every package, not just the
  StructureDefinitions, so it can grow to a few hundred MB for larger dependency trees.
- Every run prints the effective output and cache directory, which makes cache misses easy to
  spot in the job log.

### Without the action

Downloading the binary by hand works just as well, e.g. when the pipeline pins every tool
centrally:

```yaml
      - name: Download fhir-pkg-tool
        env:
          FHIR_PKG_TOOL_VERSION: v0.5.0
        run: |
          curl -sSLf -o "${RUNNER_TEMP}/fhir-pkg-tool" \
            "https://github.com/Gefyra/fhir-pkg-tool/releases/download/${FHIR_PKG_TOOL_VERSION}/fhir-pkg-tool-linux-x64"
          chmod +x "${RUNNER_TEMP}/fhir-pkg-tool"
          echo "${RUNNER_TEMP}" >> "$GITHUB_PATH"

      - name: Generate snapshots
        run: fhir-pkg-tool --sushi-deps-file ./sushi-config.yaml
```

The jar variant needs a JDK 21 on the runner (`actions/setup-java@v5`) and is otherwise identical:
`java -jar fhir-pkg-tool.jar --sushi-deps-file ./sushi-config.yaml`.

## CLI Options

- `-p, --package`: One or more package coordinates (`name@version`). Comma-separated allowed, can be repeated.
- `--package-file, --file`: Path to a local package tarball (`*.tgz`) that is installed into the cache. Repeatable. Package id and version are read from the tarball's `package.json`.
- `--force-install`: Reinstall the packages you asked for even when they are already cached — both registry packages (`-p`, re-downloaded) and local tarballs (`--package-file`, re-extracted). Without it, an existing `<packageId>#<version>` in the cache is used as-is.
- `--sushi-deps-file`: Path to a `sushi-config.yaml` (dependencies are read).
- `--sushi-deps-str`: Inline YAML block containing `dependencies:`.
- `--package-json-file`: Path to a `package.json` file. Only `dependencies` are used (`devDependencies` are ignored).
- `-o, --out`: Output directory (default: `~/.fhir/packages`; on Windows: `C:\Users\<USER>\.fhir\packages`). This directory is also used as the package cache, so packages downloaded into it are reused by later runs.
- `--no-repair-lock-files`: Keeps `*.lock` files in the effective cache directory instead of removing orphaned ones. Removal is **on by default**; `--repair-lock-files` is still accepted as a no-op for existing scripts. A lock is only removed when no process holds its OS-level lock any more **and** it has been untouched for at least 5 minutes; anything else is kept and the run still aborts with exit code 5. See [Lock files](#lock-files).
- `--registry`: Package registry URL (default: `https://packages.fhir.org`).
- `--skip-deps`: Do not auto-load transitive dependencies.
- `--no-auto-core`: Do not automatically load the matching FHIR core package as snapshot context when it is missing from the resolved packages.
- `--ignore-snapshot-errors`: Exit with 0 even when snapshots failed (default: exit code 6).
- `--overwrite`: Overwrite existing files in the output (both copied and snapshotted).
- `--pretty`: Pretty-print JSON output for rewritten StructureDefinitions. Enabled by default; there is no flag to turn it off.
- `--force-snapshot`: Always regenerate snapshots even if a snapshot exists.
- `--profiles-dir`: Directory containing local `StructureDefinition` JSON files (processed recursively, written under `--out/local`). When set, package contents are not written.
- `--debug`: Print stack traces for execution errors and keep the library log output (which is otherwise reduced to `error`).
- `-h, --help`, `-V, --version`: Standard picocli options. `--version` reports the release the binary was built from.

## Exit Codes

| Code | Meaning |
| ---- | ------- |
| 0 | Success (also when snapshots failed but `--ignore-snapshot-errors` is set) |
| 1 | Unexpected error during execution (use `--debug` for the stack trace) |
| 2 | No packages specified at all |
| 3 | No package could be loaded |
| 4 | Cache metadata (`packages.ini`) missing a readable/supported `[cache] version` |
| 5 | `*.lock` files in the cache (see [Lock files](#lock-files)) or lock repair failed |
| 6 | At least one snapshot could not be generated |

## Lock Files

The package cache is shared state: `FilesystemPackageCacheManager` creates a `<package>.lock` file
next to the package directories and holds an exclusive OS-level lock on it for as long as it works
on that package. A run that finds lock files aborts with exit code 5 rather than risk two writers
in one package directory.

The repair, **on by default**, resolves the common case: a lock left behind by a process that was
killed before it could clean up, which would otherwise wedge every later run against that cache.
A lock file is deleted only when **both** hold:

- **Nobody holds the lock.** The tool tries to take the OS-level lock itself. Succeeding means the
  owning process is gone, because a live one keeps the lock for its whole operation. A lock file
  that cannot be opened or judged counts as held.
- **It is at least 5 minutes old.** The cache manager creates the file a moment before it locks it,
  so a brand-new unlocked file may belong to a process that is about to use it. This closes that
  window.

Lock files that fail either test are kept, listed by path, and the run aborts with exit code 5.

`--no-repair-lock-files` turns the repair off entirely, so any lock file aborts the run. Use it
where the cache lives on NFS or SMB: advisory locks are unreliable there, and the first test can
report a lock as free although a process on another host holds it.

## Version Handling

- Avoid mixing FHIR versions in one run; use one version per run (context comes from the first package).
- The version `current` is supported if the registry provides it.
- Missing versions in Sushi are interpreted as "latest".

## Output Layout

- For each loaded package, a directory `--out/<packageId>#<version>/` is created and the contents of the package are copied into it (`package/`, `example/`, `other/`, etc.).
- Only `StructureDefinition` JSON files are parsed and, if needed, replaced by a version containing a `snapshot` element in `package/`.
- Without `--overwrite`, existing files are never overwritten — with one exception: StructureDefinitions whose snapshot was regenerated (because none existed or because `--force-snapshot` is set) are always updated. With `--overwrite`, unchanged files are overwritten as well.
- With `--profiles-dir`, only local files are written to `--out/local` (packages are neither copied nor snapshotted). The original directory structure is mirrored. A base package (e.g. `hl7.fhir.r4.core`) should be given via `-p` so that base definitions and bindings can be resolved.
