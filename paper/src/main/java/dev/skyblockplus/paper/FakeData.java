package dev.skyblockplus.paper;

import org.bukkit.configuration.ConfigurationSection;
import java.util.*;

/** Everything configurable about one fake player. Saved in skyblockplus.yml */
public class FakeData {
    public String name;
    public UUID uuid;
    public String world = "world";
    public double x, y, z;
    public float yaw, pitch;
    public String gamemode = "SURVIVAL";
    public String displayName = "";   // supports & colour codes, "" = default
    public String listName = "";      // tablist name, "" = default
    public int ping = 0;
    public boolean listed = true, op = false, flying = false, invulnerable = false, sprint = false, autoSpawn = true;
    public double health = 20;
    public int food = 20;
    public String skinValue = "", skinSignature = "";
    public String movement = "STAND"; // STAND, WANDER, FOLLOW, PATROL, LOOK
    public double speed = 1.0, radius = 8;
    public String followTarget = "";
    public List<double[]> patrol = new ArrayList<>();
    public Map<Integer, String> inventory = new HashMap<>(); // slot -> base64 item bytes (0-35 main, 36-39 armor, 40 offhand)
    public int selectedSlot = 0;

    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
    }

    public void write(ConfigurationSection s) {
        s.set("name", name); s.set("uuid", uuid.toString());
        s.set("world", world); s.set("x", x); s.set("y", y); s.set("z", z); s.set("yaw", yaw); s.set("pitch", pitch);
        s.set("gamemode", gamemode); s.set("display-name", displayName); s.set("list-name", listName);
        s.set("ping", ping); s.set("listed", listed); s.set("op", op); s.set("flying", flying);
        s.set("invulnerable", invulnerable); s.set("sprint", sprint); s.set("autospawn", autoSpawn);
        s.set("health", health); s.set("food", food);
        s.set("skin.value", skinValue); s.set("skin.signature", skinSignature);
        s.set("movement.mode", movement); s.set("movement.speed", speed); s.set("movement.radius", radius);
        s.set("movement.follow", followTarget);
        List<String> pts = new ArrayList<>();
        for (double[] p : patrol) pts.add(p[0] + "," + p[1]);
        s.set("movement.patrol", pts);
        s.set("selected-slot", selectedSlot);
        s.set("inventory", null);
        for (var e : inventory.entrySet()) s.set("inventory." + e.getKey(), e.getValue());
    }

    public static FakeData read(ConfigurationSection s) {
        FakeData d = new FakeData();
        d.name = s.getString("name");
        d.uuid = UUID.fromString(s.getString("uuid", offlineUuid(d.name).toString()));
        d.world = s.getString("world", "world");
        d.x = s.getDouble("x"); d.y = s.getDouble("y"); d.z = s.getDouble("z");
        d.yaw = (float) s.getDouble("yaw"); d.pitch = (float) s.getDouble("pitch");
        d.gamemode = s.getString("gamemode", "SURVIVAL");
        d.displayName = s.getString("display-name", ""); d.listName = s.getString("list-name", "");
        d.ping = s.getInt("ping"); d.listed = s.getBoolean("listed", true); d.op = s.getBoolean("op");
        d.flying = s.getBoolean("flying"); d.invulnerable = s.getBoolean("invulnerable");
        d.sprint = s.getBoolean("sprint"); d.autoSpawn = s.getBoolean("autospawn", true);
        d.health = s.getDouble("health", 20); d.food = s.getInt("food", 20);
        d.skinValue = s.getString("skin.value", ""); d.skinSignature = s.getString("skin.signature", "");
        d.movement = s.getString("movement.mode", "STAND");
        d.speed = s.getDouble("movement.speed", 1.0); d.radius = s.getDouble("movement.radius", 8);
        d.followTarget = s.getString("movement.follow", "");
        for (String p : s.getStringList("movement.patrol")) {
            String[] a = p.split(",");
            d.patrol.add(new double[]{Double.parseDouble(a[0]), Double.parseDouble(a[1])});
        }
        d.selectedSlot = s.getInt("selected-slot");
        ConfigurationSection inv = s.getConfigurationSection("inventory");
        if (inv != null) for (String k : inv.getKeys(false)) d.inventory.put(Integer.parseInt(k), inv.getString(k));
        return d;
    }
}
