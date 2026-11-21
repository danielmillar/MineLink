package dev.minelink.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.minelink.velocity.command.MineLinkCommand;
import dev.minelink.velocity.config.MineLinkConfig;
import dev.minelink.velocity.relay.HasJoinedRelay;
import dev.minelink.velocity.relay.LocalSessionServer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

@Plugin(
    id = "minelink",
    name = "MineLink",
    version = BuildConstants.VERSION,
    description = "Local multi-upstream session verifier for Minehut and MineKeep on Velocity",
    authors = {"Daniel Millar"}
)
public final class MineLinkPlugin {

    public static final String SESSION_SERVER_PROPERTY = "mojang.sessionserver";

    public enum Status {
        ACTIVE,
        EXTERNAL_PROPERTY,
        NOT_STARTED
    }

    public record ReloadResult(String error, List<String> warnings) {
        public boolean ok() {
            return error == null;
        }
    }

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final AtomicReference<MineLinkConfig> configRef = new AtomicReference<>(MineLinkConfig.defaults());

    private volatile HasJoinedRelay relay;
    private volatile LocalSessionServer sessionServer;
    private volatile Status status = Status.NOT_STARTED;

    @Inject
    public MineLinkPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public void onProxyInitialize(ProxyInitializeEvent event) {
        MineLinkConfig config;
        try {
            config = MineLinkConfig.loadOrCreate(dataDirectory, logger::warn);
        } catch (IOException | IllegalArgumentException e) {
            logger.error("Failed to load MineLink config.toml; plugin will not start: {}", errorMessage(e), e);
            return;
        }
        configRef.set(config);

        if (!proxy.getConfiguration().isOnlineMode()) {
            logger.warn("Velocity online-mode is false; session verification is bypassed until online-mode = true.");
        }

        String existingProperty = System.getProperty(SESSION_SERVER_PROPERTY);
        if (existingProperty != null && !existingProperty.isBlank()) {
            logger.warn(
                "System property -D{}=... is already set ({}). Leaving it untouched; remove -D{} from your startup flags to use MineLink.",
                SESSION_SERVER_PROPERTY,
                existingProperty,
                SESSION_SERVER_PROPERTY
            );
            this.status = Status.EXTERNAL_PROPERTY;
            registerCommand();
            return;
        }

        try {
            HasJoinedRelay newRelay = new HasJoinedRelay();
            this.relay = newRelay;
            LocalSessionServer newServer = new LocalSessionServer(configRef::get, newRelay, logger);
            this.sessionServer = newServer;
            newServer.start();
        } catch (IOException e) {
            logger.error("Failed to bind local loopback session server on 127.0.0.1", e);
            shutdownResources();
            return;
        }

        LocalSessionServer activeServer = this.sessionServer;
        System.setProperty(SESSION_SERVER_PROPERTY, activeServer.sessionServerUrl());
        this.status = Status.ACTIVE;
        registerCommand();

        logger.info(
            "MineLink v{} active on 127.0.0.1:{} (order={})",
            BuildConstants.VERSION,
            activeServer.port(),
            String.join(" -> ", config.order())
        );
    }

    @Subscribe
    public void onProxyReload(ProxyReloadEvent event) {
        ReloadResult result = reloadConfig();
        if (result.ok()) {
            if (status == Status.ACTIVE) {
                logger.info("Reloaded MineLink configuration.");
            } else {
                logger.info("Reloaded MineLink configuration (plugin is currently inactive).");
            }
        } else {
            logger.error("Failed to reload MineLink configuration: {}", result.error());
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        shutdownResources();
    }

    public ReloadResult reloadConfig() {
        List<String> warnings = new ArrayList<>();
        try {
            MineLinkConfig reloaded = MineLinkConfig.loadOrCreate(dataDirectory, warning -> {
                warnings.add(warning);
                logger.warn(warning);
            });
            configRef.set(reloaded);
            return new ReloadResult(null, List.copyOf(warnings));
        } catch (IOException | IllegalArgumentException e) {
            return new ReloadResult(errorMessage(e), List.copyOf(warnings));
        }
    }

    public MineLinkConfig currentConfig() {
        return configRef.get();
    }

    public Status status() {
        return status;
    }

    public int loopbackPort() {
        LocalSessionServer server = this.sessionServer;
        return server != null ? server.port() : -1;
    }

    public boolean isOnlineMode() {
        return proxy.getConfiguration().isOnlineMode();
    }

    private void registerCommand() {
        CommandManager commands = proxy.getCommandManager();
        CommandMeta meta = commands.metaBuilder("minelink")
            .plugin(this)
            .build();
        commands.register(meta, new MineLinkCommand(this));
    }

    private void shutdownResources() {
        status = Status.NOT_STARTED;
        LocalSessionServer server = this.sessionServer;
        this.sessionServer = null;
        if (server != null) {
            server.close();
        }
        HasJoinedRelay currentRelay = this.relay;
        this.relay = null;
        if (currentRelay != null) {
            currentRelay.close();
        }
    }

    private static String errorMessage(Throwable e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }
}
