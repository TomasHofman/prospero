/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.prospero.actions;

import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.ProvisioningDefinition;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.galleon.FeaturePackLocationParser;
import org.wildfly.prospero.galleon.GalleonUtils;
import org.wildfly.prospero.galleon.ChannelMavenArtifactRepositoryManager;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.wildfly.prospero.galleon.LocalMavenCacheManager;
import org.wildfly.prospero.galleon.ProvisioningConfigUpdater;
import org.wildfly.prospero.model.ChannelRef;
import org.wildfly.prospero.wfchannel.ChannelRefUpdater;
import org.wildfly.prospero.wfchannel.MavenSessionManager;
import org.wildfly.prospero.wfchannel.WfChannelMavenResolver;
import org.wildfly.prospero.wfchannel.WfChannelMavenResolverFactory;
import org.eclipse.aether.repository.RemoteRepository;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.ProvisioningManager;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.layout.ProvisioningLayoutFactory;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.xml.ProvisioningXmlParser;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;

import static org.wildfly.prospero.galleon.GalleonUtils.MAVEN_REPO_LOCAL;

public class Provision {

    private final MavenSessionManager mavenSessionManager;
    private Path installDir;
    private Console console;

    public Provision(Path installDir, MavenSessionManager mavenSessionManager, Console console) {
        this.installDir = installDir;
        this.console = console;
        this.mavenSessionManager = mavenSessionManager;
    }

    /**
     * Installs feature pack defined by {@code fpl} in {@code installDir}. If {@code fpl} doesn't include version,
     * the newest available version will be used.
     *
     * @param provisioningDefinition
     * @throws ProvisioningException
     * @throws MetadataException
     */
    public void provision(ProvisioningDefinition provisioningDefinition) throws ProvisioningException, OperationException {
        final List<ChannelRef> updatedRefs = new ChannelRefUpdater(mavenSessionManager)
                .resolveLatest(provisioningDefinition.getChannelRefs(), provisioningDefinition.getRepositories());
        final List<Channel> channels = mapToChannels(updatedRefs);

        final List<RemoteRepository> repositories = provisioningDefinition.getRepositories();

        final WfChannelMavenResolverFactory factory = new WfChannelMavenResolverFactory(mavenSessionManager, repositories);
        final ChannelMavenArtifactRepositoryManager repoManager = new ChannelMavenArtifactRepositoryManager(channels, factory);
        ProvisioningManager provMgr = GalleonUtils.getProvisioningManager(installDir, repoManager);
        final ProvisioningLayoutFactory layoutFactory = provMgr.getLayoutFactory();

        layoutFactory.setProgressCallback("LAYOUT_BUILD", console.getProgressCallback("LAYOUT_BUILD"));
        layoutFactory.setProgressCallback("PACKAGES", console.getProgressCallback("PACKAGES"));
        layoutFactory.setProgressCallback("CONFIGS", console.getProgressCallback("CONFIGS"));
        layoutFactory.setProgressCallback("JBMODULES", console.getProgressCallback("JBMODULES"));

        final ProvisioningConfig config;
        if (provisioningDefinition.getFpl() != null) {
            FeaturePackLocation loc = new FeaturePackLocationParser(repoManager).resolveFpl(provisioningDefinition.getFpl());

            console.println("Installing " + loc.toString());

            final FeaturePackConfig.Builder configBuilder = FeaturePackConfig.builder(loc);
            for (String includedPackage : provisioningDefinition.getIncludedPackages()) {
                configBuilder.includePackage(includedPackage);
            }
            config = ProvisioningConfig.builder().addFeaturePackDep(configBuilder.build()).build();
        } else {
            final ProvisioningConfig provisioningConfig = ProvisioningXmlParser.parse(provisioningDefinition.getDefinition());
            config = new ProvisioningConfigUpdater(repoManager).updateFPs(provisioningConfig);
        }
        try {
            System.setProperty(MAVEN_REPO_LOCAL, mavenSessionManager.getProvisioningRepo().toAbsolutePath().toString());
            final Map<String, String> options = new HashMap<>();
            options.put("jboss-fork-embedded", "true");
            provMgr.provision(config, options);
        } finally {
            System.clearProperty(MAVEN_REPO_LOCAL);
        }

        writeProsperoMetadata(installDir, repoManager, updatedRefs, repositories);

        // cache wildfly-ee-galleon-pack and wildfly-galleon-plugins
        final LocalMavenCacheManager localMavenCacheManager = new LocalMavenCacheManager(installDir.resolve(".installation"));
        localMavenCacheManager.generateCacheRepository(mavenSessionManager.getProvisioningRepo().toAbsolutePath(), ((WfChannelMavenResolver)factory.create()).getResolvedArtifacts());
    }

    /**
     * Installs feature pack based on Galleon installation file
     *
     * @param installationFile
     * @param channelRefs
     * @param repositories
     * @throws ProvisioningException
     * @throws IOException
     * @throws MetadataException
     */
    public void provision(Path installationFile, List<ChannelRef> channelRefs, List<RemoteRepository> repositories) throws ProvisioningException, MetadataException {
        if (Files.exists(installDir)) {
            throw new ProvisioningException("Installation dir " + installDir + " already exists");
        }
        final List<Channel> channels = mapToChannels(channelRefs);

        final WfChannelMavenResolverFactory factory = new WfChannelMavenResolverFactory(mavenSessionManager, repositories);
        final ChannelMavenArtifactRepositoryManager repoManager = new ChannelMavenArtifactRepositoryManager(channels, factory);
        ProvisioningManager provMgr = GalleonUtils.getProvisioningManager(installDir, repoManager);

        try {
            System.setProperty(MAVEN_REPO_LOCAL, mavenSessionManager.getProvisioningRepo().toAbsolutePath().toString());
            provMgr.provision(installationFile);
        }finally {
            System.clearProperty(MAVEN_REPO_LOCAL);
        }

        writeProsperoMetadata(installDir, repoManager, channelRefs, repositories);
    }

    private List<Channel> mapToChannels(List<ChannelRef> channelRefs) throws MetadataException {
        final List<Channel> channels = new ArrayList<>();
        for (ChannelRef ref : channelRefs) {
            try {
                channels.add(ChannelMapper.from(new URL(ref.getUrl())));
            } catch (MalformedURLException e) {
                throw new MetadataException("Unable to resolve channel configuration", e);
            }
        } return channels;
    }

    private void writeProsperoMetadata(Path home, ChannelMavenArtifactRepositoryManager maven, List<ChannelRef> channelRefs,
                                       List<RemoteRepository> repositories) throws MetadataException {
        final Channel channel = maven.resolvedChannel();

        new InstallationMetadata(home, channel, channelRefs, repositories).writeFiles();
    }
}
