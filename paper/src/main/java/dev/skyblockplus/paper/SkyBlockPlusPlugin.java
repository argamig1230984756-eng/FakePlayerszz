package dev.skyblockplus.paper;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SkyBlockPlusPlugin extends JavaPlugin implements Listener {
    private FakeManager manager;
    private ProxyBridge bridge;

    // ---- settings cached from config.yml (so event handlers never touch the config) ----
    private record GuiRule(String title, String material, String name, int slot) {}

    private boolean suppressMessages, autoRespawn, autoAcceptTpa, guiEnabled;
    private Set<String> tpaCmds = Set.of();
    private List<String> acceptCmds = List.of("tpaccept");
    private long acceptDelay = 10, guiDelay = 3;
    private ClickType guiClick = ClickType.LEFT;
    private InventoryAction guiAction = InventoryAction.PICKUP_ALL;
    private List<GuiRule> guiRules = List.of();
    private boolean joinSyncQueued;

    public FakeManager manager() { return manager; }
    public ProxyBridge bridge() { return bridge; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        manager = new FakeManager(this);
        manager.load();
        bridge = new ProxyBridge(this);
        FakeCommand cmd = new FakeCommand(this);
        getCommand("sbp").setExecutor(cmd);
        getCommand("sbp").setTabCompleter(cmd);
        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskLater(this, manager::spawnAuto, 40L); // after worlds are loaded
        // save() is debounced and writes the file off the main thread
        long save = Math.max(60L, getConfig().getLong("autosave-seconds", 300)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, manager::save, save, save);
        long sync = Math.max(60L, getConfig().getLong("sync-interval-seconds", 300)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, bridge::syncNow, sync, sync);
    }

    @Override
    public void onDisable() { if (manager != null) manager.shutdown(); }

    /** (Re)reads config.yml into plain fields. Called on enable and on /fp reload. */
    public void reloadSettings() {
        var c = getConfig();
        suppressMessages = c.getBoolean("suppress-join-quit-messages");
        autoRespawn = c.getBoolean("auto-respawn", true);
        autoAcceptTpa = c.getBoolean("auto-accept-tpa", true);

        List<String> t = c.getStringList("tpa-commands");
        if (t.isEmpty()) t = List.of("tpa", "tpahere", "tpask", "tpaskhere", "call");
        Set<String> set = new HashSet<>();
        for (String x : t) set.add(x.toLowerCase(Locale.ROOT));
        tpaCmds = set;

        List<String> a = c.getStringList("accept-commands");
        acceptCmds = a.isEmpty() ? List.of("tpaccept") : List.copyOf(a);
        acceptDelay = c.getLong("auto-accept-delay-ticks", 10);

        guiEnabled = c.getBoolean("auto-click-gui.enabled", true);
        guiDelay = Math.max(1, c.getLong("auto-click-gui.delay-ticks", 3));
        try { guiClick = ClickType.valueOf(c.getString("auto-click-gui.click-type", "LEFT").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { guiClick = ClickType.LEFT; }
        guiAction = switch (guiClick) {
            case RIGHT -> InventoryAction.PICKUP_HALF;
            case SHIFT_LEFT, SHIFT_RIGHT -> InventoryAction.MOVE_TO_OTHER_INVENTORY;
            default -> InventoryAction.PICKUP_ALL;
        };

        List<GuiRule> rules = new ArrayList<>();
        for (Map<?, ?> r : c.getMapList("auto-click-gui.rules")) {
            int slot = -1;
            String slotStr = str(r, "slot");
            try { if (!slotStr.isEmpty()) slot = Integer.parseInt(slotStr); } catch (NumberFormatException ignored) { }
            rules.add(new GuiRule(str(r, "title").toLowerCase(Locale.ROOT), str(r, "material"),
                    str(r, "name").toLowerCase(Locale.ROOT), slot));
        }
        if (rules.isEmpty()) {
            for (String mat : List.of("LIME_STAINED_GLASS_PANE", "LIME_STAINED_GLASS", "GREEN_STAINED_GLASS_PANE", "GREEN_STAINED_GLASS"))
                rules.add(new GuiRule("", mat, "", -1));
        }
        guiRules = List.copyOf(rules);

        // performance
        FakeServerPlayer.idleTickInterval = Math.max(1, c.getInt("performance.idle-tick-interval", 4));
        FakeServerPlayer.warmupTicks = Math.max(0, c.getInt("performance.warmup-ticks", 200));
        FakeServerPlayer.viewDistance = c.getInt("performance.view-distance", 2);
        FakeServerPlayer.simulationDistance = c.getInt("performance.simulation-distance", 2);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (FakeServerPlayer.isFake(e.getPlayer())) {
            if (suppressMessages) e.joinMessage(null);
        } else if (!joinSyncQueued) { // many joins at once -> one sync
            joinSyncQueued = true;
            Bukkit.getScheduler().runTaskLater(this, () -> { joinSyncQueued = false; bridge.syncNow(); }, 20L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (suppressMessages && FakeServerPlayer.isFake(e.getPlayer())) e.quitMessage(null);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!autoRespawn) return;
        Player p = e.getPlayer();
        if (FakeServerPlayer.isFake(p))
            Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isDead()) p.spigot().respawn(); }, 5L);
    }

    @EventHandler
    public void onInvClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        // cheap type check first: this event fires for every inventory every player closes
        if (inv.getType() != InventoryType.PLAYER) return;
        if (inv.getHolder(false) instanceof Player hp && FakeServerPlayer.isFake(hp)) {
            FakeData d = manager.get(hp.getName());
            if (d != null) manager.capture(d);
        }
    }

    /** When a TPA-style command targets a fake player, make the fake accept it automatically. */
    @EventHandler(ignoreCancelled = true)
    public void onTpaCommand(PlayerCommandPreprocessEvent e) {
        if (!autoAcceptTpa) return;
        String msg = e.getMessage();
        int sp = msg.indexOf(' ');
        if (sp < 0) return; // no argument -> not a "/tpa <player>"

        String label = msg.substring(1, sp).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!tpaCmds.contains(label)) return;

        int s2 = sp + 1;
        while (s2 < msg.length() && msg.charAt(s2) == ' ') s2++;
        if (s2 >= msg.length()) return;
        int e2 = msg.indexOf(' ', s2);
        String targetName = e2 < 0 ? msg.substring(s2) : msg.substring(s2, e2);

        Player fake = Bukkit.getPlayerExact(targetName);
        if (fake == null || !FakeServerPlayer.isFake(fake)) return;
        String sender = e.getPlayer().getName();
        final List<String> accept = acceptCmds;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!fake.isOnline()) return;
            for (String c : accept) fake.performCommand(c.replace("{player}", sender));
        }, acceptDelay);
    }

    private static String str(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    /** When a menu opens for a fake player, click the first item matching a rule from config.yml. */
    @EventHandler
    public void onGuiOpen(InventoryOpenEvent e) {
        if (!guiEnabled || !(e.getPlayer() instanceof Player p) || !FakeServerPlayer.isFake(p)) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            InventoryView view = p.getOpenInventory();
            Inventory top = view.getTopInventory();
            if (top.getType() == InventoryType.CRAFTING || top.getType() == InventoryType.PLAYER) return;
            String title = PlainTextComponentSerializer.plainText().serialize(view.title()).toLowerCase(Locale.ROOT);
            for (GuiRule rule : guiRules) {
                if (!rule.title().isEmpty() && !title.contains(rule.title())) continue;
                for (int i = 0; i < top.getSize(); i++) {
                    if (rule.slot() >= 0 && i != rule.slot()) continue;
                    ItemStack it = top.getItem(i);
                    if (it == null || it.getType().isAir()) continue;
                    if (!rule.material().isEmpty() && !it.getType().name().equalsIgnoreCase(rule.material())) continue;
                    if (!rule.name().isEmpty()) {
                        String n = (it.hasItemMeta() && it.getItemMeta().hasDisplayName())
                                ? PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName()).toLowerCase(Locale.ROOT)
                                : it.getType().name().toLowerCase(Locale.ROOT);
                        if (!n.contains(rule.name())) continue;
                    }
                    Bukkit.getPluginManager().callEvent(new InventoryClickEvent(
                            view, InventoryType.SlotType.CONTAINER, i, guiClick, guiAction));
                    return;
                }
            }
        }, guiDelay);
    }
}
