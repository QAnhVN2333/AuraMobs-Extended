package dev.aurelium.auramobs.util;

import dev.aurelium.auramobs.AuraMobs;
import dev.aurelium.auramobs.api.WorldGuardHook;
import dev.aurelium.auramobs.util.CustomFunctions;
import dev.aurelium.auramobs.util.MessageUtils;
import net.objecthunter.exp4j.ExpressionBuilder;
import net.objecthunter.exp4j.function.Function;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.Random;

public class MobLevelCalculator {

    private final AuraMobs plugin;
    private final Random random;

    public MobLevelCalculator(AuraMobs plugin) {
        this.plugin = plugin;
        this.random = new Random();
    }

    public int calculateLevel(LivingEntity entity, int playerCount, double distance, int maxLevel, int minLevel, int sumLevel) {
        // Build the formula with placeholders based on whether players are nearby.
        String prefix = plugin.isBossMob(entity) ? "bosses.level." : "mob_level.";
        int globalOnline = plugin.getServer().getOnlinePlayers().size();
        String formula;

        if (playerCount == 0) {
            formula = MessageUtils.setPlaceholders(null, plugin.optionString(prefix + "backup_formula")
                    .replace("{distance}", Double.toString(distance))
                    .replace("{sumlevel_global}", Integer.toString(plugin.getGlobalLevel()))
                    .replace("{playercount}", globalOnline > 0 ? String.valueOf(globalOnline) : "1")
                    .replace("{location_x}", Double.toString(entity.getLocation().getX()))
                    .replace("{location_y}", Double.toString(entity.getLocation().getY()))
                    .replace("{location_z}", Double.toString(entity.getLocation().getZ()))
                    .replace("{random_int}", String.valueOf(random.nextInt(100) + 1))
                    .replace("{random_double}", String.valueOf(random.nextDouble()))
            );
        } else {
            formula = MessageUtils.setPlaceholders(null, plugin.optionString(prefix + "formula")
                    .replace("{highestlvl}", Integer.toString(maxLevel))
                    .replace("{lowestlvl}", Integer.toString(minLevel))
                    .replace("{sumlevel}", Integer.toString(sumLevel))
                    .replace("{playercount}", Integer.toString(playerCount))
                    .replace("{distance}", Double.toString(distance))
                    .replace("{sumlevel_global}", Integer.toString(plugin.getGlobalLevel()))
                    .replace("{location_x}", Double.toString(entity.getLocation().getX()))
                    .replace("{location_y}", Double.toString(entity.getLocation().getY()))
                    .replace("{location_z}", Double.toString(entity.getLocation().getZ()))
                    .replace("{random_int}", String.valueOf(random.nextInt(100) + 1))
                    .replace("{random_double}", String.valueOf(random.nextDouble()))
            );
        }

        ExpressionBuilder builder = new ExpressionBuilder(formula);
        for (Function func : CustomFunctions.getCustomFunctions()) {
            builder.function(func);
        }

        int level = (int) builder.build().evaluate();
        return Math.min(level, plugin.optionInt(prefix + "max_level"));
    }

    public int clampToWorldGuard(Location location, int level) {
        // Respect WorldGuard min/max level flags when available.
        WorldGuardHook worldGuard = plugin.getWorldGuard();
        if (worldGuard == null) {
            return level;
        }

        if (level < worldGuard.getMinLevel(location)) {
            return worldGuard.getMinLevel(location);
        }
        return Math.min(level, worldGuard.getMaxLevel(location));
    }
}
