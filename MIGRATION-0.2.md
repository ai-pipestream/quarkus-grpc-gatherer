# Migration: 0.1.x → 0.2.0

## What changed

Gather logic moved from a Quarkus `CodeGenProvider` into a real Gradle task.
Benefits: Gradle's up-to-date check now skips the gather step when nothing
changed, cutting typical `quarkusDev` startup by ~60s. Git repos are cached
persistently across builds instead of re-cloned every time.

## Breaking changes

- Config moves from `application.properties` to `build.gradle` DSL
- MicroProfile Config keys `quarkus.grpc-gather.*` are removed
- Maven support is not available in 0.2.x (Maven consumers: stay on 0.1.x)

## Step-by-step

### Before (0.1.x)

`build.gradle`:

```gradle
plugins { id 'ai.pipestream.quarkus-grpc-gatherer' version '0.1.x' }
dependencies { implementation 'ai.pipestream:quarkus-grpc-gatherer:0.1.x' }
```

`application.properties`:

```properties
quarkus.grpc-gather.enabled=true
quarkus.grpc-gather.buf-workspace-repo=https://github.com/...
quarkus.grpc-gather.buf-workspace-ref=main
quarkus.grpc-gather.buf-workspace-modules=common,pipeline-module
```

### After (0.2.0)

`build.gradle`:

```gradle
plugins { id 'ai.pipestream.quarkus-grpc-gatherer' version '0.2.0' }
dependencies { implementation 'ai.pipestream:quarkus-grpc-gatherer:0.2.0' }

quarkusGrpcGather {
    bufWorkspace {
        repo = 'https://github.com/...'
        ref = 'main'
        modules = ['common', 'pipeline-module']
    }
}
```

`application.properties`: no gather config needed.

## Per-source migration examples

### `filesystem`

**Before (0.1.x / `application.properties`)**

```properties
quarkus.grpc-gather.filesystem-dirs=src/main/proto,../shared/proto
quarkus.grpc-gather.filesystem-scan-root=..
```

**After (0.2.0 / `build.gradle`)**

```gradle
quarkusGrpcGather {
    filesystem {
        dirs.from(file('src/main/proto'), file('../shared/proto'))
        scanRoot = file('..').absolutePath
    }
}
```

### `git`

**Before (0.1.x / `application.properties`)**

```properties
quarkus.grpc-gather.git-repo=https://github.com/example/schemas.git
quarkus.grpc-gather.git-ref=main
quarkus.grpc-gather.git-subdir=proto
quarkus.grpc-gather.git-paths=common.proto,pipeline/
quarkus.grpc-gather.git-token=${GH_TOKEN}
```

**After (0.2.0 / `build.gradle`)**

```gradle
quarkusGrpcGather {
    git {
        repo = 'https://github.com/example/schemas.git'
        ref = 'main'
        subdir = 'proto'
        paths = ['common.proto', 'pipeline/']
        token = providers.environmentVariable('GH_TOKEN').orNull
    }
}
```

### `jarDependencies`

**Before (0.1.x / `application.properties`)**

```properties
quarkus.grpc-gather.jar-dependencies=com.example:common-protos,com.example:pipeline-protos
quarkus.grpc-gather.jar-scan-all=false
```

**After (0.2.0 / `build.gradle`)**

```gradle
quarkusGrpcGather {
    jarDependencies {
        dependencies = ['com.example:common-protos', 'com.example:pipeline-protos']
        scanAll = false
    }
}
```

### `googleWkt`

**Before (0.1.x / `application.properties`)**

```properties
quarkus.grpc-gather.include-google-wkt=true
```

**After (0.2.0 / `build.gradle`)**

```gradle
quarkusGrpcGather {
    googleWkt {
        include = true
    }
}
```

### `bufWorkspace`

**Before (0.1.x / `application.properties`)**

```properties
quarkus.grpc-gather.buf-workspace-repo=https://github.com/example/protos-workspace.git
quarkus.grpc-gather.buf-workspace-ref=main
quarkus.grpc-gather.buf-workspace-modules=common,pipeline-module
quarkus.grpc-gather.buf-workspace-proto-subdir=proto
quarkus.grpc-gather.buf-workspace-token=${GH_TOKEN}
```

**After (0.2.0 / `build.gradle`)**

```gradle
quarkusGrpcGather {
    bufWorkspace {
        repo = 'https://github.com/example/protos-workspace.git'
        ref = 'main'
        modules = ['common', 'pipeline-module']
        protoSubdir = 'proto'
        token = providers.environmentVariable('GH_TOKEN').orNull
    }
}
```

## Important cleanup

If old `quarkus.grpc-gather.*` keys remain in `application.properties`, Quarkus will log:

- `Unrecognized configuration key` warnings

Remove all old gather keys from `application.properties` after moving to the Gradle DSL.

## Release workflow note for 0.2.0 (maintainers only)

0.2.0 is an explicit minor bump from 0.1.0. When triggering release automation, use a minor increment (`incrementMinor`) or force `0.2.0` explicitly (for example via `-Prelease.forceVersion=0.2.0`).
