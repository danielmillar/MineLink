# MineLink

MineLink is a Velocity plugin that verifies player logins against both [Minehut](https://minehut.com) and [MineKeep](https://minekeep.net) directly from your own proxy—no third-party session relay, no custom proxy fork.

Any hosted relay sits in a position of complete trust: whoever runs the session server can vouch for any player on every server pointing at it. Moving that lookup inside your proxy takes the middleman out. Your Velocity instance talks straight to Minehut and MineKeep over HTTPS, and because the plugin relies only on public Velocity APIs, you stay on upstream Velocity 3.4+ or 4.x and update normally when PaperMC cuts a release.

MineLink is an independent project and isn't affiliated with Minehut, MineKeep, PaperMC, or Mojang.

## How it works

When Velocity boots, it fires `ProxyInitializeEvent` before opening its listening socket or loading `InitialLoginSessionHandler`. MineLink catches that window and starts a lightweight HTTP server bound strictly to `127.0.0.1` on an OS-assigned port. It then points `mojang.sessionserver` at that local URL, behind a fresh 16-byte random hex token (`/session/minecraft/hasJoined` under a 32-character secret) generated on every boot—nothing off the machine can reach the socket, and other local processes can't guess the route.

From there, Velocity verifies joins the same way it would against Mojang—just aimed at loopback instead. **Upstream order** decides which network gets asked first (`minehut`, then `minekeep` by default). If the first network answers `204` or fails, the next one fires right away. If it stalls past `stagger-ms` (`250 ms` by default), the next upstream starts in parallel while the first keeps running, so a slow response from one network doesn't stall joins from the other. Fast Minehut logins never touch MineKeep at all.

Before accepting any `200`, the plugin checks that the JSON body has an undashed 32-character hex `id`, a non-empty `name` up to 64 characters, and a `properties` array of objects with string `name` and `value` fields (plus an optional `signature` that's a string or `null`). HTML error or challenge pages fail that check and fall through instead of breaking Velocity's parser. Once an upstream matches, any remaining in-flight lookups are cancelled and the raw response bytes go straight back to Velocity untouched—Mojang skin signatures and any extra fields survive intact. The original query string goes out verbatim with `User-Agent: minelink/<version>`, and redirects are never followed.

Disconnect codes stay accurate too. Velocity receives `204` ("online mode only") only when every configured upstream returns `204` cleanly; any timeout, bad status, invalid `200`, or `deadline-ms` hit turns into `503` ("authentication servers are down") when nothing matched.

## Setup

You'll need Velocity 3.4+ (Java 17+) or Velocity 4.x (Java 25); MineLink compiles against Velocity API 3.4.0 to Java 17 bytecode so the same JAR runs on both.

1. Download the latest `minelink-<version>.jar` release (or build it from source) and drop it into `plugins/`.
2. Remove any `-Dmojang.sessionserver` flag from your startup script. If that property is already set at boot, the plugin leaves it alone and logs a warning instead of fighting your launch flags.
3. In `velocity.toml`, keep `online-mode = true`. If you accept Minehut players alongside MineKeep, turn on `haproxy-protocol = true` in `velocity.toml` and enable PROXY protocol in MineKeep's panel too—Minehut requires it, and Velocity applies it to every incoming connection. Firewall the Velocity listener so only trusted Minehut and MineKeep ingress can reach it; anyone connecting directly to an exposed PROXY-protocol port can spoof player IPs.
4. Start Velocity. On first boot, a default `plugins/minelink/config.toml` is created and the log prints:

```text
MineLink v<version> active on 127.0.0.1:<port> (order=minehut -> minekeep)
```

## Configuration

Here's the default `plugins/minelink/config.toml`:

```toml
order = ["minehut", "minekeep"]
upstream-timeout-ms = 3500
stagger-ms = 250
deadline-ms = 4500

[upstreams]
minehut = "https://api.minehut.com/mitm/proxy/session/minecraft/hasJoined"
minekeep = "https://sessionserver.minekeep.net/session/minecraft/hasJoined"
```

| Key | Default | Bounds | Role |
|---|---|---|---|
| `order` | `["minehut", "minekeep"]` | At least one known ID | Priority list for lookups; blank entries and duplicates are ignored, and omitted upstreams aren't queried. |
| `upstream-timeout-ms` | `3500` | `100`–`20000` | Per-upstream HTTP timeout in milliseconds before that call fails. |
| `stagger-ms` | `250` | `0`–`20000` | Wait before firing the next upstream while the current one is still in flight (`0` queries all upstreams at once). |
| `deadline-ms` | `4500` | `100`–`20000` | Hard ceiling for the whole login check; keep it at or above `upstream-timeout-ms` so the deadline doesn't cut off a single request early. |

### Custom upstreams

Both `minehut` and `minekeep` are built in, and direct Mojang verification stays off by default because both networks forbid bypassing their proxies. If an upstream ever changes its endpoint—or your setup allows another session server—add or override it under `[upstreams]` and list its ID in `order`:

```toml
order = ["minehut", "custom", "minekeep"]

[upstreams]
custom = "https://sessions.example.com/session/minecraft/hasJoined"
```

Keep in mind that any upstream listed in `order` can authenticate players on your proxy, so only add endpoints you trust. Upstream IDs must match `^[a-z0-9-]{1,32}$`, and URLs must be absolute `https://` endpoints with a valid host and port (`http://` is only allowed for loopback hosts)—strip query strings and fragments, and don't embed credentials in the URL.

Edit `config.toml` and run `/minelink reload` (or `/velocity reload`) to apply changes live without changing the loopback port or path token. If the updated file fails validation, the previous config stays active and the error prints to chat or the log. If the file is broken at initial startup, the error goes to the log and neither the loopback hook nor `/minelink` registers.

## Commands and debugging

Both subcommands require `minelink.admin` (or the proxy console) and support tab completion:

- `/minelink` or `/minelink status`: prints the version, whether the hook is active, the loopback port, upstream order, and current timeouts. It also flags when `online-mode` is off or an external `-Dmojang.sessionserver` property blocked startup.
- `/minelink reload`: rereads `plugins/minelink/config.toml` on the fly.

When a join fails with an upstream error, the log prints `Session verification failed (<summary>)` naming each upstream and its reason (`timeout`, `status <code>`, `invalid 200 profile body`, `deadline`, or `not reached`). Seeing `not reached` means `deadline-ms` expired before MineLink got to that network—raise `deadline-ms` or lower `stagger-ms` if an earlier upstream is consistently slow. If every player sees "online mode only," every configured upstream returned `204`.

## Building from source

Requires JDK 17 or later (CI builds and runs the JUnit 5 suite on Java 17, 21, and 25):

```sh
./gradlew build
```

Gradle outputs `build/libs/minelink-<version>.jar`, using the version in `gradle.properties`.

## License

MIT (see [LICENSE](LICENSE)).
