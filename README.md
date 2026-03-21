# Quarkus gRPC Gatherer

`quarkus-grpc-gatherer` is a Quarkus extension that gathers `.proto` files from multiple sources before gRPC code generation.

It is designed to pair with [`quarkus-grpc-zero`](https://github.com/quarkiverse/quarkus-grpc-zero): gatherer materializes proto inputs, grpc-zero generates Java/gRPC stubs and optional descriptor sets.

## What it does

- gathers `.proto` files from:
  - filesystem directories
  - dependency jars
  - a git repository/subdirectory
  - a Buf module (`buf export`)
- merges into Quarkus proto input directory (`src/main/proto`)
- deduplicates identical files and fails fast on conflicting content for the same relative path

## Configuration

All keys are build-time and use the `quarkus.grpc-gather.*` prefix.

| Key | Description | Default |
|---|---|---|
| `quarkus.grpc-gather.enabled` | Enable gather step | `false` |
| `quarkus.grpc-gather.clean-target` | Delete existing `.proto` files in target first | `true` |
| `quarkus.grpc-gather.filesystem-dirs` | Comma-separated source dirs | unset |
| `quarkus.grpc-gather.jar-dependencies` | Comma-separated `groupId:artifactId` to scan for `.proto` | unset |
| `quarkus.grpc-gather.jar-scan-all` | Scan all runtime deps for `.proto` | `false` |
| `quarkus.grpc-gather.git-repo` | Git repo URI | unset |
| `quarkus.grpc-gather.git-ref` | Git ref/branch/tag | `main` |
| `quarkus.grpc-gather.git-subdir` | Proto subdir in git checkout | `proto` |
| `quarkus.grpc-gather.git-paths` | Optional comma-separated file/dir filters under `git-subdir` | unset |
| `quarkus.grpc-gather.git-username` | Git username for authenticated clone | unset |
| `quarkus.grpc-gather.git-password` | Git password/token paired with username | unset |
| `quarkus.grpc-gather.git-token` | Git token (uses `x-access-token` auth) | unset |
| `quarkus.grpc-gather.buf-module` | Buf module (e.g. `buf.build/org/mod`) | unset |
| `quarkus.grpc-gather.buf-paths` | Comma-separated `--path` filters for `buf export` | unset |

## Gradle usage

```gradle
dependencies {
  implementation "ai.pipestream:quarkus-grpc-gatherer:0.1.0-SNAPSHOT"
  implementation "io.quarkiverse.grpc.zero:quarkus-grpc-zero:0.0.8"
  implementation "io.quarkus:quarkus-grpc"
}
```

```properties
quarkus.grpc-gather.enabled=true
quarkus.grpc-gather.filesystem-dirs=${user.dir}/src/test-protos
quarkus.grpc-gather.jar-dependencies=com.google.api.grpc:proto-google-common-protos
```

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

## Descriptor generation (grpc-zero)

Use grpc-zero settings to emit descriptor sets:

```properties
quarkus.generate-code.grpc.descriptor-set.generate=true
quarkus.generate-code.grpc.descriptor-set.output-dir=${user.dir}/build/grpc-descriptors
quarkus.generate-code.grpc.descriptor-set.name=services.dsc
```

## Build

```bash
./gradlew clean build
```
