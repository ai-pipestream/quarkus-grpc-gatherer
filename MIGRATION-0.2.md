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

Beyond moving config from `application.properties` to `build.gradle`, 0.2.0 makes several 0.1.x workarounds obsolete. **Remove all of the following from your consumer build** — they are no longer needed and, in some cases, actively wrong:

### 1. Drop the explicit `quarkus-grpc-zero` dependency

`quarkus-grpc-gatherer` now brings in `quarkus-grpc-zero` transitively via an `api`-scope dep. Delete any line like this from your `build.gradle`:

```gradle
// DELETE THIS — no longer needed in 0.2.0
implementation 'io.quarkiverse.grpc.zero:quarkus-grpc-zero:0.0.8'
```

### 2. Drop the `sourceSets.main.java.srcDirs` workaround

0.1.x required a manual source-set addition so Quarkus's Gradle plugin would see the staged proto tree:

```gradle
// DELETE THIS — the plugin auto-wires it via quarkusBuildProperties now
sourceSets.main.java.srcDirs += file("${buildDir}/gathered-protos/java")
```

Delete it. The `ai.pipestream.quarkus-grpc-gatherer` plugin injects the gathered path via `quarkusBuildProperties` at Gradle configuration time, so Quarkus picks it up without any `sourceSets` touch.

### 3. Drop the `processResources.from(...)` descriptor-set copy block

0.1.x documentation told consumers to manually copy `services.dsc` onto the classpath:

```gradle
// DELETE THIS — the GrpcGathererProcessor BuildStep routes services.dsc automatically
tasks.named('processResources').configure {
    dependsOn 'quarkusGenerateCode'
    from(layout.buildDirectory.dir('classes/java/quarkus-generated-sources/grpc')) {
        include 'services.dsc'
        into 'META-INF/grpc'
    }
}
```

The deployment-side `GrpcGathererProcessor` BuildStep now reads `services.dsc` off disk and emits it as a `GeneratedResourceBuildItem` at `META-INF/grpc/services.dsc`. Quarkus packages it into the production runtime jar and serves it through the `MemoryClassPathElement` chain at `@QuarkusTest` runtime. Zero consumer configuration required.

### 4. Remove stale `application.properties` keys

If old `quarkus.grpc-gather.*` keys remain in `application.properties`, Quarkus will log `Unrecognized configuration key` warnings on every startup. Remove all of these after moving to the Gradle DSL:

- `quarkus.grpc-gather.enabled`
- `quarkus.grpc-gather.filesystem-dirs`
- `quarkus.grpc-gather.filesystem-scan-root`
- `quarkus.grpc-gather.jar-dependencies`
- `quarkus.grpc-gather.jar-scan-all`
- `quarkus.grpc-gather.git-repo`
- `quarkus.grpc-gather.git-ref`
- `quarkus.grpc-gather.git-subdir`
- `quarkus.grpc-gather.git-paths`
- `quarkus.grpc-gather.git-username`
- `quarkus.grpc-gather.git-password`
- `quarkus.grpc-gather.git-token`
- `quarkus.grpc-gather.excludes`
- `quarkus.grpc-gather.include-google-wkt`
- `quarkus.grpc-gather.buf-workspace-*` (all variants)

## Non-breaking improvements

Once the migration is done, 0.2.0 gives you these for free:

- **Fast repeat builds.** `gatherProtos` has declared inputs and outputs; Gradle's up-to-date check skips it when nothing changed, restoring the ~60 seconds 0.1.x had added to every `quarkusDev` start.
- **Persistent clone cache.** Git and buf-workspace checkouts live under `$gradleUserHome/caches/grpc-gatherer/<sha1(repoUrl)>/` and are reused across builds. First build clones; subsequent builds `fetch + reset` in place instead of re-cloning into a temp directory.
- **Upstream-movement detection.** Mutable refs (`main`, `master`, etc.) are fingerprinted via `git ls-remote` at Gradle input time, so the task reruns when upstream actually moves and skips when it doesn't. Pinned tags and commit SHAs never re-contact the network after the initial clone.
- **`--offline` mode.** Gradle's `--offline` flag uses the cached checkout without any network calls and fails with a clear error if no cache exists yet.
- **Transitive `quarkus-grpc-zero`.** One less line in every consumer's `build.gradle`.
- **Automatic descriptor routing.** `META-INF/grpc/services.dsc` lands on both the production runtime classpath and the `@QuarkusTest` runtime classloader without any consumer-side `processResources` or `Copy` task.

## Consumer migration checklist

1. Bump the gatherer plugin and runtime dep versions to `0.2.0` (or `0.2.0-SNAPSHOT` if you are on the current snapshot line)
2. Delete all `quarkus.grpc-gather.*` keys from `application.properties`
3. Add a `quarkusGrpcGather { ... }` block to `build.gradle` with the equivalent DSL configuration (see the per-source examples above)
4. Delete the explicit `implementation 'io.quarkiverse.grpc.zero:quarkus-grpc-zero:X.Y.Z'` line from `build.gradle` — it now comes in transitively
5. Delete any `sourceSets.main.java.srcDirs += ...` workaround pointing at `build/gathered-protos/java`
6. Delete any `processResources.from(...)` block that copies `services.dsc` into `META-INF/grpc/`
7. Run `./gradlew clean build` and verify `META-INF/grpc/services.dsc` still appears in `build/quarkus-app/quarkus/generated-bytecode.jar`:

    ```bash
    unzip -l build/quarkus-app/quarkus/generated-bytecode.jar | grep services.dsc
    ```

8. Run `./gradlew quarkusDev` and observe that repeat startups are back to normal speed — the ~60 second gather overhead from 0.1.x is gone

## Release workflow note for 0.2.0 (maintainers only)

0.2.0 is an explicit minor bump from 0.1.0. When triggering release automation, use a minor increment (`incrementMinor`) or force `0.2.0` explicitly (for example via `-Prelease.forceVersion=0.2.0`).
