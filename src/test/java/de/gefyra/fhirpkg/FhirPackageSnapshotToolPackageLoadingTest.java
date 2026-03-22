package de.gefyra.fhirpkg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.npm.IPackageCacheManager;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.Test;

class FhirPackageSnapshotToolPackageLoadingTest {

  @Test
  void loadRequestedAndDependencyPackages_continuesWhenRequestedPackageFails() throws Exception {
    FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
    tool.skipDependencies = true;

    StubCacheManager cache = new StubCacheManager();
    cache.fail("bad.pkg#1.0.0");
    cache.add(createPackage("ok.pkg", "1.0.0"));

    List<NpmPackage> loaded = tool.loadRequestedAndDependencyPackages(cache,
        List.of("bad.pkg@1.0.0", "ok.pkg@1.0.0"), new HashSet<>());

    assertEquals(1, loaded.size());
    assertEquals("ok.pkg", loaded.get(0).name());
  }

  @Test
  void loadRequestedAndDependencyPackages_continuesWhenDependencyFails() throws Exception {
    FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
    tool.skipDependencies = false;

    StubCacheManager cache = new StubCacheManager();
    cache.add(createPackage("root.pkg", "1.0.0", "dep.a#1.0.0", "dep.fail#1.0.0", "dep.c#1.0.0"));
    cache.add(createPackage("dep.a", "1.0.0"));
    cache.add(createPackage("dep.c", "1.0.0"));
    cache.fail("dep.fail#1.0.0");

    List<NpmPackage> loaded = tool.loadRequestedAndDependencyPackages(cache,
        List.of("root.pkg@1.0.0"), new HashSet<>());

    assertEquals(List.of("root.pkg", "dep.a", "dep.c"),
        loaded.stream().map(NpmPackage::name).toList());
  }

  private static NpmPackage createPackage(String name, String version, String... dependencies)
      throws Exception {
    NpmPackage npmPackage = NpmPackage.empty();
    JsonObject npm = new JsonObject();
    npm.add("name", name);
    npm.add("version", version);

    if (dependencies != null && dependencies.length > 0) {
      JsonObject depObject = new JsonObject();
      for (String dep : dependencies) {
        String[] parts = dep.split("#", 2);
        depObject.add(parts[0], parts.length > 1 ? parts[1] : "");
      }
      npm.add("dependencies", depObject);
    }

    npmPackage.setNpm(npm);
    return npmPackage;
  }

  private static final class StubCacheManager implements IPackageCacheManager {

    private final Map<String, NpmPackage> packagesByCoordinate = new HashMap<>();
    private final Set<String> failingCoordinates = new HashSet<>();

    void add(NpmPackage pkg) {
      packagesByCoordinate.put(pkg.name() + "#" + pkg.version(), pkg);
    }

    void fail(String coordinate) {
      failingCoordinates.add(coordinate);
    }

    @Override
    public NpmPackage loadPackage(String name, String version) throws IOException {
      String coordinate = name + "#" + version;
      if (failingCoordinates.contains(coordinate)) {
        throw new IOException("simulated failure for " + coordinate);
      }
      NpmPackage pkg = packagesByCoordinate.get(coordinate);
      if (pkg == null) {
        throw new IOException("missing package " + coordinate);
      }
      return pkg;
    }

    @Override
    public NpmPackage loadPackage(String name) throws IOException {
      if (failingCoordinates.contains(name)) {
        throw new IOException("simulated failure for " + name);
      }
      for (Map.Entry<String, NpmPackage> entry : packagesByCoordinate.entrySet()) {
        if (entry.getKey().startsWith(name + "#")) {
          return entry.getValue();
        }
      }
      throw new IOException("missing package " + name);
    }

    @Override
    public String getPackageId(String canonicalUrl) {
      throw new UnsupportedOperationException();
    }

    @Override
    public NpmPackage addPackageToCache(String id, String version, InputStream tgz, String source)
        throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getPackageUrl(String canonicalUrl) {
      throw new UnsupportedOperationException();
    }
  }
}
