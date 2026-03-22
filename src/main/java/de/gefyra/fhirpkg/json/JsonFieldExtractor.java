package de.gefyra.fhirpkg.json;

import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;

public final class JsonFieldExtractor {

  private JsonFieldExtractor() {
  }

  public record ProfileFields(String resourceType, String url, String name) {
  }

  public static ProfileFields extractProfileFields(String json) {
    JsonObject obj = parseObject(json);
    if (obj == null) {
      return new ProfileFields(null, "", "StructureDefinition");
    }
    return new ProfileFields(
        blankToNull(obj.asString("resourceType")),
        defaultString(obj.asString("url"), ""),
        defaultString(obj.asString("name"), "StructureDefinition")
    );
  }

  private static JsonObject parseObject(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return JsonParser.parseObject(json);
    } catch (Exception e) {
      return null;
    }
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value;
  }

  private static String defaultString(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value;
  }
}
