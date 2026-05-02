package dev.aurelium.auramobs.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import dev.aurelium.auramobs.AuraMobs;
import dev.aurelium.auramobs.api.WorldGuardHook;
import dev.aurelium.auramobs.entities.AureliumMob;
import dev.aurelium.auramobs.util.ColorUtils;
import io.lumine.mythic.core.constants.MobKeys;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@CommandAlias("auramobs")
public class AuraMobsCommand extends BaseCommand {

    private final AuraMobs plugin;

    public AuraMobsCommand(AuraMobs plugin) {
        this.plugin = plugin;
    }

    @Subcommand("reload")
    @CommandPermission("auramobs.reload")
    public void onReload(CommandSender sender) {
        // Reload runtime state so cached config and recalc tasks are refreshed.
        plugin.reloadRuntime();
        sender.sendMessage(ColorUtils.colorMessage(plugin.getMsg("commands.reload")));
    }

    @Subcommand("summon")
    @CommandPermission("auramobs.summon")
    @Syntax("<type> <level>")
    @CommandCompletion("@entitytypes @level")
    public void onSummon(CommandSender sender, String mobType, int level) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ColorUtils.colorMessage(plugin.getMsg("commands.summon.console")));
            return;
        }

        EntityType type;
        try {
            type = EntityType.valueOf(mobType.toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ColorUtils.colorMessage(plugin.getMsg("commands.summon.invalid").replace("{type}", mobType)));
            return;
        }

        if (!type.isSpawnable() || !type.isAlive() || type.getEntityClass() == null || plugin.isNotEnemy(type.getEntityClass())) {
            sender.sendMessage(ColorUtils.colorMessage(plugin.getMsg("commands.summon.failure")
                    .replace("{mob}", type.name()).replace("{level}", String.valueOf(level))));
            return;
        }

        LivingEntity entity = (LivingEntity) player.getWorld().spawn(player.getLocation(), type.getEntityClass(), ent -> {
            if (ent instanceof LivingEntity living) {
                living.getPersistentDataContainer().set(plugin.getSummonKey(), PersistentDataType.BYTE, (byte) 1);
            }
        });
        new AureliumMob(entity, level, plugin);

        sender.sendMessage(ColorUtils.colorMessage(plugin.getMsg("commands.summon.success")
                .replace("{mob}", type.name()).replace("{level}", String.valueOf(level))));
    }

    @Subcommand("setlevel")
    @CommandPermission("auramobs.setlevel")
    @Syntax("<level> <radius>")
    public void onSetLevel(CommandSender sender, int level, int radius) {
        // Ensure only players can use radius-based mob selection.
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ColorUtils.colorMessage(plugin.getMsg("commands.setlevel.console")));
            return;
        }

        // Validate input values before any heavy processing.
        if (level <= 0) {
            sender.sendMessage(ColorUtils.colorMessage(plugin.getMsg("commands.setlevel.invalid_level")
                    .replace("{level}", String.valueOf(level))));
            return;
        }

        if (radius <= 0) {
            sender.sendMessage(ColorUtils.colorMessage(plugin.getMsg("commands.setlevel.invalid_radius")
                    .replace("{radius}", String.valueOf(radius))));
            return;
        }

        // Collect all eligible mobs near the player at the time of execution.
        List<LivingEntity> targets = collectEligibleMobs(player, radius);
        if (targets.isEmpty()) {
            sender.sendMessage(ColorUtils.colorMessage(plugin.getMsg("commands.setlevel.no_targets")
                    .replace("{radius}", String.valueOf(radius))));
            return;
        }

        // Apply the level in batches to avoid a large single-tick workload.
        runBatchedSetLevel(player, targets, level, radius);
    }

    private List<LivingEntity> collectEligibleMobs(Player player, int radius) {
        List<LivingEntity> targets = new ArrayList<>();

        // Snapshot nearby entities and filter to eligible living mobs.
        for (Entity nearby : player.getNearbyEntities(radius, radius, radius)) {
            if (!(nearby instanceof LivingEntity mob)) {
                continue;
            }

            if (!isEligibleForSet(mob)) {
                continue;
            }

            targets.add(mob);
        }

        return targets;
    }

    private void runBatchedSetLevel(Player player, List<LivingEntity> targets, int level, int radius) {
        int batchSize = Math.max(1, plugin.optionInt("level_recalc.batch_size"));

        // Process a fixed batch every tick to avoid timeouts.
        new BukkitRunnable() {
            private int index = 0;
            private int updated = 0;

            @Override
            public void run() {
                // Stop if we have consumed the entire target list.
                if (index >= targets.size()) {
                    if (player.isOnline()) {
                        player.sendMessage(ColorUtils.colorMessage(plugin.getMsg("commands.setlevel.success")
                                .replace("{level}", String.valueOf(level))
                                .replace("{radius}", String.valueOf(radius))
                                .replace("{updated}", String.valueOf(updated))
                                .replace("{total}", String.valueOf(targets.size()))));
                    }
                    cancel();
                    return;
                }

                // Apply the level to the next batch of eligible mobs.
                int endIndex = Math.min(index + batchSize, targets.size());
                for (; index < endIndex; index++) {
                    LivingEntity mob = targets.get(index);
                    if (!isEligibleForSet(mob)) {
                        continue;
                    }

                    applyLevelAndLock(mob, level);
                    updated++;
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void applyLevelAndLock(LivingEntity mob, int level) {
        // Apply AureliumMob stats and name updates at the requested level.
        new AureliumMob(mob, level, plugin);

        // Lock the level to prevent recalculation from overwriting it.
        mob.getPersistentDataContainer().set(plugin.getLevelLockKey(), PersistentDataType.BYTE, (byte) 1);
    }

    private boolean isEligibleForSet(LivingEntity mob) {
        // Ignore dead or invalid entities to avoid errors.
        if (mob.isDead() || !mob.isValid()) {
            return false;
        }

        // Only affect hostile mobs, matching normal AuraMobs behavior.
        if (plugin.isNotEnemy(mob)) {
            return false;
        }

        // Respect the recalc config for boss inclusion.
        if (!plugin.optionBoolean("level_recalc.include_bosses") && plugin.isBossMob(mob)) {
            return false;
        }

        // Respect world whitelist/blacklist configuration.
        if (!passWorld(mob.getWorld())) {
            return false;
        }

        // Respect WorldGuard mob spawning flags if available.
        if (!isWorldGuardAllowed(mob.getLocation())) {
            return false;
        }

        // Respect the recalc MythicMobs ignore setting.
        if (plugin.isMythicMobsEnabled()
                && mob.getPersistentDataContainer().has(MobKeys.TYPE, PersistentDataType.STRING)
                && plugin.optionBoolean("level_recalc.ignore_mythic_mobs")) {
            return false;
        }

        return true;
    }

    private boolean passWorld(World world) {
        // Mirror spawn world filtering to keep behavior consistent.
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

    private boolean isWorldGuardAllowed(org.bukkit.Location location) {
        WorldGuardHook worldGuard = plugin.getWorldGuard();
        if (worldGuard == null) {
            return true;
        }
        return worldGuard.mobsEnabled(location);
    }
}
