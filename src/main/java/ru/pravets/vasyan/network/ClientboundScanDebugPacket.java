package ru.pravets.vasyan.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> Client: debug positions rendered after /vasyan look or dump.
 */
public record ClientboundScanDebugPacket(
    BlockPos origin,
    List<BlockPos> surfaceBlocks,
    List<BlockPos> visibleBlocks
) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(origin);
        buf.writeVarInt(surfaceBlocks.size());
        for (BlockPos pos : surfaceBlocks) {
            buf.writeBlockPos(pos);
        }
        buf.writeVarInt(visibleBlocks.size());
        for (BlockPos pos : visibleBlocks) {
            buf.writeBlockPos(pos);
        }
    }

    public static ClientboundScanDebugPacket decode(FriendlyByteBuf buf) {
        BlockPos origin = buf.readBlockPos();
        int surfaceCount = buf.readVarInt();
        List<BlockPos> surfaceBlocks = new ArrayList<>(surfaceCount);
        for (int i = 0; i < surfaceCount; i++) {
            surfaceBlocks.add(buf.readBlockPos());
        }
        int visibleCount = buf.readVarInt();
        List<BlockPos> visibleBlocks = new ArrayList<>(visibleCount);
        for (int i = 0; i < visibleCount; i++) {
            visibleBlocks.add(buf.readBlockPos());
        }
        return new ClientboundScanDebugPacket(origin, surfaceBlocks, visibleBlocks);
    }
}
