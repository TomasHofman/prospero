/*
 * Copyright 2023 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.prospero.actions;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.ProvisioningManager;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.layout.FeaturePackLayout;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.layout.ProvisioningLayoutFactory;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.wildfly.channel.ArtifactTransferException;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.NoStreamFoundException;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.Console;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.TemporaryRepositoriesHandler;
import org.wildfly.prospero.api.exceptions.ArtifactResolutionException;
import org.wildfly.prospero.api.exceptions.InvalidUpdateCandidateException;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.galleon.FeaturePackLocationParser;
import org.wildfly.prospero.galleon.GalleonEnvironment;
import org.wildfly.prospero.galleon.GalleonUtils;
import org.wildfly.prospero.model.ProsperoConfig;
import org.wildfly.prospero.wfchannel.MavenSessionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Installs a feature pack onto an existing server.
 */
public class FeaturesAddAction {

    private final MavenSessionManager mavenSessionManager;
    private final Path installDir;
    private final InstallationMetadata metadata;
    private final ProsperoConfig prosperoConfig;
    private final Console console;
    private CandidateActionsFactory candidateActionsFactory;

    public FeaturesAddAction(MavenOptions mavenOptions, Path installDir, List<Repository> repositories, Console console) throws MetadataException, ProvisioningException {
        this(mavenOptions, installDir, repositories, console, new DefaultCandidateActionsFactory(installDir));
    }

    // used for testing
    FeaturesAddAction(MavenOptions mavenOptions, Path installDir,
                      List<Repository> repositories, Console console, CandidateActionsFactory candidateActionsFactory)
            throws MetadataException, ProvisioningException {
        this.installDir = installDir;
        this.console = console;
        this.metadata = InstallationMetadata.loadInstallation(installDir);
        this.prosperoConfig = addTemporaryRepositories(repositories);

        final MavenOptions mergedOptions = prosperoConfig.getMavenOptions().merge(mavenOptions);
        this.mavenSessionManager = new MavenSessionManager(mergedOptions);

        this.candidateActionsFactory = candidateActionsFactory;
    }

    /**
     * performs feature pack installation. The added feature pack can be customized by specifying layers and configuration model name.
     * In order to install a feature pack, a server is re-provisioned and changes are applied to existing server.
     *
     * @param featurePackCoord - maven {@code groupId:artifactId} coordinates of the feature pack to install
     * @param layers - set of layer names to be provisioned
     * @param model - used to select layer model, if the feature pack provides multiple models
     * @param configName - name of the configuration file to generate if supported
     *
     * @throws ProvisioningException - if unable to provision the server
     * @throws ModelNotDefinedException - if requested model is not provided by the feature pack
     * @throws LayerNotFoundException - if one of the requested layers is not provided by the feature pack
     * @throws FeaturePackAlreadyInstalledException - if the requested feature pack configuration wouldn't change the server state
     * @throws InvalidUpdateCandidateException - if the folder at {@code updateDir} is not a valid update
     * @throws MetadataException - if unable to read or write the installation of update metadata
     */
    public void addFeaturePack(String featurePackCoord, Set<String> layers, String model, String configName)
            throws ProvisioningException, OperationException {
        if (featurePackCoord == null || featurePackCoord.isEmpty()) {
            throw new IllegalArgumentException("The feature pack coordinate cannot be null");
        }
        if (featurePackCoord.split(":").length != 2) {
            throw new IllegalArgumentException("The feature pack coordinate has to consist of <groupId>:<artifactId>");
        }

        FeaturePackLocation fpl = FeaturePackLocationParser.resolveFpl(featurePackCoord);

        if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
            ProsperoLogger.ROOT_LOGGER.trace("Adding feature pack " + fpl);
        }

        final String selectedConfig;
        final String selectedModel;

        final Map<String, Set<String>> allLayers = getAllLayers(fpl);

        if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
            ProsperoLogger.ROOT_LOGGER.trace("Found layers");
            for (String key : allLayers.keySet()) {
                ProsperoLogger.ROOT_LOGGER.trace(key + ": " + StringUtils.join(allLayers.get(key)));
            }
        }

        if (allLayers.isEmpty()) {
            selectedModel = null;
        } else {
            selectedModel = getSelectedModel(model, allLayers);
        }


        verifyLayerAvailable(layers, selectedModel, allLayers);

        if (configName == null) {
            if (selectedModel == null) {
                selectedConfig = null;
            } else {
                selectedConfig = selectedModel + ".xml";
            }
        } else {
            selectedConfig = configName;
        }

        if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
            ProsperoLogger.ROOT_LOGGER.addingFeaturePack(fpl, selectedConfig, selectedModel, StringUtils.join(layers));
        }

        final ProvisioningConfig newConfig = buildProvisioningConfig(layers, fpl, selectedConfig, selectedModel);

        final Path candidate;
        try {
            candidate = Files.createTempDirectory("prospero-candidate").toAbsolutePath();
            if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                ProsperoLogger.ROOT_LOGGER.temporaryCandidateFolder(candidate);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> FileUtils.deleteQuietly(candidate.toFile())));
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToCreateTemporaryDirectory(e);
        }

        try (PrepareCandidateAction prepareCandidateAction = candidateActionsFactory.newPrepareCandidateActionInstance(mavenSessionManager, prosperoConfig);
             GalleonEnvironment galleonEnv = getGalleonEnv(candidate)) {
            ProsperoLogger.ROOT_LOGGER.updateCandidateStarted(installDir);
            prepareCandidateAction.buildCandidate(candidate, galleonEnv, ApplyCandidateAction.Type.FEATURE_ADD, newConfig);
            ProsperoLogger.ROOT_LOGGER.updateCandidateCompleted(installDir);
        }

        final ApplyCandidateAction applyCandidateAction = candidateActionsFactory.newApplyCandidateActionInstance(candidate);
        applyCandidateAction.applyUpdate(ApplyCandidateAction.Type.FEATURE_ADD);
    }

    /**
     * check if a feature pack with {@code featurePackCoord} can be resolved in available channels.
     *
     * @param featurePackCoord - maven {@code groupId:artifactId} coordinates of the feature pack to install
     * @return true if the feature pack is available, false otherwise
     * @throws OperationException - if unable to read the metadata
     * @throws ProvisioningException - if unable to read the metadata
     */
    public boolean isFeaturePackAvailable(String featurePackCoord) throws OperationException, ProvisioningException {
        if (featurePackCoord == null || featurePackCoord.isEmpty()) {
            throw new IllegalArgumentException("The feature pack coordinate cannot be null");
        }
        final String[] splitCoordinates = featurePackCoord.split(":");
        if (splitCoordinates.length != 2) {
            throw new IllegalArgumentException("The feature pack coordinate has to consist of <groupId>:<artifactId>");
        }
        final ChannelSession channelSession = GalleonEnvironment
                .builder(installDir, prosperoConfig.getChannels(), mavenSessionManager).build()
                .getChannelSession();

        try {
            if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                ProsperoLogger.ROOT_LOGGER.trace("Resolving a feature pack: " + featurePackCoord);
            }
            channelSession.resolveMavenArtifact(splitCoordinates[0], splitCoordinates[1],
                    "zip", null, null);
        } catch (NoStreamFoundException e) {
            return false;
        } catch (ArtifactTransferException e) {
            throw new ArtifactResolutionException("Unable to resolve feature pack " + featurePackCoord, e,
                    e.getUnresolvedArtifacts(), e.getAttemptedRepositories(), false);
        }

        return true;
    }

    private ProvisioningConfig buildProvisioningConfig(Set<String> layers, FeaturePackLocation fpl, String selectedConfig, String selectedModel)
            throws ProvisioningException, OperationException {
        try (GalleonEnvironment galleonEnv = getGalleonEnv(installDir);
            ProvisioningManager pm = galleonEnv.getProvisioningManager()) {

            final ProvisioningConfig existingConfig = pm.getProvisioningConfig();
            final ProvisioningConfig.Builder builder = ProvisioningConfig.builder(existingConfig);

            if (selectedConfig != null) {
                final ConfigModel.Builder configBuilder = buildLayerConfig(layers, selectedConfig, selectedModel, existingConfig, builder);
                builder.addConfig(configBuilder.build());
            }

            final FeaturePackConfig.Builder fpBuilder = buildFeaturePackConfig(fpl, existingConfig, builder);

            final ProvisioningConfig newConfig = builder
                    .addFeaturePackDep(fpBuilder.build())
                    .build();

            if (newConfig.equals(existingConfig)) {
                throw ProsperoLogger.ROOT_LOGGER.featurePackAlreadyInstalled(fpl);
            }

            return newConfig;
        }
    }

    private static FeaturePackConfig.Builder buildFeaturePackConfig(FeaturePackLocation fpl, ProvisioningConfig existingConfig, ProvisioningConfig.Builder builder)
            throws ProvisioningException {
        final FeaturePackConfig.Builder fpBuilder;
        if (existingConfig.hasFeaturePackDep(fpl.getProducer())) {
            FeaturePackConfig fp = existingConfig.getFeaturePackDep(fpl.getProducer());
            fpBuilder = FeaturePackConfig.builder(fp);
            builder.removeFeaturePackDep(fp.getLocation());
        } else {
            fpBuilder = FeaturePackConfig.builder(fpl)
                    .setInheritConfigs(false)
                    .setInheritPackages(false);
        }
        return fpBuilder;
    }

    private static ConfigModel.Builder buildLayerConfig(Set<String> layers, String selectedConfig, String selectedModel,
                                                        ProvisioningConfig existingConfig, ProvisioningConfig.Builder builder)
            throws ProvisioningDescriptionException {
        final ConfigModel.Builder configBuilder;
        final ConfigId id = new ConfigId(selectedModel, selectedConfig);
        if (existingConfig.hasDefinedConfig(id)) {
            if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                ProsperoLogger.ROOT_LOGGER.trace("Replacing existing ConfigModel " + id);
            }
            ConfigModel cmodel = existingConfig.getDefinedConfig(id);
            configBuilder = ConfigModel.builder(cmodel);
            includeLayers(layers, configBuilder, cmodel);
            builder.removeConfig(id);
        } else {
            if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                ProsperoLogger.ROOT_LOGGER.trace("Adding new ConfigModel " + id);
            }
            configBuilder = ConfigModel.builder(selectedModel, selectedConfig);
            for (String layer: layers) {
                configBuilder.includeLayer(layer);
            }
        }
        return configBuilder;
    }

    private static void includeLayers(Set<String> layers, ConfigModel.Builder configBuilder, ConfigModel cmodel) throws ProvisioningDescriptionException {
        for (String layer: layers) {
            if (cmodel.getExcludedLayers().contains(layer)){
                if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.trace("Un-excluding layer" + layer);
                }
                configBuilder.removeExcludedLayer(layer);
            }
            if (!cmodel.getIncludedLayers().contains(layer)) {
                if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.trace("Adding layer " + layer);
                }
                configBuilder.includeLayer(layer);
            }
        }
    }

    private static void verifyLayerAvailable(Set<String> layers, String selectedModel, Map<String, Set<String>> allLayers) throws LayerNotFoundException {
        if (allLayers.isEmpty() && !layers.isEmpty()) {
            final String aLayer = layers.iterator().next();
            throw new LayerNotFoundException(ProsperoLogger.ROOT_LOGGER.layerNotFoundInFeaturePack(aLayer), aLayer,
                    Collections.emptySet());
        }
        final Set<String> modelLayers = allLayers.get(selectedModel);
        for (String layer : layers) {
            if (!modelLayers.contains(layer)) {
                // limit the model layers to only one found in the feature pack
                throw new LayerNotFoundException(ProsperoLogger.ROOT_LOGGER.layerNotFoundInFeaturePack(layer), layer, modelLayers);
            }
        }
    }

    private static String getSelectedModel(String model, Map<String, Set<String>> allLayers)
            throws ModelNotDefinedException {
        final String selectedModel;
        if (model == null || model.isEmpty()) {
            if (allLayers.size() > 1) {
                throw new ModelNotDefinedException(ProsperoLogger.ROOT_LOGGER.noDefaultModel(), allLayers.keySet());
            }
            selectedModel = allLayers.keySet().iterator().next();
        } else {
            if (!allLayers.containsKey(model)) {
                throw new ModelNotDefinedException(ProsperoLogger.ROOT_LOGGER.modelNotFoundInFeaturePack(model), model, allLayers.keySet());
            }
            selectedModel = model;
        }
        return selectedModel;
    }

    private GalleonEnvironment getGalleonEnv(Path target) throws ProvisioningException, OperationException {
        return GalleonEnvironment
                .builder(target, prosperoConfig.getChannels(), mavenSessionManager)
                .setSourceServerPath(this.installDir)
                .setConsole(console)
                .build();
    }

    private Map<String, Set<String>> getAllLayers(FeaturePackLocation fpl)
            throws ProvisioningException, OperationException {
        final ProvisioningConfig config = ProvisioningConfig.builder()
                .addFeaturePackDep(FeaturePackConfig.builder(fpl).build())
                .build();

        final MavenRepoManager repositoryManager = GalleonEnvironment
                .builder(installDir, prosperoConfig.getChannels(), mavenSessionManager).build()
                .getRepositoryManager();

        final ProvisioningLayoutFactory layoutFactory = GalleonUtils.getProvisioningLayoutFactory(repositoryManager);

        final ProvisioningLayout<FeaturePackLayout> layout = layoutFactory.newConfigLayout(config);

        final Map<String, Set<String>> layersMap = new HashMap<>();

        for (FeaturePackLayout fp : layout.getOrderedFeaturePacks()) {
            final Set<ConfigId> configIds;
            try {
                configIds = fp.loadLayers();
            } catch (IOException e) {
                // this should not happen as the code IOException is not actually thrown by loadLayers
                throw new RuntimeException(e);
            }
            for (ConfigId layer : configIds) {
                final String model = layer.getModel();
                Set<String> names = layersMap.get(model);
                if (names == null) {
                    names = new HashSet<>();
                    layersMap.put(model, names);
                }
                names.add(layer.getName());
            }
        }

        return layersMap;
    }

    private ProsperoConfig addTemporaryRepositories(List<Repository> repositories) {
        final ProsperoConfig prosperoConfig = metadata.getProsperoConfig();

        final List<Channel> channels = TemporaryRepositoriesHandler.overrideRepositories(prosperoConfig.getChannels(), repositories);

        return new ProsperoConfig(channels, prosperoConfig.getMavenOptions());
    }

    /**
     * Thrown if a requested layer cannot be found in a feature pack
     */
    public static class LayerNotFoundException extends OperationException {

        private final String layer;
        private final Set<String> supportedLayers;

        public LayerNotFoundException(String msg, String layer, Set<String> supportedLayers) {
            super(msg);
            this.layer = layer;
            this.supportedLayers = supportedLayers;
        }

        public String getLayer() {
            return layer;
        }

        public Set<String> getSupportedLayers() {
            return new TreeSet<>(supportedLayers);
        }
    }

    /**
     * Thrown if either the user requested model is not supported by the feature packs,
     * or if it is impossible to determine default model.
     */
    public static class ModelNotDefinedException extends OperationException {

        private String model;
        private Set<String> supportedModels;

        public ModelNotDefinedException(String msg, Set<String> supportedModels) {
            super(msg);
        }

        public ModelNotDefinedException(String msg, String model, Set<String> supportedModels) {
            super(msg);
            this.model = model;
            this.supportedModels = supportedModels;
        }

        public String getModel() {
            return model;
        }

        public Set<String> getSupportedModels() {
            return new TreeSet<>(supportedModels);
        }
    }

    public static class FeaturePackAlreadyInstalledException extends OperationException {

        public FeaturePackAlreadyInstalledException(String msg) {
            super(msg);
        }
    }

    // used in testing to inject mocks
    interface CandidateActionsFactory {
        PrepareCandidateAction newPrepareCandidateActionInstance(MavenSessionManager mavenSessionManager, ProsperoConfig prosperoConfig) throws OperationException;

        ApplyCandidateAction newApplyCandidateActionInstance(Path candidateDir) throws ProvisioningException, OperationException;
    }

    private static class DefaultCandidateActionsFactory implements CandidateActionsFactory {

        private final Path installDir;

        public DefaultCandidateActionsFactory(Path installDir) {
            this.installDir = installDir;
        }

        @Override
        public PrepareCandidateAction newPrepareCandidateActionInstance(
                MavenSessionManager mavenSessionManager, ProsperoConfig prosperoConfig) throws OperationException {
            return new PrepareCandidateAction(installDir, mavenSessionManager, prosperoConfig);
        }

        @Override
        public ApplyCandidateAction newApplyCandidateActionInstance(Path candidateDir)
                throws ProvisioningException, OperationException {
            return new ApplyCandidateAction(installDir, candidateDir);
        }
    }
}
