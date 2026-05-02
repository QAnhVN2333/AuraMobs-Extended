package dev.aurelium.auramobs.util;

import dev.aurelium.auramobs.AuraMobs;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StagePlaceholderManager {

    private final AuraMobs plugin;
    private final Map<Integer, StageResult> cache;
    private List<StageDefinition> stages;
    private String fallbackStage;
    private int fallbackStageLevel;

    public StagePlaceholderManager(AuraMobs plugin) {
        this.plugin = plugin;
        this.cache = new HashMap<>();
        this.stages = new ArrayList<>();
        this.fallbackStage = "Unknown";
        this.fallbackStageLevel = 0;
    }

    public void loadConfiguration() {
        // Clear previous data to keep reloads consistent.
        cache.clear();
        stages = new ArrayList<>();

        FileConfiguration config = plugin.getConfig();
        List<Map<?, ?>> configuredStages = config.getMapList("stage_placeholders.stages");
        for (Map<?, ?> entry : configuredStages) {
            Integer min = readInt(entry, "min");
            String name = readString(entry, "name");
            Integer max = readInt(entry, "max");

            if (min == null || name == null || name.isBlank()) {
                plugin.getLogger().warning("Invalid stage entry in stage_placeholders.stages; missing min or name.");
                continue;
            }

            int resolvedMax = resolveMax(max);
            stages.add(new StageDefinition(min, resolvedMax, name));
        }

        stages.sort(Comparator.comparingInt(stage -> stage.min));
        warnIfOverlapping(stages);

        fallbackStage = config.getString("stage_placeholders.fallback.stage", "Unknown");
        fallbackStageLevel = config.getInt("stage_placeholders.fallback.stage_level", 0);
    }

    public String applyStagePlaceholders(String input, int level) {
        if (input == null) {
            return null;
        }

        StageResult result = resolveStage(level);
        return input
                .replace("{stage}", result.stageName)
                .replace("{stage_level}", Integer.toString(result.stageLevel));
    }

    private StageResult resolveStage(int level) {
        StageResult cached = cache.get(level);
        if (cached != null) {
            return cached;
        }

        for (StageDefinition stage : stages) {
            if (level >= stage.min && level <= stage.max) {
                StageResult result = new StageResult(stage.name, stage.min, stage.max, level - stage.min + 1);
                cache.put(level, result);
                return result;
            }
        }

        StageResult fallback = new StageResult(fallbackStage, 0, 0, fallbackStageLevel);
        cache.put(level, fallback);
        return fallback;
    }

    private int resolveMax(Integer max) {
        if (max == null || max == -1) {
            return Integer.MAX_VALUE;
        }
        return max;
    }

    private Integer readInt(Map<?, ?> entry, String key) {
        Object value = entry.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private String readString(Map<?, ?> entry, String key) {
        Object value = entry.get(key);
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private void warnIfOverlapping(List<StageDefinition> definitions) {
        for (int index = 0; index < definitions.size() - 1; index++) {
            StageDefinition current = definitions.get(index);
            StageDefinition next = definitions.get(index + 1);
            if (current.max >= next.min) {
                plugin.getLogger().warning("Stage ranges overlap between " + current.name + " and " + next.name + ".");
            }
        }
    }

    private static class StageDefinition {
        private final int min;
        private final int max;
        private final String name;

        private StageDefinition(int min, int max, String name) {
            this.min = min;
            this.max = max;
            this.name = name;
        }
    }

    private static class StageResult {
        private final String stageName;
        private final int stageMin;
        private final int stageMax;
        private final int stageLevel;

        private StageResult(String stageName, int stageMin, int stageMax, int stageLevel) {
            this.stageName = stageName;
            this.stageMin = stageMin;
            this.stageMax = stageMax;
            this.stageLevel = stageLevel;
        }
    }
}

