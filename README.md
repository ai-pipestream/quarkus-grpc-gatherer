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
dependencies {
  implementation "ai.pipestream:quarkus-grpc-gatherer:0.1.0-SNAPSHOT"
  implementation "io.quarkiverse.grpc.zero:quarkus-grpc-zero:0.0.8"
  // Real gRPC server? keep quarkus-grpc but drop its codegen, which grpc-zero replaces.
  implementation("io.quarkus:quarkus-grpc") {
    exclude group: "io.quarkus", module: "quarkus-grpc-codegen"
  }
}

// Make Quarkus see build/gathered-protos/proto as a grpc-zero input.
sourceSets.main.java.srcDirs += file("${buildDir}/gathered-protos/java")
```

```properties
quarkus.grpc-gather.enabled=true

# Example: gather two modules from a buf workspace repo
quarkus.grpc-gather.buf-workspace-repo=https://github.com/ai-pipestream/pipestream-protos.git
quarkus.grpc-gather.buf-workspace-ref=main
quarkus.grpc-gather.buf-workspace-modules=common,pipeline-module
```

## Descriptor set generation

grpc-zero emits a `FileDescriptorSet` when you set:

```properties
quarkus.generate-code.grpc.descriptor-set.generate=true
quarkus.generate-code.grpc.descriptor-set.name=services.dsc
```

The file lands in grpc-zero's default codegen output directory (`build/classes/java/quarkus-generated-sources/grpc/services.dsc` for Gradle). That path is compiled Java output, not a resource — so runtime consumers that load descriptors from the classpath will not find it there.

To expose it at `META-INF/grpc/services.dsc` (the path pipestream's `GoogleDescriptorLoader` looks up by default), add a `processResources.from()` block:

```gradle
tasks.named('processResources').configure {
    dependsOn 'quarkusGenerateCode'
    from(layout.buildDirectory.dir('classes/java/quarkus-generated-sources/grpc')) {
        include 'services.dsc'
        into 'META-INF/grpc'
    }
}
```

After a build, `services.dsc` will be present in the final jar at `META-INF/grpc/services.dsc`.

## Maven usage

```xml
<dependency>
  <groupId>ai.pipestream</groupId>
  <artifactId>quarkus-grpc-gatherer</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>io.quarkiverse.grpc.zero</groupId>
  <artifactId>quarkus-grpc-zero</artifactId>
  <version>0.0.8</version>
</dependency>
```

Maven handles source roots and resources differently from Gradle; the equivalent hooks are not documented here yet.

## Build

```bash
./gradlew clean build
```
