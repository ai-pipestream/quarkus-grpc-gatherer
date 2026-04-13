# Quarkus gRPC Gatherer

`quarkus-grpc-gatherer` is a Quarkus extension that gathers `.proto` files from multiple sources before gRPC code generation.

It is designed to pair with [`quarkus-grpc-zero`](https://github.com/quarkiverse/quarkus-grpc-zero): the gatherer materializes proto inputs, and grpc-zero generates Java/Mutiny stubs and (optionally) a `FileDescriptorSet`.

## What it does

At Quarkus `init()` time (before any `CodeGenProvider.trigger()` runs) the gatherer:

1. Runs every configured `ProtoGatherer` implementation (discovered via `ServiceLoader`) and stages each source's `.proto` files under `build/gathered-protos-staging/<source>/`.
2. Merges every staging subdirectory into `build/gathered-protos/proto/` with content-hash dedup and conflict reporting.
3. Creates an empty `build/gathered-protos/java/` directory so consumers can declare it as a Java source directory without Gradle warning about a missing path.

The gatherer never touches the source tree. There is no `src/main/proto` mirror.

Built-in `ProtoGatherer` implementations cover:

- **Filesystem directories** (`FilesystemGatherer`) — explicit list of dirs
- **Filesystem scan** (`FilesystemScanGatherer`) — walk a root and harvest every `src/main/proto` / `src/main/resources` it finds
- **JAR dependencies** (`JarDependencyGatherer`) — extract `.proto` from named runtime deps or scan-all
- **Git repository** (`GitRepoGatherer`) — clone + pick files via `git-subdir` + `git-paths` or `git-modules`
- **Buf-style multi-module workspace** (`BufWorkspaceGatherer`) — clone once, flatten each module's `proto/` subdir onto a shared root so cross-module imports resolve
- **Google Well-Known Types** (`GoogleWktGatherer`) — extract `google/protobuf/*.proto` from `com.google.protobuf:protobuf-java`

## Hooking the staged protos into grpc-zero

Quarkus's Gradle plugin computes the "source parents" passed to every `CodeGenProvider` as `Path::getParent` of every Java `srcDir` of the main source set (see `io.quarkus.gradle.QuarkusPlugin#getSourcesParents`). grpc-zero's `inputDirectory()` is `"proto"`, so each source parent resolves to `<sourceParent>/proto`.

To make Quarkus see `build/gathered-protos/proto` as a grpc-zero input, add one line to your Gradle build script:

```gradle
sourceSets.main.java.srcDirs += file("${buildDir}/gathered-protos/java")
```

No `src/main/proto` mirror, no system properties, no absolute paths in `application.properties`.

## Configuration

All keys are build-time and use the `quarkus.grpc-gather.*` prefix.

### Core

| Key | Description | Default |
|---|---|---|
| `quarkus.grpc-gather.enabled` | Enable the gatherer | `false` |
| `quarkus.grpc-gather.excludes` | Comma-separated path globs to exclude from staging | unset |

### Filesystem sources

| Key | Description | Default |
|---|---|---|
| `quarkus.grpc-gather.filesystem-dirs` | Comma-separated source dirs | unset |
| `quarkus.grpc-gather.filesystem-scan-root` | Root to walk for `src/main/proto` / `src/main/resources` dirs | unset |

### Jar dependencies

| Key | Description | Default |
|---|---|---|
| `quarkus.grpc-gather.jar-dependencies` | Comma-separated `groupId:artifactId` list | unset |
| `quarkus.grpc-gather.jar-scan-all` | Scan every runtime dep for `.proto` | `false` |
| `quarkus.grpc-gather.include-google-wkt` | Include Google well-known types from `protobuf-java` (staged separately; not merged) | `false` |

### Git repository (single layout)

| Key | Description | Default |
|---|---|---|
| `quarkus.grpc-gather.git-repo` | Git repo URI | unset |
| `quarkus.grpc-gather.git-ref` | Git ref/branch/tag | `main` |
| `quarkus.grpc-gather.git-subdir` | Proto subdir in git checkout | `proto` |
| `quarkus.grpc-gather.git-paths` | Optional CSV file/dir filters under `git-subdir` | unset |
| `quarkus.grpc-gather.git-modules` | Optional CSV of top-level module dirs (non-buf layout) | unset |
| `quarkus.grpc-gather.git-username` / `git-password` / `git-token` | Auth | unset |

### Buf-workspace git repository

Use this when the repo is a buf workspace with each module's protos under `<module>/proto/`. The gatherer detects the `proto/` subdir per module and flattens everything onto a single root so cross-module imports resolve.

| Key | Description | Default |
|---|---|---|
| `quarkus.grpc-gather.buf-workspace-repo` | Git repo URI | unset |
| `quarkus.grpc-gather.buf-workspace-ref` | Git ref | `main` |
| `quarkus.grpc-gather.buf-workspace-modules` | CSV of module directory names | unset |
| `quarkus.grpc-gather.buf-workspace-proto-subdir` | Per-module proto subdir | `proto` |
| `quarkus.grpc-gather.buf-workspace-token` / `-username` / `-password` | Auth | unset |

## Gradle usage

```gradle
plugins {
  id 'io.quarkus' version '3.34.3'
  // Wires the gatherer's staged proto tree into grpc-zero and routes the
  // generated FileDescriptorSet onto the runtime classpath at
  // META-INF/grpc/services.dsc. See "How the Gradle plugin auto-wires
  // everything" below for what it does under the hood.
  id 'ai.pipestream.quarkus-grpc-gatherer' version '0.1.0-SNAPSHOT'
}

dependencies {
  // Brings in the runtime extension AND, transitively (via api scope),
  // io.quarkiverse.grpc.zero:quarkus-grpc-zero. Consumers do NOT need to
  // declare grpc-zero explicitly.
  implementation 'ai.pipestream:quarkus-grpc-gatherer:0.1.0-SNAPSHOT'

  // Real gRPC server? Keep quarkus-grpc but exclude its codegen module
  // (grpc-zero replaces it). Skip this whole block if you only need the
  // generated protobuf message types and not the @GrpcService runtime.
  implementation('io.quarkus:quarkus-grpc') {
    exclude group: 'io.quarkus', module: 'quarkus-grpc-codegen'
  }
}
```

```properties
quarkus.grpc-gather.enabled=true

# Example: gather two modules from a buf workspace repo
quarkus.grpc-gather.buf-workspace-repo=https://github.com/ai-pipestream/pipestream-protos.git
quarkus.grpc-gather.buf-workspace-ref=main
quarkus.grpc-gather.buf-workspace-modules=common,pipeline-module
```

That is the entire integration contract. No `sourceSets` addition, no `Copy` task, no descriptor-set config — the Gradle plugin handles all of that automatically.

## How the Gradle plugin auto-wires everything

When applied alongside `io.quarkus`, the `ai.pipestream.quarkus-grpc-gatherer` Gradle plugin reaches into `QuarkusPluginExtension.quarkusBuildProperties` at Gradle configuration time and sets three keys:

| Key | Value |
|---|---|
| `quarkus.grpc.codegen.proto-directory` | absolute path to `<buildDir>/gathered-protos/proto` |
| `quarkus.generate-code.grpc.descriptor-set.generate` | `true` |
| `quarkus.generate-code.grpc.descriptor-set.name` | `services.dsc` |

These flow through `EffectiveConfig` (source ordinal 290) into the `Properties` argument of `CodeGenerator.initAndRun` and ultimately into the `Map<String,String> properties` Quarkus passes to every `CodeGenProvider.init()` call **before** any provider is instantiated. grpc-zero's `init()` reads `proto-directory` from that Map, sets its `input` field, and `getInputDirectory()` returns the absolute path to the gatherer's staging dir. ServiceLoader ordering between providers is irrelevant because the value is in the Map before any of them load.

After grpc-zero emits `services.dsc` during code generation, the gatherer's `GrpcGathererProcessor` BuildStep reads it from disk and produces a `GeneratedResourceBuildItem("META-INF/grpc/services.dsc", bytes)`. Quarkus packages those bytes into `quarkus-app/quarkus/generated-bytecode.jar` for the production runtime classpath, and serves them through the `MemoryClassPathElement` chain for `@QuarkusTest` so `Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/grpc/services.dsc")` finds the descriptor in either context.

## Maven usage

```xml
<dependency>
  <groupId>ai.pipestream</groupId>
  <artifactId>quarkus-grpc-gatherer</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

`quarkus-grpc-zero` is pulled in transitively. Maven consumers do NOT have an equivalent of the Gradle plugin yet, so they must set the three keys above manually in `application.properties`:

```properties
quarkus.grpc.codegen.proto-directory=${project.build.directory}/gathered-protos/proto
quarkus.generate-code.grpc.descriptor-set.generate=true
quarkus.generate-code.grpc.descriptor-set.name=services.dsc
```

## Build

```bash
./gradlew clean build
```
