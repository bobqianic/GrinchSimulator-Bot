package com.example;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

final class HeadTargetScanner {
    private HeadTargetScanner() {
    }

    static HeadTargetScan scan(MinecraftClient client, ClientWorld world) {
        LongOpenHashSet found = new LongOpenHashSet();

        double sumX = 0.0;
        double sumY = 0.0;
        double sumZ = 0.0;
        int count = 0;

        ClientChunkManager chunkManager = world.getChunkManager();
        int radius = client.options.getViewDistance().getValue();
        ChunkPos center = new ChunkPos(client.player.getBlockPos());

        for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
            for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
                WorldChunk chunk = chunkManager.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }

                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof SkullBlockEntity skull)) {
                        continue;
                    }

                    BlockState state = blockEntity.getCachedState();
                    if (!state.isOf(Blocks.PLAYER_HEAD)) {
                        continue;
                    }

                    ProfileComponent owner = skull.getOwner();
                    if (owner == null || !owner.name().orElse("").equals("item")) {
                        continue;
                    }

                    BlockPos itemPos = blockEntity.getPos();

                    if (found.add(itemPos.asLong())) {
                        sumX += itemPos.getX() + 0.5;
                        sumY += itemPos.getY() + 0.5;
                        sumZ += itemPos.getZ() + 0.5;
                        count++;
                    }
                }
            }
        }

        ItemCenterMass centerMass = count > 0
                ? new ItemCenterMass(true, sumX / count, sumY / count, sumZ / count, count)
                : ItemCenterMass.EMPTY;

        return new HeadTargetScan(found.toLongArray(), centerMass);
    }
}
