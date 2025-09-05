package de.gefyra.fhirpkg;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.npm.FilesystemPackageCacheManager;
import org.hl7.fhir.utilities.npm.IPackageCacheManager;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.hl7.fhir.utilities.npm.PackageServer;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import picocli.CommandLine;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * CLI tool that downloads FHIR NPM packages, resolves dependencies, generates StructureDefinition snapshots,
 * and writes them as JSON files.
 */
public class FhirPackageSnapshotTool implements Callable<Integer> {

    @Option(names = {"-p", "--package"},
            description = "FHIR NPM packages (repeatable or comma-separated; e.g. hl7.fhir.r4.core@4.0.1,hl7.fhir.us.core@6.1.0)")
    List<String> pkgCoordinates = new ArrayList<>();

    @Option(names = {"--sushi-deps-str"}, description = "YAML block (as string) from sushi-config.yaml with 'dependencies:'")
    String sushiDepsStr;

    @Option(names = {"--sushi-deps-file"}, description = "Path to sushi-config.yaml (or a file containing the YAML dependencies)")
    Path sushiDepsFile;

    @Option(names = {"-o", "--out"}, description = "Output directory for StructureDefinitions (default: ./out)")
    Path outDir = Paths.get("out");

    @Option(names = {"--cache"}, description = "Local cache folder for NPM packages (default: ~/.fhir/packages)")
    Path cacheDir = Paths.get(System.getProperty("user.home"), ".fhir", "packages");

    @Option(names = {"--registry"}, description = "Package registry (default: https://packages.fhir.org)")
    String registryUrl = "https://packages.fhir.org";

    @Option(names = {"--skip-deps"}, description = "Do NOT automatically load dependencies")
    boolean skipDependencies = false;

    @Option(names = {"--overwrite"}, description = "Overwrite existing files")
    boolean overwrite = false;

    @Option(names = {"--pretty"}, description = "Pretty-print JSON")
    boolean pretty = true;

    @Option(names = {"--force-snapshot"}, description = "Always (re)generate snapshots, even if present")
    boolean forceSnapshot = false;

    public static void main(String[] args) {
        int exit = new CommandLine(new FhirPackageSnapshotTool()).execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() throws Exception {
        Files.createDirectories(outDir);

        // 1) Collect package coordinates: -p (comma-separated is fine), Sushi file, Sushi inline string
        Set<String> requested = new LinkedHashSet<>();
        if (pkgCoordinates != null) {
            for (String s : pkgCoordinates) {
                if (s == null) continue;
                Arrays.stream(s.split(","))
                        .map(String::trim)
                        .filter(x -> !x.isBlank())
                        .forEach(requested::add);
            }
        }
        requested.addAll(gatherPkgCoordsFromSushi(sushiDepsFile, sushiDepsStr));

        if (requested.isEmpty()) {
            System.err.println("No packages specified. Use -p or --sushi-deps-*. Aborting.");
            return 2;
        }

        // 2) Configure package cache/registry
        // FilesystemPackageCacheManager in utilities 6.6.6 uses a Builder API
        Files.createDirectories(cacheDir);
        FilesystemPackageCacheManager.Builder cacheBuilder = new FilesystemPackageCacheManager.Builder()
                .withCacheFolder(cacheDir.toString())
                .withPackageServers(List.of(new PackageServer(registryUrl)));
        IPackageCacheManager cache = cacheBuilder.build();

        // 3) Load root packages (avoid duplicates by name)
        List<NpmPackage> allPkgs = new ArrayList<>();
        Set<String> seenByName = new HashSet<>();
        for (String coord : requested) {
            NpmPackage p = loadPackage(cache, coord);
            if (seenByName.add(p.name())) {
                allPkgs.add(p);
            }
        }

        // 4) Resolve dependencies transitively (unless --skip-deps)
        if (!skipDependencies) {
            for (int i = 0; i < allPkgs.size(); i++) {
                NpmPackage root = allPkgs.get(i);
                List<NpmPackage> deps = loadAllDependencies(cache, root, seenByName);
                allPkgs.addAll(deps);
            }
        }

        if (allPkgs.isEmpty()) {
            System.err.println("No packages loaded – aborting.");
            return 3;
        }

        // 5) Choose FHIR context from the first package
        NpmPackage contextPkg = allPkgs.get(0);
        FhirContext ctx = pickFhirContext(contextPkg);

        // 6) Build ValidationSupport chain
        IValidationSupport chain = buildValidationChain(ctx, allPkgs);

        // 7) Iterate StructureDefinitions and generate snapshots as needed
        int generated = 0, total = 0, written = 0;
        for (NpmPackage p : allPkgs) {
            for (String resName : p.listResources("StructureDefinition")) {
                total++;
                try (InputStream is = p.load("package", resName)) {
                    String json = new String(is.readAllBytes());

                    boolean hasSnapshot = SNAPSHOT_FIELD.matcher(json).find();
                    if (forceSnapshot || !hasSnapshot) {
                        SnapshotGeneratingValidationSupport snap = new SnapshotGeneratingValidationSupport(ctx);
                        ValidationSupportContext vsc = new ValidationSupportContext(chain);
                        IBaseResource parsed = ctx.newJsonParser().parseResource(json);
                        IBaseResource withSnap = snap.generateSnapshot(
                                vsc,
                                parsed,
                                getUrlFromJson(json),
                                null, // web URL not provided
                                getNameFromJson(json)
                        );
                        json = pretty
                                ? ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(withSnap)
                                : ctx.newJsonParser().encodeResourceToString(withSnap);
                        generated++;
                    }

                    String fileName = safeNameFromJson(json);
                    Path target = outDir.resolve(fileName + ".json");
                    if (!overwrite && Files.exists(target)) {
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    written++;
                }
            }
        }

        System.out.printf(Locale.ROOT,
                "Done: %d StructureDefinitions found, %d snapshots generated, %d files written. Output: %s%n",
                total, generated, written, outDir.toAbsolutePath());

        return 0;
    }

    // Look for a top-level field named "snapshot"; avoid '{' to sidestep JDK21 preview parsing issues
    private static final Pattern SNAPSHOT_FIELD = Pattern.compile("\\\"snapshot\\\"\\s*:", Pattern.DOTALL);

    private static NpmPackage loadPackage(IPackageCacheManager cache, String coordinate) throws IOException {
        String name = coordinate;
        String version = null;
        if (coordinate.contains("@")) {
            String[] parts = coordinate.split("@", 2);
            name = parts[0];
            version = parts[1];
        }
        return (version == null || version.isBlank()) ? cache.loadPackage(name) : cache.loadPackage(name, version);
    }

    private static List<NpmPackage> loadAllDependencies(IPackageCacheManager cache,
                                                        NpmPackage root,
                                                        Set<String> seenByName) throws IOException {
        List<String> deps = root.dependencies(); // e.g., entries like "package.id#1.2.3"
        if (deps == null || deps.isEmpty()) return List.of();

        List<NpmPackage> out = new ArrayList<>();
        for (String entry : deps) {
            String name = entry;
            String version = null;
            int hashIdx = entry.indexOf('#');
            if (hashIdx >= 0) {
                name = entry.substring(0, hashIdx);
                version = entry.substring(hashIdx + 1);
            }

            if (!seenByName.add(name)) continue; // already loaded

            NpmPackage p = (version == null || version.isBlank())
                    ? cache.loadPackage(name)
                    : cache.loadPackage(name, version);
            out.add(p);
            out.addAll(loadAllDependencies(cache, p, seenByName));
        }
        return out;
    }

    private static FhirContext pickFhirContext(NpmPackage pkg) {
        String fhirVer = Optional.ofNullable(pkg.fhirVersion()).orElse("").toLowerCase(Locale.ROOT);
        if (fhirVer.startsWith("5")) {
            return FhirContext.forR5Cached();
        } else if (fhirVer.startsWith("4.3")) {
            return FhirContext.forR4BCached();
        } else if (fhirVer.startsWith("4")) {
            return FhirContext.forR4Cached();
        } else if (fhirVer.startsWith("3")) {
            return FhirContext.forDstu3Cached();
        }
        return FhirContext.forR5Cached();
    }

    private static IValidationSupport buildValidationChain(FhirContext ctx, List<NpmPackage> pkgs) {
        DefaultProfileValidationSupport defaultSupport = new DefaultProfileValidationSupport(ctx);
        NpmPackageValidationSupport npmSupport = new NpmPackageValidationSupport(ctx);
        // Load resources and binaries from each NpmPackage into the npmSupport
        for (NpmPackage p : pkgs) {
            try {
                // For snapshot generation, StructureDefinitions are sufficient
                for (String type : List.of("StructureDefinition")) {
                    for (String resName : p.listResources(type)) {
                        try (InputStream is = p.load("package", resName)) {
                            if (is == null) continue;
                            String input = new String(is.readAllBytes());
                            var parser = ctx.newJsonParser();
                            parser.setParserErrorHandler(new ca.uhn.fhir.parser.LenientErrorHandler(false));
                            IBaseResource resource = parser.parseResource(input);
                            npmSupport.addResource(resource);
                        }
                    }
                }
                for (String binaryName : p.list("other")) {
                    try (InputStream bin = p.load("other", binaryName)) {
                        byte[] bytes = bin.readAllBytes();
                        npmSupport.addBinary(bytes, binaryName);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed loading package resources: " + p.name() + "#" + p.version(), e);
            }
        }
        InMemoryTerminologyServerValidationSupport inMemTerm = new InMemoryTerminologyServerValidationSupport(ctx);
        CommonCodeSystemsTerminologyService commonTerminology = new CommonCodeSystemsTerminologyService(ctx);
        PrePopulatedValidationSupport prepop = new PrePopulatedValidationSupport(ctx);
        return new ValidationSupportChain(
                defaultSupport,
                npmSupport,
                prepop,
                commonTerminology,
                inMemTerm
        );
    }

    // --- Sushi parsing ---
    private static List<String> parseSushiDepsYaml(String yamlText) {
        if (yamlText == null || yamlText.isBlank()) return List.of();
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object root = yaml.load(yamlText);
        if (!(root instanceof Map<?, ?> map)) return List.of();

        Object depsNode = map.containsKey("dependencies") ? map.get("dependencies") : root;
        if (!(depsNode instanceof Map<?, ?> deps)) return List.of();

        List<String> coords = new ArrayList<>();
        for (Map.Entry<?, ?> e : deps.entrySet()) {
            String pkgName = String.valueOf(e.getKey()).trim();
            Object v = e.getValue();
            String version = null;
            if (v instanceof String s) {
                version = s.trim();
            } else if (v instanceof Map<?, ?> m) {
                Object ver = m.get("version");
                if (ver != null) version = String.valueOf(ver).trim();
            }
            coords.add((version == null || version.isBlank()) ? pkgName : (pkgName + "@" + version));
        }
        return coords;
    }

    private static List<String> gatherPkgCoordsFromSushi(Path file, String inlineYaml) throws IOException {
        List<String> all = new ArrayList<>();
        if (file != null) {
            String text = Files.readString(file);
            all.addAll(parseSushiDepsYaml(text));
        }
        if (inlineYaml != null && !inlineYaml.isBlank()) {
            all.addAll(parseSushiDepsYaml(inlineYaml));
        }
        return all;
    }

    // --- Minimal JSON helpers (avoid full parse when only url/name/id are needed) ---

    private static String getUrlFromJson(String json) {
        String u = extractJsonString(json, "url");
        return (u != null) ? u : "";
    }

    private static String getNameFromJson(String json) {
        String n = extractJsonString(json, "name");
        return (n != null) ? n : "StructureDefinition";
    }

    private static String safeNameFromJson(String json) {
        String url = extractJsonString(json, "url");
        if (url != null && !url.isBlank()) {
            int i = url.lastIndexOf('/');
            String last = (i >= 0 && i < url.length() - 1) ? url.substring(i + 1) : url;
            return last.replaceAll("[^A-Za-z0-9._-]", "_");
        }
        String name = extractJsonString(json, "name");
        if (name != null && !name.isBlank()) return name.replaceAll("[^A-Za-z0-9._-]", "_");
        String id = extractJsonString(json, "id");
        if (id != null && !id.isBlank()) return id.replaceAll("[^A-Za-z0-9._-]", "_");
        return UUID.randomUUID().toString();
    }

    /**
     * Very small JSON string extractor for a top-level string field.
     */
    private static String extractJsonString(String json, String field) {
        String regex = "\\\"" + field + "\\\"\\s*:\\s*\\\"(.*?)\\\"";
        var m = Pattern.compile(regex, Pattern.DOTALL).matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
