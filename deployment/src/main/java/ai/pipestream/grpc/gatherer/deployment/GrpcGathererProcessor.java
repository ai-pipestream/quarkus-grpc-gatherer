package ai.pipestream.grpc.gatherer.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class GrpcGathererProcessor {

    private static final String FEATURE = "grpc-gatherer";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }
}
