package ru.pravets.vasyan.client;

import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.network.ClientboundScanDebugPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Renders a short-lived debug overlay for /vasyan look: wireframe boxes around
 * surface blocks (green) and interesting visible blocks (yellow).
 */
@Mod.EventBusSubscriber(modid = VasyanMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class VasyanScanDebugRenderer {

    private static final long DISPLAY_MS = 8_000;
    private static ClientboundScanDebugPacket activeScan = null;
    private static long activeUntil = 0L;

    private VasyanScanDebugRenderer() {}

    public static void setScan(ClientboundScanDebugPacket packet) {
        activeScan = packet;
        activeUntil = System.currentTimeMillis() + DISPLAY_MS;
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (activeScan == null || System.currentTimeMillis() > activeUntil) {
            activeScan = null;
            return;
        }
        render(event.getPoseStack(), activeScan, event.getCamera().getPosition());
    }

    private static void render(PoseStack poseStack, ClientboundScanDebugPacket scan, Vec3 camera) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.lineWidth(2.0F);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        PoseStack.Pose pose = poseStack.last();
        drawBlocks(pose, builder, scan.surfaceBlocks(), 0.0f, 0.8f, 0.2f);   // green
        drawBlocks(pose, builder, scan.visibleBlocks(), 1.0f, 0.9f, 0.0f);  // yellow

        tesselator.end();

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        poseStack.popPose();
    }

    private static void drawBlocks(PoseStack.Pose pose, BufferBuilder builder,
                                   List<BlockPos> blocks, float r, float g, float b) {
        for (BlockPos pos : blocks) {
            drawBox(pose, builder,
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0f, pos.getY() + 1.0f, pos.getZ() + 1.0f,
                r, g, b);
        }
    }

    private static void drawBox(PoseStack.Pose pose, BufferBuilder builder,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float r, float g, float b) {
        // 12 edges of a 1x1x1 block
        addLine(pose, builder, x1, y1, z1, x2, y1, z1, r, g, b);
        addLine(pose, builder, x2, y1, z1, x2, y2, z1, r, g, b);
        addLine(pose, builder, x2, y2, z1, x1, y2, z1, r, g, b);
        addLine(pose, builder, x1, y2, z1, x1, y1, z1, r, g, b);

        addLine(pose, builder, x1, y1, z2, x2, y1, z2, r, g, b);
        addLine(pose, builder, x2, y1, z2, x2, y2, z2, r, g, b);
        addLine(pose, builder, x2, y2, z2, x1, y2, z2, r, g, b);
        addLine(pose, builder, x1, y2, z2, x1, y1, z2, r, g, b);

        addLine(pose, builder, x1, y1, z1, x1, y1, z2, r, g, b);
        addLine(pose, builder, x2, y1, z1, x2, y1, z2, r, g, b);
        addLine(pose, builder, x2, y2, z1, x2, y2, z2, r, g, b);
        addLine(pose, builder, x1, y2, z1, x1, y2, z2, r, g, b);
    }

    private static void addLine(PoseStack.Pose pose, BufferBuilder builder,
                               float x1, float y1, float z1,
                               float x2, float y2, float z2,
                               float r, float g, float b) {
        builder.vertex(pose, x1, y1, z1).color(r, g, b, 1.0f).endVertex();
        builder.vertex(pose, x2, y2, z2).color(r, g, b, 1.0f).endVertex();
    }
}
