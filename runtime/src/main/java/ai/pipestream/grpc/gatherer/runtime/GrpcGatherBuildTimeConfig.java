package ai.pipestream.grpc.gatherer.runtime;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Build-time configuration for the gRPC Proto Gatherer extension.
 * <p>
 * This extension allows gathering {@code .proto} files from various sources (filesystem, JARs, Git, Buf)
 * before any gRPC code generation takes place. It is typically used in conjunction with
 * {@code quarkus-grpc-zero}.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "quarkus.grpc-gather")
public interface GrpcGatherBuildTimeConfig {
    /**
     * If true, the proto gathering step is enabled.
     *
     * @return {@code true} if enabled
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * If true, existing {@code .proto} files in the target merge directory will be deleted
     * before gathering new ones.
     *
     * @return {@code true} if target should be cleaned
     */
    @WithDefault("true")
    boolean cleanTarget();

    /**
     * Comma-separated list of local filesystem directories to scan for {@code .proto} files.
     * <p>
     * Supports an optional alias prefix: {@code alias=/path/to/protos}.
     *
     * @return the filesystem directories
     */
    Optional<String> filesystemDirs();

    /**
     * Comma-separated list of {@code groupId:artifactId} coordinates to scan for {@code .proto} files
     * within the project's runtime dependencies.
     *
     * @return the JAR dependencies
     */
    Optional<String> jarDependencies();

    /**
     * If true, all runtime dependencies will be scanned for {@code .proto} files.
     * This is useful when you want to import protos from many different libraries.
     *
     * @return {@code true} if all JARs should be scanned
     */
    @WithDefault("false")
    boolean jarScanAll();

    /**
     * The URI of a Git repository to clone and gather protos from.
     *
     * @return the git repository URI
     */
    Optional<String> gitRepo();

    /**
     * The Git reference (branch, tag, or commit SHA) to check out.
     *
     * @return the git reference
     */
    @WithDefault("main")
    String gitRef();

    /**
     * The subdirectory within the Git repository that contains the {@code .proto} files.
     *
     * @return the git subdirectory
     */
    @WithDefault("proto")
    String gitSubdir();

    /**
     * Optional comma-separated list of specific file or directory paths within {@link #gitSubdir()}
     * to include. If unset, the entire subdirectory is gathered.
     *
     * @return the git paths
     */
    Optional<String> gitPaths();

    /**
     * Username for authenticated Git clones.
     *
     * @return the git username
     */
    Optional<String> gitUsername();

    /**
     * Password or personal access token for authenticated Git clones.
     * Use {@link #gitToken()} for token-only (x-access-token) authentication.
     *
     * @return the git password
     */
    Optional<String> gitPassword();

    /**
     * Personal access token for Git authentication (uses {@code x-access-token}).
     *
     * @return the git token
     */
    Optional<String> gitToken();

    /**
     * The Buf module to export (e.g., {@code buf.build/org/module}).
     * Requires the {@code buf} CLI to be installed and available on the PATH.
     *
     * @return the buf module
     */
    Optional<String> bufModule();

    /**
     * Comma-separated list of {@code --path} filters to pass to {@code buf export}.
     *
     * @return the buf paths
     */
    Optional<String> bufPaths();

    /**
     * If true, includes Google's well-known types (e.g., {@code any.proto}, {@code timestamp.proto})
     * by extracting them from the {@code com.google.protobuf:protobuf-java} dependency.
     *
     * @return {@code true} if Google WKTs should be included
     */
    @WithDefault("false")
    boolean includeGoogleWkt();
}
