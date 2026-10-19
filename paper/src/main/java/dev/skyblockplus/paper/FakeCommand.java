package dev.skyblockplus.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class FakeCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("add", "remove", "removeall", "list", "info", "tp", "set", "rename",
            "skin", "inv", "patrol", "say", "cmd", "action", "respawn", "save", "reload");
    private static final List<String> KEYS = List.of("displayname", "listname", "gamemode", "ping", "listed", "op", "flying",
            "invulnerable", "health", "food", "movement", "speed", "radius", "follow", "sprint", "autospawn", "slot");
    private final SkyBlockPlusPlugin plugin;
    private final FakeManager m;

    public FakeCommand(SkyBlockPlusPlugin plugin) { this.plugin = plugin; this.m = plugin.manager(); }

    private void msg(CommandSender s, String text) { s.sendMessage(FakeManager.color("&8[&bFP&8] &7" + text)); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) { msg(s, "/sbp <" + String.join("|", SUBS) + ">"); return true; }
        String sub = a[0].toLowerCase();
        try {
            switch (sub) {
                case "add" -> add(s, a);
                case "removeall" -> { for (FakeData d : new ArrayList<>(m.all())) m.delete(d); msg(s, "Removed all."); }
                case "list" -> list(s);
                case "save" -> { m.saveNow(); msg(s, "Saved."); }
                case "reload" -> { m.saveNow(); plugin.reloadConfig(); plugin.reloadSettings(); m.load(); m.spawnAuto(); msg(s, "Reloaded."); }
                default -> {
                    if (a.length < 2) { msg(s, "Usage: /sbp " + sub + " <name> ..."); return true; }
                    FakeData d = m.get(a[1]);
                    if (d == null) { msg(s, "No fake player named " + a[1]); return true; }
                    target(s, sub, d, a);
                }
            }
        } catch (Exception e) { msg(s, "&cError: " + e.getMessage()); e.printStackTrace(); }
        return true;
    }

    private void add(CommandSender s, String[] a) {
        if (a.length < 2) { msg(s, "/sbp add <name> [world x y z]"); return; }
        String name = a[1];
        if (!name.matches("[A-Za-z0-9_]{1,16}")) { msg(s, "Invalid name (1-16 chars: letters, digits, _)."); return; }
        if (m.get(name) != null) { msg(s, "Already exists."); return; }
        Location loc;
        if (a.length >= 6) {
            World w = Bukkit.getWorld(a[2]);
            if (w == null) { msg(s, "Unknown world."); return; }
            loc = new Location(w, Double.parseDouble(a[3]), Double.parseDouble(a[4]), Double.parseDouble(a[5]));
        } else if (s instanceof Player p) loc = p.getLocation();
        else { msg(s, "From console use: /sbp add <name> <world> <x> <y> <z>"); return; }
        FakeData d = m.create(name, loc);
        String err = m.spawn(d);
        msg(s, err == null ? "Spawned &f" + name : "&c" + err);
        m.save();
    }

    private void list(CommandSender s) {
        msg(s, "Fake players (" + m.all().size() + "):");
        for (FakeData d : m.all())
            s.sendMessage(FakeManager.color(" &8- &f" + d.name + (m.online(d) != null ? " &a[online]" : " &c[offline]")
                    + " &7" + d.movement + " " + d.world + " " + (int) d.x + "," + (int) d.y + "," + (int) d.z));
    }

    private void target(CommandSender s, String sub, FakeData d, String[] a) {
        Player p = m.online(d);
        switch (sub) {
            case "remove" -> { m.delete(d); msg(s, "Removed " + d.name); }
            case "respawn" -> { m.respawn(d); msg(s, "Respawning " + d.name); }
            case "info" -> {
                msg(s, "&f" + d.name + " &7uuid=" + d.uuid);
                msg(s, "gm=" + d.gamemode + " ping=" + d.ping + " listed=" + d.listed + " op=" + d.op + " flying=" + d.flying);
                msg(s, "move=" + d.movement + " speed=" + d.speed + " radius=" + d.radius + " follow=" + d.followTarget
                        + " sprint=" + d.sprint + " patrolpts=" + d.patrol.size());
                msg(s, "display='" + d.displayName + "' list='" + d.listName + "' skin=" + (d.skinValue.isEmpty() ? "default" : "custom"));
            }
            case "tp" -> {
                Location loc;
                if (a.length >= 5) {
                    World w = a.length >= 6 ? Bukkit.getWorld(a[5]) : (p != null ? p.getWorld() : Bukkit.getWorld(d.world));
                    if (w == null) { msg(s, "Unknown world."); return; }
                    loc = new Location(w, Double.parseDouble(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4]));
                } else if (s instanceof Player sp) loc = sp.getLocation();
                else { msg(s, "/sbp tp <name> <x> <y> <z> [world]"); return; }
                d.world = loc.getWorld().getName(); d.x = loc.getX(); d.y = loc.getY(); d.z = loc.getZ();
                if (p != null) p.teleport(loc);
                msg(s, "Teleported " + d.name);
            }
            case "rename" -> {
                if (a.length < 3 || !a[2].matches("[A-Za-z0-9_]{1,16}")) { msg(s, "/sbp rename <name> <newname>"); return; }
                if (m.get(a[2]) != null) { msg(s, "Name taken."); return; }
                m.rename(d, a[2]); msg(s, "Renamed to " + a[2]);
            }
            case "skin" -> {
                if (a.length < 3) { msg(s, "/sbp skin <name> <mojangName|reset>"); return; }
                if (a[2].equalsIgnoreCase("reset")) { d.skinValue = ""; d.skinSignature = ""; m.respawn(d); return; }
                msg(s, "Fetching skin...");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    String[] r = SkinFetcher.fetch(a[2]);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (r == null) { msg(s, "&cCould not fetch that skin."); return; }
                        d.skinValue = r[0]; d.skinSignature = r[1]; m.respawn(d); msg(s, "Skin applied.");
                    });
                });
            }
            case "set" -> set(s, d, a);
            case "inv" -> inv(s, d, p, a);
            case "patrol" -> {
                String o = a.length > 2 ? a[2].toLowerCase() : "list";
                switch (o) {
                    case "add" -> {
                        if (!(s instanceof Player sp)) { msg(s, "Players only."); return; }
                        d.patrol.add(new double[]{sp.getLocation().getX(), sp.getLocation().getZ()});
                        msg(s, "Point #" + d.patrol.size() + " added.");
                    }
                    case "clear" -> { d.patrol.clear(); msg(s, "Cleared."); }
                    default -> { for (int i = 0; i < d.patrol.size(); i++) msg(s, "#" + (i + 1) + " " + (int) d.patrol.get(i)[0] + ", " + (int) d.patrol.get(i)[1]); }
                }
            }
            case "say" -> { if (need(s, p, d)) p.chat(String.join(" ", Arrays.copyOfRange(a, 2, a.length))); }
            case "cmd" -> { if (need(s, p, d)) p.performCommand(String.join(" ", Arrays.copyOfRange(a, 2, a.length)).replaceFirst("^/", "")); }
            case "action" -> {
                if (!need(s, p, d) || a.length < 3) return;
                switch (a[2].toLowerCase()) {
                    case "jump" -> p.setVelocity(p.getVelocity().setY(0.42));
                    case "sneak" -> p.setSneaking(true);
                    case "unsneak" -> p.setSneaking(false);
                    case "swing" -> p.swingMainHand();
                    case "drop" -> p.dropItem(false);
                    default -> msg(s, "jump|sneak|unsneak|swing|drop");
                }
            }
            default -> msg(s, "Unknown subcommand.");
        }
        m.save();
    }

    private boolean need(CommandSender s, Player p, FakeData d) {
        if (p == null) { msg(s, d.name + " is not online."); return false; }
        return true;
    }

    private void set(CommandSender s, FakeData d, String[] a) {
        if (a.length < 4) { msg(s, "/sbp set <name> <" + String.join("|", KEYS) + "> <value>"); return; }
        String key = a[2].toLowerCase();
        String val = String.join(" ", Arrays.copyOfRange(a, 3, a.length));
        boolean reset = false;
        switch (key) {
            case "displayname" -> d.displayName = val.equals("-") ? "" : val;
            case "listname" -> d.listName = val.equals("-") ? "" : val;
            case "gamemode" -> d.gamemode = val.toUpperCase();
            case "ping" -> d.ping = Integer.parseInt(val);
            case "listed" -> d.listed = Boolean.parseBoolean(val);
            case "op" -> d.op = Boolean.parseBoolean(val);
            case "flying" -> d.flying = Boolean.parseBoolean(val);
            case "invulnerable" -> d.invulnerable = Boolean.parseBoolean(val);
            case "health" -> d.health = Double.parseDouble(val);
            case "food" -> d.food = Integer.parseInt(val);
            case "movement" -> {
                val = val.toUpperCase();
                if (!List.of("STAND", "WANDER", "FOLLOW", "PATROL", "LOOK").contains(val)) { msg(s, "STAND|WANDER|FOLLOW|PATROL|LOOK"); return; }
                d.movement = val;
                Player p = m.online(d);
                if (p != null) { d.x = p.getLocation().getX(); d.z = p.getLocation().getZ(); } // home point for WANDER
            }
            case "speed" -> d.speed = Double.parseDouble(val);
            case "radius" -> d.radius = Double.parseDouble(val);
            case "follow" -> { d.followTarget = val; d.movement = "FOLLOW"; }
            case "sprint" -> d.sprint = Boolean.parseBoolean(val);
            case "autospawn" -> d.autoSpawn = Boolean.parseBoolean(val);
            case "slot" -> d.selectedSlot = Integer.parseInt(val);
            default -> { msg(s, "Unknown key. Keys: " + String.join(", ", KEYS)); return; }
        }
        m.apply(d);
        msg(s, d.name + "." + key + " = " + val);
    }

    private void inv(CommandSender s, FakeData d, Player p, String[] a) {
        String o = a.length > 2 ? a[2].toLowerCase() : "open";
        if (!need(s, p, d)) return;
        switch (o) {
            case "open" -> {
                if (!(s instanceof Player sp)) { msg(s, "Players only."); return; }
                sp.openInventory(p.getInventory());
            }
            case "clear" -> { p.getInventory().clear(); m.capture(d); msg(s, "Cleared."); }
            case "copy" -> {
                if (!(s instanceof Player sp)) { msg(s, "Players only."); return; }
                p.getInventory().setContents(sp.getInventory().getContents()); m.capture(d); msg(s, "Copied your inventory.");
            }
            case "give" -> {
                if (!(s instanceof Player sp) || sp.getInventory().getItemInMainHand().getType().isAir()) { msg(s, "Hold an item."); return; }
                p.getInventory().addItem(sp.getInventory().getItemInMainHand().clone()); m.capture(d); msg(s, "Given.");
            }
            default -> msg(s, "/sbp inv <name> open|clear|copy|give");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return filter(SUBS, a[0]);
        if (a.length == 2 && !List.of("add", "list", "save", "reload", "removeall").contains(a[0].toLowerCase()))
            return filter(m.all().stream().map(x -> x.name).collect(Collectors.toList()), a[1]);
        if (a.length == 3 && a[0].equalsIgnoreCase("set")) return filter(KEYS, a[2]);
        if (a.length == 3 && a[0].equalsIgnoreCase("inv")) return filter(List.of("open", "clear", "copy", "give"), a[2]);
        if (a.length == 3 && a[0].equalsIgnoreCase("patrol")) return filter(List.of("add", "clear", "list"), a[2]);
        if (a.length == 3 && a[0].equalsIgnoreCase("action")) return filter(List.of("jump", "sneak", "unsneak", "swing", "drop"), a[2]);
        if (a.length == 4 && a[0].equalsIgnoreCase("set")) {
            return switch (a[2].toLowerCase()) {
                case "movement" -> filter(List.of("STAND", "WANDER", "FOLLOW", "PATROL", "LOOK"), a[3]);
                case "gamemode" -> filter(List.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR"), a[3]);
                case "listed", "op", "flying", "invulnerable", "sprint", "autospawn" -> filter(List.of("true", "false"), a[3]);
                default -> List.of();
            };
        }
        return List.of();
    }

    private List<String> filter(List<String> in, String start) {
        return in.stream().filter(x -> x.toLowerCase().startsWith(start.toLowerCase())).collect(Collectors.toList());
    }
}
