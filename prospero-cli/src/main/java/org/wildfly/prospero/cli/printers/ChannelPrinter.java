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

package org.wildfly.prospero.cli.printers;

import org.wildfly.channel.Channel;
import org.wildfly.channel.MavenCoordinate;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.api.Console;

public class ChannelPrinter {

    private final Console console;
    private final int indentSize;

    public ChannelPrinter(Console console) {
        this(console, 0);
    }

    public ChannelPrinter(Console console, int indentSize) {
        this.console = console;
        this.indentSize = indentSize;
    }

    public void print(Channel channel) {
        String startMarker = "# ";
        if (channel.getName() != null && !channel.getName().isEmpty()) {
            printLine(startMarker + channel.getName());
            startMarker = "  ";
        }
        final String manifest = channel.getManifestCoordinate().getMaven() == null
                ?channel.getManifestCoordinate().getUrl().toExternalForm():toGav(channel.getManifestCoordinate().getMaven());
        printLine(startMarker + "manifest: " + manifest);
        printLine("  " + "repositories:");
        for (Repository repository : channel.getRepositories()) {
            printLine("  " + "  " + "id: " + repository.getId());
            printLine("  " + "  " + "url: " + repository.getUrl());
        }
    }

    private void printLine(String text) {
        console.println(" ".repeat(indentSize) + text);
    }

    private static String toGav(MavenCoordinate coord) {
        final String ga = coord.getGroupId() + ":" + coord.getArtifactId();
        if (coord.getVersion() != null && !coord.getVersion().isEmpty()) {
            return ga + ":" + coord.getVersion();
        }
        return ga;
    }
}
