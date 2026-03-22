package de.gefyra.fhirpkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FhirPackageSnapshotToolRuntimeTest {

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
}
