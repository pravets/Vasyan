package ru.pravets.vasyan.debug;

import ru.pravets.vasyan.memory.VisionScanner;
import ru.pravets.vasyan.entity.VasyanEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic, LLM-free scanner that describes what a Vasyan can see around
 * itself. Used by {@code /vasyan look} and by the full-state dump.
 */
public final class VasyanEnvironmentScanner {

    private static final int SURFACE_RADIUS = 16;
    private static final int SURFACE_MAX_BLOCKS = 256;

    public record BlockEntry(String blockId, int x, int y, int z) {}
    public record EntityEntry(String type, String name, double distance, String direction) {}

    public record SurfaceScan(
        String biome,
        long dayTime,
        boolean raining,
        boolean thundering,
        String visibleSummary,
        List<BlockEntry> surfaceBlocks,
        List<EntityEntry> nearbyEntities
    ) {}

    private VasyanEnvironmentScanner() {}

    /**
     * Scans the world around the given Vasyan. Runs on the server thread.
     */
    public static SurfaceScan scan(VasyanEntity vasyan) {
        Level level = vasyan.level();
        BlockPos origin = vasyan.blockPosition();

        String biome = level.getBiome(origin).unwrapKey()
            .map(k -> k.location().toString())
            .orElse("unknown");

        List<BlockEntry> surfaceBlocks = collectSurface(level, origin);
        List<EntityEntry> entities = collectEntities(vasyan, origin);

        return new SurfaceScan(
            biome,
            level.getDayTime(),
            level.isRaining(),
            level.isThundering(),
            VisionScanner.getVisibleSummary(vasyan),
            surfaceBlocks,
            entities
        );
    }

    /**
     * Formats a {@link SurfaceScan} as a short, human-readable sentence in
     * Russian (the project's default UI language). Keep under ~200 characters.
     */
    public static String describe(SurfaceScan scan) {
        StringBuilder sb = new StringBuilder();
        String biome = scan.biome();
        String biomeShort = biome.contains(":")
            ? biome.substring(biome.indexOf(':') + 1)
            : biome;
        sb.append("Я вижу ").append(biomeShort);
        if (scan.surfaceBlocks().isEmpty()) {
            sb.append(", вокруг ничего нет");
        } else {
            sb.append(", вокруг: ").append(shortBlockList(scan.surfaceBlocks()));
        }
        if (!scan.nearbyEntities().isEmpty()) {
            sb.append(". Рядом: ").append(shortEntityList(scan.nearbyEntities()));
        }
        String visible = scan.visibleSummary();
        if (visible != null && !visible.isEmpty() && !"nothing interesting".equals(visible)) {
            sb.append(". Замечаю ").append(visible);
        }
        return sb.toString();
    }

    private static List<BlockEntry> collectSurface(Level level, BlockPos origin) {
        List<BlockEntry> entries = new ArrayList<>();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int dx = -SURFACE_RADIUS;
             dx <= SURFACE_RADIUS && entries.size() < SURFACE_MAX_BLOCKS;
             dx += 2) {
            for (int dz = -SURFACE_RADIUS;
                 dz <= SURFACE_RADIUS && entries.size() < SURFACE_MAX_BLOCKS;
                 dz += 2) {
                mutable.set(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
                if (!level.hasChunkAt(mutable)) {
                    continue;
                }
                BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, mutable);
                if (surface == null) {
                    continue;
                }
                BlockState state = level.getBlockState(surface);
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                String blockId = id != null ? id.toString() : state.getBlock().toString();
                entries.add(new BlockEntry(blockId, surface.getX(), surface.getY(), surface.getZ()));
            }
        }
        return entries;
    }

    private static List<EntityEntry> collectEntities(VasyanEntity vasyan, BlockPos origin) {
        List<EntityEntry> entries = new ArrayList<>();
        List<Entity> nearby = vasyan.level().getEntities(vasyan,
            vasyan.getBoundingBox().inflate(24.0),
            e -> e instanceof LivingEntity && !(e instanceof VasyanEntity));
        for (Entity e : nearby) {
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            String name = e instanceof Player p ? p.getName().getString() : null;
            double dist = Math.sqrt(e.distanceToSqr(vasyan));
            String dir = direction(origin, e.blockPosition());
            entries.add(new EntityEntry(type, name, dist, dir));
        }
        entries.sort(Comparator.comparingDouble(EntityEntry::distance));
        return entries.size() > 8 ? entries.subList(0, 8) : entries;
    }

    private static String shortBlockList(List<BlockEntry> entries) {
        // Use the most common surface block names, deduplicated, limited to 4.
        return entries.stream()
            .map(BlockEntry::blockId)
            .map(id -> id.contains(":") ? id.substring(id.indexOf(':') + 1) : id)
            .distinct()
            .limit(4)
            .reduce((a, b) -> a + ", " + b)
            .orElse("блоки");
    }

    private static String shortEntityList(List<EntityEntry> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        EntityEntry first = entries.get(0);
        String type = first.type();
        String shortType = type.contains(":") ? type.substring(type.indexOf(':') + 1) : type;
        String label = first.name() != null ? first.name() : shortType;
        if (entries.size() == 1) {
            return label + " (~" + Math.round(first.distance()) + "m " + first.direction() + ")";
        }
        return label + " и ещё " + (entries.size() - 1);
    }

    private static String direction(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return "here";
        }
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        if (angle < 0) {
            angle += 360;
        }
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return dirs[(int) Math.round(angle / 45) % 8];
    }
}
