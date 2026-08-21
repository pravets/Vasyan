package ru.pravets.vasyan.debug;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VasyanEnvironmentScannerTest {

    @Test
    void describeFormatsBiomeAndBlocks() {
        VasyanEnvironmentScanner.SurfaceScan scan = new VasyanEnvironmentScanner.SurfaceScan(
            "minecraft:plains",
            6000L,
            true,
            false,
            "oak_log x3 (12m S), iron_ore (8m down)",
            List.of(new VasyanEnvironmentScanner.BlockEntry("grass_block", 1, 64, 2),
                    new VasyanEnvironmentScanner.BlockEntry("oak_log", 5, 65, -3)),
            List.of(new VasyanEnvironmentScanner.EntityEntry("zombie", null, 10.0, "E"))
        );

        String description = VasyanEnvironmentScanner.describe(scan);

        assertTrue(description.contains("равнины") || description.contains("plains"),
            "Should mention biome");
        assertTrue(description.contains("дуб") || description.contains("oak"),
            "Should mention oak");
        assertTrue(description.contains("зомби") || description.contains("zombie"),
            "Should mention zombie");
    }

    @Test
    void emptyScanIsHonest() {
        VasyanEnvironmentScanner.SurfaceScan scan = new VasyanEnvironmentScanner.SurfaceScan(
            "minecraft:plains", 6000L, false, false, "nothing interesting",
            List.of(), List.of());

        String description = VasyanEnvironmentScanner.describe(scan);

        assertTrue(description.contains("ничего") || description.contains("nothing"));
    }
}
