package org.wildfly.prospero.it.cli;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wildfly.prospero.cli.ReturnCodes;
import org.wildfly.prospero.cli.commands.UpdateCommand;
import org.wildfly.prospero.test.MetadataTestUtils;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;

public class InstallationDirDetectionTest {

    public static final String A_PROSPERO_FP = UpdateCommand.PROSPERO_FP_GA + ":1.0.0";

    private Path installationDir;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        installationDir = tempFolder.newFolder().toPath();

        MetadataTestUtils.createInstallationMetadata(installationDir);
        MetadataTestUtils.createGalleonProvisionedState(installationDir, A_PROSPERO_FP);

        Assert.assertTrue(installationDir.resolve("bin").toFile().mkdir());
    }

    @Test
    public void validInstallationIfBinDirIsPresent() throws Exception {
        Path workingDir = installationDir.resolve("bin");
        String javaPath = ProcessHandle.current().info().command().get();

        ProcessBuilder processBuilder = new ProcessBuilder()
                .command(javaPath, "-jar", Paths.get(System.getenv("jar-path")).toAbsolutePath().toString(),
                        "channel", "list")
                .directory(workingDir.toFile());

        System.out.println("Executed command: " + String.join(" ", processBuilder.command()));

        Process process = processBuilder.start();
        process.waitFor();

        assertEquals("Process error output: " + new String(process.getErrorStream().readAllBytes()),
                ReturnCodes.SUCCESS, process.waitFor());
    }

}
