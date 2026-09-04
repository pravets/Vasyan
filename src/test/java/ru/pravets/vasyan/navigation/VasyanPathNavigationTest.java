package ru.pravets.vasyan.navigation;

import net.minecraft.world.level.pathfinder.Node;
import org.junit.jupiter.api.Test;
import ru.pravets.vasyan.test.McTestBootstrap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VasyanPathNavigationTest {
    @Test
    void exhaustedPathDoesNotExecuteAnEdge() {
        McTestBootstrap.bootstrap();
        Node node = new Node(0, 64, 0);
        VasyanPath path = new VasyanPath(List.of(node), List.of(), node.asBlockPos(), true);
        assertFalse(VasyanPathNavigation.executeNextEdge(null, path));
    }
}
