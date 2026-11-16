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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Resource;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
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

        Set<String> projectSourceDirs = new HashSet<>();

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

            for (Model model : models) {
                MavenInfo info = getMavenInfoFromModel(context, model);

                for (String jarPath : info.externalSourceJars) {
                    externSourceJarPaths.add(jarPath);
                }
                for (String dir : info.projectSourceDirectories) {
                    projectSourceDirs.add(dir);
                }
            }
        }

        return new MavenInfo(externSourceJarPaths, projectSourceDirs);
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

    private static MavenInfo getMavenInfoFromModel(Context context, Model model) throws DependencyCollectionException {
        // =================================
        // Get project source directories
        // =================================
        Set<String> projectSourceDirs = getProjectSourceDirsFromModel(model);

        Set<String> externSourceJarPaths = new HashSet<>();

        // =================================
        // get model's RemoteRepository s
        // =================================
        List<RemoteRepository> remoteRepos = getRemoteRepositoriesFromModel(context, model);

        // =================================
        // Resolve model`s dependencies and collect transient dependencies
        // =================================
        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRepositories(remoteRepos);

        for (final String depArtifactId : getDependencyArtifactIdsFromModel(model)) {
            Artifact depArtifact = new DefaultArtifact(depArtifactId);
            Dependency dep = new Dependency(depArtifact, null);
            collectRequest.addDependency(dep);
        }

        DependencyNode rootNode = context.repositorySystem()
                .collectDependencies(context.repositorySystemSession(), collectRequest)
                .getRoot();

        NodeListGenerator nodeListGenerator = new NodeListGenerator();
        PreorderDependencyNodeConsumerVisitor visitor = new PreorderDependencyNodeConsumerVisitor(nodeListGenerator);
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
                ArtifactResult res = getArtifact(context, remoteRepos, coord);

                Artifact artifact = res.getArtifact();

                externSourceJarPaths.add(Util.cleanPath(artifact.getPath()).toString());

            } catch (ArtifactResolutionException err) {
                logger.error("failed to download {}", coord, err);
            }
        }

        return new MavenInfo(externSourceJarPaths, projectSourceDirs);
    }

    private static String getRepoHashKey(RemoteRepository repo) {
        String url = repo.getUrl();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return repo.getId() + "\0" + url;
    }

    private static List<RemoteRepository> getRemoteRepositoriesFromModel(Context context, Model model) {
        // convert pom.xml repositories, then apply mirrors, proxies and auth from settings.xml
        List<RemoteRepository> rawPomRepos = new ArrayList<>();
        for (org.apache.maven.model.Repository mavenRepo : model.getRepositories()) {
            rawPomRepos.add(toRemoteRepository(mavenRepo));
        }

        List<RemoteRepository> pomRepos =
                context.repositorySystem().newResolutionRepositories(context.repositorySystemSession(), rawPomRepos);

        Map<String, RemoteRepository> repoMap = new LinkedHashMap<>();

        // context repos come first, they are dominant (like settings.xml in maven)
        // order matters, resolver tries repositories in order
        for (RemoteRepository repo : context.remoteRepositories()) {
            repoMap.putIfAbsent(getRepoHashKey(repo), repo);
        }
        for (RemoteRepository repo : pomRepos) {
            repoMap.putIfAbsent(getRepoHashKey(repo), repo);
        }

        return new ArrayList<>(repoMap.values());
    }

    // copied from org.apache.maven.repository.internal.ArtifactDescriptorUtils (maven-resolver-provider)
    // since that one is in an internal package
    private static RemoteRepository toRemoteRepository(org.apache.maven.model.Repository repository) {
        RemoteRepository.Builder builder =
                new RemoteRepository.Builder(repository.getId(), repository.getLayout(), repository.getUrl());
        builder.setSnapshotPolicy(toRepositoryPolicy(repository.getSnapshots()));
        builder.setReleasePolicy(toRepositoryPolicy(repository.getReleases()));
        return builder.build();
    }

    // copied from org.apache.maven.repository.internal.ArtifactDescriptorUtils (maven-resolver-provider)
    // since that one is in an internal package
    private static RepositoryPolicy toRepositoryPolicy(org.apache.maven.model.RepositoryPolicy policy) {
        boolean enabled = true;
        String checksums = RepositoryPolicy.CHECKSUM_POLICY_WARN;
        String updates = RepositoryPolicy.UPDATE_POLICY_DAILY;

        if (policy != null) {
            enabled = policy.isEnabled();
            if (policy.getUpdatePolicy() != null) {
                updates = policy.getUpdatePolicy();
            }
            if (policy.getChecksumPolicy() != null) {
                checksums = policy.getChecksumPolicy();
            }
        }

        return new RepositoryPolicy(enabled, updates, checksums);
    }

    private static Set<String> getDependencyArtifactIdsFromModel(Model model) {
        Set<String> depArtifactIds = new HashSet<>();

        for (final org.apache.maven.model.Dependency mavenDep : model.getDependencies()) {
            String depArtifactId = mavenDep.getGroupId() + ":" + mavenDep.getArtifactId() + ":" + mavenDep.getVersion();
            depArtifactIds.add(depArtifactId);
        }

        return depArtifactIds;
    }

    private static boolean isPathDirectory(String path) {
        if (Util.isStringBlank(path)) {
            return false;
        }

        return Files.isDirectory(Paths.get(path));
    }

    private static Set<String> getProjectSourceDirsFromModel(Model model) {
        // TODO: Maven reports directories that don't exist as resource directories
        //
        // For now, we filter out paths that don't exist
        //
        // But consider this situation
        //
        // 1. User doesn't have /src/test/resources directory
        // 2. User runs gdep, and cache gets saved with no \src\test\resources entry
        // 3. User creates /src/test/resources later
        //
        // In this case, cache becomes wrong and it'll stay wrong until cache expires
        //
        // Of course, go-wrapper handles the case when the paths reported by java don't actually exist
        // but it logs error everytime which boils my piss
        //
        // So we will continue to filter none existing paths until I find a way to log shits silently in go-wrapper side

        Set<String> projectSourceDirs = new HashSet<>();

        Build build = model.getBuild();

        String sourceDirectory = build.getSourceDirectory();
        if (isPathDirectory(sourceDirectory)) {
            projectSourceDirs.add(Util.cleanPath(sourceDirectory));
        }

        String testSourceDirectory = Util.cleanPath(build.getTestSourceDirectory());
        if (isPathDirectory(testSourceDirectory)) {
            projectSourceDirs.add(Util.cleanPath(testSourceDirectory));
        }

        for (final Resource resource : build.getResources()) {
            String dir = resource.getDirectory();
            if (isPathDirectory(dir)) {
                projectSourceDirs.add(Util.cleanPath(dir));
            }
        }

        for (final Resource resource : build.getTestResources()) {
            String dir = Util.cleanPath(resource.getDirectory());
            if (isPathDirectory(dir)) {
                projectSourceDirs.add(Util.cleanPath(dir));
            }
        }

        return projectSourceDirs;
    }

    private static ArtifactResult getArtifact(Context context, List<RemoteRepository> repos, String coordinate)
            throws ArtifactResolutionException {
        ArtifactRequest artifactRequest = new ArtifactRequest();
        artifactRequest.setRepositories(repos);

        Artifact depArtifact = new DefaultArtifact(coordinate);
        artifactRequest.setArtifact(depArtifact);

        ArtifactResult res =
                context.repositorySystem().resolveArtifact(context.repositorySystemSession(), artifactRequest);

        return res;
    }
}
