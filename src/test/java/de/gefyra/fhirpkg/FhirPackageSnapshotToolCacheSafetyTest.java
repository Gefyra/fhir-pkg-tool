package de.gefyra.fhirpkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FhirPackageSnapshotToolCacheSafetyTest {

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
