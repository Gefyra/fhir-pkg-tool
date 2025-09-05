# FHIR Package Snapshot Tool (Java 21, HAPI 8)

A small CLI tool that downloads FHIR NPM packages from the registry, resolves recursive dependencies, collects all `StructureDefinition`s, and generates snapshots.

## Features
- Multiple packages via `-p` (also comma-separated)
- Dependencies from `sushi-config.yaml` (file or YAML string) – supports both Sushi structures
- Automatic FHIR context detection (R4, R4B, R5; DSTU3 fallback)
- Snapshot generation (optional `--force-snapshot`)
- Output as JSON in a target directory

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

## Notes
- Avoid mixing FHIR versions in one run; use one version per run (context comes from the first package).
- The version `current` is supported if the registry provides it.
- Missing versions in Sushi are interpreted as "latest".
