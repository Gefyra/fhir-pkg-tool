package de.gefyra.fhirpkg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FhirPackageSnapshotToolParsingTest {

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
  void parseSushiFhirVersion_readsInlineVersion() {
    String yaml = """
        fhirVersion: 4.0.1
        dependencies:
          de.basisprofil.r4: 1.5.4
        """;

    Optional<String> result = FhirPackageSnapshotTool.parseSushiFhirVersion(yaml);

    assertEquals(Optional.of("4.0.1"), result);
  }
}
