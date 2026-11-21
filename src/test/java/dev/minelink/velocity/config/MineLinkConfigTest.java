package dev.minelink.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MineLinkConfigTest {

    @Test
    void loadOrCreateWritesDefaultConfigAndParsesCleanly(@TempDir Path tempDir) throws IOException {
        MineLinkConfig cfg = MineLinkConfig.loadOrCreate(tempDir);
        assertTrue(Files.exists(tempDir.resolve("config.toml")));
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
    void parsesCustomUpstreamsOverridesAndDeduplicatesOrder() {
        String toml = """
            order = ["minekeep", "custom-net", "minekeep", "minehut"]
            upstream-timeout-ms = 2000
            stagger-ms = 0
            deadline-ms = 4000

            [upstreams]
            minehut = "https://new.minehut.example/hasJoined"
            custom-net = "https://auth.example.com/session/minecraft/hasJoined"
            """;

        MineLinkConfig cfg = MineLinkConfig.parse(toml);
        assertEquals(List.of("minekeep", "custom-net", "minehut"), cfg.order());
        assertEquals(2000, cfg.upstreamTimeoutMs());
        assertEquals(0, cfg.staggerMs());
        assertEquals(4000, cfg.deadlineMs());
        assertEquals("https://new.minehut.example/hasJoined", cfg.upstreams().get("minehut").hasJoinedUrl());
        assertEquals(
            "https://auth.example.com/session/minecraft/hasJoined",
            cfg.upstreams().get("custom-net").hasJoinedUrl()
        );
    }

    @Test
    void rejectsInvalidConfig() {
        assertThrows(IllegalArgumentException.class, () -> MineLinkConfig.parse("order = []"));
        assertThrows(IllegalArgumentException.class, () -> MineLinkConfig.parse("order = [\"unknown\"]"));
        assertThrows(IllegalArgumentException.class, () -> MineLinkConfig.parse(
            "[upstreams]\nBad_ID = \"https://example.com/hasJoined\""
        ));
        assertThrows(IllegalArgumentException.class, () -> MineLinkConfig.parse(
            "[upstreams]\na = \"https://example.com/hasJoined?x=1\""
        ));
        assertThrows(IllegalArgumentException.class, () -> MineLinkConfig.parse(
            "[upstreams]\na = \"https://user:pass@example.com/hasJoined\""
        ));
        assertThrows(IllegalArgumentException.class, () -> MineLinkConfig.parse("upstream-timeout-ms = 50"));
        assertThrows(IllegalArgumentException.class, () -> MineLinkConfig.parse("deadline-ms = 99999"));
        assertThrows(IllegalArgumentException.class, () -> MineLinkConfig.parse("stagger-ms = -1"));
    }
}
