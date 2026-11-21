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

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final AtomicReference<MineLinkConfig> configRef = new AtomicReference<>(MineLinkConfig.defaults());

    private HasJoinedRelay relay;
    private LocalSessionServer sessionServer;
    private volatile boolean hooked = false;

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
            config = MineLinkConfig.loadOrCreate(dataDirectory);
        } catch (IOException | IllegalArgumentException e) {
            logger.error("Failed to load MineLink config.toml; plugin will not start: {}", e.getMessage(), e);
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
            registerCommand();
            return;
        }

        try {
            this.relay = new HasJoinedRelay();
            this.sessionServer = new LocalSessionServer(config, relay, logger);
            this.sessionServer.start();
        } catch (IOException e) {
            logger.error("Failed to bind local loopback session server on 127.0.0.1", e);
            shutdownResources();
            return;
        }

        System.setProperty(SESSION_SERVER_PROPERTY, sessionServer.sessionServerUrl());
        this.hooked = true;
        registerCommand();

        logger.info(
            "MineLink v{} active on 127.0.0.1:{} (order={})",
            BuildConstants.VERSION,
            sessionServer.port(),
            String.join(" -> ", config.order())
        );
    }

    @Subscribe
    public void onProxyReload(ProxyReloadEvent event) {
        String error = reloadConfig();
        if (error == null) {
            logger.info("Reloaded MineLink configuration.");
        } else {
            logger.error("Failed to reload MineLink configuration: {}", error);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        shutdownResources();
    }

    public String reloadConfig() {
        try {
            MineLinkConfig reloaded = MineLinkConfig.loadOrCreate(dataDirectory);
            configRef.set(reloaded);
            if (sessionServer != null) {
                sessionServer.updateConfig(reloaded);
            }
            return null;
        } catch (IOException | IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    public MineLinkConfig currentConfig() {
        return configRef.get();
    }

    public boolean isHooked() {
        return hooked;
    }

    public int loopbackPort() {
        return sessionServer != null ? sessionServer.port() : -1;
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
        hooked = false;
        if (sessionServer != null) {
            sessionServer.close();
            sessionServer = null;
        }
        if (relay != null) {
            relay.close();
            relay = null;
        }
    }
}
