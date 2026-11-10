package com.gdep;

import eu.maveniverse.maven.mima.context.Context;
import eu.maveniverse.maven.mima.context.ContextOverrides;
import eu.maveniverse.maven.mima.context.Runtime;
import eu.maveniverse.maven.mima.context.Runtimes;
import eu.maveniverse.maven.mima.extensions.mmr.MavenModelReader;
import eu.maveniverse.maven.mima.extensions.mmr.ModelRequest;
import eu.maveniverse.maven.mima.extensions.mmr.ModelResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.model.Model;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.VersionResolutionException;
import org.eclipse.aether.util.graph.visitor.NodeListGenerator;
import org.eclipse.aether.util.graph.visitor.PreorderDependencyNodeConsumerVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MavenHandler {
    private static final Logger logger = LoggerFactory.getLogger(MavenHandler.class);

    public static MavenInfo getMavenInfo(String projectDir)
            throws VersionResolutionException, DependencyCollectionException, ArtifactResolutionException,
                    ArtifactDescriptorException {
        // first check if pom.xml exists and regular
        Path pomPath = Util.cleanPath(Paths.get(projectDir, "pom.xml"));

        if (!Files.isRegularFile(pomPath)) {
            throw new RuntimeException("pom.xml doesn't exist in " + projectDir);
        }

        Set<String> externSourceJarPaths = new HashSet<>();

        // =================================
        // DO MAVEN SHIT
        // =================================
        logger.info("connecting to maven...");

        Runtime runtime = Runtimes.INSTANCE.getRuntime();

        try (Context context = runtime.create(ContextOverrides.create().build())) {
            // =================================
            // Get the models
            // =================================
            MavenModelReader modelReader = new MavenModelReader(context);
            List<Model> models = getModels(modelReader, pomPath);

            // =================================
            // Resolve models' dependencies and collect transient dependencies
            // =================================
            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRepositories(context.remoteRepositories());

            for (final String depArtifactId : getDependencyArtifactIdsFromModels(models)) {
                Artifact depArtifact = new DefaultArtifact(depArtifactId);
                Dependency dep = new Dependency(depArtifact, null);
                collectRequest.addDependency(dep);
            }

            DependencyNode rootNode = context.repositorySystem()
                    .collectDependencies(context.repositorySystemSession(), collectRequest)
                    .getRoot();

            NodeListGenerator nodeListGenerator = new NodeListGenerator();
            PreorderDependencyNodeConsumerVisitor visitor =
                    new PreorderDependencyNodeConsumerVisitor(nodeListGenerator);
            rootNode.accept(visitor);

            // =================================
            // Generate source maven coordinates
            // =================================
            HashSet<String> sourceCoords = new HashSet<>();

            for (final DependencyNode node : nodeListGenerator.getNodes()) {
                Artifact artifact = node.getArtifact();

                if (artifact == null) {
                    continue;
                }

                String sourceCoord = artifact.getGroupId() + ":" + artifact.getArtifactId()
                        + ":" + "jar"
                        + ":" + "sources"
                        + ":" + artifact.getVersion();

                sourceCoords.add(sourceCoord);
            }

            // =================================
            // Get source jar artifacts
            // =================================

            // TODO: make this parallel
            for (final String coord : sourceCoords) {
                logger.info("downloading {}", coord);

                try {
                    ArtifactResult res = getArtifact(context, coord);

                    Artifact artifact = res.getArtifact();

                    externSourceJarPaths.add(Util.cleanPath(artifact.getPath()).toString());

                } catch (ArtifactResolutionException err) {
                    logger.error("failed to download {}", coord, err);
                }
            }
        }

        return new MavenInfo(externSourceJarPaths);
    }

    private static List<Model> getModels(MavenModelReader modelReader, Path rootPomPath)
            throws VersionResolutionException, ArtifactResolutionException, ArtifactDescriptorException {
        List<Model> models = new ArrayList<>();
        Set<Path> visitedPoms = new HashSet<>();

        visitedPoms.add(rootPomPath);

        getModelsImpl(modelReader, models, rootPomPath, visitedPoms);

        return models;
    }

    private static void getModelsImpl(
            MavenModelReader modelReader, List<Model> models, Path pomPath, Set<Path> visitedPoms)
            throws VersionResolutionException, ArtifactResolutionException, ArtifactDescriptorException {
        ModelResponse response =
                modelReader.readModel(ModelRequest.builder().setPomFile(pomPath).build());
        Model model = response.getEffectiveModel();

        models.add(model);

        Path pomDirPath = pomPath.getParent();

        // according to the documentation, Path.getParent can return null
        // which shouldn't be possible since pom.xml should be in some kind of directory
        // but just in case if it's null, we should log it as error and bail out
        if (pomDirPath == null) {
            logger.error(
                    "pom.xml file at {} doesn't have a parent directory, stopping from collecting models", pomPath);
            return;
        }

        for (String module : model.getModules()) {
            Path modulePath = Util.cleanPath(Paths.get(pomDirPath.toString(), module, "pom.xml"));

            if (!Files.isRegularFile(modulePath)) {
                logger.error("pom.xml file for {}'s module {} doesn't exist at {}", pomPath, module, modulePath);
                continue;
            }

            if (visitedPoms.add(modulePath)) {
                try {
                    getModelsImpl(modelReader, models, modulePath, visitedPoms);
                } catch (Exception e) {
                    logger.error("failed to get model for {}'s module {}", pomPath, module, e);
                }
            }
        }
    }

    private static Set<String> getDependencyArtifactIdsFromModels(List<Model> models) {
        Set<String> depArtifactIds = new HashSet<>();

        for (final Model model : models) {
            for (final org.apache.maven.model.Dependency mavenDep : model.getDependencies()) {
                String depArtifactId =
                        mavenDep.getGroupId() + ":" + mavenDep.getArtifactId() + ":" + mavenDep.getVersion();
                depArtifactIds.add(depArtifactId);
            }
        }

        return depArtifactIds;
    }

    private static ArtifactResult getArtifact(Context context, String coordinate) throws ArtifactResolutionException {
        ArtifactRequest artifactRequest = new ArtifactRequest();
        artifactRequest.setRepositories(context.remoteRepositories());

        Artifact depArtifact = new DefaultArtifact(coordinate);
        artifactRequest.setArtifact(depArtifact);

        ArtifactResult res =
                context.repositorySystem().resolveArtifact(context.repositorySystemSession(), artifactRequest);

        return res;
    }
}
