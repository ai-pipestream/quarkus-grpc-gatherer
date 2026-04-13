# Migration from `ai.pipestream.proto-toolchain`

This extension intentionally narrows scope.

- `proto-toolchain` handled fetch + generation + descriptor workflows.
- `quarkus-grpc-gatherer` handles **proto gathering only**.
- `quarkus-grpc-zero` handles **code generation** and optional descriptor output.

## 1) Replace dependencies/plugins

Remove:
- Gradle plugin `ai.pipestream.proto-toolchain`

Add:
- `ai.pipestream:quarkus-grpc-gatherer`
- `io.quarkiverse.grpc.zero:quarkus-grpc-zero`
- `io.quarkus:quarkus-grpc`

## 2) Move gather config

Translate source inputs into `quarkus.grpc-gather.*` properties.

Typical mapping:
- BSR/git/file modules -> gatherer source config
- dependency proto imports -> `quarkus.grpc-gather.jar-dependencies`

## 3) Automated defaults with grpc-zero

When `quarkus.grpc-gather.enabled=true`, the extension automatically:
- Points `quarkus.generate-code.grpc.proto-directory` to the gathered files.
- Provides defaults for `quarkus.generate-code.grpc.descriptor-set.output-dir` (to `build/grpc-descriptors`) and `name` (to `services.dsc`) if `generate=true` is set.

Typically, you only need:

```properties
quarkus.grpc-gather.enabled=true
quarkus.generate-code.grpc.descriptor-set.generate=true
```

## 4) Validate generated outputs

- Generated classes exist under Quarkus generated grpc sources.
- Descriptor set exists where configured.
- Required proto imports (well-known and dependency protos) resolve.

## 5) Rollout guidance

Pilot one service first (recommended: `opensearch-manager`), ensure CI is green, then migrate remaining services in dependency order.
