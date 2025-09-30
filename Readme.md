# FHIR Package Snapshot Tool (Java 21, HAPI 8)

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
- Dependencies from `sushi-config.yaml` (file or YAML string) – supports both Sushi structures
- Automatic FHIR context detection (R4, R4B, R5; DSTU3 fallback)
- Snapshot generation (optional `--force-snapshot`)
- Output layout: one subfolder per package in `--out`, named `<packageId>#<version>` (e.g. `hl7.fhir.us.core#6.1.0`). The complete package is copied there; only `StructureDefinition` JSON files are parsed and (re)written with snapshots.
- Local profiles folder via `--profiles-dir` (recursively loads JSON `StructureDefinition`s and writes snapshots under `--out/local`)

## Build
```bash
mvn -q -DskipTests package
```

## Run – Examples

**Multiple packages:**
```bash
java -jar target/fhir-pkg-tool-jar-with-dependencies.jar   -p hl7.fhir.r4.core@4.0.1   -p hl7.fhir.us.core@6.1.0,hl7.fhir.au.core@5.0.0   -o ./out/mix
```

**Dependencies from Sushi file:**
```bash
java -jar target/fhir-pkg-tool-jar-with-dependencies.jar   --sushi-deps-file ./sushi-config.yaml   -o ./out/from-sushi
```

**Dependencies from inline YAML:**
```bash
java -jar target/fhir-pkg-tool-jar-with-dependencies.jar   --sushi-deps-str "$(cat <<'YAML' 
dependencies:
  hl7.fhir.us.core: 3.1.0
  hl7.fhir.us.mcode:
    id: mcode
    uri: http://hl7.org/fhir/us/mcode/ImplementationGuide/hl7.fhir.us.mcode
    version: 1.0.0
YAML
)"   -o ./out/from-inline
```

**Always rebuild snapshots:**
```bash
java -jar target/fhir-pkg-tool-jar-with-dependencies.jar   --sushi-deps-file ./sushi-config.yaml   --force-snapshot
```

**Only root packages (skip dependencies):**
```bash
java -jar target/fhir-pkg-tool-jar-with-dependencies.jar   -p hl7.fhir.r5.core@5.0.0 -p hl7.fhir.uv.tools@current   --skip-deps
```

**Local profiles from a folder (local-only output):**
```bash
java -jar target/fhir-pkg-tool-jar-with-dependencies.jar \
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

## CLI Options

- `-p, --package`: One or more package coordinates (`name@version`). Comma-separated allowed, can be repeated.
- `--sushi-deps-file`: Path to a `sushi-config.yaml` (dependencies are read).
- `--sushi-deps-str`: Inline YAML block containing `dependencies:`.
- `-o, --out`: Output directory (default: `%APPDATA%\fhir\packages` on Windows, `~/.fhir/packages` on Linux/macOS). Each package is copied to a subfolder `<id>#<version>`.
- `--cache`: Local NPM package cache directory (default: `~/.fhir/packages`).
- `--registry`: Package registry URL (default: `https://packages.fhir.org`).
- `--skip-deps`: Do not auto-load transitive dependencies.
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
