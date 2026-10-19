package dev.skyblockplus.velocity;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Proxy side of SkyBlockPlus.
 *
 * Fake players live on ONE backend server and are shown there by that server itself. This plugin does
 * NOT put them into the tab list (or the name suggestions) of players on any other server. It only
 * keeps a registry for /vfp list, /vfp run and (optionally) the server-list player count.
 */
@Plugin(id = "skyblockplus", name = "SkyBlockPlus", version = "1.1.0", authors = {"you"})
public final class SkyBlockPlusVelocity {
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("skyblockplus", "main");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dir;
    private final Gson gson = new Gson();
    private final Map<UUID, FakeEntry> registry = new ConcurrentHashMap<>();
    private String secret = "change-me";
    private boolean showInServerList = true;
    private volatile String lastSavedJson = "";

    @Inject
    public SkyBlockPlusVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dir) {
        this.proxy = proxy; this.logger = logger; this.dir = dir;
    }

    /** Public API for other proxy plugins. */
    public Collection<FakeEntry> getSkyBlockPlus() { return Collections.unmodifiableCollection(registry.values()); }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        try {
            Files.createDirectories(dir);
            Path cfg = dir.resolve("config.properties");
            Properties p = new Properties();
            if (Files.exists(cfg)) try (var in = Files.newInputStream(cfg)) { p.load(in); }
            else {
                p.setProperty("secret", "change-me");
                p.setProperty("show-in-server-list", "true");
                try (var out = Files.newOutputStream(cfg)) { p.store(out, "secret must match 'secret' in the Paper plugin config.yml"); }
            }
            secret = p.getProperty("secret", "change-me");
            showInServerList = Boolean.parseBoolean(p.getProperty("show-in-server-list", "true"));
            Path reg = dir.resolve("registry.json");
            if (Files.exists(reg)) {
                List<FakeEntry> list = gson.fromJson(Files.readString(reg), new TypeToken<List<FakeEntry>>() {}.getType());
                if (list != null) list.forEach(f -> registry.put(f.uuid, f));
            }
        } catch (Exception ex) { logger.error("Config load failed", ex); }

        proxy.getChannelRegistrar().register(CHANNEL);
        var meta = proxy.getCommandManager().metaBuilder("vfp").aliases("fglist").plugin(this).build();
        proxy.getCommandManager().register(meta, new Cmd());
    }

    // ---------- backend -> proxy ----------
    @Subscribe
    public void onMessage(PluginMessageEvent e) {
        if (!e.getIdentifier().equals(CHANNEL)) return;
        e.setResult(PluginMessageEvent.ForwardResult.handled()); // never forward either way
        if (!(e.getSource() instanceof ServerConnection sc)) return; // ignore anything a CLIENT tries to send
        String server = sc.getServerInfo().getName();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(e.getData()))) {
            switch (in.readUTF()) {
                case "UPSERT" -> {
                    FakeEntry f = new FakeEntry();
                    f.server = server;
                    f.epoch = in.readLong();
                    f.uuid = UUID.fromString(in.readUTF());
                    f.name = in.readUTF(); f.display = in.readUTF();
                    f.skinValue = in.readUTF(); f.skinSignature = in.readUTF();
                    f.ping = in.readInt(); f.gamemode = in.readInt(); f.listed = in.readBoolean();
                    registry.put(f.uuid, f);
                }
                case "COMMIT" -> {
                    long epoch = in.readLong();
                    registry.values().removeIf(x -> x.server.equals(server) && x.epoch != epoch);
                    saveRegistryIfChanged();
                }
                default -> { }
            }
        } catch (Exception ex) { logger.warn("Bad message from {}: {}", server, ex.toString()); }
    }

    /** Only touches the disk when something really changed, and does it off the message thread. */
    private void saveRegistryIfChanged() {
        String json = gson.toJson(new ArrayList<>(registry.values()));
        if (json.equals(lastSavedJson)) return;
        lastSavedJson = json;
        proxy.getScheduler().buildTask(this, () -> {
            try { Files.writeString(dir.resolve("registry.json"), json); }
            catch (IOException ex) { logger.warn("Could not save registry", ex); }
        }).schedule();
    }

    // ---------- server list ----------
    @Subscribe
    public void onPing(ProxyPingEvent e) {
        if (!showInServerList || registry.isEmpty()) return;
        ServerPing ping = e.getPing();
        if (ping.getPlayers().isEmpty()) return;
        List<FakeEntry> fakes = registry.values().stream().filter(f -> f.listed).collect(Collectors.toList());
        if (fakes.isEmpty()) return;
        ServerPing.Players pl = ping.getPlayers().get();
        List<ServerPing.SamplePlayer> sample = new ArrayList<>(pl.getSample());
        for (FakeEntry f : fakes) if (sample.size() < 12) sample.add(new ServerPing.SamplePlayer(f.name, f.uuid));
        e.setPing(ping.asBuilder().onlinePlayers(pl.getOnline() + fakes.size())
                .samplePlayers(sample.toArray(new ServerPing.SamplePlayer[0])).build());
    }

    // ---------- proxy -> backend ----------
    private boolean run(String server, String command) {
        Optional<RegisteredServer> rs = proxy.getServer(server);
        if (rs.isEmpty()) return false;
        for (Player p : rs.get().getPlayersConnected()) {
            Optional<ServerConnection> sc = p.getCurrentServer();
            if (sc.isEmpty()) continue;
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                DataOutputStream o = new DataOutputStream(bo);
                o.writeUTF("RUN"); o.writeUTF(secret); o.writeUTF(command);
                return sc.get().sendPluginMessage(CHANNEL, bo.toByteArray());
            } catch (IOException ignored) {}
        }
        return false;
    }

    private final class Cmd implements SimpleCommand {
        @Override
        public boolean hasPermission(Invocation inv) { return inv.source().hasPermission("skyblockplus.admin"); }

        @Override
        public void execute(Invocation inv) {
            var src = inv.source();
            String[] a = inv.arguments();
            if (inv.alias().equalsIgnoreCase("fglist") || a.length == 0 || a[0].equalsIgnoreCase("list")) {
                int real = proxy.getPlayerCount();
                src.sendMessage(Component.text("Online: " + real + " real + " + registry.size() + " fake", NamedTextColor.AQUA));
                Map<String, List<FakeEntry>> by = registry.values().stream().collect(Collectors.groupingBy(f -> f.server));
                by.forEach((srv, list) -> src.sendMessage(Component.text(" " + srv + ": "
                        + list.stream().map(f -> f.name).collect(Collectors.joining(", ")), NamedTextColor.GRAY)));
                return;
            }
            if (a[0].equalsIgnoreCase("run") && a.length >= 3) {
                String server = a[1];
                String command = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                src.sendMessage(Component.text(run(server, command) ? "Sent to " + server
                        : "Failed: server unknown or no real player online there to carry the message.", NamedTextColor.YELLOW));
                return;
            }
            src.sendMessage(Component.text("/vfp list | /vfp run <server> <fp command...>   e.g. /vfp run lobby set Bob ping 25", NamedTextColor.GRAY));
        }

        @Override
        public List<String> suggest(Invocation inv) {
            String[] a = inv.arguments();
            if (a.length <= 1) return List.of("list", "run");
            if (a.length == 2 && a[0].equalsIgnoreCase("run"))
                return proxy.getAllServers().stream().map(s -> s.getServerInfo().getName()).collect(Collectors.toList());
            return List.of();
        }
    }
}
