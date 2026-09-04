package ru.pravets.vasyan.navigation;

import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.test.McTestBootstrap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Answers.CALLS_REAL_METHODS;

class VasyanPathNavigationTest {
    @Test
    void exhaustedPathDoesNotExecuteAnEdge() {
        McTestBootstrap.bootstrap();
        Node node = new Node(0, 64, 0);
        VasyanPath path = new VasyanPath(List.of(node), List.of(), node.asBlockPos(), true);
        assertFalse(VasyanPathNavigation.executeNextEdge(null, path));
    }

    @Test
    void entityCreatesVasyanPathNavigation() {
        McTestBootstrap.bootstrap();
        VasyanEntity entity = mock(VasyanEntity.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        when(entity.getAttributes()).thenReturn(mock(AttributeMap.class));

        PathNavigation navigation;
        try {
            var method = VasyanEntity.class.getDeclaredMethod("createNavigation", Level.class);
            method.setAccessible(true);
            navigation = (PathNavigation) method.invoke(entity, mock(Level.class));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }

        assertInstanceOf(VasyanPathNavigation.class, navigation);
    }
}
