package com.brainserve.appointment.iam.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** Resolves only numeric addresses supplied through explicitly trusted proxy hops. */
@Component
public class ClientAddressResolver {
    private static final int MAX_HEADER_LENGTH = 4096;
    private static final int MAX_HOPS = 32;
    private final List<IpAddressMatcher> trustedProxies;

    public ClientAddressResolver(@Value("${brainserve.security.trusted-proxy-cidrs:}") String cidrs) {
        trustedProxies = new ArrayList<>();
        for (String value : cidrs.split(",")) {
            if (value.isBlank()) continue;
            String[] parts = value.trim().split("/", -1);
            String address = literal(parts[0]);
            if (address == null || parts.length > 2) {
                throw new IllegalArgumentException("Trusted proxy entries must be numeric IP addresses or CIDRs");
            }
            int bits = address.contains(":") ? 128 : 32;
            int prefix = parts.length == 1 ? bits : Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > bits) throw new IllegalArgumentException("Invalid trusted proxy CIDR prefix");
            trustedProxies.add(new IpAddressMatcher(address + "/" + prefix));
        }
    }

    public String resolve(HttpServletRequest request) {
        String peer = literal(request.getRemoteAddr());
        if (peer == null) return "unknown";
        if (!trusted(peer)) return peer;

        List<String> hops = new ArrayList<>();
        var headers = request.getHeaders("X-Forwarded-For");
        int length = 0;
        while (headers.hasMoreElements()) {
            String header = headers.nextElement();
            length += header.length();
            if (length > MAX_HEADER_LENGTH) return peer;
            for (String hop : header.split(",", -1)) {
                if (hops.size() == MAX_HOPS) return peer;
                hops.add(hop.trim());
            }
        }

        String client = peer;
        // The first untrusted hop from the right is the client. Values to its
        // left can be supplied by that client and must never influence limits.
        for (int index = hops.size() - 1; index >= 0 && trusted(client); index--) {
            String hop = literal(hops.get(index));
            if (hop == null) return peer;
            client = hop;
        }
        return client;
    }

    private boolean trusted(String address) {
        return trustedProxies.stream().anyMatch(proxy -> proxy.matches(address));
    }

    private static String literal(String value) {
        if (value == null || value.isEmpty() || value.length() > 45) return null;
        // Reject hostnames, zones, brackets, ports and ambiguous short IPv4
        // forms before InetAddress, so forwarded input can never trigger DNS.
        if (!value.matches("[0-9a-fA-F:.]+")) return null;
        if (!value.contains(":")) {
            String[] octets = value.split("\\.", -1);
            if (octets.length != 4) return null;
            for (String octet : octets) {
                if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) return null;
            }
        }
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
