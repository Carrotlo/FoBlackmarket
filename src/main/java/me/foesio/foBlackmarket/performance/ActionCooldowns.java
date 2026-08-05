package me.foesio.foBlackmarket.performance;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ActionCooldowns implements Listener {

    private final Map<String, Map<UUID, Long>> buckets = new ConcurrentHashMap<>();

    public boolean tryAcquire(UUID playerId, String bucket, long cooldownMillis) {
        if (cooldownMillis <= 0L) {
            return true;
        }

        long cooldownNanos = cooldownMillis * 1_000_000L;
        long now = System.nanoTime();
        Map<UUID, Long> cooldowns = buckets.computeIfAbsent(bucket, ignored -> new ConcurrentHashMap<>());
        Long previous = cooldowns.get(playerId);
        if (previous != null && now - previous < cooldownNanos) {
            return false;
        }
        cooldowns.put(playerId, now);
        return true;
    }

    public void clear(UUID playerId) {
        for (Map<UUID, Long> cooldowns : buckets.values()) {
            cooldowns.remove(playerId);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }
}
