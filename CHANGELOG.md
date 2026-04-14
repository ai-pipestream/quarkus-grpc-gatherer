## 0.2.0

### Breaking changes
- Gather config moved from `application.properties` to Gradle DSL extension `quarkusGrpcGather { }`
- MicroProfile Config keys `quarkus.grpc-gather.*` removed
- Maven support temporarily dropped — Maven consumers should stay on 0.1.x

### Improvements
- Gather step now runs as a proper Gradle task with native up-to-date checking
- Persistent clone cache at `$gradleUserHome/caches/grpc-gatherer/` replaces the per-build temp-dir clone
- `gatherProtos` skips entirely when no inputs changed
- `ls-remote` SHA fingerprint makes mutable refs (`main`) rerun only when upstream actually moves
- `--offline` supported: uses cached checkout without network

### Internal
- `CodeGenProvider` path removed from `deployment/` module
- Source types (`*Gatherer`) moved from `deployment/sources/` to `gradle-plugin/`
- Integration tests rewritten to use Gradle TestKit
