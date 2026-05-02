package dev.aurelium.auramobs.listeners;

import dev.aurelium.auramobs.AuraMobs;
import dev.aurelium.auramobs.entities.AureliumMob;
import dev.aurelium.auramobs.util.MobLevelCalculator;
import io.lumine.mythic.core.constants.MobKeys;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Locale;

public class MobSpawn implements Listener {

    private final AuraMobs plugin;
    private final MobLevelCalculator levelCalculator;

    public MobSpawn(AuraMobs plugin) {
        this.plugin = plugin;
        this.levelCalculator = new MobLevelCalculator(plugin);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSpawn(CreatureSpawnEvent e) {
        try {
            if (!plugin.getSpawnReasons().contains(e.getSpawnReason().name())) return;

            if (plugin.isNotEnemy(e.getEntity())) {
                return;
            }

            LivingEntity entity = e.getEntity();

            if (!plugin.optionBoolean("bosses.enabled") && plugin.isBossMob(entity)) {
                return;
            }

            if (!passWorld(e.getEntity().getWorld())) return;

            if (plugin.getWorldGuard() != null) {
                if (!(plugin.getWorldGuard().mobsEnabled(e.getLocation()))) {
                    return;
                }
            }

            List<String> mobs = plugin.optionList("mob_replacements.list");
            String type = plugin.optionString("mob_replacements.type");

            if (type.equalsIgnoreCase("blacklist") && (mobs.contains(e.getEntity().getType().name()) || mobs.contains("*"))) {
                return;
            } else if (type.equalsIgnoreCase("whitelist") && (!mobs.contains(e.getEntity().getType().name().toUpperCase(Locale.ROOT)) && !mobs.contains("*"))) {
                return;
            }

            if (!plugin.optionBoolean("custom_name.allow_override")) {
                if (e.getEntity().getCustomName() != null) {
                    return;
                }
            }

            PersistentDataContainer data = entity.getPersistentDataContainer();
            if (data.has(plugin.getSummonKey(), PersistentDataType.BYTE)) {
                return; // Already handled via command
            }

            int radius = plugin.optionInt("player_level.check_radius");

            changeMob(entity, radius).runTask(plugin);
        } catch (NullPointerException ex) {
            plugin.getLogger().severe(ex.getMessage());
        }
    }


    private boolean passWorld(World world) {
        if (plugin.isWorldWhitelist()) {
            if (plugin.getEnabledWorlds().contains("*")) return true;
            for (String enabledworld : plugin.getEnabledWorlds()) {
                if (world.getName().equalsIgnoreCase(enabledworld) || world.getName().startsWith(enabledworld.replace("*", ""))) {
                    return true;
                }
            }
            return false;
        } else {
            if (plugin.getEnabledWorlds().contains("*")) return false;
            for (String enabledworld : plugin.getEnabledWorlds()) {
                if (world.getName().equalsIgnoreCase(enabledworld) || world.getName().startsWith(enabledworld.replace("*", ""))) {
                    return false;
                }
            }
            return true;
        }
    }

    public BukkitRunnable changeMob(LivingEntity entity, int radius) {
        return new BukkitRunnable() {
            @Override
            public void run() {
                if (entity.isDead() || !entity.isValid()) {
                    return;
                }

                if (plugin.isMythicMobsEnabled() && entity.getPersistentDataContainer().has(MobKeys.TYPE, PersistentDataType.STRING) && plugin.ignoreMythicMobs()) {
                    return;
                }

                int sumLevel = 0;
                int maxLevel = Integer.MIN_VALUE;
                int minLevel = Integer.MAX_VALUE;
                int playerCount = 0;

                for (Entity entity : entity.getNearbyEntities(radius, radius, radius)) {
                    if (!(entity instanceof Player player)) continue;
                    if (player.hasMetadata("NPC")) continue;
                    if (player.hasPermission("auramobs.exclude") || isVanished(player)) continue;

                    int lvl = plugin.getLevel(player);
                    sumLevel += lvl;
                    playerCount++;
                    if (lvl > maxLevel) {
                        maxLevel = lvl;
                    }
                    if (lvl < minLevel) {
                        minLevel = lvl;
                    }
                }
                Location mobLoc = entity.getLocation();
                Location spawnPoint = entity.getWorld().getSpawnLocation();
                double distance = mobLoc.distance(spawnPoint);
                int level;

                int overrideLevel = getMetadataLevel(entity);
                if (overrideLevel != 0) {
                    level = overrideLevel;
                } else {
                    level = levelCalculator.calculateLevel(entity, playerCount, distance, maxLevel, minLevel, sumLevel);
                }
                new AureliumMob(entity, levelCalculator.clampToWorldGuard(entity.getLocation(), level), plugin);
            }
        };
    }

    private boolean isVanished(Player player) {
        for (MetadataValue meta : player.getMetadata("vanished")) {
            if (meta.asBoolean()) return true;
        }
        return false;
    }

    private int getMetadataLevel(Entity entity) {
        int overrideLevel = 0;
        List<MetadataValue> meta = entity.getMetadata("auraskills_level");
        if (!meta.isEmpty()) {
            for (MetadataValue val : meta) {
                Plugin owning = val.getOwningPlugin();
                if (owning == null) continue;

                if (owning.getName().equals("AuraSkills")) {
                    overrideLevel = val.asInt();
                    break;
                }
            }
        }
        return overrideLevel;
    }

}
