//package com.ticket.gatling.console;
//
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.io.TempDir;
//
//import java.nio.file.Path;
//import java.util.List;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertThrows;
//
//class LocalFolderOpenerTest {
//
//    @TempDir
//    Path tempDir;
//
//    @Test
//    void buildsWindowsExplorerCommand() {
//        final LocalFolderOpener opener = new LocalFolderOpener("Windows 11");
//
//        final List<String> command = opener.openCommand(tempDir);
//
//        assertEquals("explorer.exe", command.getFirst());
//        assertEquals(tempDir.toAbsolutePath().normalize().toString(), command.get(1));
//    }
//
//    @Test
//    void rejectsMissingDirectory() {
//        final LocalFolderOpener opener = new LocalFolderOpener("Windows 11");
//
//        assertThrows(IllegalArgumentException.class, () -> opener.open(tempDir.resolve("missing")));
//    }
//}
