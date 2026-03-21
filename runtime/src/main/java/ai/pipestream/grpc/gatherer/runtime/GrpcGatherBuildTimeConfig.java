package ai.pipestream.grpc.gatherer.runtime;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "quarkus.grpc-gather")
public interface GrpcGatherBuildTimeConfig {
    @WithDefault("false")
    boolean enabled();

    @WithDefault("true")
    boolean cleanTarget();

    Optional<String> filesystemDirs();

    Optional<String> jarDependencies();

    @WithDefault("false")
    boolean jarScanAll();

    Optional<String> gitRepo();

    @WithDefault("main")
    String gitRef();

    @WithDefault("proto")
    String gitSubdir();

    Optional<String> gitPaths();

    Optional<String> gitUsername();

    Optional<String> gitPassword();

    Optional<String> gitToken();

    Optional<String> bufModule();

    Optional<String> bufPaths();
}
