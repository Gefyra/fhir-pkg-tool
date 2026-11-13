package de.gefyra.fhirpkg.snapshot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The snapshot context applies the corrections to known defects in the core definitions that the
 * IG Publisher applies (StructureDefinitionHacker), so derived snapshots do not inherit them.
 */
class CoreDefinitionFixesTest {

  private static final String CORE_CONSENT = """
      {
        "resourceType": "StructureDefinition",
        "id": "Consent",
        "url": "http://hl7.org/fhir/StructureDefinition/Consent",
        "name": "Consent",
        "status": "active",
        "fhirVersion": "4.0.1",
        "kind": "resource",
        "abstract": false,
        "type": "Consent",
        "baseDefinition": "http://hl7.org/fhir/StructureDefinition/DomainResource",
        "derivation": "specialization",
        "snapshot": { "element": [
          { "id": "Consent", "path": "Consent", "min": 0, "max": "*",
            "base": { "path": "Consent", "min": 0, "max": "*" } },
          { "id": "Consent.identifier", "path": "Consent.identifier", "min": 0, "max": "*",
            "base": { "path": "Consent.identifier", "min": 0, "max": "*" },
            "type": [ { "code": "Identifier" } ],
            "example": [ { "label": "General",
              "valueIdentifier": { "system": "urn:ietf:rfc:3986", "value": "Local eCMS identifier" } } ] }
        ] },
        "differential": { "element": [
          { "id": "Consent", "path": "Consent" },
          { "id": "Consent.identifier", "path": "Consent.identifier",
            "example": [ { "label": "General",
              "valueIdentifier": { "system": "urn:ietf:rfc:3986", "value": "Local eCMS identifier" } } ] }
        ] }
      }
      """;

  private static final String DERIVED = """
      {
        "resourceType": "StructureDefinition",
        "id": "derived-consent",
        "url": "http://example.org/StructureDefinition/derived-consent",
        "name": "DerivedConsent",
        "status": "draft",
        "fhirVersion": "4.0.1",
        "kind": "resource",
        "abstract": false,
        "type": "Consent",
        "baseDefinition": "http://hl7.org/fhir/StructureDefinition/Consent",
        "derivation": "constraint",
        "differential": { "element": [ { "id": "Consent", "path": "Consent" } ] }
      }
      """;

  @Test
  void derivedSnapshotGetsCorrectedConsentIdentifierExample(@TempDir Path dir) throws Exception {
    Path pkgDir = Files.createDirectories(dir.resolve("package"));
    Files.writeString(pkgDir.resolve("package.json"),
        "{\"name\":\"test.core\",\"version\":\"4.0.1\",\"fhirVersions\":[\"4.0.1\"]}",
        StandardCharsets.UTF_8);
    Files.writeString(pkgDir.resolve("StructureDefinition-Consent.json"), CORE_CONSENT,
        StandardCharsets.UTF_8);
    NpmPackage pkg = NpmPackage.fromFolder(dir.toString());

    SnapshotSupport.SnapshotEngine engine = SnapshotSupport.createSnapshotEngine(
        SnapshotSupport.FhirRelease.R4, "4.0.1", List.of(pkg));
    String snapshot = engine.generateSnapshot(DERIVED, false, null, null);

    assertTrue(snapshot.contains("http://acme.org/identifier/local/eCMS"), snapshot);
    assertFalse(snapshot.contains("urn:ietf:rfc:3986"), snapshot);
  }
}
