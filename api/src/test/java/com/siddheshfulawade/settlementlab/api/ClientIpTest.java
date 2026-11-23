package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * REQ-RUN-006: the client is the first address, walking X-Forwarded-For from the right, outside the
 * trusted proxy ranges (default none, so the last entry). An entry reached that is not an IP, nothing
 * left, or no header means the remote address. IPv6 is keyed by its /64, IPv4-mapped IPv6 is IPv4.
 * No DNS lookup, ever.
 *
 * <p>ASSUMED API: {@code new ClientIp(List<String> trustedProxyCidrs)} (throws IllegalArgumentException for
 * an invalid CIDR; {@code new ClientIp(List.of())} trusts nothing), and {@code
 * String resolve(List<String> forwardedForHeaderLines, String remoteAddress)}.
 */
class ClientIpTest {

    private static final String REMOTE = "192.0.2.10";

    private final ClientIp direct = new ClientIp(List.of());

    private static ClientIp trusting(String... cidrs) {
        return new ClientIp(List.of(cidrs));
    }

    @Test
    @DisplayName("REQ-RUN-006 with no trusted ranges the last entry is the client")
    void lastEntryWins() {
        assertThat(direct.resolve(List.of("203.0.113.9"), REMOTE)).isEqualTo("203.0.113.9");
        assertThat(direct.resolve(List.of("198.51.100.1, 10.0.0.1, 203.0.113.9"), REMOTE))
                .isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("REQ-RUN-006 spaces around entries are trimmed")
    void spacesTrimmed() {
        assertThat(direct.resolve(List.of("  198.51.100.1 ,   203.0.113.9   "), REMOTE))
                .isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("REQ-RUN-006 with several header lines the last entry of the last line wins")
    void multipleHeaderLines() {
        assertThat(direct.resolve(List.of("1.1.1.1, 2.2.2.2", "3.3.3.3, 4.4.4.4"), REMOTE))
                .isEqualTo("4.4.4.4");
    }

    @Test
    @DisplayName("REQ-RUN-006 trusted proxies are skipped from the right; the first address outside them is the client")
    void walksPastTrustedProxies() {
        ClientIp ip = trusting("35.191.0.0/16", "10.0.0.0/8");

        assertThat(ip.resolve(List.of("198.51.100.1, 203.0.113.9, 35.191.0.5, 10.0.0.2"), REMOTE))
                .isEqualTo("203.0.113.9");
        assertThat(ip.resolve(List.of("203.0.113.9, 10.0.0.2", "35.191.0.5"), REMOTE))
                .as("trusted entries across header lines")
                .isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("REQ-RUN-006 a forged entry to the left of the first untrusted one is never used")
    void entriesLeftOfTheClientAreIgnored() {
        assertThat(trusting("10.0.0.0/8").resolve(List.of("6.6.6.6, 203.0.113.9, 10.1.1.1"), REMOTE))
                .isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("REQ-RUN-006 the CIDR edges are exact: /32 matches one address, /24 stops at its last address")
    void cidrEdges() {
        ClientIp ip = trusting("203.0.113.9/32", "198.51.100.0/24");

        assertThat(ip.resolve(List.of("1.1.1.1, 203.0.113.9"), REMOTE)).isEqualTo("1.1.1.1");
        assertThat(ip.resolve(List.of("1.1.1.1, 203.0.113.10"), REMOTE)).isEqualTo("203.0.113.10");
        assertThat(ip.resolve(List.of("1.1.1.1, 198.51.100.255"), REMOTE)).isEqualTo("1.1.1.1");
        assertThat(ip.resolve(List.of("1.1.1.1, 198.51.101.0"), REMOTE)).isEqualTo("198.51.101.0");
    }

    @Test
    @DisplayName("REQ-RUN-006 an IPv6 range can be trusted, and an IPv4-mapped address matches an IPv4 range")
    void ipv6AndMappedRanges() {
        ClientIp ip = trusting("2001:db8::/32", "10.0.0.0/8");

        assertThat(ip.resolve(List.of("203.0.113.9, 2001:db8:5::1"), REMOTE)).isEqualTo("203.0.113.9");
        assertThat(ip.resolve(List.of("203.0.113.9, ::ffff:10.0.0.2"), REMOTE)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("REQ-RUN-006 no header falls back to the remote address")
    void noHeader() {
        assertThat(direct.resolve(List.of(), REMOTE)).isEqualTo(REMOTE);
    }

    @Test
    @DisplayName("REQ-RUN-006 an empty or blank header falls back to the remote address")
    void emptyHeader() {
        assertThat(direct.resolve(List.of(""), REMOTE)).isEqualTo(REMOTE);
        assertThat(direct.resolve(List.of("   "), REMOTE)).isEqualTo(REMOTE);
    }

    @Test
    @DisplayName("REQ-RUN-006 an empty last entry is not replaced by the forgeable one before it")
    void emptyLastEntryFallsBack() {
        assertThat(direct.resolve(List.of("198.51.100.1,"), REMOTE)).isEqualTo(REMOTE);
        assertThat(direct.resolve(List.of("198.51.100.1, ,  "), REMOTE)).isEqualTo(REMOTE);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(
            strings = {
                "garbage",
                "localhost",
                "example.com",
                "203.0.113.9:4711",
                "999.1.1.1",
                "1.2.3",
                "203.0.113.9/24",
                "[2001:db8::1]:443",
                "0x7f.0.0.1",
                "unknown",
                "_hidden"
            })
    @DisplayName("REQ-RUN-006 an entry reached by the walk that is not an IP address means the remote address")
    void unparseableEntryFallsBack(String entry) {
        assertThat(direct.resolve(List.of("203.0.113.9, " + entry), REMOTE)).isEqualTo(REMOTE);
        assertThat(trusting("10.0.0.0/8").resolve(List.of(entry + ", 10.0.0.2"), REMOTE))
                .as("reached after skipping a trusted proxy")
                .isEqualTo(REMOTE);
    }

    @Test
    @DisplayName("REQ-RUN-006 an unparseable entry the walk never reaches does not matter")
    void unreachedGarbageIsIgnored() {
        assertThat(direct.resolve(List.of("garbage, 203.0.113.9"), REMOTE)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("REQ-RUN-006 when every entry is a trusted proxy, nothing is left: the remote address")
    void allTrustedFallsBack() {
        assertThat(trusting("10.0.0.0/8").resolve(List.of("10.0.0.1, 10.0.0.2"), REMOTE))
                .isEqualTo(REMOTE);
    }

    @Test
    @DisplayName("REQ-RUN-006 IPv6 clients are keyed by /64: same /64 same key, different /64 different key")
    void ipv6KeyedBySlash64() {
        String key = direct.resolve(List.of("2001:db8:1:2:aaaa:bbbb:cccc:dddd"), REMOTE);

        assertThat(direct.resolve(List.of("2001:db8:1:2::1"), REMOTE)).isEqualTo(key);
        assertThat(direct.resolve(List.of("2001:0db8:0001:0002:ffff:ffff:ffff:ffff"), REMOTE))
                .as("spelling does not matter")
                .isEqualTo(key);
        assertThat(direct.resolve(List.of("2001:db8:1:3::1"), REMOTE)).isNotEqualTo(key);
        assertThat(direct.resolve(List.of("2001:db8:1:2::1"), REMOTE)).isNotEqualTo(REMOTE);
    }

    @Test
    @DisplayName("REQ-RUN-006 a remote IPv6 address is keyed by /64 as well")
    void remoteIpv6KeyedBySlash64() {
        assertThat(direct.resolve(List.of(), "2001:db8:1:2:1:2:3:4"))
                .isEqualTo(direct.resolve(List.of("2001:db8:1:2::99"), REMOTE));
    }

    @Test
    @DisplayName("REQ-RUN-006 an IPv4-mapped IPv6 address is the same client as the IPv4 address")
    void mappedAddressIsIpv4() {
        assertThat(direct.resolve(List.of("::ffff:203.0.113.9"), REMOTE)).isEqualTo("203.0.113.9");
        assertThat(direct.resolve(List.of(), "::ffff:192.0.2.10")).isEqualTo("192.0.2.10");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(
            strings = {
                "not-a-cidr",
                "10.0.0.0/33",
                "10.0.0.0/-1",
                "999.0.0.0/8",
                "2001:db8::/129",
                "10.0.0.0/",
                "/8",
                "10.0.0.0/abc",
                ""
            })
    @DisplayName("REQ-RUN-006 an invalid CIDR is rejected when the proxy list is built")
    void invalidCidrIsRejected(String cidr) {
        assertThatThrownBy(() -> new ClientIp(List.of(cidr))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Disabled("TODO-ME: write this check, then remove @Disabled")
    @DisplayName("REQ-RUN-006 a /0 range trusts everything, so the remote address is used")
    void slashZeroTrustsEveryone() {
        // TODO-ME: build ClientIp with List.of("0.0.0.0/0"), resolve List.of("203.0.113.9") with REMOTE and
        // assert the result is REMOTE (every entry is trusted, nothing is left). Check it by running this class;
        // it should pass without changing ClientIp. Then decide whether "0.0.0.0/0" should be refused at startup.
    }
}
