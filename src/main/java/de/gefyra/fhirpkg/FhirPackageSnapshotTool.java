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

    public static Path defaultOutputDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData, "fhir", "packages");
        }
        
        // Check if we're in GitHub Actions
        String githubActions = System.getenv("GITHUB_ACTIONS");
        if ("true".equals(githubActions)) {
            // In GitHub Actions, use HOME env var instead of user.home property
            String home = System.getenv("HOME");
            if (home != null && !home.isBlank()) {
                return Paths.get(home, ".fhir", "packages");
            }
        }
        
        return Paths.get(System.getProperty("user.home"), ".fhir", "packages");
    }

    @Option(names = {"-o", "--out"}, description = "Output directory for StructureDefinitions (default: %APPDATA%\\fhir\\packages on Windows, ~/.fhir/packages on Linux/macOS)")
    Path outDir = defaultOutputDir();

    @Option(names = {"--cache"}, description = "Local cache folder for NPM packages (default: ~/.fhir/packages)")
    Path cacheDir = defaultCacheDir();

    public static Path defaultCacheDir() {
        // Check if we're in GitHub Actions
        String githubActions = System.getenv("GITHUB_ACTIONS");
        if ("true".equals(githubActions)) {
            // In GitHub Actions, use HOME env var instead of user.home property
            String home = System.getenv("HOME");
            if (home != null && !home.isBlank()) {
                return Paths.get(home, ".fhir", "packages");
            }
        }
        
        return Paths.get(System.getProperty("user.home"), ".fhir", "packages");
    }

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

    @Option(names = {"--profiles-dir"}, description = "Directory with local StructureDefinition JSONs (processed recursively)")
    Path profilesDir;

    public static void main(String[] args) {
        int exit = new CommandLine(new FhirPackageSnapshotTool()).execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() throws Exception {
        // Debug output: Show which directories will be used
        System.out.println("FHIR Package Tool Configuration:");
        System.out.println("  Output directory: " + outDir.toAbsolutePath());
        System.out.println("  Cache directory: " + cacheDir.toAbsolutePath());
        
        // Show how directories were determined
        String githubActions = System.getenv("GITHUB_ACTIONS");
        if ("true".equals(githubActions)) {
            System.out.println("  GitHub Actions detected (GITHUB_ACTIONS=true)");
            System.out.println("  HOME environment variable: " + System.getenv("HOME"));
            System.out.println("  user.home system property: " + System.getProperty("user.home"));
        } else {
            System.out.println("  Running in local environment");
        }
        System.out.println();
        
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
        List<String> sushiPackages = gatherPkgCoordsFromSushi(sushiDepsFile, sushiDepsStr);
        requested.addAll(sushiPackages);

        Optional<String> sushiFhirVersion = gatherFhirVersionFromSushi(sushiDepsFile);

        if (requested.isEmpty() && profilesDir == null) {
            System.err.println("No packages specified. Use -p or --sushi-deps-* (or provide --profiles-dir). Aborting.");
            return 2;
        }

        // 2) Configure package cache/registry
        // FilesystemPackageCacheManager in utilities 6.6.6 uses a Builder API
        Files.createDirectories(cacheDir);
        Set<Path> knownCacheDirs = initKnownCacheDirs(cacheDir);
        FilesystemPackageCacheManager.Builder cacheBuilder = new FilesystemPackageCacheManager.Builder()
                .withCacheFolder(cacheDir.toString())
                .withPackageServers(List.of(new PackageServer(registryUrl)));
        IPackageCacheManager cache = cacheBuilder.build();

        // 3) Load root packages (avoid duplicates by name)
        List<NpmPackage> allPkgs = new ArrayList<>();
        Set<String> seenByName = new HashSet<>();
        for (String coord : requested) {
            NpmPackage p = loadPackage(cache, coord, knownCacheDirs);
            if (seenByName.add(p.name())) {
                allPkgs.add(p);
            }
        }

        // 4) Resolve dependencies transitively (unless --skip-deps)
        if (!skipDependencies) {
            for (int i = 0; i < allPkgs.size(); i++) {
                NpmPackage root = allPkgs.get(i);
                List<NpmPackage> deps = loadAllDependencies(cache, root, seenByName, knownCacheDirs);
                allPkgs.addAll(deps);
            }
        }

        if (allPkgs.isEmpty()) {
            if (profilesDir == null) {
                System.err.println("No packages loaded – aborting.");
                return 3;
            }
            System.out.println("No FHIR packages loaded; continuing with local profiles only.");
        }

        // 5) Choose FHIR context: prefer sushi-config value when provided, otherwise fallback to packages/default
        FhirContext ctx;
        if (sushiFhirVersion.isPresent()) {
            ctx = pickFhirContext(sushiFhirVersion.get());
        } else if (!allPkgs.isEmpty()) {
            ctx = pickFhirContext(allPkgs.get(0));
        } else {
            System.err.println("No FHIR version available; defaulting to R5 context.");
            ctx = pickFhirContext((String) null);
        }

        // 6) Build ValidationSupport chain
        IValidationSupport chain = buildValidationChain(ctx, allPkgs);

        // 7) If no --profiles-dir: copy packages and snapshot their StructureDefinitions
        //    If --profiles-dir is present: skip package output entirely (local-only output)
        int generated = 0, total = 0, sdWritten = 0, filesCopied = 0;
        if (profilesDir == null) {
            for (NpmPackage p : allPkgs) {
                String pkgFolderName = p.name() + "#" + p.version();
                Path pkgOutDir = outDir.resolve(pkgFolderName);
                // 7a) Copy all folders/files
                var folders = p.getFolders();
                for (Map.Entry<String, org.hl7.fhir.utilities.npm.NpmPackage.NpmPackageFolder> e : folders.entrySet()) {
                    String folderName = e.getKey();
                    var folder = e.getValue();
                    Path folderOut = pkgOutDir.resolve(folderName);
                    Files.createDirectories(folderOut);
                    for (String fname : folder.listFiles()) {
                        Path target = folderOut.resolve(fname);
                        if (!overwrite && Files.exists(target)) {
                            continue;
                        }
                        try (InputStream is = p.load(folderName, fname)) {
                            if (is == null) continue;
                            byte[] bytes = is.readAllBytes();
                            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                            filesCopied++;
                        }
                    }
                }

                // 7b) Snapshot StructureDefinitions and overwrite copied files when a new snapshot was generated
                for (String resName : p.listResources("StructureDefinition")) {
                    total++;
                    try (InputStream is = p.load("package", resName)) {
                        if (is == null) continue;
                        String json = new String(is.readAllBytes());

                        boolean hasSnapshot = SNAPSHOT_FIELD.matcher(json).find();
                        boolean didGenerate = forceSnapshot || !hasSnapshot;
                        if (didGenerate) {
                            SnapshotGeneratingValidationSupport snap = new SnapshotGeneratingValidationSupport(ctx);
                            ValidationSupportContext vsc = new ValidationSupportContext(chain);
                            IBaseResource parsed = ctx.newJsonParser().parseResource(json);
                            IBaseResource withSnap = snap.generateSnapshot(vsc, parsed, getUrlFromJson(json), null, getNameFromJson(json));
                            json = pretty
                                    ? ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(withSnap)
                                    : ctx.newJsonParser().encodeResourceToString(withSnap);
                            generated++;
                        }

                        Path target = pkgOutDir.resolve("package").resolve(resName);
                        // Write if we generated a new snapshot, regardless of existing file; otherwise honor overwrite flag
                        if (!didGenerate && !overwrite && Files.exists(target)) {
                            continue;
                        }
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        sdWritten++;
                    }
                }
            }
        }

        // 8) Optionally: process local profiles from --profiles-dir
        int localTotal = 0, localGenerated = 0, localWritten = 0;
        if (profilesDir != null) {
            if (!Files.exists(profilesDir) || !Files.isDirectory(profilesDir)) {
                System.err.printf(Locale.ROOT, "Profiles directory not found or not a directory: %s%n", profilesDir);
            } else {
                Path localOutBase = outDir.resolve("local");
                SnapshotGeneratingValidationSupport snap = new SnapshotGeneratingValidationSupport(ctx);

                // Build a local support layer with all local resources so cross-references resolve (bases, extensions)
                NpmPackageValidationSupport localSupport = new NpmPackageValidationSupport(ctx);
                var parser = ctx.newJsonParser();
                parser.setParserErrorHandler(new ca.uhn.fhir.parser.LenientErrorHandler(false));

                // First pass: load all JSON resources from the folder into localSupport
                try (var stream = Files.walk(profilesDir)) {
                    for (Path f : (Iterable<Path>) stream::iterator) {
                        if (Files.isDirectory(f)) continue;
                        String fn = f.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!fn.endsWith(".json")) continue;
                        try {
                            String input = Files.readString(f);
                            // parse and add any FHIR resource present; failures are non-fatal
                            IBaseResource res = parser.parseResource(input);
                            if (res != null) {
                                localSupport.addResource(res);
                            }
                        } catch (Exception e) {
                            System.err.printf(Locale.ROOT, "Skip (load into support) failed: %s (%s)%n", f, e.getMessage());
                        }
                    }
                }

                // Use a chain that prefers local resources first, then the package chain
                IValidationSupport localChain = new ValidationSupportChain(localSupport, chain);
                ValidationSupportContext vsc = new ValidationSupportContext(localChain);

                try (var stream = Files.walk(profilesDir)) {
                    for (Path f : (Iterable<Path>) stream::iterator) {
                        if (Files.isDirectory(f)) continue;
                        String fn = f.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!fn.endsWith(".json")) continue;

                        String json;
                        try {
                            json = Files.readString(f);
                        } catch (Exception e) {
                            System.err.printf(Locale.ROOT, "Skip unreadable file: %s (%s)%n", f, e.getMessage());
                            continue;
                        }

                        // Only handle StructureDefinition resources
                        String rt = extractJsonString(json, "resourceType");
                        if (rt == null || !"StructureDefinition".equals(rt)) continue;
                        localTotal++;

                        boolean hasSnapshot = SNAPSHOT_FIELD.matcher(json).find();
                        boolean didGenerate = forceSnapshot || !hasSnapshot;
                        if (didGenerate) {
                            try {
                                IBaseResource parsedRes = parser.parseResource(json);
                                IBaseResource withSnap = snap.generateSnapshot(vsc, parsedRes, getUrlFromJson(json), null, getNameFromJson(json));
                                json = pretty
                                        ? ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(withSnap)
                                        : ctx.newJsonParser().encodeResourceToString(withSnap);
                                localGenerated++;
                            } catch (Exception e) {
                                System.err.printf(Locale.ROOT, "Snapshot generation failed for %s: %s%n", f, e.getMessage());
                                continue;
                            }
                        }

                        // mirror relative path under out/local
                        Path rel = profilesDir.relativize(f);
                        Path target = localOutBase.resolve(rel);
                        try {
                            if (!didGenerate && !overwrite && Files.exists(target)) {
                                continue;
                            }
                            Files.createDirectories(target.getParent());
                            Files.writeString(target, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                            localWritten++;
                        } catch (Exception e) {
                            System.err.printf(Locale.ROOT, "Write failed for %s: %s%n", target, e.getMessage());
                        }
                    }
                }
            }
        }

        System.out.printf(Locale.ROOT,
                "Done: %d SDs found, %d snapshots generated, %d SD files written, %d files copied. Local: %d SDs, %d generated, %d written.%n",
                total, generated, sdWritten, filesCopied, localTotal, localGenerated, localWritten);
        System.out.printf(Locale.ROOT,
                "Output directory: %s%n", outDir.toAbsolutePath());
        System.out.printf(Locale.ROOT,
                "Cache directory: %s%n", cacheDir.toAbsolutePath());

        return 0;
    }

    // Look for a top-level field named "snapshot"; avoid '{' to sidestep JDK21 preview parsing issues
    private static final Pattern SNAPSHOT_FIELD = Pattern.compile("\\\"snapshot\\\"\\s*:", Pattern.DOTALL);

    private static NpmPackage loadPackage(IPackageCacheManager cache,
                                          String coordinate,
                                          Set<Path> knownCacheDirs) throws IOException {
        String name = coordinate;
        String version = null;
        if (coordinate.contains("@")) {
            String[] parts = coordinate.split("@", 2);
            name = parts[0];
            version = parts[1];
        }
        NpmPackage pkg = (version == null || version.isBlank())
                ? cache.loadPackage(name)
                : cache.loadPackage(name, version);
        notePackageCacheLocation(pkg, knownCacheDirs);
        return pkg;
    }

    private static List<NpmPackage> loadAllDependencies(IPackageCacheManager cache,
                                                        NpmPackage root,
                                                        Set<String> seenByName,
                                                        Set<Path> knownCacheDirs) throws IOException {
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
            notePackageCacheLocation(p, knownCacheDirs);
            out.add(p);
            out.addAll(loadAllDependencies(cache, p, seenByName, knownCacheDirs));
        }
        return out;
    }

    private static FhirContext pickFhirContext(NpmPackage pkg) {
        return pickFhirContext(pkg.fhirVersion());
    }

    private static FhirContext pickFhirContext(String version) {
        String fhirVer = Optional.ofNullable(version).orElse("").toLowerCase(Locale.ROOT);
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

    private static Set<Path> initKnownCacheDirs(Path cacheDir) {
        Set<Path> known = new HashSet<>();
        if (cacheDir == null) {
            return known;
        }
        try {
            if (Files.exists(cacheDir) && Files.isDirectory(cacheDir)) {
                try (var stream = Files.list(cacheDir)) {
                    for (Path entry : (Iterable<Path>) stream::iterator) {
                        if (Files.isDirectory(entry)) {
                            known.add(entry.toAbsolutePath().normalize());
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.printf(Locale.ROOT, "Failed to scan cache directory %s: %s%n", cacheDir, e.getMessage());
        }
        return known;
    }

    private static void notePackageCacheLocation(NpmPackage pkg, Set<Path> knownCacheDirs) {
        if (pkg == null || knownCacheDirs == null) {
            return;
        }
        String rawPath = pkg.getPath();
        if (rawPath == null || rawPath.isBlank()) {
            return;
        }
        try {
            Path resolved = Paths.get(rawPath).toAbsolutePath().normalize();
            if (knownCacheDirs.add(resolved)) {
                System.out.printf(Locale.ROOT,
                        "Cached package %s#%s at %s%n",
                        Optional.ofNullable(pkg.name()).orElse(""),
                        Optional.ofNullable(pkg.version()).orElse(""),
                        resolved);
            }
        } catch (Exception e) {
            System.err.printf(Locale.ROOT,
                    "Cached package %s#%s but resolved cache path failed: %s%n",
                    Optional.ofNullable(pkg.name()).orElse(""),
                    Optional.ofNullable(pkg.version()).orElse(""),
                    e.getMessage());
        }
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

    private static Optional<String> gatherFhirVersionFromSushi(Path file) throws IOException {
        if (file == null) {
            return Optional.empty();
        }
        return parseSushiFhirVersion(Files.readString(file));
    }

    private static Optional<String> parseSushiFhirVersion(String yamlText) {
        if (yamlText == null || yamlText.isBlank()) return Optional.empty();
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object root = yaml.load(yamlText);
        if (!(root instanceof Map<?, ?> map)) return Optional.empty();
        Object raw = map.get("fhirVersion");
        return extractFhirVersion(raw);
    }

    private static Optional<String> extractFhirVersion(Object node) {
        if (node == null) return Optional.empty();
        if (node instanceof String s) {
            String trimmed = s.trim();
            return trimmed.isBlank() ? Optional.empty() : Optional.of(trimmed);
        }
        if (node instanceof Collection<?> coll) {
            for (Object entry : coll) {
                if (entry == null) continue;
                String trimmed = entry.toString().trim();
                if (!trimmed.isBlank()) {
                    return Optional.of(trimmed);
                }
            }
            return Optional.empty();
        }
        if (node.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(node);
            for (int i = 0; i < length; i++) {
                Object entry = java.lang.reflect.Array.get(node, i);
                if (entry == null) continue;
                String trimmed = entry.toString().trim();
                if (!trimmed.isBlank()) {
                    return Optional.of(trimmed);
                }
            }
            return Optional.empty();
        }
        String fallback = node.toString().trim();
        return fallback.isBlank() ? Optional.empty() : Optional.of(fallback);
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
