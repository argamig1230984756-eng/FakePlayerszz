package dev.skyblockplus.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.*;

/** Talks to the Velocity plugin through the (real) players' connections. */
public class ProxyBridge implements PluginMessageListener {
    public static final String CHANNEL = "skyblockplus:main";
    private final SkyBlockPlusPlugin plugin;
    private boolean queued;

    public ProxyBridge(SkyBlockPlusPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
    }

    private Player carrier() {
        for (Player p : Bukkit.getOnlinePlayers()) if (!FakeServerPlayer.isFake(p)) return p;
        return null;
    }

    public void syncSoon() {
        if (queued) return;
        queued = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> { queued = false; syncNow(); }, 5L);
    }

    public void syncNow() {
        Player c = carrier();
        if (c == null) return; // nobody to carry the message; re-sent when a real player joins
        long epoch = System.currentTimeMillis();
        try {
            for (FakeData d : plugin.manager().all()) {
                Player p = plugin.manager().online(d);
                if (p == null) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                DataOutputStream o = new DataOutputStream(bo);
                o.writeUTF("UPSERT");
                o.writeLong(epoch);
                o.writeUTF(d.uuid.toString());
                o.writeUTF(d.name);
                o.writeUTF(!d.listName.isEmpty() ? d.listName : d.displayName);
                o.writeUTF(d.skinValue == null ? "" : d.skinValue);
                o.writeUTF(d.skinSignature == null ? "" : d.skinSignature);
                o.writeInt(d.ping);
                o.writeInt(p.getGameMode().getValue());
                o.writeBoolean(d.listed);
                c.sendPluginMessage(plugin, CHANNEL, bo.toByteArray());
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeUTF("COMMIT");
            o.writeLong(epoch);
            c.sendPluginMessage(plugin, CHANNEL, bo.toByteArray());
        } catch (IOException e) { e.printStackTrace(); }
    }

    /** Proxy -> backend: run a /fp command as console. */
    @Override
    public void onPluginMessageReceived(String channel, Player source, byte[] msg) {
        if (!channel.equals(CHANNEL)) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(msg))) {
            String type = in.readUTF();
            if (type.equals("RUN")) {
                String secret = in.readUTF();
                String cmd = in.readUTF();
                if (!secret.equals(plugin.getConfig().getString("secret", "change-me"))) {
                    plugin.getLogger().warning("Rejected proxy command with bad secret");
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fp " + cmd));
            }
        } catch (IOException ignored) {}
    }
}
