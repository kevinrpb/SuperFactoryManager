package ca.teamdman.sfm.common.net;

import ca.teamdman.sfm.common.Constants;
import ca.teamdman.sfm.common.compat.SFMCompat;
import ca.teamdman.sfm.common.compat.SFMMekanismCompat;
import ca.teamdman.sfm.common.registry.SFMPackets;
import ca.teamdman.sfm.common.registry.SFMResourceTypes;
import ca.teamdman.sfm.common.resourcetype.ResourceType;
import ca.teamdman.sfm.common.util.SFMUtils;
import ca.teamdman.sfml.ast.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

public record ServerboundContainerExportsInspectionRequestPacket(
        int windowId,
        BlockPos pos
) {
    public static void encode(ServerboundContainerExportsInspectionRequestPacket msg, FriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeVarInt(msg.windowId());
        friendlyByteBuf.writeBlockPos(msg.pos());
    }

    public static ServerboundContainerExportsInspectionRequestPacket decode(FriendlyByteBuf friendlyByteBuf) {
        return new ServerboundContainerExportsInspectionRequestPacket(
                friendlyByteBuf.readVarInt(),
                friendlyByteBuf.readBlockPos()
        );
    }

    public static void handle(
            ServerboundContainerExportsInspectionRequestPacket msg,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        SFMPackets.handleServerboundContainerPacket(
                contextSupplier,
                AbstractContainerMenu.class,
                BlockEntity.class,
                msg.pos,
                msg.windowId,
                (menu, blockEntity) -> {
                    assert blockEntity.getLevel() != null;
                    String payload = buildInspectionResults(blockEntity.getLevel(), blockEntity.getBlockPos());
                    var player = contextSupplier.get().getSender();

                    SFMPackets.INSPECTION_CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new ClientboundContainerExportsInspectionResultsPacket(
                                    msg.windowId,
                                    SFMUtils.truncate(
                                            payload,
                                            ClientboundContainerExportsInspectionResultsPacket.MAX_RESULTS_LENGTH
                                    )
                            )
                    );
                }
        );
        contextSupplier.get().setPacketHandled(true);
    }


    public static String buildInspectionResults(Level level, BlockPos pos) {
        StringBuilder sb = new StringBuilder();
        Direction[] dirs = Arrays.copyOf(Direction.values(), Direction.values().length + 1);
        dirs[dirs.length - 1] = null;
        for (Direction direction : dirs) {
            sb.append("-- ").append(direction).append("\n");
            int len = sb.length();
            //noinspection unchecked,rawtypes
            SFMResourceTypes.DEFERRED_TYPES
                    .get()
                    .getEntries()
                    .forEach(entry -> sb.append(buildInspectionResults(
                            (ResourceKey) entry.getKey(),
                            entry.getValue(),
                            level,
                            pos,
                            direction
                    )));
            if (sb.length() == len) {
                sb.append("No exports found");
            }
            sb.append("\n");
        }

        if (SFMCompat.isMekanismLoaded()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                sb.append(SFMMekanismCompat.gatherInspectionResults(be)).append("\n");
            }
        }

        return sb.toString();
    }

    public static <STACK, ITEM, CAP> String buildInspectionResults(
            ResourceKey<ResourceType<STACK, ITEM, CAP>> resourceTypeResourceKey,
            ResourceType<STACK, ITEM, CAP> resourceType,
            Level level,
            BlockPos pos,
            @Nullable
            Direction direction
    ) {
        StringBuilder sb = new StringBuilder();
        SFMUtils
                .discoverCapabilityProvider(level, pos)
                .ifPresent(prov -> prov.getCapability(resourceType.CAPABILITY_KIND, direction).ifPresent(cap -> {
                    int slots = resourceType.getSlots(cap);
                    Int2ObjectMap<STACK> slotContents = new Int2ObjectArrayMap<>(slots);
                    for (int slot = 0; slot < slots; slot++) {
                        STACK stack = resourceType.getStackInSlot(cap, slot);
                        if (!resourceType.isEmpty(stack)) {
                            slotContents.put(slot, stack);
                        }
                    }

                    if (!slotContents.isEmpty()) {
                        slotContents.forEach((slot, stack) -> {
                            InputStatement inputStatement = SFMUtils.getInputStatementForStack(
                                    resourceTypeResourceKey,
                                    resourceType,
                                    stack,
                                    "target",
                                    slot,
                                    false,
                                    direction
                            );
                            sb.append(inputStatement.toStringPretty()).append("\n");
                        });

                        List<ResourceLimit<STACK, ITEM, CAP>> resourceLimitList = new ArrayList<>();
                        slotContents.forEach((slot, stack) -> {
                            ResourceLocation stackId = resourceType.getRegistryKey(stack);
                            ResourceIdentifier<STACK, ITEM, CAP> resourceIdentifier = new ResourceIdentifier<>(
                                    resourceTypeResourceKey.location().getNamespace(),
                                    resourceTypeResourceKey.location().getPath(),
                                    stackId.getNamespace(),
                                    stackId.getPath()
                            );
                            ResourceLimit<STACK, ITEM, CAP> resourceLimit = new ResourceLimit<>(
                                    resourceIdentifier,
                                    Limit.MAX_QUANTITY_NO_RETENTION
                            );
                            resourceLimitList.add(resourceLimit);
                        });
                        InputStatement inputStatement = new InputStatement(
                                new LabelAccess(
                                        List.of(new Label("target")),
                                        new DirectionQualifier(direction == null
                                                               ? EnumSet.noneOf(Direction.class)
                                                               : EnumSet.of(direction)),
                                        NumberRangeSet.MAX_RANGE,
                                        RoundRobin.disabled()
                                ),
                                new ResourceLimits(
                                        resourceLimitList.stream().distinct().toList(),
                                        ResourceIdSet.EMPTY
                                ),
                                false
                        );
                        sb.append(inputStatement.toStringPretty());
                    }
                }));
        String result = sb.toString();
        if (!result.isBlank()) {
            BlockEntity be = level.getBlockEntity(pos);
            //noinspection DataFlowIssue
            if (be != null && direction == null && ForgeRegistries.BLOCK_ENTITY_TYPES
                    .getKey(be.getType())
                    .getNamespace()
                    .equals("mekanism")) {
                return "-- "
                       + Constants.LocalizationKeys.CONTAINER_INSPECTOR_MEKANISM_NULL_DIRECTION_WARNING.getString()
                       + "\n"
                       + result;
            }
        }
        return result;
    }

}
