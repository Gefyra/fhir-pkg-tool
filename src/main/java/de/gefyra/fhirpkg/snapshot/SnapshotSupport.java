package de.gefyra.fhirpkg.snapshot;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.hl7.fhir.utilities.VersionUtilities;
import org.hl7.fhir.utilities.npm.NpmPackage;

public final class SnapshotSupport {

  private SnapshotSupport() {
  }

  public enum FhirRelease {
    R4,
    R4B,
    R5
  }

  public interface SnapshotEngine {

    String generateSnapshot(String json, boolean pretty, String profileUrl, String profileName)
        throws Exception;

    void cacheResource(String json) throws Exception;
  }

  public static FhirRelease resolveFhirRelease(String version) {
    String fhirVer = Optional.ofNullable(version).orElse("").toLowerCase(Locale.ROOT);
    try {
      if (VersionUtilities.isR4BVer(fhirVer)) {
        return FhirRelease.R4B;
      }
      if (VersionUtilities.isR4Ver(fhirVer)) {
        return FhirRelease.R4;
      }
    } catch (Exception e) {
      return FhirRelease.R5;
    }
    return FhirRelease.R5;
  }

  public static SnapshotEngine createSnapshotEngine(FhirRelease release, List<NpmPackage> pkgs)
      throws Exception {
    return switch (release) {
      case R4 -> createR4SnapshotEngine(pkgs);
      case R4B -> createR4bSnapshotEngine(pkgs);
      case R5 -> createR5SnapshotEngine(pkgs);
    };
  }

  private static SnapshotEngine createR4SnapshotEngine(List<NpmPackage> pkgs) throws Exception {
    final org.hl7.fhir.r4.context.SimpleWorkerContext context = org.hl7.fhir.r4.context.SimpleWorkerContext.fromNothing();
    preloadR4StructureDefinitions(context, pkgs);
    context.setCanRunWithoutTerminology(true);

    return new SnapshotEngine() {
      @Override
      public String generateSnapshot(String json, boolean pretty, String profileUrl,
          String profileName) throws Exception {
        org.hl7.fhir.r4.formats.IParser parser = context.newJsonParser();
        org.hl7.fhir.r4.model.Resource parsed = parser.parse(json);
        if (!(parsed instanceof org.hl7.fhir.r4.model.StructureDefinition sd)) {
          throw new IllegalArgumentException("Resource is not a StructureDefinition");
        }
        context.generateSnapshot(sd);
        parser.setOutputStyle(pretty
            ? org.hl7.fhir.r4.formats.IParser.OutputStyle.PRETTY
            : org.hl7.fhir.r4.formats.IParser.OutputStyle.NORMAL);
        return parser.composeString(sd);
      }

      @Override
      public void cacheResource(String json) throws Exception {
        org.hl7.fhir.r4.model.Resource parsed = context.newJsonParser().parse(json);
        if (parsed != null) {
          context.cacheResource(parsed);
        }
      }
    };
  }

  private static SnapshotEngine createR4bSnapshotEngine(List<NpmPackage> pkgs) throws Exception {
    final org.hl7.fhir.r4b.context.SimpleWorkerContext context = org.hl7.fhir.r4b.context.SimpleWorkerContext.fromNothing();
    preloadR4bStructureDefinitions(context, pkgs);
    context.setCanRunWithoutTerminology(true);
    context.setNoTerminologyServer(true);

    return new SnapshotEngine() {
      @Override
      public String generateSnapshot(String json, boolean pretty, String profileUrl,
          String profileName) throws Exception {
        org.hl7.fhir.r4b.formats.IParser parser = context.newJsonParser();
        org.hl7.fhir.r4b.model.Resource parsed = parser.parse(json);
        if (!(parsed instanceof org.hl7.fhir.r4b.model.StructureDefinition sd)) {
          throw new IllegalArgumentException("Resource is not a StructureDefinition");
        }
        context.generateSnapshot(sd);
        parser.setOutputStyle(pretty
            ? org.hl7.fhir.r4b.formats.IParser.OutputStyle.PRETTY
            : org.hl7.fhir.r4b.formats.IParser.OutputStyle.NORMAL);
        return parser.composeString(sd);
      }

      @Override
      public void cacheResource(String json) throws Exception {
        org.hl7.fhir.r4b.model.Resource parsed = context.newJsonParser().parse(json);
        if (parsed != null) {
          context.cacheResource(parsed);
        }
      }
    };
  }

  private static SnapshotEngine createR5SnapshotEngine(List<NpmPackage> pkgs) throws Exception {
    final org.hl7.fhir.r5.context.SimpleWorkerContext context;
    org.hl7.fhir.r5.context.SimpleWorkerContext.SimpleWorkerContextBuilder builder =
        new org.hl7.fhir.r5.context.SimpleWorkerContext.SimpleWorkerContextBuilder();
    context = builder.fromNothing();
    preloadR5StructureDefinitions(context, pkgs);
    context.setCanRunWithoutTerminology(true);
    context.setNoTerminologyServer(true);
    context.setCanNoTS(true);

    return new SnapshotEngine() {
      @Override
      public String generateSnapshot(String json, boolean pretty, String profileUrl,
          String profileName) throws Exception {
        org.hl7.fhir.r5.formats.IParser parser = new org.hl7.fhir.r5.formats.JsonParser();
        org.hl7.fhir.r5.model.Resource parsed = parser.parse(json);
        if (!(parsed instanceof org.hl7.fhir.r5.model.StructureDefinition sd)) {
          throw new IllegalArgumentException("Resource is not a StructureDefinition");
        }
        if (!sd.hasBaseDefinition()) {
          throw new IllegalArgumentException("StructureDefinition has no baseDefinition");
        }
        org.hl7.fhir.r5.model.StructureDefinition base =
            context.fetchResource(org.hl7.fhir.r5.model.StructureDefinition.class,
                sd.getBaseDefinition());
        if (base == null) {
          throw new IllegalStateException(
              "Base StructureDefinition not found: " + sd.getBaseDefinition());
        }
        org.hl7.fhir.r5.conformance.profile.ProfileUtilities profileUtilities =
            new org.hl7.fhir.r5.conformance.profile.ProfileUtilities(context, new ArrayList<>(),
                null);
        String resolvedUrl = sd.hasUrl() ? sd.getUrl() : Optional.ofNullable(profileUrl).orElse("");
        String resolvedName = sd.hasName() ? sd.getName()
            : Optional.ofNullable(profileName).orElse("StructureDefinition");
        profileUtilities.generateSnapshot(base, sd, resolvedUrl, null, resolvedName);
        parser.setOutputStyle(pretty
            ? org.hl7.fhir.r5.formats.IParser.OutputStyle.PRETTY
            : org.hl7.fhir.r5.formats.IParser.OutputStyle.NORMAL);
        return parser.composeString(sd);
      }

      @Override
      public void cacheResource(String json) throws Exception {
        org.hl7.fhir.r5.model.Resource parsed = new org.hl7.fhir.r5.formats.JsonParser().parse(
            json);
        if (parsed != null) {
          context.cacheResource(parsed);
        }
      }
    };
  }

  private static void preloadR4StructureDefinitions(org.hl7.fhir.r4.context.SimpleWorkerContext context,
      List<NpmPackage> pkgs) throws Exception {
    Set<String> loadedKeys = new HashSet<>();
    org.hl7.fhir.r4.formats.IParser parser = context.newJsonParser();
    for (NpmPackage pkg : pkgs) {
      for (String resName : pkg.listResources("StructureDefinition")) {
        try (InputStream is = pkg.load("package", resName)) {
          if (is == null) {
            continue;
          }
          String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
          org.hl7.fhir.r4.model.Resource parsed = parser.parse(json);
          if (!(parsed instanceof org.hl7.fhir.r4.model.StructureDefinition sd)) {
            continue;
          }
          String key = structureDefinitionKey(sd.getUrl(), sd.getId());
          if (key != null && !loadedKeys.add(key)) {
            continue;
          }
          try {
            context.cacheResource(sd);
          } catch (org.hl7.fhir.exceptions.DefinitionException ignored) {
            // Duplicates across packages are expected for some canonicals.
          }
        }
      }
    }
  }

  private static void preloadR4bStructureDefinitions(
      org.hl7.fhir.r4b.context.SimpleWorkerContext context, List<NpmPackage> pkgs) throws Exception {
    Set<String> loadedKeys = new HashSet<>();
    org.hl7.fhir.r4b.formats.IParser parser = context.newJsonParser();
    for (NpmPackage pkg : pkgs) {
      for (String resName : pkg.listResources("StructureDefinition")) {
        try (InputStream is = pkg.load("package", resName)) {
          if (is == null) {
            continue;
          }
          String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
          org.hl7.fhir.r4b.model.Resource parsed = parser.parse(json);
          if (!(parsed instanceof org.hl7.fhir.r4b.model.StructureDefinition sd)) {
            continue;
          }
          String key = structureDefinitionKey(sd.getUrl(), sd.getId());
          if (key != null && !loadedKeys.add(key)) {
            continue;
          }
          try {
            context.cacheResource(sd);
          } catch (org.hl7.fhir.exceptions.DefinitionException ignored) {
            // Duplicates across packages are expected for some canonicals.
          }
        }
      }
    }
  }

  private static void preloadR5StructureDefinitions(org.hl7.fhir.r5.context.SimpleWorkerContext context,
      List<NpmPackage> pkgs) throws Exception {
    Set<String> loadedKeys = new HashSet<>();
    org.hl7.fhir.r5.formats.JsonParser parser = new org.hl7.fhir.r5.formats.JsonParser();
    for (NpmPackage pkg : pkgs) {
      for (String resName : pkg.listResources("StructureDefinition")) {
        try (InputStream is = pkg.load("package", resName)) {
          if (is == null) {
            continue;
          }
          String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
          org.hl7.fhir.r5.model.Resource parsed = parser.parse(json);
          if (!(parsed instanceof org.hl7.fhir.r5.model.StructureDefinition sd)) {
            continue;
          }
          String key = structureDefinitionKey(sd.getUrl(), sd.getId());
          if (key != null && !loadedKeys.add(key)) {
            continue;
          }
          try {
            context.cacheResource(sd);
          } catch (org.hl7.fhir.exceptions.DefinitionException ignored) {
            // Duplicates across packages are expected for some canonicals.
          }
        }
      }
    }
  }

  private static String structureDefinitionKey(String url, String id) {
    if (url != null && !url.isBlank()) {
      return "url:" + url.trim();
    }
    if (id != null && !id.isBlank()) {
      return "id:" + id.trim();
    }
    return null;
  }
}
