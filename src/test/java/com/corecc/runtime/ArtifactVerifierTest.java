package com.corecc.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactVerifierTest {
    @TempDir Path tempDir;

    @Test
    void nativeChecksHandlePathsWithSpacesWithoutCallingTheShell() throws Exception {
        Path artifact = tempDir.resolve("result file.txt");
        Files.writeString(artifact, "required marker");

        ArtifactVerifier.VerifyReport report = ArtifactVerifier.verify(
            "must contain 'required marker' and under 1KB",
            List.of(artifact.toString()),
            command -> fail("native checks must not call the shell"));

        assertTrue(report.allPassed());
        assertEquals(3, report.getResults().size());
    }

    @Test
    void missingArtifactProducesAnActionableFailure() {
        ArtifactVerifier.VerifyReport report = ArtifactVerifier.verify(
            "create the file", List.of(tempDir.resolve("missing.txt").toString()), command -> "");
        assertFalse(report.allPassed());
        assertTrue(report.getNudge().contains("MISSING"));
    }

    @Test
    void compilationUsesAnOsAppropriateQuotedCommand() throws Exception {
        Path source = tempDir.resolve("hello world.java");
        Files.writeString(source, "class HelloWorld {}");
        AtomicReference<String> command = new AtomicReference<>();

        ArtifactVerifier.VerifyReport report = ArtifactVerifier.verify(
            "compile this source", List.of(source.toString()), value -> {
                command.set(value);
                return "COMPILES";
            });

        assertTrue(report.allPassed());
        assertNotNull(command.get());
        assertTrue(command.get().contains(source.toAbsolutePath().toString()));
        String quote = System.getProperty("os.name", "").toLowerCase().contains("win") ? "\"" : "'";
        assertTrue(command.get().contains(quote));
    }
}
