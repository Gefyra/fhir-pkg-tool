package de.gefyra.fhirpkg;

import java.util.regex.Pattern;

final class JsonFieldExtractor {

  private JsonFieldExtractor() {
  }

  static String getUrlFromJson(String json) {
    String u = extractJsonString(json, "url");
    return (u != null) ? u : "";
  }

  static String getNameFromJson(String json) {
    String n = extractJsonString(json, "name");
    return (n != null) ? n : "StructureDefinition";
  }

  static String extractJsonString(String json, String field) {
    String regex = "\\\"" + field + "\\\"\\s*:\\s*\\\"(.*?)\\\"";
    var m = Pattern.compile(regex, Pattern.DOTALL).matcher(json);
    if (m.find()) {
      return m.group(1);
    }
    return null;
  }
}

