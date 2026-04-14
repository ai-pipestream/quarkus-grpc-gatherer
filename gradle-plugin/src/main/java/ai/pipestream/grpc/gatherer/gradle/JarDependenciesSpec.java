package ai.pipestream.grpc.gatherer.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;

public abstract class JarDependenciesSpec {

    @Input
    public abstract ListProperty<String> getDependencies();

    @Input
    public abstract Property<Boolean> getScanAll();

    @Classpath
    public abstract ConfigurableFileCollection getResolvedJars();
}
