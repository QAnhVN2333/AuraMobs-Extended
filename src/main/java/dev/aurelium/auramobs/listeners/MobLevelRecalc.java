package dev.aurelium.auramobs.listeners;

import dev.aurelium.auramobs.AuraMobs;
import dev.aurelium.auramobs.api.WorldGuardHook;
import dev.aurelium.auramobs.entities.AureliumMob;
import dev.aurelium.auramobs.util.MobLevelCalculator;
import io.lumine.mythic.core.constants.MobKeys;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MobLevelRecalc implements Listener {

    private final AuraMobs plugin;
    private final MobLevelCalculator levelCalculator;
    private BukkitRunnable periodicTask;

    public MobLevelRecalc(AuraMobs plugin) {
        this.plugin = plugin;
        this.levelCalculator = new MobLevelCalculator(plugin);
    }

    public void start() {
        // Stop any previous task before starting a new schedule.
        stop();
        if (!isRecalcEnabled()) {
            return;
        }
        if (!isPeriodicMode()) {
            return;
        }

        int intervalTicks = Math.max(1, plugin.optionInt("level_recalc.interval_ticks"));
        periodicTask = new BukkitRunnable() {
            @Override
            public void run() {
                runPeriodicScan();
            }
        };
        periodicTask.runTaskTimer(plugin, intervalTicks, intervalTicks);
    }

    public void stop() {
        // Cancel the periodic task when reloading or disabling recalc.
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
    }

    @EventHandler
    public void onMobTargetPlayer(EntityTargetLivingEntityEvent event) {
        if (!isRecalcEnabled()) {
            return;
        }
        if (!isEventMode()) {
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity mob)) {
            return;
        }

        if (!(event.getTarget() instanceof Player targetPlayer)) {
            return;
        }

        if (!shouldProcessMob(mob)) {
            return;
        }

        if (!isNewTarget(mob, targetPlayer)) {
            return;
        }

        if (!isCooldownReady(mob)) {
            return;
        }

        recalcMob(mob, getRadius());
    }

    private void runPeriodicScan() {
        if (!isRecalcEnabled()) {
            return;
        }
        int batchSize = Math.max(1, plugin.optionInt("level_recalc.batch_size"));
        int radius = getRadius();
        int processed = 0;
        Set<UUID> seen = new HashSet<>();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                if (processed >= batchSize) {
                    return;
                }

                if (!(entity instanceof LivingEntity mob)) {
                    continue;
                }

                if (!seen.add(mob.getUniqueId())) {
                    continue;
                }

                if (!shouldProcessMob(mob)) {
                    continue;
                }

                if (!isCooldownReady(mob)) {
                    continue;
                }

                recalcMob(mob, radius);
                processed++;
            }
        }
    }

    private void recalcMob(LivingEntity mob, int radius) {
        // Gather nearby player stats to reuse the existing level formula logic.
        PlayerStats stats = collectPlayerStats(mob, radius);
        double distance = mob.getLocation().distance(mob.getWorld().getSpawnLocation());

        int calculatedLevel = levelCalculator.calculateLevel(
                mob,
                stats.playerCount,
                distance,
                stats.maxLevel,
                stats.minLevel,
                stats.sumLevel
        );

        int newLevel = levelCalculator.clampToWorldGuard(mob.getLocation(), calculatedLevel);
        int currentLevel = mob.getPersistentDataContainer()
                .getOrDefault(plugin.getLevelKey(), PersistentDataType.INTEGER, 1);

        if (currentLevel != newLevel) {
            new AureliumMob(mob, newLevel, plugin);
        }

        markRecalc(mob);
    }

    private PlayerStats collectPlayerStats(LivingEntity mob, int radius) {
        int sumLevel = 0;
        int maxLevel = Integer.MIN_VALUE;
        int minLevel = Integer.MAX_VALUE;
        int playerCount = 0;

        for (Entity nearby : mob.getNearbyEntities(radius, radius, radius)) {
            if (!(nearby instanceof Player player)) {
                continue;
            }

            if (player.hasMetadata("NPC")) {
                continue;
            }

            if (player.hasPermission("auramobs.exclude") || isVanished(player)) {
                continue;
            }

            int level = plugin.getLevel(player);
            sumLevel += level;
            playerCount++;

            if (level > maxLevel) {
                maxLevel = level;
            }
            if (level < minLevel) {
                minLevel = level;
            }
        }

        return new PlayerStats(sumLevel, maxLevel, minLevel, playerCount);
    }

    private boolean shouldProcessMob(LivingEntity mob) {
        if (mob.isDead() || !mob.isValid()) {
            return false;
        }

        // Skip mobs explicitly locked by admin commands.
        if (mob.getPersistentDataContainer().has(plugin.getLevelLockKey(), PersistentDataType.BYTE)) {
            return false;
        }

        if (!plugin.isAuraMob(mob)) {
            return false;
        }

        if (!plugin.optionBoolean("level_recalc.include_bosses") && plugin.isBossMob(mob)) {
            return false;
        }

        if (!passWorld(mob.getWorld())) {
            return false;
        }

        if (!isWorldGuardAllowed(mob.getLocation())) {
            return false;
        }

        if (plugin.isMythicMobsEnabled()
                && mob.getPersistentDataContainer().has(MobKeys.TYPE, PersistentDataType.STRING)
                && plugin.optionBoolean("level_recalc.ignore_mythic_mobs")) {
            return false;
        }

        return true;
    }

    private boolean passWorld(World world) {
        // Mirror spawn world filtering to avoid recalculating in disabled worlds.
        if (plugin.isWorldWhitelist()) {
            if (plugin.getEnabledWorlds().contains("*")) {
                return true;
            }
            for (String enabledWorld : plugin.getEnabledWorlds()) {
                if (world.getName().equalsIgnoreCase(enabledWorld)
                        || world.getName().startsWith(enabledWorld.replace("*", ""))) {
                    return true;
                }
            }
            return false;
        } else {
            if (plugin.getEnabledWorlds().contains("*")) {
                return false;
            }
            for (String enabledWorld : plugin.getEnabledWorlds()) {
                if (world.getName().equalsIgnoreCase(enabledWorld)
                        || world.getName().startsWith(enabledWorld.replace("*", ""))) {
                    return false;
                }
            }
            return true;
        }
    }

    private boolean isWorldGuardAllowed(Location location) {
        WorldGuardHook worldGuard = plugin.getWorldGuard();
        if (worldGuard == null) {
            return true;
        }
        return worldGuard.mobsEnabled(location);
    }

    private boolean isRecalcEnabled() {
        // Read the latest config toggle so reloads take effect immediately.
        return plugin.optionBoolean("level_recalc.enabled");
    }

    private boolean isEventMode() {
        TriggerMode mode = getTriggerMode();
        return mode == TriggerMode.EVENT || mode == TriggerMode.HYBRID;
    }

    private boolean isPeriodicMode() {
        TriggerMode mode = getTriggerMode();
        return mode == TriggerMode.PERIODIC || mode == TriggerMode.HYBRID;
    }

    private TriggerMode getTriggerMode() {
        String value = plugin.optionString("level_recalc.trigger_mode");
        if (value == null) {
            return TriggerMode.HYBRID;
        }

        try {
            return TriggerMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return TriggerMode.HYBRID;
        }
    }

    private int getRadius() {
        int radius = plugin.optionInt("level_recalc.radius");
        if (radius > 0) {
            return radius;
        }
        return plugin.optionInt("player_level.check_radius");
    }

    private boolean isCooldownReady(LivingEntity mob) {
        long lastCheck = mob.getPersistentDataContainer()
                .getOrDefault(plugin.getRecalcKey(), PersistentDataType.LONG, 0L);
        long cooldownMillis = Math.max(0L, plugin.optionInt("level_recalc.cooldown_ticks")) * 50L;
        long now = System.currentTimeMillis();
        return (now - lastCheck) >= cooldownMillis;
    }

    private void markRecalc(LivingEntity mob) {
        mob.getPersistentDataContainer().set(
                plugin.getRecalcKey(),
                PersistentDataType.LONG,
                System.currentTimeMillis()
        );
    }

    private boolean isNewTarget(LivingEntity mob, Player targetPlayer) {
        PersistentDataContainer data = mob.getPersistentDataContainer();
        String newTargetId = targetPlayer.getUniqueId().toString();
        String previousTargetId = data.get(plugin.getTargetKey(), PersistentDataType.STRING);

        if (newTargetId.equals(previousTargetId)) {
            return false;
        }

        data.set(plugin.getTargetKey(), PersistentDataType.STRING, newTargetId);
        return true;
    }

    private boolean isVanished(Player player) {
        for (MetadataValue meta : player.getMetadata("vanished")) {
            if (meta.asBoolean()) {
                return true;
            }
        }
        return false;
    }

    private enum TriggerMode {
        PERIODIC,
        EVENT,
        HYBRID
    }

    private static class PlayerStats {
        private final int sumLevel;
        private final int maxLevel;
        private final int minLevel;
        private final int playerCount;

        private PlayerStats(int sumLevel, int maxLevel, int minLevel, int playerCount) {
            this.sumLevel = sumLevel;
            this.maxLevel = maxLevel;
            this.minLevel = minLevel;
            this.playerCount = playerCount;
        }
    }
}

