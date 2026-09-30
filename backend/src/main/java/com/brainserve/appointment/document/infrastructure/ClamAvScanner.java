package com.brainserve.appointment.document.infrastructure;

import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

@Component
public class ClamAvScanner {
    private final String host;
    private final int port;
    public ClamAvScanner(@Value("${brainserve.document.clamav-host}") String host,
                         @Value("${brainserve.document.clamav-port}") int port) { this.host = host; this.port = port; }

    public void assertClean(byte[] bytes) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(15000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            int offset = 0;
            while (offset < bytes.length) {
                int length = Math.min(8192, bytes.length - offset);
                output.writeInt(length); output.write(bytes, offset, length); offset += length;
            }
            output.writeInt(0); output.flush();
            String response = new String(new BufferedInputStream(socket.getInputStream()).readAllBytes(), StandardCharsets.UTF_8);
            if (response.contains("FOUND"))
                throw new BusinessException("MALWARE_DETECTED", "The uploaded file failed security scanning", HttpStatus.UNPROCESSABLE_ENTITY);
            if (!response.contains("OK"))
                throw new BusinessException("MALWARE_SCAN_FAILED", "The uploaded file could not be verified", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (IOException ex) {
            throw new BusinessException("MALWARE_SCANNER_UNAVAILABLE", "Secure file scanning is temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
