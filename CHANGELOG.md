## 0.2.0

### Breaking changes
- Gather config moved from `application.properties` to Gradle DSL extension `quarkusGrpcGather { }`
- MicroProfile Config keys `quarkus.grpc-gather.*` removed
- Maven support temporarily dropped — Maven consumers should stay on 0.1.x

### Improvements
- Gather step now runs as a proper Gradle task with native up-to-date checking, restoring the ~60 seconds 0.1.x added to every `quarkusDev` startup
- Persistent clone cache at `$gradleUserHome/caches/grpc-gatherer/<sha1(repoUrl)>/` replaces the per-build temp-dir git clones
- `gatherProtos` skips entirely when no inputs changed
- `git ls-remote` SHA fingerprint makes mutable refs (e.g. `main`) rerun only when upstream actually moves; pinned tags and SHAs never re-contact the network after the initial clone
- `--offline` supported: uses cached checkout without network, fails with a clear message if no cache exists
- `quarkus-grpc-zero` is now an `api` dependency of `quarkus-grpc-gatherer` runtime — consumers can drop their explicit `implementation 'io.quarkiverse.grpc.zero:quarkus-grpc-zero:X.Y.Z'` line
- `META-INF/grpc/services.dsc` descriptor routing is automatic via `GeneratedResourceBuildItem` from the deployment-side `GrpcGathererProcessor` BuildStep — no consumer-side `processResources.from(...)` block, `Copy` task, or `sourceSets.main.java.srcDirs` workaround required
- Zero-network integration tests via local bare git repo fixtures in the TestKit suite

### Internal
- `CodeGenProvider` path removed from `deployment/` module (~1800 lines of dead gather code deleted)
- Source types (`*Gatherer`) moved from `deployment/src/main/java/.../sources/` to `gradle-plugin/src/main/java/.../internal/` as stagers
- `GrpcGatherBuildTimeConfig` deleted — the `GrpcGathererProcessor` BuildStep is now unconditional and uses natural file-existence as its only gate
- `spi/` package promoted to top-level `gradle-plugin-api/` module, published as `ai.pipestream:quarkus-grpc-gatherer-api` as the stable surface for out-of-tree extension plugins (see #19)
- Integration tests rewritten to use Gradle TestKit (`GatherProtosTaskIntegrationTest`) plus a single `@QuarkusTest` smoke test (`DescriptorSetClasspathIT`) that verifies runtime classloader resolution through the `MemoryClassPathElement` chain
