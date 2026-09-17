package de.gefyra.fhirpkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class BuildVersionTest {

  @Test
  void current_isFilledInByTheBuild() {
    String version = BuildVersion.current();

    assertNotEquals("unknown", version, "the filtered version resource must be on the classpath");
    assertFalse(version.startsWith("${"), "the version placeholder must be resolved by filtering");
  }

  @Test
  void commandReportsTheBuildVersion() {
    String[] version = new CommandLine(new FhirPackageSnapshotTool()).getCommandSpec().version();

    assertEquals(1, version.length);
    assertEquals("fhir-pkg-tool " + BuildVersion.current(), version[0]);
  }
}
