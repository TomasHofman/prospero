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

import org.wildfly.prospero.model.ChannelRef;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.galleon.GalleonUtils;
import org.wildfly.prospero.galleon.ChannelMavenArtifactRepositoryManager;
import org.wildfly.prospero.wfchannel.MavenSessionManager;
import org.wildfly.prospero.wfchannel.WfChannelMavenResolverFactory;
import org.eclipse.aether.repository.RemoteRepository;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.ProvisioningManager;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class InstallationRestore {

    private final Path installDir;
    private MavenSessionManager sessionManager;

    public InstallationRestore(Path installDir, MavenSessionManager sessionManager) {
        this.installDir = installDir;
        this.sessionManager = sessionManager;
    }

    public static void main(String[] args) throws Exception {

        String targetDir = args[0];
        String metadataBundle = args[1];

        new InstallationRestore(Paths.get(targetDir), new MavenSessionManager()).restore(Paths.get(metadataBundle));
    }

    public void restore(Path metadataBundleZip)
            throws ProvisioningException, IOException, MetadataException {
        if (installDir.toFile().exists()) {
            throw new ProvisioningException("Installation dir " + installDir + " already exists");
        }

        final InstallationMetadata metadataBundle = InstallationMetadata.importMetadata(metadataBundleZip);
        final List<Channel> channels = mapToChannels(metadataBundle.getChannels());
        final List<RemoteRepository> repositories = metadataBundle.getRepositories();

        final WfChannelMavenResolverFactory factory = new WfChannelMavenResolverFactory(sessionManager, repositories);
        final ChannelMavenArtifactRepositoryManager repoManager = new ChannelMavenArtifactRepositoryManager(channels, factory, metadataBundle.getManifest());

        ProvisioningManager provMgr = GalleonUtils.getProvisioningManager(installDir, repoManager);

        GalleonUtils.executeGalleon(options -> provMgr.provision(metadataBundle.getProvisioningConfig(), options),
                sessionManager.getProvisioningRepo().toAbsolutePath());
        writeProsperoMetadata(repoManager, metadataBundle.getChannels(), repositories);
    }

    private void writeProsperoMetadata(ChannelMavenArtifactRepositoryManager maven, List<ChannelRef> channelRefs, List<RemoteRepository> repositories)
            throws MetadataException {
        new InstallationMetadata(installDir, maven.resolvedChannel(), channelRefs, repositories).writeFiles();
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
}
