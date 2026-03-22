package de.gefyra.fhirpkg.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SnapshotSupportTest {

  @Test
  void resolveFhirRelease_mapsR4AndR4BAndR5() {
    assertEquals(SnapshotSupport.FhirRelease.R4, SnapshotSupport.resolveFhirRelease("4.0.1"));
    assertEquals(SnapshotSupport.FhirRelease.R4B, SnapshotSupport.resolveFhirRelease("4.3.0"));
    assertEquals(SnapshotSupport.FhirRelease.R5, SnapshotSupport.resolveFhirRelease("5.0.0"));
  }

  @Test
  void resolveFhirRelease_defaultsToR5ForUnknownOrEmpty() {
    assertEquals(SnapshotSupport.FhirRelease.R5, SnapshotSupport.resolveFhirRelease(null));
    assertEquals(SnapshotSupport.FhirRelease.R5, SnapshotSupport.resolveFhirRelease(""));
    assertEquals(SnapshotSupport.FhirRelease.R5, SnapshotSupport.resolveFhirRelease("abc"));
  }
}
