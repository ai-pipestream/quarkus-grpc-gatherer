package ai.pipestream.grpc.gatherer.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;

public abstract class GoogleWktSpec {

    @Input
    public abstract Property<Boolean> getInclude();

    @Classpath
    public abstract ConfigurableFileCollection getProtobufJavaJar();
}
