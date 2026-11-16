package com.gdep;

import eu.maveniverse.maven.mima.context.Context;
import eu.maveniverse.maven.mima.context.ContextOverrides;
import eu.maveniverse.maven.mima.context.Runtime;
import eu.maveniverse.maven.mima.context.Runtimes;
import eu.maveniverse.maven.mima.extensions.mmr.MavenModelReader;
import eu.maveniverse.maven.mima.extensions.mmr.ModelRequest;
import eu.maveniverse.maven.mima.extensions.mmr.ModelResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Resource;
import org.apache.maven.toolchain.RequirementMatcher;
import org.apache.maven.toolchain.RequirementMatcherFactory;
import org.apache.maven.toolchain.model.PersistedToolchains;
import org.apache.maven.toolchain.model.ToolchainModel;
import org.apache.maven.toolchain.model.io.xpp3.MavenToolchainsXpp3Reader;
import org.codehaus.plexus.interpolation.EnvarBasedValueSource;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.RegexBasedInterpolator;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
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

        Set<String> jdkPaths = new HashSet<>();

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
            // Get jdk toolchains from toolchains.xml
            // =================================
            List<ToolchainModel> jdkToolchains = getJdkToolchains(context);

            for (Model model : models) {
                MavenInfo info = getMavenInfoFromModel(context, model, jdkToolchains);

                externSourceJarPaths.addAll(info.externalSourceJars);
                projectSourceDirs.addAll(info.projectSourceDirectories);
                jdkPaths.addAll(info.jdkPaths);
            }
        }

        return new MavenInfo(externSourceJarPaths, projectSourceDirs, jdkPaths);
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

    private static MavenInfo getMavenInfoFromModel(Context context, Model model, List<ToolchainModel> jdkToolchains)
            throws DependencyCollectionException {
        // =================================
        // Get project source directories
        // =================================
        Set<String> projectSourceDirs = getProjectSourceDirsFromModel(model);

        // =================================
        // Get jdk paths from toolchains
        // =================================
        Set<String> jdkPaths = getJdkPathsFromModel(model, jdkToolchains);

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

        return new MavenInfo(externSourceJarPaths, projectSourceDirs, jdkPaths);
    }

    // =================================
    // JDK toolchains
    // =================================
    //
    // Maven doesn't report which JDK a project uses.
    // By default it's whatever JDK runs mvn (JAVA_HOME), which go-wrapper falls back to.
    //
    // But if project uses toolchains (maven-toolchains-plugin, or maven-compiler-plugin's jdkToolchain),
    // maven picks a JDK from toolchains.xml that matches the requirements.
    // So we do the same thing here.
    //
    // NOTE: newer maven-toolchains-plugin (3.2.0+) can also discover JDKs without toolchains.xml
    // (select-jdk-toolchain goal), that's not supported

    private static List<ToolchainModel> getJdkToolchains(Context context) {
        // user toolchains come first, just like maven
        List<Path> toolchainsFiles = new ArrayList<>();
        toolchainsFiles.add(context.mavenUserHome().toolchainsXml());
        if (context.mavenSystemHome() != null) {
            toolchainsFiles.add(context.mavenSystemHome().toolchainsXml());
        }

        List<ToolchainModel> jdkToolchains = new ArrayList<>();

        for (Path toolchainsFile : toolchainsFiles) {
            if (!Files.isRegularFile(toolchainsFile)) {
                continue;
            }

            try (InputStream in = Files.newInputStream(toolchainsFile)) {
                PersistedToolchains toolchains = new MavenToolchainsXpp3Reader().read(in, false);

                for (ToolchainModel toolchain : toolchains.getToolchains()) {
                    if ("jdk".equals(toolchain.getType())) {
                        jdkToolchains.add(toolchain);
                    }
                }
            } catch (IOException | XmlPullParserException e) {
                logger.error("failed to read {}", toolchainsFile, e);
            }
        }

        return jdkToolchains;
    }

    private static Set<String> getJdkPathsFromModel(Model model, List<ToolchainModel> jdkToolchains) {
        Set<String> jdkPaths = new HashSet<>();

        for (Map<String, String> requirements : getJdkRequirementsFromModel(model)) {
            // maven picks the first matching toolchain
            String jdkHome = null;

            for (ToolchainModel toolchain : jdkToolchains) {
                if (!toolchainMatchesRequirements(toolchain, requirements)) {
                    continue;
                }

                jdkHome = getToolchainJdkHome(toolchain);
                if (jdkHome != null) {
                    break;
                }
            }

            if (jdkHome == null) {
                logger.warn(
                        "{}:{} requires jdk toolchain {}, but no matching toolchain was found",
                        model.getGroupId(),
                        model.getArtifactId(),
                        requirements);
                continue;
            }

            jdkPaths.add(Util.cleanPath(jdkHome));
        }

        return jdkPaths;
    }

    private static final String TOOLCHAINS_PLUGIN_KEY = "org.apache.maven.plugins:maven-toolchains-plugin";
    private static final String COMPILER_PLUGIN_KEY = "org.apache.maven.plugins:maven-compiler-plugin";

    private static Set<Map<String, String>> getJdkRequirementsFromModel(Model model) {
        // effective model merges plugin configuration into executions,
        // so we use set to remove duplicates
        Set<Map<String, String>> requirementsSet = new HashSet<>();

        Build build = model.getBuild();
        if (build == null) {
            return requirementsSet;
        }

        for (Plugin plugin : build.getPlugins()) {
            String[] path;

            if (TOOLCHAINS_PLUGIN_KEY.equals(plugin.getKey())) {
                path = new String[] {"toolchains", "jdk"};
            } else if (COMPILER_PLUGIN_KEY.equals(plugin.getKey())) {
                path = new String[] {"jdkToolchain"};
            } else {
                continue;
            }

            List<Object> configs = new ArrayList<>();
            configs.add(plugin.getConfiguration());
            for (PluginExecution execution : plugin.getExecutions()) {
                configs.add(execution.getConfiguration());
            }

            for (Object config : configs) {
                Xpp3Dom dom = getXpp3DomChild(config, path);
                if (dom == null) {
                    continue;
                }

                Map<String, String> requirements = new LinkedHashMap<>();
                for (Xpp3Dom child : dom.getChildren()) {
                    if (!Util.isStringBlank(child.getValue())) {
                        requirements.put(child.getName(), child.getValue().trim());
                    }
                }

                if (!requirements.isEmpty()) {
                    requirementsSet.add(requirements);
                }
            }
        }

        return requirementsSet;
    }

    private static Xpp3Dom getXpp3DomChild(Object config, String... path) {
        if (!(config instanceof Xpp3Dom)) {
            return null;
        }

        Xpp3Dom dom = (Xpp3Dom) config;
        for (String name : path) {
            dom = dom.getChild(name);
            if (dom == null) {
                return null;
            }
        }

        return dom;
    }

    // same logic as maven's JavaToolchainFactory and DefaultToolchain.matchesRequirements
    private static boolean toolchainMatchesRequirements(ToolchainModel toolchain, Map<String, String> requirements) {
        Properties provides = toolchain.getProvides();

        for (Map.Entry<String, String> requirement : requirements.entrySet()) {
            String key = requirement.getKey();
            String provided = provides.getProperty(key);

            if (provided == null) {
                return false;
            }

            RequirementMatcher matcher = "version".equals(key)
                    ? RequirementMatcherFactory.createVersionMatcher(provided)
                    : RequirementMatcherFactory.createExactMatcher(provided);

            if (!matcher.matches(requirement.getValue())) {
                return false;
            }
        }

        return true;
    }

    private static String getToolchainJdkHome(ToolchainModel toolchain) {
        Xpp3Dom jdkHomeDom = getXpp3DomChild(toolchain.getConfiguration(), "jdkHome");
        if (jdkHomeDom == null || Util.isStringBlank(jdkHomeDom.getValue())) {
            logger.warn("jdk toolchain {} doesn't have jdkHome", toolchain.getProvides());
            return null;
        }

        // maven interpolates ${env.XXX} in toolchains.xml
        String jdkHome = jdkHomeDom.getValue().trim();
        try {
            RegexBasedInterpolator interpolator = new RegexBasedInterpolator();
            interpolator.addValueSource(new EnvarBasedValueSource());
            jdkHome = interpolator.interpolate(jdkHome);
        } catch (IOException | InterpolationException e) {
            logger.warn("failed to interpolate jdkHome {}", jdkHome, e);
        }

        if (!Files.isDirectory(Paths.get(jdkHome))) {
            logger.warn("jdk toolchain {} has jdkHome {} which doesn't exist", toolchain.getProvides(), jdkHome);
            return null;
        }

        return jdkHome;
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
