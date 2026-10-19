package dev.skyblockplus.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class FakeManager {
    private final SkyBlockPlusPlugin plugin;
    private final Map<String, FakeData> map = new LinkedHashMap<>();
    private final File file;

    public FakeManager(SkyBlockPlusPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "skyblockplus.yml");
    }

    public static Component color(String s) { return LegacyComponentSerializer.legacyAmpersand().deserialize(s); }
    public Collection<FakeData> all() { return map.values(); }
    public FakeData get(String name) { return map.get(name.toLowerCase()); }

    public Player online(FakeData d) {
        Player p = Bukkit.getPlayer(d.uuid);
        return (p != null && FakeServerPlayer.isFake(p)) ? p : null;
    }

    // ---------- storage ----------
    public void load() {
        map.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("players");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try { FakeData d = FakeData.read(s.getConfigurationSection(k)); map.put(d.name.toLowerCase(), d); }
            catch (Exception e) { plugin.getLogger().warning("Bad entry " + k + ": " + e.getMessage()); }
        }
    }

    private final Object ioLock = new Object();
    private boolean dirty, saveQueued;

    /** Builds the whole file on the main thread (cheap) so the slow part can run off-thread. */
    private YamlConfiguration snapshot() {
        YamlConfiguration y = new YamlConfiguration();
        for (FakeData d : map.values()) {
            capture(d);
            d.write(y.createSection("players." + d.name));
        }
        return y;
    }

    private void writeFile(YamlConfiguration y) {
        synchronized (ioLock) {
            try { plugin.getDataFolder().mkdirs(); y.save(file); }
            catch (IOException e) { plugin.getLogger().warning("Could not save skyblockplus.yml: " + e.getMessage()); }
        }
    }

    /** Debounced + async save: many calls within 2 seconds become one disk write off the main thread. */
    public void save() {
        dirty = true;
        if (saveQueued) return;
        saveQueued = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            saveQueued = false;
            if (!dirty) return;
            dirty = false;
            YamlConfiguration y = snapshot();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeFile(y));
        }, 40L);
    }

    /** Immediate, blocking save. Use on shutdown / before a reload. */
    public void saveNow() {
        dirty = false;
        writeFile(snapshot());
    }

    /** Pull live state (position, inventory, health) from the online player into the data object. */
    public void capture(FakeData d) {
        Player p = online(d);
        if (p == null) return;
        Location l = p.getLocation();
        d.world = l.getWorld().getName(); d.x = l.getX(); d.y = l.getY(); d.z = l.getZ(); d.yaw = l.getYaw(); d.pitch = l.getPitch();
        d.health = p.getHealth(); d.food = p.getFoodLevel();
        d.selectedSlot = p.getInventory().getHeldItemSlot();
        d.inventory.clear();
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i <= 40; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir())
                d.inventory.put(i, Base64.getEncoder().encodeToString(it.serializeAsBytes()));
        }
    }

    // ---------- lifecycle ----------
    public FakeData create(String name, Location loc) {
        FakeData d = new FakeData();
        d.name = name; d.uuid = FakeData.offlineUuid(name);
        d.world = loc.getWorld().getName(); d.x = loc.getX(); d.y = loc.getY(); d.z = loc.getZ();
        d.yaw = loc.getYaw(); d.pitch = loc.getPitch();
        var c = plugin.getConfig();
        d.gamemode = c.getString("defaults.gamemode", "SURVIVAL");
        d.ping = c.getInt("defaults.ping", 0);
        d.listed = c.getBoolean("defaults.listed", true);
        d.autoSpawn = c.getBoolean("defaults.autospawn", true);
        map.put(name.toLowerCase(), d);
        return d;
    }

    public String spawn(FakeData d) {
        if (online(d) != null) return "already online";
        Player clash = Bukkit.getPlayerExact(d.name);
        if (clash != null) return "a real player with that name is online";
        World w = Bukkit.getWorld(d.world);
        if (w == null) w = Bukkit.getWorlds().get(0);
        Location loc = new Location(w, d.x, d.y, d.z, d.yaw, d.pitch);
        try {
            Player p = FakeServerPlayer.spawn(d, loc);
            applyInventory(d, p);
            apply(d);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player q = online(d);
                if (q != null) { q.teleport(loc); apply(d); }
            }, 3L);
            plugin.bridge().syncSoon();
            return null;
        } catch (Throwable t) {
            t.printStackTrace();
            return "spawn failed: " + t;
        }
    }

    public void despawn(FakeData d) {
        Player p = online(d);
        if (p == null) return;
        capture(d);
        FakeServerPlayer.remove(p);
        plugin.bridge().syncSoon();
    }

    public void delete(FakeData d) {
        despawn(d);
        map.remove(d.name.toLowerCase());
        save();
    }

    public void respawn(FakeData d) {
        despawn(d);
        Bukkit.getScheduler().runTaskLater(plugin, () -> spawn(d), 3L);
    }

    public void rename(FakeData d, String newName) {
        despawn(d);
        map.remove(d.name.toLowerCase());
        d.name = newName;
        map.put(newName.toLowerCase(), d);
        Bukkit.getScheduler().runTaskLater(plugin, () -> spawn(d), 3L);
    }

    public void spawnAuto() { for (FakeData d : map.values()) if (d.autoSpawn) spawn(d); }

    public void shutdown() {
        saveNow();
        for (FakeData d : new ArrayList<>(map.values())) {
            Player p = online(d);
            if (p != null) FakeServerPlayer.remove(p);
        }
    }

    // ---------- live apply ----------
    public void applyInventory(FakeData d, Player p) {
        PlayerInventory inv = p.getInventory();
        if (d.inventory.isEmpty()) { inv.setHeldItemSlot(d.selectedSlot); return; }
        inv.clear();
        for (var e : d.inventory.entrySet()) {
            try { inv.setItem(e.getKey(), ItemStack.deserializeBytes(Base64.getDecoder().decode(e.getValue()))); }
            catch (Exception ex) { plugin.getLogger().warning("Bad item slot " + e.getKey() + " on " + d.name); }
        }
        inv.setHeldItemSlot(d.selectedSlot);
    }

    public void apply(FakeData d) {
        Player p = online(d);
        if (p == null) return;
        try { p.setGameMode(GameMode.valueOf(d.gamemode.toUpperCase())); } catch (Exception ignored) {}
        p.displayName(d.displayName.isEmpty() ? null : color(d.displayName));
        p.playerListName(d.listName.isEmpty() ? null : color(d.listName));
        for (Player v : Bukkit.getOnlinePlayers()) {
            if (v.equals(p) || FakeServerPlayer.isFake(v)) continue;
            if (d.listed) v.listPlayer(p); else v.unlistPlayer(p);
        }
        FakeServerPlayer.setPing(p, d.ping);
        var attr = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double max = attr == null ? 20 : attr.getValue();
        p.setHealth(Math.max(0.5, Math.min(d.health, max)));
        p.setFoodLevel(d.food);
        p.setOp(d.op);
        p.setInvulnerable(d.invulnerable);
        p.setAllowFlight(d.flying || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR);
        if (d.flying) p.setFlying(true);
        p.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, d.selectedSlot)));
        plugin.bridge().syncSoon();
    }
}
