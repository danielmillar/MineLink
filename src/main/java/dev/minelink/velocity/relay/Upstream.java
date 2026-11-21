package dev.minelink.velocity.relay;

import java.util.Objects;

public record Upstream(String id, String hasJoinedUrl) {
    public Upstream {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(hasJoinedUrl, "hasJoinedUrl");
    }
}
