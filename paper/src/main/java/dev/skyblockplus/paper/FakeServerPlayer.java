package dev.skyblockplus.paper;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A REAL ServerPlayer (so every plugin/command treats it as a player) with a built-in movement brain.
 *
 * Performance notes:
 *  - idle (STAND) fake players that are resting on the ground only run the full vanilla tick every
 *    {@link #idleTickInterval} ticks; the moment anything happens (damage, water, a menu, movement ...)
 *    they go back to ticking every tick.
 *  - chunk tracking is only refreshed when the fake player actually changes chunk.
 *  - per-player view / simulation distance is lowered so the server does not load, send and simulate a
 *    full view-distance worth of chunks around every fake player.
 */
public class FakeServerPlayer extends ServerPlayer {
    private static final int STAND = 0, WANDER = 1, FOLLOW = 2, PATROL = 3, LOOK = 4;

    // ---- settings, filled from config.yml (performance.*) by SkyBlockPlusPlugin ----
    public static volatile int idleTickInterval = 4;     // 1 = never skip ticks
    public static volatile int warmupTicks = 200;        // always tick fully right after spawning
    public static volatile int viewDistance = 2;         // <= 0 = leave the server default
    public static volatile int simulationDistance = 2;   // <= 0 = leave the server default

    public final FakeData data;
    public FakeConnection fakeConnection;

    private double tx, tz;
    private boolean hasTarget;
    private int wait, stuck, patrolIdx, ticks;

    private String modeSrc = "";
    private int mode = STAND;

    private Object lastLevel;
    private int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;

    private String followName;
    private Player followCache;
    private int followTimer;

    private FakeServerPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, FakeData data) {
        super(server, level, profile, ClientInformation.createDefault());
        this.data = data;
    }

    // ---------- spawn / remove ----------
    public static GameProfile profile(FakeData d) {
        Multimap<String, Property> props = HashMultimap.create();
        if (d.skinValue != null && !d.skinValue.isEmpty())
            props.put("textures", new Property("textures", d.skinValue, d.skinSignature.isEmpty() ? null : d.skinSignature));
        return new GameProfile(d.uuid, d.name, new PropertyMap(props));
    }

    public static Player spawn(FakeData d, Location loc) {
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) loc.getWorld()).getHandle();
        GameProfile profile = profile(d);
        FakeServerPlayer sp = new FakeServerPlayer(server, level, profile, d);
        sp.fakeConnection = new FakeConnection();
        sp.snapTo(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        server.getPlayerList().placeNewPlayer(sp.fakeConnection, sp, CommonListenerCookie.createInitial(profile, false));
        Player bukkit = sp.getBukkitEntity();
        applyDistances(bukkit);
        return bukkit;
    }

    /** Fake players don't need a full view distance: cut chunk loading/sending/simulation around them. */
    private static void applyDistances(Player p) {
        try {
            int v = viewDistance, s = simulationDistance;
            if (v > 0) {
                v = Math.max(2, Math.min(32, v));
                p.setViewDistance(v);
                p.setSendViewDistance(v);
            }
            if (s > 0) p.setSimulationDistance(Math.max(2, Math.min(32, s)));
        } catch (Throwable ignored) { }
    }

    public static void remove(Player p) {
        ServerPlayer sp = ((CraftPlayer) p).getHandle();
        sp.connection.onDisconnect(new DisconnectionDetails(net.minecraft.network.chat.Component.literal("Fake player removed")));
    }

    public static boolean isFake(Player p) {
        return ((CraftPlayer) p).getHandle() instanceof FakeServerPlayer;
    }

    public static void setPing(Player p, int ms) {
        try {
            Object listener = ((CraftPlayer) p).getHandle().connection;
            for (Class<?> c = listener.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("latency");
                    f.setAccessible(true);
                    f.setInt(listener, ms);
                    return;
                } catch (NoSuchFieldException ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    // ---------- tick ----------
    @Override
    public void tick() {
        final int t = ++ticks;
        final int m = mode();
        final int idle = idleTickInterval;

        // Standing still on the ground and nothing going on: only do the full tick every N ticks.
        if (m == STAND && idle > 1 && t > warmupTicks && (t % idle) != 0 && !busy()) return;

        if (t % 10 == 0) refreshChunkPosition();
        think(m);
        try { super.tick(); this.doTick(); } catch (NullPointerException ignored) {}
    }

    /** True when the fake player needs the normal every-tick treatment. */
    private boolean busy() {
        return !this.onGround()
                || this.isInWater() || this.isInLava() || this.isOnFire()
                || this.isPassenger()
                || this.hurtTime > 0 || this.invulnerableTime > 0 || this.isDeadOrDying()
                || this.containerMenu != this.inventoryMenu
                || this.getAbilities().flying
                || this.getDeltaMovement().lengthSqr() > 1.0E-4;
    }

    private void refreshChunkPosition() {
        this.connection.resetPosition();
        ServerLevel lvl = (ServerLevel) this.level();
        int cx = ((int) Math.floor(getX())) >> 4;
        int cz = ((int) Math.floor(getZ())) >> 4;
        if (lvl != lastLevel || cx != lastCx || cz != lastCz) {
            lastLevel = lvl; lastCx = cx; lastCz = cz;
            lvl.getChunkSource().move(this);
        }
    }

    private int mode() {
        String src = data.movement;
        if (src != modeSrc) { modeSrc = src; mode = parseMode(src); }
        return mode;
    }

    private static int parseMode(String s) {
        if (s == null) return STAND;
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "WANDER" -> WANDER;
            case "FOLLOW" -> FOLLOW;
            case "PATROL" -> PATROL;
            case "LOOK" -> LOOK;
            default -> STAND;
        };
    }

    private void think(int m) {
        this.zza = 0; this.xxa = 0; this.setJumping(false);
        boolean moving = false;
        switch (m) {
            case WANDER -> moving = wander();
            case FOLLOW -> moving = follow();
            case PATROL -> moving = patrol();
            case LOOK -> { float y = getYRot() + (float) (3 * data.speed); setYRot(y); setYHeadRot(y); }
            default -> { }
        }
        if (moving) {
            this.zza = (float) Math.min(1.0, data.speed);
            if (this.horizontalCollision && this.onGround()) this.setJumping(true);
        }
        // setSprinting rebuilds an attribute modifier every call, so only call it on a change
        boolean sprint = moving && data.sprint;
        if (this.isSprinting() != sprint) this.setSprinting(sprint);
    }

    private double face(double x, double z) {
        double dx = x - getX(), dz = z - getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        setYRot(yaw); setYHeadRot(yaw);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean wander() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (!hasTarget) {
            if (wait-- > 0) return false;
            double a = r.nextDouble() * Math.PI * 2, rad = r.nextDouble() * data.radius;
            tx = data.x + Math.cos(a) * rad; tz = data.z + Math.sin(a) * rad;
            hasTarget = true; stuck = 0;
        }
        double d = face(tx, tz);
        if (d < 0.6 || ++stuck > 200) { hasTarget = false; wait = 20 + r.nextInt(80); return false; }
        return true;
    }

    private boolean follow() {
        String name = data.followTarget;
        if (name != followName) { followName = name; followCache = null; followTimer = 0; }
        // look the target up at most once a second instead of every tick
        if (followTimer-- <= 0) {
            followTimer = 20;
            followCache = (name == null || name.isEmpty()) ? null : Bukkit.getPlayerExact(name);
        }
        Player target = followCache;
        if (target == null || !target.isOnline()) return false;
        ServerPlayer tp = ((CraftPlayer) target).getHandle();
        if (tp.level() != this.level()) return false;
        return face(tp.getX(), tp.getZ()) > 2.5;
    }

    private boolean patrol() {
        if (data.patrol.isEmpty()) return false;
        if (wait > 0) { wait--; return false; }
        if (patrolIdx >= data.patrol.size()) patrolIdx = 0;
        double[] p = data.patrol.get(patrolIdx);
        double d = face(p[0], p[1]);
        if (d < 0.8 || ++stuck > 400) { patrolIdx++; stuck = 0; wait = 20; return false; }
        return true;
    }
}
