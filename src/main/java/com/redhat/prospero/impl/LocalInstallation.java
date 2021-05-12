/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.redhat.prospero.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

import com.redhat.prospero.api.PackageInstallationException;
import com.redhat.prospero.descriptors.Manifest;
import com.redhat.prospero.modules.Modules;
import com.redhat.prospero.xml.ManifestReader;
import com.redhat.prospero.xml.XmlException;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.apache.commons.io.FileUtils;

public class LocalInstallation {

   private final Manifest manifest;
   private final Path base;
   private final Modules modules;

   public static LocalInstallation newInstallation(Path base, File basePackage) throws PackageInstallationException, XmlException {
      installPackage(basePackage, base);
      return new LocalInstallation(base);
   }

   public LocalInstallation(Path base) throws XmlException {
      this.base = base;
      manifest = ManifestReader.parse(base.resolve("manifest.xml").toFile());
      modules = new Modules(base);
   }

   public void installPackage(File packageFile) throws PackageInstallationException {
      installPackage(packageFile, base);
   }

   private static void installPackage(File packageFile, Path base) throws PackageInstallationException {
      try {
         new ZipFile(packageFile).extractAll(base.toString());
      } catch (ZipException e) {
         throw new PackageInstallationException("Error when extracting package: " + packageFile, e);
      }
   }

   public void installPackage(Manifest.Package definition, File archiveFile) {

   }

   public void installArtifact(Manifest.Artifact definition, File archiveFile) throws PackageInstallationException {
      //  find in modules
      Collection<Path> updates = modules.find(definition);

      if (updates.isEmpty()) {
         throw new PackageInstallationException("Artifact " + definition.getFileName() + " not found");
      }

      //  drop jar into module folder
      updates.forEach(p -> {
         try {
            FileUtils.copyFile(archiveFile, p.getParent().resolve(archiveFile.getName()).toFile());
         } catch (IOException e) {
            throw new RuntimeException(e);
         }
      });
   }

   public Manifest getManifest() {
      return manifest;
   }
}
