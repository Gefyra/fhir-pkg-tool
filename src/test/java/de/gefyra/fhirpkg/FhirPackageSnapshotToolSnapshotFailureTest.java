package de.gefyra.fhirpkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.gefyra.fhirpkg.testsupport.TestPackageTarball;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FhirPackageSnapshotToolSnapshotFailureTest {

  @Test
  void call_failsWithExitCodeSixWhenSnapshotCannotBeBuilt(@TempDir Path tempDir) throws Exception {
    FhirPackageSnapshotTool tool = toolWithUnbuildableProfile(tempDir);

    int exit = tool.call();

    assertEquals(6, exit);
  }

  @Test
  void call_processesRemainingFilesAndKeepsResourceWhenSnapshotFails(@TempDir Path tempDir)
      throws Exception {
    FhirPackageSnapshotTool tool = toolWithUnbuildableProfile(tempDir);

    tool.call();

    // The run continued past the failure: the package is fully materialised in the output.
    Path pkgDir = tempDir.resolve("out").resolve("demo.local.pkg#0.1.20").resolve("package");
    assertTrue(Files.exists(pkgDir.resolve("package.json")));
    String sd = Files.readString(pkgDir.resolve("StructureDefinition-DemoPatient.json"));
    assertFalse(sd.contains("\"snapshot\""), "resource should be kept unchanged, not half-written");
  }

  @Test
  void call_exitsZeroWhenSnapshotErrorsAreIgnored(@TempDir Path tempDir) throws Exception {
    FhirPackageSnapshotTool tool = toolWithUnbuildableProfile(tempDir);
    tool.ignoreSnapshotErrors = true;

    int exit = tool.call();

    assertEquals(0, exit);
  }

  /**
   * A local package whose profile derives from Patient, with dependency resolution and the core
   * auto-load both switched off - so the snapshot context is empty and the base cannot resolve.
   */
  private static FhirPackageSnapshotTool toolWithUnbuildableProfile(Path tempDir) throws Exception {
    Path tgz = TestPackageTarball.write(tempDir.resolve("demo.local.pkg-0.1.20.tgz"),
        "demo.local.pkg", "0.1.20",
        Map.of("StructureDefinition-DemoPatient.json", TestPackageTarball.PATIENT_PROFILE));

    FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
    tool.outDir = tempDir.resolve("out");
    tool.packageFiles = List.of(tgz);
    tool.skipDependencies = true;
    tool.noAutoCore = true;
    return tool;
  }
}
