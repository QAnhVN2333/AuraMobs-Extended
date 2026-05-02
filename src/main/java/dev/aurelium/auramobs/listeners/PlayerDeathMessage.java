package dev.aurelium.auramobs.listeners;

import dev.aurelium.auramobs.AuraMobs;
import dev.aurelium.auramobs.util.ColorUtils;
import dev.aurelium.auramobs.util.MessageUtils;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class PlayerDeathMessage implements Listener {

    private final AuraMobs plugin;

    public PlayerDeathMessage(AuraMobs plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        // Resolve the mob that caused the death and ensure it is an AuraMob.
        LivingEntity killer = resolveKiller(player);
        if (killer == null || !plugin.isAuraMob(killer)) {
            return;
        }

        // Pick a template from config, supporting list or single string.
        String template = resolveTemplate();
        // Replace placeholders and apply colors/placeholders.
        String message = applyTemplate(template, player, killer);
        event.setDeathMessage(message);
    }

    private LivingEntity resolveKiller(Player player) {
        EntityDamageEvent lastDamage = player.getLastDamageCause();
        if (!(lastDamage instanceof EntityDamageByEntityEvent damageEvent)) {
            return null;
        }

        if (damageEvent.getDamager() instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof LivingEntity shooter) {
                return shooter;
            }
            return null;
        }

        if (damageEvent.getDamager() instanceof LivingEntity livingDamager) {
            return livingDamager;
        }

        return null;
    }

    private String resolveTemplate() {
        Object rawValue = plugin.option("custom_name.kill_message_template").getValue();
        if (rawValue instanceof List<?>) {
            List<String> templates = plugin.optionList("custom_name.kill_message_template");
            String chosen = selectRandomNonEmpty(templates);
            if (chosen != null) {
                return chosen;
            }
        }

        String template = plugin.optionString("custom_name.kill_message_template");
        if (template != null && !template.isBlank()) {
            return template;
        }

        return plugin.optionString("custom_name.format");
    }

    private String selectRandomNonEmpty(List<String> templates) {
        if (templates == null || templates.isEmpty()) {
            return null;
        }

        List<String> candidates = templates.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }

        int index = ThreadLocalRandom.current().nextInt(candidates.size());
        return candidates.get(index);
    }

    private String applyTemplate(String template, Player player, LivingEntity killer) {
        int level = killer.getPersistentDataContainer().getOrDefault(plugin.getLevelKey(), PersistentDataType.INTEGER, 1);
        String mobName = resolveMobName(killer);
        String formattedHealth = plugin.getFormatter().format(Math.max(killer.getHealth(), 0.0));
        String formattedMaxHealth = plugin.getFormatter().format(resolveMaxHealth(killer));
        String distance = String.valueOf(killer.getLocation().distance(killer.getWorld().getSpawnLocation()));

        String message = template
                .replace("{player}", player.getName())
                .replace("{mob}", mobName)
                .replace("{lvl}", Integer.toString(level))
                .replace("{health}", formattedHealth)
                .replace("{maxhealth}", formattedMaxHealth)
                .replace("{distance}", distance);

        message = plugin.getStagePlaceholderManager().applyStagePlaceholders(message, level);
        message = MessageUtils.setPlaceholders(player, message);
        return ColorUtils.colorMessage(message);
    }

    private double resolveMaxHealth(LivingEntity killer) {
        AttributeInstance attribute = killer.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attribute == null) {
            return killer.getHealth();
        }
        return attribute.getValue();
    }

    private String resolveMobName(LivingEntity killer) {
        String key = "mobs." + killer.getType().name().toLowerCase(Locale.ROOT);
        String name = plugin.getMsg(key);
        if (name == null || name.isBlank()) {
            return killer.getType().name();
        }
        return name;
    }
}
