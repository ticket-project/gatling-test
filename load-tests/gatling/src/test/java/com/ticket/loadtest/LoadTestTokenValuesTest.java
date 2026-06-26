package com.ticket.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoadTestTokenValuesTest {

    @TempDir
    Path tempDir;

    @Test
    void readsTokensFromInlineCsvWhenFilePathIsBlank() {
        List<String> tokens = LoadTestTokenValues.fromCsvOrFile(" token-1,token-2 , token-3 ", "", "accessTokens");

        assertEquals(List.of("token-1", "token-2", "token-3"), tokens);
    }

    @Test
    void readsTokensFromFileAndPrefersFileOverInlineCsv() throws Exception {
        Path tokenFile = tempDir.resolve("access-tokens.txt");
        Files.writeString(tokenFile, "file-token-1\nfile-token-2,file-token-3\n");

        List<String> tokens = LoadTestTokenValues.fromCsvOrFile(
                "inline-token",
                tokenFile.toString(),
                "accessTokens"
        );

        assertEquals(List.of("file-token-1", "file-token-2", "file-token-3"), tokens);
    }
}
