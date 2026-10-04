package com.brainserve.appointment.document;

import com.brainserve.appointment.document.infrastructure.ClamAvScanner;
import com.brainserve.appointment.shared.application.BusinessException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Sprint6DocumentScannerTest {
    private void scan(String response, String expected) throws Exception {
        try (var server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            var reply = executor.submit(() -> {
                try (var client = server.accept()) {
                    var input = new java.io.DataInputStream(client.getInputStream());
                    assertEquals("zINSTREAM\0", new String(input.readNBytes(10), StandardCharsets.US_ASCII));
                    int length;
                    while ((length = input.readInt()) != 0) assertEquals(length, input.readNBytes(length).length);
                    client.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
            var scanner = new ClamAvScanner("127.0.0.1", server.getLocalPort());
            if (expected == null) assertDoesNotThrow(() -> scanner.assertClean(new byte[]{1,2,3}));
            else assertEquals(expected, assertThrows(BusinessException.class,
                    () -> scanner.assertClean(new byte[]{1,2,3})).getErrorCode());
            reply.get();
        }
    }
    @Test void cleanProtocolResponse() throws Exception { scan("stream: OK\0", null); }
    @Test void maliciousResponse() throws Exception { scan("stream: Eicar-Test-Signature FOUND\0", "MALWARE_DETECTED"); }
    @Test void rejectsOkSubstring() throws Exception { scan("stream: NOTOK\0", "MALWARE_SCAN_FAILED"); }
    @Test void rejectsEmptyResponse() throws Exception { scan("\0", "MALWARE_SCAN_FAILED"); }
    @Test void rejectsUnterminatedReply() throws Exception { scan("stream: OK", "MALWARE_SCAN_FAILED"); }
    @Test void rejectsOversizedReply() throws Exception { scan("x".repeat(2048)+"\0", "MALWARE_SCAN_FAILED"); }
    @Test void scannerUnavailableFailsClosed() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        assertEquals("MALWARE_SCANNER_UNAVAILABLE", assertThrows(BusinessException.class,
                () -> new ClamAvScanner("127.0.0.1", port).assertClean(new byte[]{1})).getErrorCode());
    }
}
