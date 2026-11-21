package dev.minelink.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import dev.minelink.velocity.command.MineLinkCommand;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class MineLinkPluginAndCommandTest {

    private String originalSessionServerProperty;

    @BeforeEach
    void saveProperty() {
        originalSessionServerProperty = System.getProperty(MineLinkPlugin.SESSION_SERVER_PROPERTY);
        System.clearProperty(MineLinkPlugin.SESSION_SERVER_PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (originalSessionServerProperty == null) {
            System.clearProperty(MineLinkPlugin.SESSION_SERVER_PROPERTY);
        } else {
            System.setProperty(MineLinkPlugin.SESSION_SERVER_PROPERTY, originalSessionServerProperty);
        }
    }

    @Test
    void initializesHooksSessionPropertyReloadsAndHandlesBadReload(@TempDir Path dataDir) throws Exception {
        AtomicReference<Command> registered = new AtomicReference<>();
        ProxyServer proxy = stubProxy(true, registered);
        MineLinkPlugin plugin = new MineLinkPlugin(
            proxy,
            LoggerFactory.getLogger(MineLinkPluginAndCommandTest.class),
            dataDir
        );

        try {
            plugin.onProxyInitialize(new ProxyInitializeEvent());
            assertEquals(MineLinkPlugin.Status.ACTIVE, plugin.status());
            assertTrue(plugin.loopbackPort() > 0);
            assertNotNull(registered.get());

            String prop = System.getProperty(MineLinkPlugin.SESSION_SERVER_PROPERTY);
            assertNotNull(prop);
            assertTrue(prop.startsWith("http://127.0.0.1:" + plugin.loopbackPort() + "/"));

            SimpleCommand cmd = (SimpleCommand) registered.get();
            assertEquals(List.of("status", "reload"), cmd.suggest(invocation(stubSource(true, new ArrayList<>()))));
            assertEquals(List.of("reload"), cmd.suggest(invocation(stubSource(true, new ArrayList<>()), "re")));
            assertEquals(List.of(), cmd.suggest(invocation(stubSource(true, new ArrayList<>()), "status", "extra")));
            assertTrue(cmd.hasPermission(invocation(stubSource(true, new ArrayList<>()))));
            assertFalse(cmd.hasPermission(invocation(stubSource(false, new ArrayList<>()))));

            List<String> statusOut = new ArrayList<>();
            cmd.execute(invocation(stubSource(true, statusOut)));
            assertTrue(statusOut.stream().anyMatch(s -> s.contains("Active")));
            assertTrue(statusOut.stream().anyMatch(s -> s.contains("127.0.0.1:" + plugin.loopbackPort())));

            Files.writeString(dataDir.resolve("config.toml"), "order = [");
            List<String> badReloadOut = new ArrayList<>();
            cmd.execute(invocation(stubSource(true, badReloadOut), "reload"));
            assertTrue(badReloadOut.stream().anyMatch(s -> s.contains("Reload failed:")));
            assertEquals(List.of("minehut", "minekeep"), plugin.currentConfig().order());

            Files.writeString(dataDir.resolve("config.toml"), "order = [\"minekeep\"]\nstager-ms = 0\n");
            List<String> okReloadOut = new ArrayList<>();
            cmd.execute(invocation(stubSource(true, okReloadOut), "reload"));
            assertTrue(okReloadOut.stream().anyMatch(s -> s.contains("Config reloaded.")));
            assertTrue(okReloadOut.stream().anyMatch(s -> s.contains("stager-ms")));
            assertEquals(List.of("minekeep"), plugin.currentConfig().order());

            plugin.onProxyReload(new ProxyReloadEvent());

            List<String> usageOut = new ArrayList<>();
            cmd.execute(invocation(stubSource(true, usageOut), "unknown"));
            assertTrue(usageOut.stream().anyMatch(s -> s.contains("Usage: /minelink <status|reload>")));
        } finally {
            plugin.onProxyShutdown(new ProxyShutdownEvent());
        }
        assertEquals(MineLinkPlugin.Status.NOT_STARTED, plugin.status());
    }

    @Test
    void respectsExistingSystemPropertyAndReportsInactiveInStatus(@TempDir Path dataDir) {
        System.setProperty(MineLinkPlugin.SESSION_SERVER_PROPERTY, "https://custom.example/hasJoined");
        AtomicReference<Command> registered = new AtomicReference<>();
        MineLinkPlugin plugin = new MineLinkPlugin(
            stubProxy(false, registered),
            LoggerFactory.getLogger(MineLinkPluginAndCommandTest.class),
            dataDir
        );

        try {
            plugin.onProxyInitialize(new ProxyInitializeEvent());
            assertEquals(MineLinkPlugin.Status.EXTERNAL_PROPERTY, plugin.status());
            assertEquals("https://custom.example/hasJoined", System.getProperty(MineLinkPlugin.SESSION_SERVER_PROPERTY));
            assertNotNull(registered.get());

            List<String> out = new ArrayList<>();
            SimpleCommand cmd = (SimpleCommand) registered.get();
            cmd.execute(invocation(stubSource(true, out), "status"));
            assertTrue(out.stream().anyMatch(s -> s.contains("Inactive")));
            assertTrue(out.stream().anyMatch(s -> s.contains("External -Dmojang.sessionserver is set")));
            assertTrue(out.stream().anyMatch(s -> s.contains("online-mode is false")));

            List<String> reloadOut = new ArrayList<>();
            cmd.execute(invocation(stubSource(true, reloadOut), "reload"));
            assertTrue(reloadOut.stream().anyMatch(s -> s.contains("inactive")));
        } finally {
            plugin.onProxyShutdown(new ProxyShutdownEvent());
        }
    }

    @Test
    void handlesBrokenConfigAtStartupWithoutThrowing(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("config.toml"), "order = \"minehut\"");
        AtomicReference<Command> registered = new AtomicReference<>();
        MineLinkPlugin plugin = new MineLinkPlugin(
            stubProxy(true, registered),
            LoggerFactory.getLogger(MineLinkPluginAndCommandTest.class),
            dataDir
        );

        plugin.onProxyInitialize(new ProxyInitializeEvent());
        assertEquals(MineLinkPlugin.Status.NOT_STARTED, plugin.status());
        assertNull(registered.get());
        assertNull(System.getProperty(MineLinkPlugin.SESSION_SERVER_PROPERTY));
    }

    private static SimpleCommand.Invocation invocation(CommandSource source, String... args) {
        return new SimpleCommand.Invocation() {
            @Override
            public String alias() {
                return "minelink";
            }

            @Override
            public CommandSource source() {
                return source;
            }

            @Override
            public String[] arguments() {
                return args;
            }
        };
    }

    private static CommandSource stubSource(boolean permitted, List<String> messages) {
        return (CommandSource) Proxy.newProxyInstance(
            CommandSource.class.getClassLoader(),
            new Class<?>[] {CommandSource.class},
            (proxy, method, args) -> {
                if ("hasPermission".equals(method.getName())) {
                    return permitted && MineLinkCommand.PERMISSION.equals(args[0]);
                }
                if ("sendMessage".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof Component c) {
                    messages.add(PlainTextComponentSerializer.plainText().serialize(c));
                    return null;
                }
                return defaultValue(method.getReturnType());
            }
        );
    }

    private static ProxyServer stubProxy(boolean onlineMode, AtomicReference<Command> registeredCommand) {
        ProxyConfig config = (ProxyConfig) Proxy.newProxyInstance(
            ProxyConfig.class.getClassLoader(),
            new Class<?>[] {ProxyConfig.class},
            (proxy, method, args) -> "isOnlineMode".equals(method.getName()) ? onlineMode : defaultValue(method.getReturnType())
        );

        CommandMeta meta = (CommandMeta) Proxy.newProxyInstance(
            CommandMeta.class.getClassLoader(),
            new Class<?>[] {CommandMeta.class},
            (proxy, method, args) -> defaultValue(method.getReturnType())
        );

        CommandMeta.Builder metaBuilder = (CommandMeta.Builder) Proxy.newProxyInstance(
            CommandMeta.Builder.class.getClassLoader(),
            new Class<?>[] {CommandMeta.Builder.class},
            (proxy, method, args) -> "build".equals(method.getName()) ? meta : proxy
        );

        CommandManager commandManager = (CommandManager) Proxy.newProxyInstance(
            CommandManager.class.getClassLoader(),
            new Class<?>[] {CommandManager.class},
            (proxy, method, args) -> {
                if ("metaBuilder".equals(method.getName())) {
                    return metaBuilder;
                }
                if ("register".equals(method.getName()) && args != null && args.length == 2 && args[1] instanceof Command c) {
                    registeredCommand.set(c);
                    return null;
                }
                return defaultValue(method.getReturnType());
            }
        );

        return (ProxyServer) Proxy.newProxyInstance(
            ProxyServer.class.getClassLoader(),
            new Class<?>[] {ProxyServer.class},
            (proxy, method, args) -> {
                if ("getConfiguration".equals(method.getName())) {
                    return config;
                }
                if ("getCommandManager".equals(method.getName())) {
                    return commandManager;
                }
                return defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
