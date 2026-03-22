package de.gefyra.fhirpkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class FhirPackageSnapshotToolVersioningTest {

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
  void selectLatestCoordinatesByPackageId_throwsOnInvalidVersion() {
    assertThrows(IllegalArgumentException.class, () ->
        FhirPackageSnapshotTool.selectLatestCoordinatesByPackageId(List.of(
            "de.test@1.0.0",
            "de.test@foo"
        )));
  }
}
