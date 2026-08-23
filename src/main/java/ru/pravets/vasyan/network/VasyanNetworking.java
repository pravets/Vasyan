package ru.pravets.vasyan.network;

import ru.pravets.vasyan.VasyanMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Simple network channel for client <-> server communication
 * (e.g. the GUI panel requesting a Vasyan's inventory).
 */
public final class VasyanNetworking {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(VasyanMod.MODID, "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    private VasyanNetworking() {}

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++,
            ServerboundRequestInventoryPacket.class,
            ServerboundRequestInventoryPacket::encode,
            ServerboundRequestInventoryPacket::decode,
            VasyanNetworking::handleRequestInventory,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CHANNEL.registerMessage(id++,
            ClientboundInventoryPacket.class,
            ClientboundInventoryPacket::encode,
            ClientboundInventoryPacket::decode,
            VasyanNetworking::handleInventory,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        CHANNEL.registerMessage(id++,
            ServerboundRequestVasyanListPacket.class,
            ServerboundRequestVasyanListPacket::encode,
            ServerboundRequestVasyanListPacket::decode,
            VasyanNetworking::handleRequestVasyanList,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CHANNEL.registerMessage(id++,
            ClientboundVasyanListPacket.class,
            ClientboundVasyanListPacket::encode,
            ClientboundVasyanListPacket::decode,
            VasyanNetworking::handleVasyanList,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        CHANNEL.registerMessage(id++,
            ServerboundVoiceChunkPacket.class,
            ServerboundVoiceChunkPacket::encode,
            ServerboundVoiceChunkPacket::decode,
            VasyanNetworking::handleVoiceChunk,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CHANNEL.registerMessage(id++,
            ClientboundScanDebugPacket.class,
            ClientboundScanDebugPacket::encode,
            ClientboundScanDebugPacket::decode,
            VasyanNetworking::handleScanDebug,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private static void handleVoiceChunk(ServerboundVoiceChunkPacket packet,
                                         Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender != null) {
                ru.pravets.vasyan.voice.VoiceCommandHandler.onChunk(sender, packet.chunk, packet.seq, packet.last);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleRequestVasyanList(ServerboundRequestVasyanListPacket packet,
                                               Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            ClientboundVasyanListPacket response = new ClientboundVasyanListPacket(
                VasyanMod.getVasyanManager().getVasyanNames());
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), response);
        });
        ctx.setPacketHandled(true);
    }

    private static void handleVasyanList(ClientboundVasyanListPacket packet,
                                        Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> ru.pravets.vasyan.client.VasyanGUI.setVasyanList(packet.vasyanNames())));
        ctx.setPacketHandled(true);
    }

    private static void handleRequestInventory(ServerboundRequestInventoryPacket packet,
                                               Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            var vasyan = VasyanMod.getVasyanManager().getVasyan(packet.vasyanName());
            if (vasyan == null) {
                return;
            }
            ClientboundInventoryPacket response =
                ClientboundInventoryPacket.fromInventory(vasyan.getVasyanName(), vasyan.getInventory());
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), response);
        });
        ctx.setPacketHandled(true);
    }

    private static void handleInventory(ClientboundInventoryPacket packet,
                                        Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> ru.pravets.vasyan.client.VasyanGUI.setInventoryView(packet.vasyanName(), packet.stacks())));
        ctx.setPacketHandled(true);
    }

    private static void handleScanDebug(ClientboundScanDebugPacket packet,
                                        Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> ru.pravets.vasyan.client.VasyanScanDebugRenderer.setScan(packet)));
        ctx.setPacketHandled(true);
    }
}
