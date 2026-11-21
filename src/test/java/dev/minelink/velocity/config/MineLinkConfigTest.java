package dev.minelink.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MineLinkConfigTest {

    @Test
    void loadOrCreateWritesDefaultConfigAndParsesCleanly(@TempDir Path tempDir) throws IOException {
        List<String> warnings = new ArrayList<>();
        MineLinkConfig cfg = MineLinkConfig.loadOrCreate(tempDir, warnings::add);
        assertTrue(Files.exists(tempDir.resolve("config.toml")));
        assertTrue(warnings.isEmpty());
        assertEquals(List.of("minehut", "minekeep"), cfg.order());
        assertEquals(3500, cfg.upstreamTimeoutMs());
        assertEquals(250, cfg.staggerMs());
        assertEquals(4500, cfg.deadlineMs());
        assertEquals(
            "https://api.minehut.com/mitm/proxy/session/minecraft/hasJoined",
            cfg.upstreams().get("minehut").hasJoinedUrl()
        );
        assertEquals(
            "https://sessionserver.minekeep.net/session/minecraft/hasJoined",
            cfg.upstreams().get("minekeep").hasJoinedUrl()
        );
    }

    @Test
    void parsesCustomUpstreamsQuotedKeysBomLoopbackHttpAndDeduplicatesOrder() {
        String toml = "\uFEFF" + """
            order = ["minekeep", "custom-net", "minekeep", "local", "minehut"]
            upstream-timeout-ms = 2000
            stagger-ms = 0
            deadline-ms = 4000

            [upstreams]
            minehut = "https://new.minehut.example/hasJoined"
            "custom-net" = "https://auth.example.com/session/minecraft/hasJoined"
            local = "http://127.0.0.1:8080/session/minecraft/hasJoined"
            """;

        MineLinkConfig cfg = MineLinkConfig.parse(toml);
        assertEquals(List.of("minekeep", "custom-net", "local", "minehut"), cfg.order());
        assertEquals(2000, cfg.upstreamTimeoutMs());
        assertEquals(0, cfg.staggerMs());
        assertEquals(4000, cfg.deadlineMs());
        assertEquals("https://new.minehut.example/hasJoined", cfg.upstreams().get("minehut").hasJoinedUrl());
        assertEquals(
            "https://auth.example.com/session/minecraft/hasJoined",
            cfg.upstreams().get("custom-net").hasJoinedUrl()
        );
        assertEquals(
            "http://127.0.0.1:8080/session/minecraft/hasJoined",
            cfg.upstreams().get("local").hasJoinedUrl()
        );
    }

    @Test
    void warnsOnUnknownTopLevelKeysAndDeadlineBelowTimeout() {
        List<String> warnings = new ArrayList<>();
        MineLinkConfig.parse("""
            stager-ms = 100
            upstream-timeout-ms = 4000
            deadline-ms = 2000
            """, warnings::add);

        assertEquals(2, warnings.size());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("stager-ms")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("deadline-ms")));

        MineLinkConfig.parse("stager-ms = 100\n", null);
    }

    @Test
    void rejectsMalformedTomlWrongTypesAndInvalidValuesAsIllegalArgumentException() {
        List<String> invalid = List.of(
            "order = [",
            "order = \"minehut\"",
            "\"order\" = [\"minekeep\"]",
            "\"stagger-ms\" = 0",
            "[\"upstreams\"]\nminehut = \"https://other.example/hasJoined\"",
            "['upstreams']\nminehut = \"https://other.example/hasJoined\"",
            "[upstreams]\nfoo = \"https://a.example/hasJoined\"\n\"foo\" = \"https://b.example/hasJoined\"",
            "order = [1]",
            "order = []",
            "order = [\"   \"]",
            "order = [\"unknown\"]",
            "upstreams = \"https://example.com/hasJoined\"",
            "[upstreams]\nBad_ID = \"https://example.com/hasJoined\"",
            "[upstreams]\na = \"https://example.com/hasJoined?x=1\"",
            "[upstreams]\na = \"https://example.com/hasJoined#frag\"",
            "[upstreams]\na = \"https://user:pass@example.com/hasJoined\"",
            "[upstreams]\na = \"http://auth.example.com/hasJoined\"",
            "[upstreams]\na = \"ftp://auth.example.com/hasJoined\"",
            "[upstreams]\na = \"https://auth.example.com:0/hasJoined\"",
            "[upstreams]\na = \"https://auth.example.com:99999/hasJoined\"",
            "upstream-timeout-ms = 50",
            "deadline-ms = 99999",
            "stagger-ms = -1",
            "stagger-ms = \"fast\""
        );

        for (String toml : invalid) {
            assertThrows(IllegalArgumentException.class, () -> MineLinkConfig.parse(toml), "Expected failure for:\n" + toml);
        }
    }
}
