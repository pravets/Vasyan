package ru.pravets.vasyan.action.actions;

import org.junit.jupiter.api.Test;
import ru.pravets.vasyan.testutil.AbstractMinecraftTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Anti-xray policy for gather target discovery and mining. */
class GatherResourceActionTest extends AbstractMinecraftTest {

    @Test
    void noLineOfSightNearbyScanIsOnlyForLogs() {
        assertFalse(GatherResourceAction.allowsNoLosNearbyScan(false),
            "ores hidden underground must never be discovered through terrain");
        assertTrue(GatherResourceAction.allowsNoLosNearbyScan(true),
            "the no-LOS nearby scan exists only for tree trunks hidden by foliage");
    }

    @Test
    void miningReachRequiresLineOfSight() {
        assertFalse(GatherResourceAction.canMineFrom(4.0, false),
            "a nearby but fully hidden block must not be broken through terrain");
        assertTrue(GatherResourceAction.canMineFrom(4.0, true));
        assertTrue(GatherResourceAction.canMineFrom(25.0, true),
            "the historical five-block reach boundary remains inclusive");
        assertFalse(GatherResourceAction.canMineFrom(26.0, true));
    }
}
