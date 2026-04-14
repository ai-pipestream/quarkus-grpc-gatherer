package ai.pipestream.grpc.gatherer.gradle;

import java.io.File;
import java.util.List;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

/**
 * Gradle plugin that wires {@code quarkus-grpc-gatherer} and
 * {@code quarkus-grpc-zero} into a Quarkus Gradle build with zero
 * additional consumer boilerplate.
 *
 * <p>When applied, the plugin:
 *
 * <ol>
 *   <li>Waits for the Quarkus Gradle plugin ({@code io.quarkus}) to be
 *       applied to the same project. If the consumer has not applied
 *       Quarkus, this plugin does nothing - it assumes Quarkus will be
 *       added later in the same build script. (Users who apply this
 *       plugin without ever applying Quarkus clearly did not want the
 *       gatherer's wiring, so silently no-oping is fine.)
 *   <li>Reaches into the {@code QuarkusPluginExtension} and sets three
 *       entries on its {@code quarkusBuildProperties} map via
 *       {@code QuarkusPluginExtension.set(name, value)}:
 *       <ul>
 *         <li>{@code grpc.codegen.proto-directory} &rarr; absolute path to
 *             {@code <buildDir>/gathered-protos/proto}, so grpc-zero's
 *             {@code GrpcZeroCodeGen.init()} stores it in its {@code input}
 *             field and its {@code getInputDirectory()} returns it
 *             regardless of ServiceLoader ordering.</li>
 *         <li>{@code generate-code.grpc.descriptor-set.generate} &rarr;
 *             {@code "true"}, so grpc-zero emits a
 *             {@code FileDescriptorSet} during code generation.</li>
 *         <li>{@code generate-code.grpc.descriptor-set.name} &rarr;
 *             {@code "services.dsc"}, matching the filename pipestream's
 *             {@code GoogleDescriptorLoader} looks up at runtime from
 *             {@code META-INF/grpc/services.dsc}.</li>
 *       </ul>
 * </ol>
 *
 * <p>The {@code quarkusBuildProperties} map flows into
 * {@code EffectiveConfig.withBuildProperties} at ordinal 290, then into
 * the {@code Properties} argument of {@code CodeGenerator.initAndRun},
 * then into the {@code HashMap<String,String>} passed to every
 * {@code CodeGenProvider.init()} call. All of this happens before any
 * provider is instantiated, so the wiring is ordering-independent.
 *
 * <p>The plugin intentionally does NOT add the
 * {@code quarkus-grpc-gatherer} runtime artifact or the
 * {@code quarkus-grpc-zero} artifact as dependencies - the consumer still
 * declares those explicitly in their {@code dependencies} block, where
 * the version is pinned alongside every other dependency. The plugin's
 * job is just the build-time wiring.
 */
public class QuarkusGrpcGathererPlugin implements Plugin<Project> {

    /**
     * Quarkus plugin ID to wait for.
     */
    private static final String QUARKUS_PLUGIN_ID = "io.quarkus";

    /**
     * Build-system-relative path to the gatherer's canonical staging root.
     * Mirrors {@code GrpcGatherCodeGen.BUILD_TARGET_ROOT /
     * BUILD_TARGET_PROTO_SUBDIR}; must stay in sync with that class.
     */
    private static final String GATHERER_OUTPUT_SUBDIR = "gathered-protos/proto";

    /**
     * Default filename for the {@code FileDescriptorSet} that grpc-zero
     * emits. Matches the default consumed by pipestream's
     * {@code GoogleDescriptorLoader}.
     */
    private static final String DESCRIPTOR_SET_NAME = "services.dsc";

    @Override
    public void apply(Project project) {
        project.getPlugins().withId(QUARKUS_PLUGIN_ID, ignored -> configure(project));
    }

    private static void configure(Project project) {
        Object quarkus = project.getExtensions().findByName("quarkus");
        if (quarkus == null) {
            project.getLogger().warn(
                    "ai.pipestream.quarkus-grpc-gatherer: Quarkus plugin applied but "
                            + "QuarkusPluginExtension not found; gatherer wiring skipped.");
            return;
        }

        QuarkusGrpcGatherExtension extension = project.getExtensions().create("quarkusGrpcGather",
                QuarkusGrpcGatherExtension.class);
        extension.getOutputDir().convention(project.getLayout().getBuildDirectory().dir(GATHERER_OUTPUT_SUBDIR));
        extension.getBufWorkspace().getModules().convention(List.of());

        TaskProvider<GatherProtosTask> gatherProtosProvider = project.getTasks().register("gatherProtos",
                GatherProtosTask.class, task -> {
                    task.getOutputDir().convention(project.getLayout().getBuildDirectory().dir(GATHERER_OUTPUT_SUBDIR));
                    task.getOutputDir().set(extension.getOutputDir());

                    task.getBufWorkspace().getRepo().set(extension.getBufWorkspace().getRepo());
                    task.getBufWorkspace().getRef().set(extension.getBufWorkspace().getRef());
                    task.getBufWorkspace().getModules().set(extension.getBufWorkspace().getModules());
                    task.getBufWorkspace().getProtoSubdir().set(extension.getBufWorkspace().getProtoSubdir());
                    task.getBufWorkspace().getToken().set(extension.getBufWorkspace().getToken());
                    task.getBufWorkspace().getUsername().set(extension.getBufWorkspace().getUsername());
                    task.getBufWorkspace().getPassword().set(extension.getBufWorkspace().getPassword());
                });

        project.getTasks().matching(t -> "quarkusGenerateCode".equals(t.getName()))
                .configureEach(t -> t.dependsOn(gatherProtosProvider));

        Provider<String> protoDirectory = project.getLayout().getBuildDirectory()
                .dir(GATHERER_OUTPUT_SUBDIR)
                .map(Directory::getAsFile)
                .map(File::getAbsolutePath);

        // QuarkusPluginExtension.set(name, Provider<String>) stores into
        // quarkusBuildProperties with the "quarkus." prefix prepended. The
        // resulting keys are:
        //   quarkus.grpc.codegen.proto-directory
        //   quarkus.generate-code.grpc.descriptor-set.generate
        //   quarkus.generate-code.grpc.descriptor-set.name
        setQuarkusBuildProperty(quarkus, "grpc.codegen.proto-directory", protoDirectory);
        setQuarkusBuildProperty(quarkus, "generate-code.grpc.descriptor-set.generate",
                project.provider(() -> "true"));
        setQuarkusBuildProperty(quarkus, "generate-code.grpc.descriptor-set.name",
                project.provider(() -> DESCRIPTOR_SET_NAME));

        project.getLogger().info(
                "ai.pipestream.quarkus-grpc-gatherer: wired grpc-zero to read protos from "
                        + "{} and emit descriptor set as META-INF/grpc/{}",
                protoDirectory.getOrElse("(unresolved)"), DESCRIPTOR_SET_NAME);
    }

    private static void setQuarkusBuildProperty(Object quarkus, String key, Provider<String> value) {
        try {
            quarkus.getClass().getMethod("set", String.class, Provider.class).invoke(quarkus, key, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "ai.pipestream.quarkus-grpc-gatherer: failed to set Quarkus build property " + key, e);
        }
    }
}
