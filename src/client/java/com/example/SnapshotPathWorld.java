package com.example;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

final class SnapshotPathWorld implements PathWorldView {
    private final int bottomY;
    private final int height;
    private final Long2ObjectOpenHashMap<SnapshotChunk> chunks;
    private final LongOpenHashSet itemHeads;

    private SnapshotPathWorld(
            int bottomY,
            int height,
            Long2ObjectOpenHashMap<SnapshotChunk> chunks,
            LongOpenHashSet itemHeads
    ) {
        this.bottomY = bottomY;
        this.height = height;
        this.chunks = chunks;
        this.itemHeads = itemHeads;
    }

    static SnapshotPathWorld capture(
            ClientWorld world,
            BlockPos center,
            int chunkRadius,
            long[] itemHeadTargets
    ) {
        Long2ObjectOpenHashMap<SnapshotChunk> chunks = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet itemHeads = new LongOpenHashSet(itemHeadTargets);

        ClientChunkManager chunkManager = world.getChunkManager();
        ChunkPos centerChunk = new ChunkPos(center);

        for (int cx = centerChunk.x - chunkRadius; cx <= centerChunk.x + chunkRadius; cx++) {
            for (int cz = centerChunk.z - chunkRadius; cz <= centerChunk.z + chunkRadius; cz++) {
                WorldChunk chunk = chunkManager.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }

                ChunkSection[] sourceSections = chunk.getSectionArray();
                ChunkSection[] copiedSections = new ChunkSection[sourceSections.length];

                for (int i = 0; i < sourceSections.length; i++) {
                    copiedSections[i] = sourceSections[i].copy();
                }

                chunks.put(ChunkPos.toLong(cx, cz), new SnapshotChunk(copiedSections));
            }
        }

        return new SnapshotPathWorld(world.getBottomY(), world.getHeight(), chunks, itemHeads);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (pos.getY() < bottomY || pos.getY() >= bottomY + height) {
            return Blocks.VOID_AIR.getDefaultState();
        }

        SnapshotChunk chunk = chunks.get(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (chunk == null) {
            return Blocks.VOID_AIR.getDefaultState();
        }

        int sectionIndex = (pos.getY() - bottomY) >> 4;
        if (sectionIndex < 0 || sectionIndex >= chunk.sections.length) {
            return Blocks.VOID_AIR.getDefaultState();
        }

        ChunkSection section = chunk.sections[sectionIndex];
        if (section == null) {
            return Blocks.AIR.getDefaultState();
        }

        return section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        if (pos.getY() < bottomY || pos.getY() >= bottomY + height) {
            return Fluids.EMPTY.getDefaultState();
        }

        SnapshotChunk chunk = chunks.get(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (chunk == null) {
            return Fluids.EMPTY.getDefaultState();
        }

        int sectionIndex = (pos.getY() - bottomY) >> 4;
        if (sectionIndex < 0 || sectionIndex >= chunk.sections.length) {
            return Fluids.EMPTY.getDefaultState();
        }

        ChunkSection section = chunk.sections[sectionIndex];
        if (section == null) {
            return Fluids.EMPTY.getDefaultState();
        }

        return section.getFluidState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override
    public int getBottomY() {
        return bottomY;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        if (pos.getY() < bottomY || pos.getY() >= bottomY + height) {
            return false;
        }

        return chunks.containsKey(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    @Override
    public boolean isItemHeadAt(BlockPos pos) {
        return itemHeads.contains(pos.asLong()) && getBlockState(pos).isOf(Blocks.PLAYER_HEAD);
    }

    private record SnapshotChunk(ChunkSection[] sections) {}
}
