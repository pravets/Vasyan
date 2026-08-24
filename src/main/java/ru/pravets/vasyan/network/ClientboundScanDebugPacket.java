package ru.pravets.vasyan.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> Client: debug positions rendered after /vasyan look or dump.
 *
 * @param origin         the Vasyan's block position at scan time
 * @param surfaceBlocks  sampled surface blocks (up to scanRadius² × 4)
 * @param visibleBlocks  interesting blocks with line of sight (up to 2112)
 */
public record ClientboundScanDebugPacket(
    BlockPos origin,
    List<BlockPos> surfaceBlocks,
    List<BlockPos> visibleBlocks
) {
    private static final int MAX_SURFACE_BLOCKS = 16_384;
    private static final int MAX_VISIBLE_BLOCKS = 2_112;

    /**
     * Encodes this packet into the network buffer.
     * Order: origin, surfaceBlocks size + entries, visibleBlocks size + entries.
     */
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

    /**
     * Decodes a packet from the network buffer.
     * Validates counts before allocating lists to prevent resource exhaustion.
     */
    public static ClientboundScanDebugPacket decode(FriendlyByteBuf buf) {
        BlockPos origin = buf.readBlockPos();
        int surfaceCount = buf.readVarInt();
        if (surfaceCount < 0 || surfaceCount > MAX_SURFACE_BLOCKS) {
            throw new IllegalArgumentException("Invalid surface block count: " + surfaceCount);
        }
        List<BlockPos> surfaceBlocks = new ArrayList<>(surfaceCount);
        for (int i = 0; i < surfaceCount; i++) {
            surfaceBlocks.add(buf.readBlockPos());
        }
        int visibleCount = buf.readVarInt();
        if (visibleCount < 0 || visibleCount > MAX_VISIBLE_BLOCKS) {
            throw new IllegalArgumentException("Invalid visible block count: " + visibleCount);
        }
        List<BlockPos> visibleBlocks = new ArrayList<>(visibleCount);
        for (int i = 0; i < visibleCount; i++) {
            visibleBlocks.add(buf.readBlockPos());
        }
        return new ClientboundScanDebugPacket(origin, surfaceBlocks, visibleBlocks);
    }
}
