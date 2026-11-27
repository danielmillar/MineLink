package dev.minelink.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import dev.minelink.velocity.BuildConstants;
import dev.minelink.velocity.MineLinkPlugin;
import dev.minelink.velocity.config.DirectBridgeConfig;
import dev.minelink.velocity.config.MineLinkConfig;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class MineLinkCommand implements SimpleCommand {

    public static final String PERMISSION = "minelink.admin";

    private static final TextColor ACCENT = TextColor.color(0x38BDF8);
    private static final TextColor MUTED = TextColor.color(0x94A3B8);
    private static final TextColor DIM = TextColor.color(0x64748B);
    private static final TextColor VALUE = TextColor.color(0xF8FAFC);
    private static final TextColor SUCCESS = TextColor.color(0x4ADE80);
    private static final TextColor WARN = TextColor.color(0xFBBF24);
    private static final TextColor ERROR = TextColor.color(0xF87171);

    private final MineLinkPlugin plugin;

    public MineLinkCommand(MineLinkPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sendStatus(source);
            return;
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            MineLinkPlugin.ReloadResult result = plugin.reloadConfig();
            if (result.ok()) {
                if (plugin.status() == MineLinkPlugin.Status.ACTIVE) {
                    source.sendMessage(prefix().append(Component.text("Config reloaded.", SUCCESS)));
                } else {
                    source.sendMessage(prefix().append(Component.text("Config reloaded (plugin is inactive).", WARN)));
                }
                for (String warning : result.warnings()) {
                    source.sendMessage(Component.text("Warning: " + warning, WARN));
                }
            } else {
                source.sendMessage(prefix().append(Component.text("Reload failed: " + result.error(), ERROR)));
            }
            return;
        }

        source.sendMessage(prefix().append(Component.text("Usage: /minelink <status|reload>", WARN)));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("status", "reload").stream()
                .filter(s -> s.startsWith(prefix))
                .toList();
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }

    private void sendStatus(CommandSource source) {
        MineLinkConfig config = plugin.currentConfig();
        MineLinkPlugin.Status status = plugin.status();

        Component header = Component.text()
            .append(Component.text("MineLink", ACCENT, TextDecoration.BOLD))
            .append(Component.text(" v" + BuildConstants.VERSION, MUTED))
            .append(Component.text(" · ", DIM))
            .append(status == MineLinkPlugin.Status.ACTIVE
                ? Component.text("Active", SUCCESS)
                : Component.text("Inactive", ERROR))
            .build();
        source.sendMessage(header);

        if (status == MineLinkPlugin.Status.ACTIVE) {
            source.sendMessage(field("Loopback", "127.0.0.1:" + plugin.loopbackPort()));
        } else if (status == MineLinkPlugin.Status.EXTERNAL_PROPERTY) {
            source.sendMessage(Component.text("External -Dmojang.sessionserver is set", ERROR));
        } else {
            source.sendMessage(Component.text("Loopback server is not running", ERROR));
        }

        if (!plugin.isOnlineMode()) {
            source.sendMessage(Component.text("Warning: online-mode is false in velocity.toml", WARN));
        }

        TextComponent.Builder orderLine = Component.text()
            .append(Component.text("Order: ", MUTED));
        List<String> order = config.order();
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) {
                orderLine.append(Component.text(" → ", DIM));
            }
            orderLine.append(Component.text(order.get(i), VALUE));
        }
        source.sendMessage(orderLine.build());

        source.sendMessage(Component.text()
            .append(Component.text("Timeout: ", MUTED))
            .append(Component.text(config.upstreamTimeoutMs() + "ms", VALUE))
            .append(Component.text(" · ", DIM))
            .append(Component.text("Stagger: ", MUTED))
            .append(Component.text(config.staggerMs() + "ms", VALUE))
            .build());

        source.sendMessage(field("Deadline", config.deadlineMs() + "ms"));

        if (plugin.isDirectBridgeActive()) {
            InetSocketAddress bound = plugin.directBridgeBoundAddress();
            InetSocketAddress target = plugin.directBridgeTargetAddress();
            int active = plugin.directBridgeActiveConnections();
            source.sendMessage(Component.text()
                .append(Component.text("Direct bridge: ", MUTED))
                .append(Component.text(formatAddress(bound) + " → " + formatAddress(target), VALUE))
                .append(Component.text(" (" + active + " active)", MUTED))
                .build());
        } else if (plugin.directBridgeError() != null) {
            source.sendMessage(Component.text()
                .append(Component.text("Direct bridge: ", MUTED))
                .append(Component.text("Error (" + plugin.directBridgeError() + ")", ERROR))
                .build());
        } else {
            source.sendMessage(Component.text()
                .append(Component.text("Direct bridge: ", MUTED))
                .append(Component.text("Disabled", DIM))
                .build());
        }
    }

    private static String formatAddress(InetSocketAddress address) {
        if (address == null) {
            return "unknown";
        }
        InetAddress addr = address.getAddress();
        String host = addr != null ? addr.getHostAddress() : address.getHostString();
        return DirectBridgeConfig.formatHostPort(host, address.getPort());
    }

    private static Component prefix() {
        return Component.text()
            .append(Component.text("MineLink", ACCENT, TextDecoration.BOLD))
            .append(Component.text(" · ", DIM))
            .build();
    }

    private static Component field(String label, String value) {
        return Component.text()
            .append(Component.text(label + ": ", MUTED))
            .append(Component.text(value, VALUE))
            .build();
    }
}
