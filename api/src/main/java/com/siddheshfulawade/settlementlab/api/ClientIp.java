package com.siddheshfulawade.settlementlab.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out which visitor a request came from, for the per-IP run limit (REQ-RUN-006).
 *
 * <p>Invariant 1: a client-supplied value is never trusted just because it is first. The walk goes
 * through {@code X-Forwarded-For} from the right (the entries our own proxies appended), skips entries
 * inside the trusted proxy ranges, and stops at the first address outside them. Anything to the left of
 * that is whatever the visitor typed, and is never read. An entry that is not an IP address, an empty
 * entry, or running out of entries all mean the remote address: the answer can fall back to the
 * connection's peer, but never to a forgeable value.
 *
 * <p>Invariant 2: no DNS lookup, ever. {@code InetAddress.getByName} resolves host names (a visitor
 * could make the server do network I/O), and {@code InetAddress.ofLiteral} needs Java 22. So addresses
 * are parsed here, by shape, and no host name ever reaches a resolver.
 *
 * <p>The key is what the limiter stores: an IPv4 address in dotted form, or an IPv6 address reduced to
 * its /64 (one household or one VPS is one visitor; its other 2^64 addresses are not other visitors).
 * An IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) is the IPv4 address. The key is never logged.
 */
final class ClientIp {

    /** An address as bytes: 4 for IPv4, 16 for IPv6. */
    private record Address(byte[] bytes) {}

    private record Range(Address network, int prefixBits) {

        boolean contains(Address address) {
            if (address.bytes().length != network.bytes().length) {
                return false;
            }
            for (int bit = 0; bit < prefixBits; bit++) {
                if (bitAt(address.bytes(), bit) != bitAt(network.bytes(), bit)) {
                    return false;
                }
            }
            return true;
        }

        private static int bitAt(byte[] bytes, int bit) {
            return (bytes[bit / 8] >> (7 - bit % 8)) & 1;
        }
    }

    private final List<Range> trusted;

    /**
     * @throws IllegalArgumentException for a range that is not {@code address/prefix} (start-up fails)
     */
    ClientIp(List<String> trustedProxyRanges) {
        List<Range> ranges = new ArrayList<>();
        for (String cidr : trustedProxyRanges) {
            ranges.add(parseRange(cidr));
        }
        this.trusted = List.copyOf(ranges);
    }

    /**
     * @param forwardedForLines every {@code X-Forwarded-For} header line, in order (a proxy may add its own)
     * @param remoteAddress the connection's peer address
     */
    String resolve(List<String> forwardedForLines, String remoteAddress) {
        List<String> entries = new ArrayList<>();
        for (String line : forwardedForLines) {
            for (String entry : line.split(",", -1)) {
                entries.add(entry.trim());
            }
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            Address address = parse(entries.get(i));
            if (address == null) {
                break; // not an IP (or empty): do not look further left, use the peer
            }
            if (!isTrusted(address)) {
                return keyOf(address);
            }
        }
        return remoteKey(remoteAddress);
    }

    private boolean isTrusted(Address address) {
        for (Range range : trusted) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private static String remoteKey(String remoteAddress) {
        String literal = remoteAddress == null ? "" : remoteAddress;
        int zone = literal.indexOf('%'); // a scope id such as fe80::1%eth0 is not part of the address
        Address address = parse(zone < 0 ? literal : literal.substring(0, zone));
        return address == null ? "unknown" : keyOf(address);
    }

    private static String keyOf(Address address) {
        byte[] b = address.bytes();
        if (b.length == 4) {
            return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + "." + (b[3] & 0xff);
        }
        // IPv6: the first 64 bits only, so every address of one /64 is one visitor.
        return String.format("%x:%x:%x:%x::/64", group(b, 0), group(b, 1), group(b, 2), group(b, 3));
    }

    private static int group(byte[] b, int index) {
        return (b[2 * index] & 0xff) << 8 | (b[2 * index + 1] & 0xff);
    }

    // ---- literal parsing: no InetAddress, so no resolver can ever be asked ---------------------------

    private static Range parseRange(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash <= 0 || slash == cidr.length() - 1) {
            throw new IllegalArgumentException("trusted proxy range must look like 10.0.0.0/8: " + cidr);
        }
        Address network = parse(cidr.substring(0, slash));
        String prefixText = cidr.substring(slash + 1);
        if (network == null || !isDigits(prefixText) || prefixText.length() > 3) {
            throw new IllegalArgumentException("trusted proxy range must look like 10.0.0.0/8: " + cidr);
        }
        int prefix = Integer.parseInt(prefixText);
        if (prefix > network.bytes().length * 8) {
            throw new IllegalArgumentException("prefix length is too long for the address: " + cidr);
        }
        return new Range(network, prefix);
    }

    /** The address a literal stands for, or null if it is not exactly an IPv4 or IPv6 literal. */
    private static Address parse(String text) {
        Address v4 = parseIpv4(text);
        if (v4 != null) {
            return v4;
        }
        byte[] v6 = parseIpv6(text);
        if (v6 == null) {
            return null;
        }
        if (isIpv4Mapped(v6)) {
            return new Address(new byte[] {v6[12], v6[13], v6[14], v6[15]});
        }
        return new Address(v6);
    }

    /** Four decimal parts of 1 to 3 digits, each 0 to 255, no leading zeros (they could mean octal). */
    private static Address parseIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (!isDigits(part) || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) {
                return null;
            }
            int value = Integer.parseInt(part);
            if (value > 255) {
                return null;
            }
            bytes[i] = (byte) value;
        }
        return new Address(bytes);
    }

    /** Hex groups of 1 to 4 digits, at most one "::", an optional IPv4 tail; no brackets, port or zone. */
    private static byte[] parseIpv6(String text) {
        if (text.indexOf(':') < 0 || text.contains(":::")) {
            return null;
        }
        int compression = text.indexOf("::");
        if (compression >= 0 && text.indexOf("::", compression + 1) >= 0) {
            return null; // two "::"
        }
        List<Integer> head = new ArrayList<>();
        List<Integer> tail = new ArrayList<>();
        if (compression < 0) {
            if (!collectGroups(text, head)) {
                return null;
            }
        } else if (!collectGroups(text.substring(0, compression), head)
                || !collectGroups(text.substring(compression + 2), tail)) {
            return null;
        }
        int groups = head.size() + tail.size();
        if (compression < 0 ? groups != 8 : groups > 7) { // "::" stands for at least one zero group
            return null;
        }
        byte[] bytes = new byte[16];
        int index = 0;
        for (int value : head) {
            bytes[index++] = (byte) (value >> 8);
            bytes[index++] = (byte) value;
        }
        index = 16 - 2 * tail.size();
        for (int value : tail) {
            bytes[index++] = (byte) (value >> 8);
            bytes[index++] = (byte) value;
        }
        return bytes;
    }

    /** Adds the 16-bit groups of "a:b:c"; an IPv4 tail adds two. An empty string adds none. */
    private static boolean collectGroups(String text, List<Integer> groups) {
        if (text.isEmpty()) {
            return true;
        }
        String[] parts = text.split(":", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == parts.length - 1 && part.indexOf('.') >= 0) {
                Address v4 = parseIpv4(part);
                if (v4 == null) {
                    return false;
                }
                groups.add((v4.bytes()[0] & 0xff) << 8 | (v4.bytes()[1] & 0xff));
                groups.add((v4.bytes()[2] & 0xff) << 8 | (v4.bytes()[3] & 0xff));
            } else if (part.isEmpty() || part.length() > 4 || !isHex(part)) {
                return false;
            } else {
                groups.add(Integer.parseInt(part, 16));
            }
        }
        return true;
    }

    private static boolean isIpv4Mapped(byte[] v6) {
        for (int i = 0; i < 10; i++) {
            if (v6[i] != 0) {
                return false;
            }
        }
        return v6[10] == (byte) 0xff && v6[11] == (byte) 0xff;
    }

    /** ASCII digits only: Character.isDigit would accept other scripts. */
    private static boolean isDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
