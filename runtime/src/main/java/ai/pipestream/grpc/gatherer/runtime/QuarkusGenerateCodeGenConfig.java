package ai.pipestream.grpc.gatherer.runtime;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Claims the {@code quarkus.generate-code.grpc.*} config keys that
 * {@code quarkus-grpc-zero} reads imperatively via
 * {@code Config.getOptionalValue(...)}. grpc-zero itself does not register
 * a {@link io.quarkus.runtime.annotations.ConfigRoot} for these keys, so
 * without this mapping Quarkus's strict unused-key validator would log
 * {@code "Unrecognized configuration key"} warnings for every project that
 * legitimately sets them (e.g. to enable descriptor set generation).
 *
 * <p>This interface exists purely to tell Quarkus "yes, these keys are
 * expected." It is never injected anywhere. The real consumer of the
 * values is grpc-zero, at code generation time.
 *
 * <p>Keys covered:
 *
 * <ul>
 *   <li>{@code quarkus.generate-code.grpc.proto-directory}</li>
 *   <li>{@code quarkus.generate-code.grpc.scan-for-proto}</li>
 *   <li>{@code quarkus.generate-code.grpc.scan-for-imports}</li>
 *   <li>{@code quarkus.generate-code.grpc.descriptor-set.generate}</li>
 *   <li>{@code quarkus.generate-code.grpc.descriptor-set.output-dir}</li>
 *   <li>{@code quarkus.generate-code.grpc.descriptor-set.name}</li>
 *   <li>{@code quarkus.generate-code.grpc.kotlin.generate}</li>
 * </ul>
 */
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "quarkus.generate-code.grpc")
public interface QuarkusGenerateCodeGenConfig {

    /**
     * Custom proto input directory for grpc-zero. When unset, grpc-zero
     * falls back to resolving {@code "proto"} against each Quarkus source
     * parent.
     *
     * @return the proto directory override, if any
     */
    Optional<String> protoDirectory();

    /**
     * Coordinate list ({@code groupId:artifactId,...} or {@code "all"} /
     * {@code "none"}) of dependencies whose {@code .proto} files grpc-zero
     * should harvest from the classpath.
     *
     * @return the scan coordinates, if any
     */
    Optional<String> scanForProto();

    /**
     * Coordinate list of dependencies to scan when resolving
     * {@code import "..."} statements in proto files.
     *
     * @return the import-scan coordinates, if any
     */
    Optional<String> scanForImports();

    /**
     * grpc-zero Kotlin generator toggle.
     *
     * @return Kotlin generation config group
     */
    Kotlin kotlin();

    /**
     * grpc-zero descriptor set generation settings. These are the keys that
     * runtime consumers set to get a {@code FileDescriptorSet} emitted as
     * part of code generation.
     *
     * @return descriptor set config group
     */
    DescriptorSet descriptorSet();

    /**
     * Descriptor set generation config group.
     */
    interface DescriptorSet {

        /**
         * Enable descriptor set generation. When {@code true}, grpc-zero
         * writes a {@code FileDescriptorSet} binary during codegen.
         *
         * @return {@code true} if enabled
         */
        @WithDefault("false")
        boolean generate();

        /**
         * Output directory, resolved by grpc-zero against
         * {@code CodeGenContext.workDir()}. When unset, grpc-zero writes
         * to its default codegen output directory.
         *
         * @return the output directory override, if any
         */
        Optional<String> outputDir();

        /**
         * Filename for the generated descriptor set. When unset, grpc-zero
         * defaults to {@code descriptor_set.dsc}.
         *
         * @return the filename override, if any
         */
        Optional<String> name();
    }

    /**
     * Kotlin config group.
     */
    interface Kotlin {
        /**
         * Enable Kotlin generator. When unset, grpc-zero auto-detects
         * based on the presence of a {@code quarkus-kotlin} dependency.
         *
         * @return {@code true} if Kotlin generation is enabled
         */
        Optional<Boolean> generate();
    }
}
