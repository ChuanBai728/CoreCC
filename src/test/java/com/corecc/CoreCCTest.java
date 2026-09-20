package com.corecc;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreCCTest {
    @Test
    void helpOptionIsRecognized() {
        CommandLine.ParseResult result = new CommandLine(new CoreCC()).parseArgs("--help");
        assertTrue(result.isUsageHelpRequested());
    }
}
