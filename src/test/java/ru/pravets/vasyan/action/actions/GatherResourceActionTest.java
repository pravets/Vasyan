package ru.pravets.vasyan.action.actions;

import org.junit.jupiter.api.Test;
import ru.pravets.vasyan.testutil.AbstractMinecraftTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void oreRoutesGetVerticalOnlyStationsAndTreesGetFull() {
        assertEquals(ru.pravets.vasyan.navigation.VasyanPathing.RecoveryPolicy.VERTICAL_ONLY,
            GatherResourceAction.recoveryPolicyFor(true, false),
            "ore routes may only climb/descend to a visible exposed face - never dig/tunnel");
        assertEquals(ru.pravets.vasyan.navigation.VasyanPathing.RecoveryPolicy.FULL,
            GatherResourceAction.recoveryPolicyFor(true, true),
            "tree routes keep full recovery (canopy pillars)");
        assertEquals(ru.pravets.vasyan.navigation.VasyanPathing.RecoveryPolicy.FULL,
            GatherResourceAction.recoveryPolicyFor(false, false),
            "look-out stations keep the full ladder (dig budget caps tunnels)");
    }

    @Test
    void waterGiveUpShouldBeRememberedLocallyOnly() {
        assertTrue(GatherResourceAction.shouldKeepLocalOnly(true, true),
            "a routing give-up while the bot is in water must stay local, "
                + "because the old fish-out teleport was intentionally removed");
        assertFalse(GatherResourceAction.shouldKeepLocalOnly(true, false),
            "a give-up on dry land must still feed global memory as before");
        assertFalse(GatherResourceAction.shouldKeepLocalOnly(false, true),
            "no actual give-up means nothing needs to be skipped at all");
        assertFalse(GatherResourceAction.shouldKeepLocalOnly(false, false));
    }

    @Test
    void treeRoutesGetALargerLeafDigBudget() {
        int leafMax = ru.pravets.vasyan.config.VasyanConfig.GATHER_LEAF_DIG_MAX_DEPTH.get();
        int navMax = ru.pravets.vasyan.config.VasyanConfig.NAV_DIG_THROUGH_MAX.get();
        assertTrue(leafMax > navMax,
            "leaf dig budget must exceed the generic tunnel cap for mangrove canopies");
        assertEquals(leafMax, GatherResourceAction.maxDigDepthFor(true, true),
            "tree mine routes use the leaf-specific dig budget");
        assertEquals(navMax, GatherResourceAction.maxDigDepthFor(true, false),
            "ore routes keep the generic tunnel cap");
        assertEquals(navMax, GatherResourceAction.maxDigDepthFor(false, false),
            "look-out stations keep the generic tunnel cap");
    }
}
