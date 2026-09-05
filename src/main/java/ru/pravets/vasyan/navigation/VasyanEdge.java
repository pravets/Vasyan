package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;

import javax.annotation.Nullable;

/** Immutable metadata for one transition between path nodes. */
public final class VasyanEdge {

    private final Node from;
    private final Node to;
    private final MoveType moveType;
    private final float cost;
    private final BlockPos digFoot;
    private final BlockPos digHead;
    private final BlockPos placePosition;

    public VasyanEdge(Node from, Node to, MoveType moveType, float cost,
                      @Nullable BlockPos digFoot, @Nullable BlockPos digHead,
                      @Nullable BlockPos placePosition) {
        this.from = from;
        this.to = to;
        this.moveType = moveType;
        this.cost = cost;
        this.digFoot = immutable(digFoot);
        this.digHead = immutable(digHead);
        this.placePosition = immutable(placePosition);
    }

    public Node from() { return from; }

    public Node to() { return to; }

    public MoveType moveType() { return moveType; }

    public float cost() { return cost; }

    @Nullable
    public BlockPos digFoot() { return digFoot; }

    @Nullable
    public BlockPos digHead() { return digHead; }

    @Nullable
    public BlockPos placePosition() { return placePosition; }

    @Nullable
    private static BlockPos immutable(@Nullable BlockPos position) {
        return position == null ? null : position.immutable();
    }
}
