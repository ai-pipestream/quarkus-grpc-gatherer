package ai.pipestream.grpc.gatherer.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;

public abstract class GitSpec {

    @Input
    @Optional
    public abstract Property<String> getRepo();

    @Input
    @Optional
    public abstract Property<String> getRef();

    @Input
    @Optional
    public abstract Property<String> getSubdir();

    @Input
    public abstract ListProperty<String> getPaths();

    @Input
    public abstract ListProperty<String> getModules();

    @Internal
    public abstract Property<String> getToken();

    @Internal
    public abstract Property<String> getUsername();

    @Internal
    public abstract Property<String> getPassword();

    @Input
    public abstract Property<String> getResolvedHeadSha();
}
