package de.gefyra.fhirpkg.snapshot;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.hl7.fhir.convertors.factory.VersionConvertorFactory_40_50;
import org.hl7.fhir.convertors.factory.VersionConvertorFactory_43_50;
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

  public static SnapshotEngine createSnapshotEngine(FhirRelease release, String selectedFhirVersion,
      List<NpmPackage> pkgs)
      throws Exception {
    return createR5StackSnapshotEngine(release, selectedFhirVersion, pkgs);
  }

  private static SnapshotEngine createR5StackSnapshotEngine(FhirRelease release,
      String selectedFhirVersion,
      List<NpmPackage> pkgs) throws Exception {
    final org.hl7.fhir.r5.context.SimpleWorkerContext context;
    org.hl7.fhir.r5.context.SimpleWorkerContext.SimpleWorkerContextBuilder builder =
        new org.hl7.fhir.r5.context.SimpleWorkerContext.SimpleWorkerContextBuilder();
    context = builder.fromNothing();
    setContextVersion(context, normalizeContextVersion(release, selectedFhirVersion));
    preloadStructureDefinitionsAsR5(context, pkgs, release);
    context.setCanRunWithoutTerminology(true);
    context.setNoTerminologyServer(true);
    context.setCanNoTS(true);

    return new SnapshotEngine() {
      @Override
      public String generateSnapshot(String json, boolean pretty, String profileUrl,
          String profileName) throws Exception {
        org.hl7.fhir.r5.model.Resource parsed = parseInputToR5(json, release);
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
        profileUtilities.setAutoFixSliceNames(true);
        String resolvedUrl = sd.hasUrl() ? sd.getUrl() : Optional.ofNullable(profileUrl).orElse("");
        String resolvedName = sd.hasName() ? sd.getName()
            : Optional.ofNullable(profileName).orElse("StructureDefinition");
        profileUtilities.generateSnapshot(base, sd, resolvedUrl, null, resolvedName);
        return serializeR5ToOutputRelease(sd, release, pretty);
      }

      @Override
      public void cacheResource(String json) throws Exception {
        org.hl7.fhir.r5.model.Resource parsed = parseInputToR5(json, release);
        if (parsed != null) {
          context.cacheResource(parsed);
        }
      }
    };
  }

  private static void preloadStructureDefinitionsAsR5(
      org.hl7.fhir.r5.context.SimpleWorkerContext context, List<NpmPackage> pkgs,
      FhirRelease release) throws Exception {
    Set<String> loadedKeys = new HashSet<>();
    for (NpmPackage pkg : pkgs) {
      for (String resName : pkg.listResources("StructureDefinition")) {
        try (InputStream is = pkg.load("package", resName)) {
          if (is == null) {
            continue;
          }
          String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
          org.hl7.fhir.r5.model.Resource parsed = parseInputToR5(json, release);
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

  private static org.hl7.fhir.r5.model.Resource parseInputToR5(String json, FhirRelease release)
      throws Exception {
    return switch (release) {
      case R4 -> VersionConvertorFactory_40_50.convertResource(
          new org.hl7.fhir.r4.formats.JsonParser().parse(json));
      case R4B -> VersionConvertorFactory_43_50.convertResource(
          new org.hl7.fhir.r4b.formats.JsonParser().parse(json));
      case R5 -> new org.hl7.fhir.r5.formats.JsonParser().parse(json);
    };
  }

  private static String serializeR5ToOutputRelease(org.hl7.fhir.r5.model.Resource resource,
      FhirRelease release, boolean pretty) throws Exception {
    return switch (release) {
      case R4 -> {
        org.hl7.fhir.r4.model.Resource converted = VersionConvertorFactory_40_50.convertResource(
            resource);
        org.hl7.fhir.r4.formats.JsonParser parser = new org.hl7.fhir.r4.formats.JsonParser();
        parser.setOutputStyle(pretty
            ? org.hl7.fhir.r4.formats.IParser.OutputStyle.PRETTY
            : org.hl7.fhir.r4.formats.IParser.OutputStyle.NORMAL);
        yield parser.composeString(converted);
      }
      case R4B -> {
        org.hl7.fhir.r4b.model.Resource converted = VersionConvertorFactory_43_50.convertResource(
            resource);
        org.hl7.fhir.r4b.formats.JsonParser parser = new org.hl7.fhir.r4b.formats.JsonParser();
        parser.setOutputStyle(pretty
            ? org.hl7.fhir.r4b.formats.IParser.OutputStyle.PRETTY
            : org.hl7.fhir.r4b.formats.IParser.OutputStyle.NORMAL);
        yield parser.composeString(converted);
      }
      case R5 -> {
        org.hl7.fhir.r5.formats.JsonParser parser = new org.hl7.fhir.r5.formats.JsonParser();
        parser.setOutputStyle(pretty
            ? org.hl7.fhir.r5.formats.IParser.OutputStyle.PRETTY
            : org.hl7.fhir.r5.formats.IParser.OutputStyle.NORMAL);
        yield parser.composeString(resource);
      }
    };
  }

  private static String normalizeContextVersion(FhirRelease release, String selectedFhirVersion) {
    if (selectedFhirVersion != null && !selectedFhirVersion.isBlank()) {
      return selectedFhirVersion.trim();
    }
    return switch (release) {
      case R4 -> "4.0.1";
      case R4B -> "4.3.0";
      case R5 -> "5.0.0";
    };
  }

  private static void setContextVersion(org.hl7.fhir.r5.context.SimpleWorkerContext context,
      String version) throws Exception {
    Field versionField = org.hl7.fhir.r5.context.BaseWorkerContext.class.getDeclaredField(
        "version");
    versionField.setAccessible(true);
    versionField.set(context, version);
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
