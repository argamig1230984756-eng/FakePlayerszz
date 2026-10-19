package dev.skyblockplus.velocity;

import java.util.UUID;

/** Proxy-side record of a fake player living on some backend server. */
public class FakeEntry {
    public String server;
    public UUID uuid;
    public String name, display = "", skinValue = "", skinSignature = "";
    public int ping, gamemode;
    public boolean listed = true;
    /** Only used to prune stale entries after a sync; not worth saving to disk. */
    public transient long epoch;
}
