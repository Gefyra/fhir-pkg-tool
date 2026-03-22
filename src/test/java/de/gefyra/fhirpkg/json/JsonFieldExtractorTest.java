package de.gefyra.fhirpkg.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class JsonFieldExtractorTest {

  @Test
  void extractProfileFields_readsExpectedFields() {
    String json = """
        {
          "resourceType": "StructureDefinition",
          "url": "http://example.org/StructureDefinition/test",
          "name": "MyProfile"
        }
        """;

    JsonFieldExtractor.ProfileFields fields = JsonFieldExtractor.extractProfileFields(json);

    assertEquals("StructureDefinition", fields.resourceType());
    assertEquals("http://example.org/StructureDefinition/test", fields.url());
    assertEquals("MyProfile", fields.name());
  }

  @Test
  void extractProfileFields_returnsDefaultsOnInvalidJson() {
    JsonFieldExtractor.ProfileFields fields = JsonFieldExtractor.extractProfileFields("{not-json");

    assertNull(fields.resourceType());
    assertEquals("", fields.url());
    assertEquals("StructureDefinition", fields.name());
  }
}
