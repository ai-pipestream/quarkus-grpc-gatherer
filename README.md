# Quarkus gRPC Gatherer

`quarkus-grpc-gatherer` stages `.proto` files from multiple sources before grpc-zero generates Java stubs. Use it when you need to aggregate protos from local files, jars, and git/buf repos, and when you want a descriptor set packaged at `META-INF/grpc/services.dsc` for runtime reflection consumers.

## Quick start

> Examples below use the target release coordinate `0.2.0`. Until the release is cut, use the current snapshot (`0.2.0-SNAPSHOT`) in local builds.

```gradle
plugins {
    id 'io.quarkus' version '3.34.3'
    id 'ai.pipestream.quarkus-grpc-gatherer' version '0.2.0'
}

dependencies {
    implementation 'ai.pipestream:quarkus-grpc-gatherer:0.2.0'
    implementation('io.quarkus:quarkus-grpc') {
        exclude group: 'io.quarkus', module: 'quarkus-grpc-codegen'
    }
}

quarkusGrpcGather {
    filesystem {
        dirs.from(file('src/main/proto'))
    }
}
```

`quarkus-grpc-zero` is brought in transitively by `quarkus-grpc-gatherer`.
If you only need generated message types (no gRPC server), replace `io.quarkus:quarkus-grpc` with `io.quarkus:quarkus-grpc-stubs`, or omit both entirely.
No gather-specific keys are needed in `application.properties` on 0.2.x.

## Full DSL example

```gradle
quarkusGrpcGather {
    outputDir = layout.buildDirectory.dir('gathered-protos/proto').get()

    filesystem {
        dirs.from(sourceSets.main.resources.srcDirs)
        dirs.from(file('src/main/proto'))
        scanRoot = layout.projectDirectory.dir('..').asFile.absolutePath
    }

    jarDependencies {
        dependencies = ['com.example:common-protos', 'com.example:pipeline-protos']
        scanAll = false
    }

    googleWkt {
        include = true
    }

    git {
        repo = 'https://github.com/example/schemas.git'
        ref = providers.gradleProperty('schemaRef').orElse('main').get()
        subdir = 'proto'
        paths = ['common.proto', 'pipeline/']
        token = providers.environmentVariable('GH_TOKEN').orNull
    }

    bufWorkspace {
        repo = 'https://github.com/example/protos-workspace.git'
        ref = providers.gradleProperty('workspaceRef').orElse('main').get()
        modules = ['common', 'pipeline-module']
        protoSubdir = 'proto'
        token = providers.environmentVariable('GH_TOKEN').orNull
    }
}
```

## Source types

### `filesystem` — local directories

```gradle
quarkusGrpcGather {
    filesystem {
        dirs.from(file('src/main/proto'))
        dirs.from(files('../shared/protos', '../contracts/proto'))
        scanRoot = file('../../').absolutePath // optional: find nested src/main/proto trees
    }
}
```

### `jarDependencies` — protos packaged inside jars

```gradle
quarkusGrpcGather {
    jarDependencies {
        dependencies = ['com.example:common-protos', 'com.example:pipeline-protos']
        scanAll = false // true = scan every runtime dependency for .proto files
    }
}
```

### `googleWkt` — Google Well-Known Types

```gradle
quarkusGrpcGather {
    googleWkt {
        include = true
    }
}
```

Well-known types are staged for imports but are not merged into the final output tree.

### `git` — single-repo layout

```gradle
quarkusGrpcGather {
    git {
        repo = 'https://github.com/example/schemas.git'
        ref = 'main' // default: main
        subdir = 'proto' // default: proto
        paths = ['common.proto', 'pipeline/'] // optional filters under subdir
        modules = ['common', 'pipeline'] // optional module dirs, overrides paths
        token = providers.environmentVariable('GH_TOKEN').orNull
        username = providers.environmentVariable('GIT_USERNAME').orNull
        password = providers.environmentVariable('GIT_PASSWORD').orNull
    }
}
```

### `bufWorkspace` — buf-style multi-module repository

```gradle
quarkusGrpcGather {
    bufWorkspace {
        repo = 'https://github.com/example/protos-workspace.git'
        ref = 'main'
        modules = ['common', 'pipeline-module']
        protoSubdir = 'proto' // default: proto
        token = providers.environmentVariable('GH_TOKEN').orNull
        username = providers.environmentVariable('GIT_USERNAME').orNull
        password = providers.environmentVariable('GIT_PASSWORD').orNull
    }
}
```

Each configured module contributes `<module>/<protoSubdir>/**.proto` into the shared gathered tree.

## Configuration reference

The full DSL model and property types are documented in the [`QuarkusGrpcGatherExtension` Javadoc](gradle-plugin/src/main/java/ai/pipestream/grpc/gatherer/gradle/QuarkusGrpcGatherExtension.java).

## How the Gradle plugin auto-wires everything

When `ai.pipestream.quarkus-grpc-gatherer` is applied alongside `io.quarkus`, it registers `gatherProtos` and wires `quarkusGenerateCode` to depend on it.

It also writes these `quarkusBuildProperties` entries before codegen starts:

- `quarkus.grpc.codegen.proto-directory` → absolute path of `build/gathered-protos/proto`
- `quarkus.generate-code.grpc.descriptor-set.generate` → `true`
- `quarkus.generate-code.grpc.descriptor-set.name` → `services.dsc`

This guarantees grpc-zero reads gathered inputs and emits the descriptor set with a stable filename.

## Cache behavior

Git and buf checkouts are cached persistently under:

- `$gradleUserHome/caches/grpc-gatherer/<repo-hash>`

Behavior:

- Normal mode: first run clones, later runs `fetch + reset` in the cache
- `--offline`: uses the cached checkout only (fails if no cache exists)
- Force re-run of gather logic: `./gradlew gatherProtos --rerun`

## Descriptor set packaging

`services.dsc` is generated during `quarkusGenerateCode`. To package it in your runtime jar at `META-INF/grpc/services.dsc`:

```gradle
tasks.named('processResources').configure {
    dependsOn 'quarkusGenerateCode'
    from(layout.buildDirectory.dir('classes/java/quarkus-generated-sources/grpc')) {
        include 'services.dsc'
        into 'META-INF/grpc'
    }
}
```

## Maven support

Maven support is not yet available in 0.2.x. Existing Maven consumers should stay on 0.1.x until a Maven Mojo is released.

## Build

```bash
./gradlew build --no-daemon
```
