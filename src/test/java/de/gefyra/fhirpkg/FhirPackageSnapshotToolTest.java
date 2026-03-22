package de.gefyra.fhirpkg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FhirPackageSnapshotToolTest {

    @Test
    void parseSushiDepsYaml_readsDependenciesAndSkipsKnownProblematicVersion() {
        String yaml = """
                dependencies:
                  de.basisprofil.r4: 1.5.4
                  de.gematik.terminology:
                    version: 1.0.6
                  hl7.fhir.extensions.r5: 4.0.1
                """;

        List<String> result = FhirPackageSnapshotTool.parseSushiDepsYaml(yaml);

        assertEquals(List.of(
                "de.basisprofil.r4@1.5.4",
                "de.gematik.terminology@1.0.6"
        ), result);
    }

    @Test
    void parseSushiDepsYaml_returnsEmptyWhenDependenciesSectionIsMissing() {
        String yaml = """
                name: example
                id: some.project
                """;

        List<String> result = FhirPackageSnapshotTool.parseSushiDepsYaml(yaml);

        assertEquals(List.of(), result);
    }

    @Test
    void parsePackageJsonDependencies_readsOnlyDependenciesAndIgnoresDevDependencies() {
        String packageJson = """
                {
                  "name": "de.gematik.isik-basismodul",
                  "version": "6.0.0-rc",
                  "dependencies": {
                    "hl7.fhir.uv.subscriptions-backport.r4": "1.1.0",
                    "de.basisprofil.r4": "1.5.4",
                    "hl7.fhir.extensions.r5": "4.0.1"
                  },
                  "devDependencies": {
                    "hl7.fhir.uv.ips": "2.0.0"
                  }
                }
                """;

        List<String> result = FhirPackageSnapshotTool.parsePackageJsonDependencies(packageJson);

        assertEquals(List.of(
                "hl7.fhir.uv.subscriptions-backport.r4@1.1.0",
                "de.basisprofil.r4@1.5.4"
        ), result);
    }

    @Test
    void selectLatestCoordinatesByPackageId_picksNewestSemVerForDuplicatePackageIds() {
        List<String> result = FhirPackageSnapshotTool.selectLatestCoordinatesByPackageId(List.of(
                "de.test@1.5.4",
                "de.test@2.0.0-rc",
                "de.test@2.0.0",
                "de.other@2025.0.1",
                "de.other@2024.9.9",
                "de.unversioned",
                "de.unversioned@1.0.0"
        ));

        assertEquals(List.of(
                "de.test@2.0.0",
                "de.other@2025.0.1",
                "de.unversioned@1.0.0"
        ), result);
    }

    @Test
    void parseSushiFhirVersion_readsInlineVersion() {
        String yaml = """
                fhirVersion: 4.0.1
                dependencies:
                  de.basisprofil.r4: 1.5.4
                """;

        Optional<String> result = FhirPackageSnapshotTool.parseSushiFhirVersion(yaml);

        assertEquals(Optional.of("4.0.1"), result);
    }

    @Test
    void parseCacheVersionFromIni_readsCacheSectionVersion() {
        String ini = """
                [cache]
                version=3
                [other]
                foo=bar
                """;

        Optional<String> result = FhirPackageSnapshotTool.parseCacheVersionFromIni(ini);

        assertEquals(Optional.of("3"), result);
    }

    @Test
    void parseCacheVersionFromIni_returnsEmptyWhenCacheVersionMissing() {
        String ini = """
                [cache]
                somethingElse=1
                """;

        Optional<String> result = FhirPackageSnapshotTool.parseCacheVersionFromIni(ini);

        assertEquals(Optional.empty(), result);
    }

    @Test
    void isSupportedCacheVersion_acceptsVersion4AndRejectsOthers() {
        assertTrue(FhirPackageSnapshotTool.isSupportedCacheVersion("4"));
        assertTrue(FhirPackageSnapshotTool.isSupportedCacheVersion(" 4 "));
        assertFalse(FhirPackageSnapshotTool.isSupportedCacheVersion("3"));
        assertFalse(FhirPackageSnapshotTool.isSupportedCacheVersion(""));
    }

    @Test
    void shouldValidateDefaultCacheSafety_trueForDefaultAndFalseForCustomPath() {
        Path defaultDir = FhirPackageSnapshotTool.defaultCacheDir().toAbsolutePath().normalize();
        Path customDir = defaultDir.resolve("custom-output");
        assertTrue(FhirPackageSnapshotTool.shouldValidateDefaultCacheSafety(defaultDir));
        assertFalse(FhirPackageSnapshotTool.shouldValidateDefaultCacheSafety(customDir));
    }

    @Test
    void summarizeException_returnsDeepestCauseMessage() {
        RuntimeException ex = new RuntimeException("outer", new IllegalStateException("inner"));
        assertEquals("inner", FhirPackageSnapshotTool.summarizeException(ex));
    }

    @Test
    void summarizeException_fallsBackToClassNameWhenNoMessage() {
        RuntimeException ex = new RuntimeException((String) null);
        assertEquals("RuntimeException", FhirPackageSnapshotTool.summarizeException(ex));
    }

    @Test
    void hasDebugFlag_detectsLongOption() {
        assertTrue(FhirPackageSnapshotTool.hasDebugFlag(new String[]{"--debug"}));
        assertFalse(FhirPackageSnapshotTool.hasDebugFlag(new String[]{"-p", "de.test@1.0.0"}));
    }

    @Test
    void call_checksLockFilesAlsoForCustomOut(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("custom-out");
        Files.createDirectories(out);
        Files.writeString(out.resolve("test.lock"), "locked");

        FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
        tool.outDir = out;

        int exit = tool.call();

        assertEquals(5, exit);
    }

    @Test
    void call_repairLockFilesWorksAlsoForCustomOut(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("custom-out");
        Files.createDirectories(out);
        Path lock = out.resolve("repair.lock");
        Files.writeString(lock, "locked");

        FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
        tool.outDir = out;
        tool.repairLockFiles = true;

        int exit = tool.call();

        assertEquals(2, exit);
        assertFalse(Files.exists(lock));
    }

    @Test
    void call_missingPackagesIniOnCustomOutIsAllowed(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("custom-out");

        FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
        tool.outDir = out;

        int exit = tool.call();

        assertEquals(2, exit);
    }

    @Test
    void call_invalidPackagesIniOnCustomOutFails(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("custom-out");
        Files.createDirectories(out);
        Files.writeString(out.resolve("packages.ini"), """
                [cache]
                version = 3
                """);

        FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
        tool.outDir = out;

        int exit = tool.call();

        assertEquals(4, exit);
    }
}
