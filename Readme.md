# FHIR Package Snapshot Tool (Java 21)

[![Build](https://github.com/Gefyra/fhir-pkg-tool/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Gefyra/fhir-pkg-tool/actions/workflows/ci.yml)
[![Release Workflow](https://github.com/Gefyra/fhir-pkg-tool/actions/workflows/release.yml/badge.svg)](https://github.com/Gefyra/fhir-pkg-tool/actions/workflows/release.yml)
[![Latest Release](https://img.shields.io/github/v/release/Gefyra/fhir-pkg-tool?logo=github&label=Latest%20Release)](https://github.com/Gefyra/fhir-pkg-tool/releases/latest)
![Dependabot](https://img.shields.io/badge/dependabot-enabled-blue?logo=dependabot)
[![License](https://img.shields.io/github/license/Gefyra/fhir-pkg-tool?logo=opensource&label=License)](LICENSE)
![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![FHIR](https://img.shields.io/badge/FHIR-R4%20%7C%20R4B%20%7C%20R5-red?logo=hl7&logoColor=white)

A small CLI tool that downloads FHIR NPM packages from the registry, resolves recursive dependencies, and generates snapshots for `StructureDefinition`s.

## Features
- Multiple packages via `-p` (also comma-separated)
- Local package tarballs via `--package-file` (equivalent to `fhir install <file.tgz> --file`)
- Reinstall of already cached packages via `--force-install`
- Dependencies from `sushi-config.yaml` (file or YAML string) – supports both Sushi structures
- Dependencies from `package.json` via `dependencies` (ignores `devDependencies`)
- Automatic FHIR context detection (R4, R4B, R5; DSTU3 fallback)
- Snapshot generation (optional `--force-snapshot`)
- If the same package ID is provided multiple times with different versions, the newest SemVer is selected
- Output layout: one subfolder per package in `--out`, named `<packageId>#<version>` (e.g. `hl7.fhir.us.core#6.1.0`). The complete package is copied there; only `StructureDefinition` JSON files are parsed and (re)written with snapshots.
- Local profiles folder via `--profiles-dir` (recursively loads JSON `StructureDefinition`s and writes snapshots under `--out/local`)

## Run – Examples

**Multiple packages:**
```bash
java -jar target/fhir-pkg-tool.jar   -p hl7.fhir.r4.core@4.0.1   -p hl7.fhir.us.core@6.1.0,hl7.fhir.au.core@5.0.0   -o ./out/mix
```

**Dependencies from Sushi file:**
```bash
java -jar target/fhir-pkg-tool.jar   --sushi-deps-file ./sushi-config.yaml   -o ./out/from-sushi
```

**Dependencies from inline YAML:**
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

**Dependencies from package.json:**
```bash
java -jar target/fhir-pkg-tool.jar   --package-json-file ./package.json   -o ./out/from-package-json
```

**Install a package from a local tarball (like `fhir install <file> --file`):**
```bash
java -jar target/fhir-pkg-tool.jar   --package-file ./packages/molit-service.fhir.vitu-0.1.20.tgz
```
Notes:
- Package id and version are taken from the tarball's own `package.json`; the package is installed into the cache as `<packageId>#<version>`.
- Can be repeated and combined with `-p`/`--sushi-deps-*`; a local tarball wins over a registry package with the same id.
- If `<packageId>#<version>` is already in the cache, the cached copy is kept and the tarball is ignored (the tool says so). Use `--force-install` to drop the cached copy and install the tarball instead.
- Dependencies of the local package are resolved from the registry unless `--skip-deps` is given.

**Always rebuild snapshots:**
```bash
java -jar target/fhir-pkg-tool.jar   --sushi-deps-file ./sushi-config.yaml   --force-snapshot
```

**Only root packages (skip dependencies):**
```bash
java -jar target/fhir-pkg-tool.jar   -p hl7.fhir.r5.core@5.0.0 -p hl7.fhir.uv.tools@current   --skip-deps
```

**Local profiles from a folder (local-only output):**
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
- Bei Nutzung von `--sushi-deps-file` wird die dort hinterlegte `fhirVersion` für den Kontext herangezogen; ohne Sushi-Datei übernimmt das erste geladene Paket die Wahl bzw. fällt das Tool auf R5 zurück.

**Reinstall a package that was republished under the same version:**
```bash
java -jar target/fhir-pkg-tool.jar   -p de.medizininformatikinitiative.kerndatensatz.labor@2027.0.0-ballot.rc1   --force-install
```
The cache keys packages by `<packageId>#<version>`, so a package that is rebuilt without a version bump (a ballot RC, a nightly IG build) is never picked up again: the cached copy wins and the tool reports that it ignored the newer one. `--force-install` drops the cached copy first.

Notes:
- Applies to the packages you ask for via `-p` and `--package-file`. Dependencies and the auto-loaded FHIR core package keep using the cache, so a forced run does not re-download the whole tree.
- Needs an explicit version (`name@version`); without one there is no single cache entry to drop and the tool says so.
- `current` and `dev` are always refreshed anyway, with or without the flag.
- For `-p`, the cached copy is deleted before the package is fetched again — if that download fails, the package stays uninstalled until the next successful run. `--package-file` has no such risk, the tarball is local.
- Not to be confused with `--force-snapshot`, which regenerates snapshots for packages that are already installed.

## CLI Options

- `-p, --package`: One or more package coordinates (`name@version`). Comma-separated allowed, can be repeated.
- `--package-file, --file`: Path to a local package tarball (`*.tgz`) that is installed into the cache. Repeatable. Package id and version are read from the tarball's `package.json`.
- `--force-install`: Reinstall the requested packages (`-p`, `--package-file`) even when they are already cached. Without it, an existing `<packageId>#<version>` in the cache is used as-is.
- `--sushi-deps-file`: Path to a `sushi-config.yaml` (dependencies are read).
- `--sushi-deps-str`: Inline YAML block containing `dependencies:`.
- `--package-json-file`: Path to a `package.json` file. Only `dependencies` are used (`devDependencies` are ignored).
- `-o, --out`: Output directory (default: `~/.fhir/packages`; on Windows: `C:\Users\<USER>\.fhir\packages`).
- `--repair-lock-files`: Deletes `*.lock` files in the default cache directory before package loading.
- `--registry`: Package registry URL (default: `https://packages.fhir.org`).
- `--skip-deps`: Do not auto-load transitive dependencies.
- `--no-auto-core`: Do not automatically load the matching FHIR core package as snapshot context when it is missing from the resolved packages.
- `--ignore-snapshot-errors`: Exit with 0 even when snapshots failed (default: exit code 6).
- `--overwrite`: Overwrite existing files in the output (both copied and snapshotted).
- `--pretty`: Pretty-print JSON output for rewritten StructureDefinitions (default: true).
- `--force-snapshot`: Always regenerate snapshots even if a snapshot exists.
- `--profiles-dir`: Directory containing local `StructureDefinition` JSON files (processed recursively, written under `--out/local`). When set, package contents are not written.

## Notes
- Avoid mixing FHIR versions in one run; use one version per run (context comes from the first package).
- The version `current` is supported if the registry provides it.
- Missing versions in Sushi are interpreted as "latest".

## Output Layout

- For each loaded package, a directory `--out/<packageId>#<version>/` is created and the contents of the package are copied into it (`package/`, `example/`, `other/`, etc.).
- Only `StructureDefinition` JSON files are parsed and, if needed, replaced by a version containing a `snapshot` element in `package/`.
- Ohne `--overwrite` werden bestehende Dateien grundsätzlich nicht überschrieben – Ausnahme: StructureDefinitions, für die ein Snapshot neu generiert wurde (weil keiner vorhanden war oder `--force-snapshot` gesetzt ist), werden immer aktualisiert. Mit `--overwrite` werden außerdem unveränderte Dateien überschrieben.
- Für `--profiles-dir` werden nur lokale Dateien nach `--out/local` geschrieben (Packages werden nicht kopiert/gesnapshottet). Die Original-Verzeichnisstruktur wird gespiegelt. Eine Basis-Package (z. B. `hl7.fhir.r4.core`) sollte via `-p` angegeben werden, damit Basen/Bindings aufgelöst werden können.
