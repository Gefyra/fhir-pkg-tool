# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The section for a version is what the release workflow publishes as the release notes, so every
release needs an entry here before it is tagged.

## [0.5.0] - unreleased

### Added

- A composite GitHub Action, so a pipeline can install packages and generate snapshots without
  wiring up the binary itself. By default it writes into the standard FHIR package cache, which
  means SUSHI, the IG Publisher or the validator in a later step of the same job find the packages
  where they expect them.
- `--no-repair-lock-files` to opt out of lock file repair, for caches on NFS or SMB where advisory
  locks cannot be trusted.

### Changed

- **Breaking:** lock file repair is now on by default, and it no longer deletes every lock file it
  finds. A lock is removed only when no process holds its OS-level lock and it has been untouched
  for five minutes; anything else is kept and the run still aborts with exit code 5. The previous
  behaviour deleted locks unconditionally, including ones another process was holding.
  `--repair-lock-files` is still accepted as a no-op.

### Fixed

- **Native binaries now work at all.** Up to and including 0.4.0 they were built without three data
  resources (`lang.dat.txt`, `languages.csv`, `spdx.json`) that the HL7 core library reads when it
  constructs a FHIR worker context, so every run that got past `--help` ended in a
  `NullPointerException`. The jar of those releases was unaffected. Anyone using a native binary
  should upgrade.
- `--version` reported `1.0-SNAPSHOT` for every release. It now reports the release the binary was
  built from.

### Documentation

- Installation section covering the released binaries and the jar, documentation for `--debug`, an
  exit code table, and a section on how lock files are handled. Corrected two wrong claims: that
  `--pretty` can be turned off, and the omission that `-o/--out` doubles as the package cache.

## [0.4.0] - 2026-09-02

### Added

- `--force-install` to reinstall packages that are already cached, for packages republished under
  the same version.

## [0.3.0] - 2026-08-04

### Added

- Install packages from local tarballs via `--package-file`, automatic loading of the matching FHIR
  core package as snapshot context, and tolerance for individual snapshot failures.

### Fixed

- Use a regex pattern instead of a glob in the native-image resource configuration.
- Resolve "Can't find bundle for base name Messages, locale en_US".
- Use the R5 snapshot generation code path.

## [0.2.0] - 2026-03-22

### Added

- Dependencies from `package.json`, cache management, and native image builds for ARM.

### Fixed

- Error handling during package loading.

## [0.1.0] - 2026-03-20

Initial release: download FHIR NPM packages, resolve dependencies recursively and generate
StructureDefinition snapshots, with jar and native binaries for Linux, macOS and Windows.

[0.5.0]: https://github.com/Gefyra/fhir-pkg-tool/releases/tag/v0.5.0
[0.4.0]: https://github.com/Gefyra/fhir-pkg-tool/releases/tag/v0.4.0
[0.3.0]: https://github.com/Gefyra/fhir-pkg-tool/releases/tag/v0.3.0
[0.2.0]: https://github.com/Gefyra/fhir-pkg-tool/releases/tag/v0.2.0
[0.1.0]: https://github.com/Gefyra/fhir-pkg-tool/releases/tag/v0.1.0
