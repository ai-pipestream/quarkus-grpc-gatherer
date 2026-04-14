package ai.pipestream.grpc.gatherer.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;

public abstract class BufWorkspaceSpec {

    @Input
    @Optional
    public abstract Property<String> getRepo();

    @Input
    @Optional
    public abstract Property<String> getRef();

    @Input
    @Optional
    public abstract ListProperty<String> getModules();

    @Input
    @Optional
    public abstract Property<String> getProtoSubdir();

    @Internal
    public abstract Property<String> getToken();

    @Internal
    public abstract Property<String> getUsername();

    @Internal
    public abstract Property<String> getPassword();
}
