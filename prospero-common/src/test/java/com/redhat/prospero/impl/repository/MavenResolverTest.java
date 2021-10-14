package com.redhat.prospero.impl.repository;

import com.redhat.prospero.testing.util.MockResolver;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

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

public class MavenResolverTest {

    @Test
    public void findLatest_searchesRangeStartingWithVersion() throws Exception {
        final MockResolver resolver = new MockResolver();
        final MavenRepository repository = new MavenRepository(resolver, false);
        resolver.setArtifactRange("foo:bar", Arrays.asList("1.0.1", "1.0.2", "1.0.3"));

        final Artifact artifact = repository.resolveLatestVersionOf(new DefaultArtifact("foo:bar:1.0.2"));
        Assert.assertEquals("1.0.3", artifact.getVersion());
    }
}