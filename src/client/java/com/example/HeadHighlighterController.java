package com.example;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.chunk.ChunkStatus;

import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

final class HeadHighlighterController {

    public static final String MODID = "supergrinch";

    private static volatile long[] HEAD_POSITIONS_SNAPSHOT = new long[0];
    private static volatile ItemCenterMass ITEM_CENTER_MASS_SNAPSHOT = ItemCenterMass.EMPTY;

    private static final int REFRESH_EVERY_TICKS = 20;
    private static int refreshCountdown = 0;

    private static final int OUTLINE_COLOR = 0xFFFF00FF;

    private static final double GLOW_DISTANCE = 8.0;
    private static final double GLOW_DISTANCE_SQ = GLOW_DISTANCE * GLOW_DISTANCE;


    private static volatile long[] PATH_POSITIONS_SNAPSHOT = new long[0];
    private static volatile long PATH_TARGET_SNAPSHOT = Long.MIN_VALUE;

    static final long NO_POS = Long.MIN_VALUE;

    private static final int PATH_REFRESH_EVERY_TICKS = 5;
    private static int pathRefreshCountdown = 0;

    private static final int MAX_BFS_NODES = 80_000;
    private static final int MAX_BFS_XZ = 96;
    private static final int MAX_BFS_Y = 48;
    private static final int MAX_DROP = 12;

    private static final SearchBounds DEFAULT_SEARCH_BOUNDS = new SearchBounds(MAX_BFS_XZ, MAX_BFS_Y);

    private static final int TWO_STEP_SECOND_MAX_BFS_XZ = 32;
    private static final int TWO_STEP_SECOND_MAX_BFS_Y = 24;
    private static final SearchBounds TWO_STEP_SECOND_SEARCH_BOUNDS =
            new SearchBounds(TWO_STEP_SECOND_MAX_BFS_XZ, TWO_STEP_SECOND_MAX_BFS_Y);
    private static final int TWO_STEP_SECOND_TARGET_RADIUS_XZ = TWO_STEP_SECOND_MAX_BFS_XZ + 8;
    private static final int TWO_STEP_SECOND_TARGET_RADIUS_Y = TWO_STEP_SECOND_MAX_BFS_Y + 8;

    private static final int THREE_STEP_THIRD_MAX_BFS_XZ = 32;
    private static final int THREE_STEP_THIRD_MAX_BFS_Y = 24;
    private static final SearchBounds THREE_STEP_THIRD_SEARCH_BOUNDS =
            new SearchBounds(THREE_STEP_THIRD_MAX_BFS_XZ, THREE_STEP_THIRD_MAX_BFS_Y);
    private static final int THREE_STEP_THIRD_TARGET_RADIUS_XZ = THREE_STEP_THIRD_MAX_BFS_XZ + 6;
    private static final int THREE_STEP_THIRD_TARGET_RADIUS_Y = THREE_STEP_THIRD_MAX_BFS_Y + 6;

    private static final long PATHFIND_HARD_LIMIT_NANOS = 2_000_000_000L;

    private static final int FAILED_TARGET_IGNORE_TICKS = 100;
    private static final Long2IntOpenHashMap FAILED_PATH_TARGETS = new Long2IntOpenHashMap();

    private static final int MAX_TARGET_CANDIDATES = 8;

    private static final int TWO_STEP_FIRST_TARGET_CANDIDATES = 8;
    private static final int TWO_STEP_SECOND_TARGET_CANDIDATES = 8;
    private static final int TWO_STEP_MISSING_SECOND_PENALTY = 1_000_000;

    private static final int THREE_STEP_THIRD_TARGET_CANDIDATES = 8;
    private static final int THREE_STEP_MISSING_THIRD_PENALTY = 500_000;

    private static final int LOCAL_PICKUP_TARGET_CANDIDATES = 6;
    private static final int LOCAL_PICKUP_RADIUS_XZ = 16;
    private static final int LOCAL_PICKUP_RADIUS_Y = 4;
    private static final int LOCAL_PICKUP_MAX_PATH_COST = 160;
    private static final int LOCAL_PICKUP_CLUSTER_RADIUS_XZ = 5;
    private static final int LOCAL_PICKUP_CLUSTER_RADIUS_Y = 3;
    private static final int LOCAL_PICKUP_CLUSTER_COST_TOLERANCE = 35;

    private static final int PATH_REUSE_DISTANCE = 3;
    private static final int TARGET_STICKY_COST_BONUS = 1_000_000;

    private static final int CENTER_MASS_MIN_ITEM_COUNT = 3;
    private static final double CENTER_MASS_AWAY_PENALTY_PER_BLOCK = 6.0;
    private static final int CENTER_MASS_MAX_EXTRA_COST = 350;

    private static final int CLICKED_TARGET_IGNORE_TICKS = 60;
    private static long RECENTLY_CLICKED_TARGET = NO_POS;
    private static int recentlyClickedTargetTicks = 0;
    private static boolean FORCE_PATH_REBUILD = false;

    private static final double REACH_PADDING = 0.25;

    private static final double PLAYER_WIDTH = 0.6;
    private static final double PLAYER_HALF_WIDTH = PLAYER_WIDTH * 0.5;
    private static final double PLAYER_HEIGHT = 1.8;

    private static final double STEP_HEIGHT = 0.6;
    private static final double JUMP_HEIGHT = 1.25;

    private static final double PATH_REUSE_VERTICAL_TOLERANCE = JUMP_HEIGHT + 0.15;
    private static final double AUTO_PATH_VERTICAL_TOLERANCE = JUMP_HEIGHT + 0.15;
    private static final double AUTO_VERTICAL_SEGMENT_SCORE_WEIGHT = 0.25;
    private static final double AUTO_LOOKAHEAD_DISTANCE = 1.15;
    private static int AUTO_SIDE_CORRECTION_DIR = 0; // -1 = left, 1 = right
    private static int AUTO_SIDE_CORRECTION_SEGMENT = -1;
    private static final double AUTO_DROP_LOOKAHEAD_DISTANCE = 0.80;
    private static final double AUTO_DROP_REUSE_HORIZONTAL_DISTANCE = 1.35;
    private static final double AUTO_DROP_REUSE_HORIZONTAL_DISTANCE_SQ =
            AUTO_DROP_REUSE_HORIZONTAL_DISTANCE * AUTO_DROP_REUSE_HORIZONTAL_DISTANCE;
    private static final double AUTO_DROP_VERTICAL_PADDING = 0.75;
    private static final double PATH_SIDE_CORRECTION_ENGAGE = 0.28;
    private static final double PATH_SIDE_CORRECTION_RELEASE = 0.10;
    private static final double PATH_SIDE_CORRECTION_SWITCH = 0.42;
    private static final double PATH_SMOOTH_MAX_DOWN_STEP = 0.05;

    private static final double HEIGHT_CHANGE_EPSILON = 0.05;
    private static final double JUMP_HEADROOM_RISE = 0.42;
    private static final double JUMP_RISE_EPSILON = 0.08;
    private static final double JUMP_TRIGGER_DISTANCE_SQ = 0.85 * 0.85;
    private static final double COLLISION_EPSILON = 1.0E-5;

    private static final int MOVE_COST_FLAT = 10;
    private static final int MOVE_COST_STEP_UP = 12;
    private static final int MOVE_COST_DROP = 12;
    private static final int MOVE_COST_JUMP_UP = 96;
    private static final int WATER_WALK_PENALTY = 96;

    private static final int TRAPDOOR_MOVE_SWEEP_SAMPLES = 6;
    private static final int MOVE_SWEEP_SAMPLES = 6;
    private static final int OPEN_DOOR_CLOSE_SWEEP_SAMPLES = 6;

    private static final int PATH_SMOOTH_MAX_LOOKAHEAD = 28;
    private static final int PATH_SMOOTH_MAX_SAMPLES = 96;
    private static final double PATH_SMOOTH_SAMPLE_SPACING = 0.35;
    private static final double PATH_SMOOTH_FEET_Y_TOLERANCE = 0.18;
    private static final double PATH_SMOOTH_MAX_HEIGHT_DELTA = STEP_HEIGHT + 0.05;
    private static final double PATH_SMOOTH_DOOR_SCAN_PADDING = 0.08;
    private static final double PATH_SMOOTH_Y_FLOOR_EPSILON = 1.0E-4;

    // Wall-safety tuning. These keep smoothed/auto paths a little farther from walls,
    // especially around corners, without banning narrow corridors completely.
    private static final double PATH_SMOOTH_EXTRA_WALL_CLEARANCE = 0.10;
    private static final double PATH_NODE_WALL_PROBE_CLEARANCE = 0.22;
    private static final int PATH_NODE_WALL_PENALTY = 8;
    private static final int PATH_DIAGONAL_WALL_PENALTY = 8;
    private static final int PATH_TURN_WALL_PENALTY = 28;
    private static final double AUTO_WALL_AVOIDANCE_OFFSET = 0.11;
    private static final double AUTO_WALL_AVOIDANCE_PROBE = 0.22;
    private static final double AUTO_CORNER_LOOKAHEAD_DISTANCE = 0.55;
    private static final double AUTO_CORNER_LOOKAHEAD_DISTANCE_SQ =
            AUTO_CORNER_LOOKAHEAD_DISTANCE * AUTO_CORNER_LOOKAHEAD_DISTANCE;

    private static final int[][] WALK_OFFSETS = {
            { 1,  0},
            {-1,  0},
            { 0,  1},
            { 0, -1},
            { 1,  1},
            { 1, -1},
            {-1,  1},
            {-1, -1}
    };

    static final double UNKNOWN_FEET_Y = -1.0E30;

    private static final int PATH_COLOR = 0xFF00FFFF;
    private static final int PATH_TARGET_COLOR = 0xFFFFFF00;

    private static KeyBinding AUTO_KEY;

    private static boolean AUTO_ENABLED = false;
    private static boolean AUTO_WAS_DRIVING = false;

    private static final int AUTO_CLICK_COOLDOWN_TICKS = 8;
    private static int autoClickCooldown = 0;

    private static int AUTO_WAYPOINT_INDEX = 1;
    private static long AUTO_LAST_TARGET = NO_POS;
    private static long AUTO_LAST_PATH_END = NO_POS;
    private static long[] AUTO_LAST_PATH_REF = null;

    private static final double WAYPOINT_ADVANCE_DISTANCE_SQ = 0.55 * 0.55;
    private static final double WAYPOINT_PASS_DISTANCE_SQ = 0.95 * 0.95;

    private static final float AUTO_MOVE_YAW_TOLERANCE = 18.0f;
    private static final float AUTO_MAX_YAW_CHANGE_PER_TICK = 60.0f;

    private static final double AUTO_REALIGN_DISTANCE = 1.35;
    private static final double AUTO_REALIGN_DISTANCE_SQ = AUTO_REALIGN_DISTANCE * AUTO_REALIGN_DISTANCE;
    private static final double AUTO_REALIGN_IMPROVEMENT_SQ = 0.35 * 0.35;
    private static final double AUTO_SEGMENT_ADVANCE_T = 0.72;

    private static final ExecutorService PATH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "supergrinch-pathfinder");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicLong PATH_REQUEST_COUNTER = new AtomicLong();

    private static volatile boolean PATH_SEARCH_RUNNING = false;
    private static volatile long LATEST_PATH_REQUEST_ID = 0L;
    private static volatile AsyncPathResult PENDING_PATH_RESULT = null;

    private static final int PATH_SNAPSHOT_RADIUS_CHUNKS = 8;
    private static final ThreadLocal<LongOpenHashSet> PATH_THREAD_IGNORES = new ThreadLocal<>();

    private record PathResult(long[] path, long target) {}

    private record ScoredPath(long[] path, long target, int cost) {}

    private record LocalPickupChoice(ScoredPath path, int nearbyItemCount, double directDistanceSq) {}

    private record AStarNode(long packed, int g, int h, int f) {}

    private record SearchBounds(int maxXz, int maxY) {}

    private record AsyncPathResult(long requestId, long[] path, long target) {}

    private record PathJob(
            long requestId,
            PathWorldView world,
            BlockPos start,
            long[] targetsSnapshot,
            ItemCenterMass centerMass,
            double reach,
            double eyeHeight,
            long stickyTarget,
            LongOpenHashSet ignoredTargets
    ) {}


    static void initialize() {
        registerKeyBindings();
        registerHud();
        registerClientTick();
        registerWorldRenderer();
    }

    private static void registerKeyBindings() {
        AUTO_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.supergrinch.auto",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.supergrinch"
        ));
    }

    private static void registerHud() {
        HudElementRegistry.addLast(Identifier.of(MODID, "auto_hud"), (context, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();

            if (client == null || client.options == null || client.options.hudHidden) {
                return;
            }

            String autoText = AUTO_ENABLED ? "AUTO: ON" : "AUTO: OFF";
            int autoColor = AUTO_ENABLED ? 0xFF55FF55 : 0xFFFF5555;

            context.drawTextWithShadow(client.textRenderer, autoText, 10, 10, autoColor);
            context.drawTextWithShadow(client.textRenderer, PATH_TARGET_SNAPSHOT != NO_POS ? "Target: found" : "Target: none", 10, 22, 0xFFFFFFFF);
            context.drawTextWithShadow(client.textRenderer, "Path: " + PATH_POSITIONS_SNAPSHOT.length + " nodes", 10, 34, 0xFFFFFFFF);
            context.drawTextWithShadow(client.textRenderer, PATH_SEARCH_RUNNING ? "Worker: pathing" : "Worker: idle", 10, 46, 0xFFFFFFFF);
        });
    }

    private static void registerClientTick() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null) {
                return;
            }

            publishPendingPathResult(client);
            tickHeadCache(client);
            tickTargetIgnoreTimers();
            tickPathRebuild(client);
            tickAutoToggle(client);

            if (AUTO_ENABLED) {
                tickAutoMode(client, client.world);
            }
        });
    }

    private static void tickHeadCache(MinecraftClient client) {
        if (refreshCountdown-- > 0) {
            return;
        }

        refreshCountdown = REFRESH_EVERY_TICKS;
        rebuildCache(client, client.world);
    }

    private static void tickTargetIgnoreTimers() {
        if (recentlyClickedTargetTicks > 0 && --recentlyClickedTargetTicks <= 0) {
            RECENTLY_CLICKED_TARGET = NO_POS;
        }

        tickFailedPathTargets();
    }

    private static void tickPathRebuild(MinecraftClient client) {
        boolean forcePathRebuild = FORCE_PATH_REBUILD;

        if (!forcePathRebuild && pathRefreshCountdown-- > 0) {
            return;
        }

        pathRefreshCountdown = PATH_REFRESH_EVERY_TICKS;
        FORCE_PATH_REBUILD = false;

        if (!forcePathRebuild
                && AUTO_ENABLED
                && PATH_TARGET_SNAPSHOT != NO_POS
                && isPathTargetStillValid(client.world, PATH_TARGET_SNAPSHOT)
                && isAutoInDropTransition(client, client.world, PATH_POSITIONS_SNAPSHOT)) {
            return;
        }

        if (forcePathRebuild || !shouldReuseCurrentPath(client, client.world)) {
            requestAsyncPathRebuild(client, client.world, HEAD_POSITIONS_SNAPSHOT);
        }
    }

    private static void tickAutoToggle(MinecraftClient client) {
        while (AUTO_KEY.wasPressed()) {
            AUTO_ENABLED = !AUTO_ENABLED;

            if (!AUTO_ENABLED) {
                releaseAutoMovement(client);
            }
        }
    }

    private static void registerWorldRenderer() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            ClientWorld world = (ClientWorld) context.world();
            if (world == null) {
                return;
            }

            long[] heads = HEAD_POSITIONS_SNAPSHOT;
            long[] path = PATH_POSITIONS_SNAPSHOT;

            if (heads.length == 0 && path.length == 0) {
                return;
            }

            Vec3d cameraPos = context.camera().getPos();
            MatrixStack matrices = context.matrixStack();

            renderGlowingHeads(world, matrices, context.consumers().getBuffer(XrayRenderLayers.glow()), cameraPos, heads);

            VertexConsumer lines = context.consumers().getBuffer(XrayRenderLayers.lines());

            if (path.length > 0) {
                drawPath(world, matrices, lines, cameraPos, path, PATH_TARGET_SNAPSHOT);
            }

            renderHeadOutlines(world, matrices, lines, cameraPos, heads);
        });
    }

    private static void renderGlowingHeads(
            ClientWorld world,
            MatrixStack matrices,
            VertexConsumer glow,
            Vec3d cameraPos,
            long[] heads
    ) {
        for (long packedPos : heads) {
            BlockPos pos = BlockPos.fromLong(packedPos);
            BlockState state = world.getBlockState(pos);

            if (!isPlayerHeadBlock(state)) {
                continue;
            }

            if (cameraPos.squaredDistanceTo(Vec3d.ofCenter(pos)) > GLOW_DISTANCE_SQ) {
                continue;
            }

            VoxelShape shape = state.getOutlineShape(world, pos);
            if (shape == null || shape.isEmpty()) {
                continue;
            }

            Box box = shape.getBoundingBox();
            drawGlowBox(matrices, glow, pos, cameraPos, box, 0.06, 0.06f);
            drawGlowBox(matrices, glow, pos, cameraPos, box, 0.02, 0.14f);
        }
    }

    private static void renderHeadOutlines(
            ClientWorld world,
            MatrixStack matrices,
            VertexConsumer lines,
            Vec3d cameraPos,
            long[] heads
    ) {
        for (long packedPos : heads) {
            BlockPos pos = BlockPos.fromLong(packedPos);
            BlockState state = world.getBlockState(pos);

            if (!isPlayerHeadBlock(state)) {
                continue;
            }

            VoxelShape shape = state.getOutlineShape(world, pos);
            if (shape == null || shape.isEmpty()) {
                continue;
            }

            VertexRendering.drawOutline(
                    matrices,
                    lines,
                    shape,
                    pos.getX() - cameraPos.x,
                    pos.getY() - cameraPos.y,
                    pos.getZ() - cameraPos.z,
                    OUTLINE_COLOR
            );
        }
    }

    private static void drawGlowBox(
            MatrixStack matrices,
            VertexConsumer quads,
            BlockPos pos,
            Vec3d camPos,
            Box localBox,
            double expand,
            float alpha
    ) {
        double minX = pos.getX() + localBox.minX - camPos.x - expand;
        double minY = pos.getY() + localBox.minY - camPos.y - expand;
        double minZ = pos.getZ() + localBox.minZ - camPos.z - expand;

        double maxX = pos.getX() + localBox.maxX - camPos.x + expand;
        double maxY = pos.getY() + localBox.maxY - camPos.y + expand;
        double maxZ = pos.getZ() + localBox.maxZ - camPos.z + expand;

        VertexRendering.drawFilledBox(
                matrices,
                quads,
                minX, minY, minZ,
                maxX, maxY, maxZ,
                1.0f, 0.647f, 0.0f,
                alpha
        );
    }

    private static boolean isPlayerHeadBlock(BlockState state) {
        return state.isOf(Blocks.PLAYER_HEAD);
    }

    private static void rebuildCache(MinecraftClient client, ClientWorld world) {
        HeadTargetScan scan = HeadTargetScanner.scan(client, world);
        HEAD_POSITIONS_SNAPSHOT = scan.positions();
        ITEM_CENTER_MASS_SNAPSHOT = scan.centerMass();
    }

    private static void requestAsyncPathRebuild(
            MinecraftClient client,
            ClientWorld world,
            long[] targetsSnapshot
    ) {
        if (PATH_SEARCH_RUNNING) {
            return;
        }

        if (client.player == null || targetsSnapshot.length == 0) {
            clearPathSnapshot();
            return;
        }

        LivePathWorld liveWorld = new LivePathWorld(world);
        BlockPos start = resolvePathStart(liveWorld, client.player.getPos(), client.player.getBlockPos());

        if (start == null) {
            clearPathSnapshot();
            return;
        }

        LongOpenHashSet ignoredTargets = snapshotIgnoredTargets();
        long[] filteredTargets = filterIgnoredTargets(targetsSnapshot, ignoredTargets);

        if (filteredTargets.length == 0) {
            clearPathSnapshot();
            return;
        }

        long stickyTarget = PATH_TARGET_SNAPSHOT;
        if (stickyTarget != NO_POS && ignoredTargets.contains(stickyTarget)) {
            stickyTarget = NO_POS;
        }

        SnapshotPathWorld snapshot = SnapshotPathWorld.capture(
                world,
                start,
                Math.min(client.options.getViewDistance().getValue(), PATH_SNAPSHOT_RADIUS_CHUNKS),
                filteredTargets
        );

        long requestId = PATH_REQUEST_COUNTER.incrementAndGet();
        LATEST_PATH_REQUEST_ID = requestId;
        PATH_SEARCH_RUNNING = true;

        PathJob job = new PathJob(
                requestId,
                snapshot,
                start,
                filteredTargets,
                ITEM_CENTER_MASS_SNAPSHOT,
                client.player.getBlockInteractionRange() + REACH_PADDING,
                client.player.getEyeY() - client.player.getY(),
                stickyTarget,
                ignoredTargets
        );

        PATH_EXECUTOR.execute(() -> {
            PATH_THREAD_IGNORES.set(job.ignoredTargets());

            try {
                PathResult result = rebuildPathToClosestItem(job);
                PENDING_PATH_RESULT = new AsyncPathResult(job.requestId(), result.path(), result.target());
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            } finally {
                PATH_THREAD_IGNORES.remove();
                PATH_SEARCH_RUNNING = false;
            }
        });
    }

    private static void clearPathSnapshot() {
        PATH_POSITIONS_SNAPSHOT = new long[0];
        PATH_TARGET_SNAPSHOT = NO_POS;
    }

    private static void publishPendingPathResult(MinecraftClient client) {
        AsyncPathResult result = PENDING_PATH_RESULT;
        if (result == null) {
            return;
        }

        PENDING_PATH_RESULT = null;

        if (result.requestId() != LATEST_PATH_REQUEST_ID || client.world == null) {
            return;
        }

        if (result.target() != NO_POS && !isPathTargetStillValid(client.world, result.target())) {
            return;
        }

        PATH_POSITIONS_SNAPSHOT = result.path();
        PATH_TARGET_SNAPSHOT = result.target();
    }

    private static LongOpenHashSet snapshotIgnoredTargets() {
        LongOpenHashSet ignored = new LongOpenHashSet();

        if (RECENTLY_CLICKED_TARGET != NO_POS) {
            ignored.add(RECENTLY_CLICKED_TARGET);
        }

        ignored.addAll(FAILED_PATH_TARGETS.keySet());
        return ignored;
    }

    private static long[] filterIgnoredTargets(long[] targets, LongOpenHashSet ignored) {
        LongArrayList filtered = new LongArrayList();

        for (long target : targets) {
            if (target != NO_POS && !ignored.contains(target)) {
                filtered.add(target);
            }
        }

        return filtered.toLongArray();
    }

    private static void tickFailedPathTargets() {
        if (FAILED_PATH_TARGETS.isEmpty()) {
            return;
        }

        long[] keys = FAILED_PATH_TARGETS.keySet().toLongArray();

        for (long key : keys) {
            int ticks = FAILED_PATH_TARGETS.get(key) - 1;

            if (ticks <= 0) {
                FAILED_PATH_TARGETS.remove(key);
            } else {
                FAILED_PATH_TARGETS.put(key, ticks);
            }
        }
    }

    private static boolean isTemporarilyIgnoredTarget(long targetPacked) {
        if (targetPacked == NO_POS) {
            return true;
        }

        LongOpenHashSet workerIgnores = PATH_THREAD_IGNORES.get();
        if (workerIgnores != null) {
            return workerIgnores.contains(targetPacked);
        }

        return targetPacked == RECENTLY_CLICKED_TARGET || FAILED_PATH_TARGETS.containsKey(targetPacked);
    }

    private static void discardFailedTarget(long targetPacked) {
        if (targetPacked == NO_POS || PATH_THREAD_IGNORES.get() != null) {
            return;
        }

        FAILED_PATH_TARGETS.put(targetPacked, FAILED_TARGET_IGNORE_TICKS);

        if (targetPacked == PATH_TARGET_SNAPSHOT) {
            clearPathSnapshot();
        }

        if (targetPacked == AUTO_LAST_TARGET) {
            resetAutoPathState();
        }
    }

    private static void discardFailedTargets(long[] targets) {
        for (long target : targets) {
            discardFailedTarget(target);
        }
    }

    private static void resetAutoPathState() {
        AUTO_LAST_TARGET = NO_POS;
        AUTO_LAST_PATH_END = NO_POS;
        AUTO_LAST_PATH_REF = null;
        AUTO_WAYPOINT_INDEX = 1;
    }

    private static PathResult rebuildPathToClosestItem(PathJob job) {
        PathWorldView world = job.world();
        long[] targetsSnapshot = job.targetsSnapshot();

        if (targetsSnapshot.length == 0) {
            return new PathResult(new long[0], NO_POS);
        }

        PathSearchBudget budget = new PathSearchBudget(PATHFIND_HARD_LIMIT_NANOS);
        BlockPos start = job.start();

        if (start == null || budget.expired()) {
            return new PathResult(new long[0], NO_POS);
        }

        ItemCenterMass centerMass = job.centerMass();
        double reach = job.reach();
        double eyeHeight = job.eyeHeight();

        long stickyTarget = job.stickyTarget();
        if (stickyTarget != NO_POS && !isPathTargetStillValid(world, stickyTarget)) {
            stickyTarget = NO_POS;
        }

        ScoredPath localPickup = findLocalPickupPath(world, start, targetsSnapshot, reach, eyeHeight, budget);
        if (localPickup != null) {
            return new PathResult(localPickup.path(), localPickup.target());
        }

        long[] firstCandidates = getClosestTargetsByDistance(start, targetsSnapshot, TWO_STEP_FIRST_TARGET_CANDIDATES);
        firstCandidates = includeStickyTargetCandidate(start, targetsSnapshot, firstCandidates, stickyTarget, TWO_STEP_FIRST_TARGET_CANDIDATES);

        if (firstCandidates.length == 0) {
            return new PathResult(new long[0], NO_POS);
        }

        ScoredPath bestFirst = null;
        int bestTotalCost = Integer.MAX_VALUE;
        int bestFirstCost = Integer.MAX_VALUE;

        for (long firstTarget : firstCandidates) {
            if (budget.expired()) {
                discardFailedTarget(firstTarget);
                break;
            }

            ScoredPath firstPath = findScoredPathToAnyTarget(world, start, new long[]{firstTarget}, reach, eyeHeight, budget);

            if (firstPath == null || firstPath.path().length == 0) {
                discardFailedTarget(firstTarget);
                continue;
            }

            BlockPos firstEnd = BlockPos.fromLong(firstPath.path()[firstPath.path().length - 1]);

            long[] secondCandidates = getClosestTargetsByDistance(
                    firstEnd,
                    targetsSnapshot,
                    TWO_STEP_SECOND_TARGET_CANDIDATES,
                    firstTarget,
                    TWO_STEP_SECOND_TARGET_RADIUS_XZ,
                    TWO_STEP_SECOND_TARGET_RADIUS_Y
            );

            int futureCost = 0;
            int firstCenterPenalty = centerMassAwayPenalty(firstPath.path(), centerMass);

            if (secondCandidates.length > 0 && !budget.expired()) {
                ScoredPath secondPath = findScoredPathToAnyTarget(
                        world,
                        firstEnd,
                        secondCandidates,
                        reach,
                        eyeHeight,
                        TWO_STEP_SECOND_SEARCH_BOUNDS,
                        budget
                );

                if (secondPath != null && secondPath.path().length > 0) {
                    futureCost += secondPath.cost();
                    futureCost += centerMassAwayPenalty(secondPath.path(), centerMass);

                    BlockPos secondEnd = BlockPos.fromLong(secondPath.path()[secondPath.path().length - 1]);

                    long[] thirdCandidates = getClosestTargetsByDistance(
                            secondEnd,
                            targetsSnapshot,
                            THREE_STEP_THIRD_TARGET_CANDIDATES,
                            new long[]{firstTarget, secondPath.target()},
                            THREE_STEP_THIRD_TARGET_RADIUS_XZ,
                            THREE_STEP_THIRD_TARGET_RADIUS_Y
                    );

                    if (thirdCandidates.length > 0 && !budget.expired()) {
                        ScoredPath thirdPath = findScoredPathToAnyTarget(
                                world,
                                secondEnd,
                                thirdCandidates,
                                reach,
                                eyeHeight,
                                THREE_STEP_THIRD_SEARCH_BOUNDS,
                                budget
                        );

                        if (thirdPath != null && thirdPath.path().length > 0) {
                            futureCost += thirdPath.cost();
                            futureCost += centerMassAwayPenalty(thirdPath.path(), centerMass);
                        } else {
                            futureCost += THREE_STEP_MISSING_THIRD_PENALTY;

                            if (!budget.expired()) {
                                discardFailedTargets(thirdCandidates);
                            }
                        }
                    }
                } else {
                    futureCost += TWO_STEP_MISSING_SECOND_PENALTY;

                    if (!budget.expired()) {
                        discardFailedTargets(secondCandidates);
                    }
                }
            }

            int totalCost = firstPath.cost() + firstCenterPenalty + futureCost;

            if (bestFirst == null || isBetterTargetChoice(
                    firstPath,
                    totalCost,
                    bestFirst,
                    bestTotalCost,
                    firstPath.cost(),
                    bestFirstCost,
                    stickyTarget
            )) {
                bestTotalCost = totalCost;
                bestFirstCost = firstPath.cost();
                bestFirst = firstPath;
            }
        }

        if (bestFirst == null) {
            if (budget.expired()) {
                return new PathResult(new long[0], NO_POS);
            }

            long[] fallbackCandidates = getClosestTargetsByDistance(start, targetsSnapshot, MAX_TARGET_CANDIDATES);
            fallbackCandidates = includeStickyTargetCandidate(start, targetsSnapshot, fallbackCandidates, stickyTarget, MAX_TARGET_CANDIDATES);

            ScoredPath fallback = findScoredPathToAnyTarget(world, start, fallbackCandidates, reach, eyeHeight, budget);

            if (fallback == null) {
                discardFailedTargets(fallbackCandidates);
                return new PathResult(new long[0], NO_POS);
            }

            return new PathResult(fallback.path(), fallback.target());
        }

        return new PathResult(bestFirst.path(), bestFirst.target());
    }

    private static ScoredPath findLocalPickupPath(
            PathWorldView world,
            BlockPos start,
            long[] targetsSnapshot,
            double reach,
            double eyeHeight,
            PathSearchBudget budget
    ) {
        long[] localCandidates = getClosestTargetsByDistance(
                start,
                targetsSnapshot,
                LOCAL_PICKUP_TARGET_CANDIDATES,
                NO_POS,
                LOCAL_PICKUP_RADIUS_XZ,
                LOCAL_PICKUP_RADIUS_Y
        );

        if (localCandidates.length == 0 || budget.expired()) {
            return null;
        }

        LocalPickupChoice best = null;

        for (long target : localCandidates) {
            if (budget.expired()) {
                return null;
            }

            ScoredPath path = findScoredPathToAnyTarget(world, start, new long[]{target}, reach, eyeHeight, budget);

            if (path == null || path.path().length == 0) {
                discardFailedTarget(target);
                continue;
            }

            if (path.cost() > LOCAL_PICKUP_MAX_PATH_COST) {
                continue;
            }

            int nearbyItemCount = countNearbyTargets(
                    path.target(),
                    targetsSnapshot,
                    LOCAL_PICKUP_CLUSTER_RADIUS_XZ,
                    LOCAL_PICKUP_CLUSTER_RADIUS_Y
            );

            LocalPickupChoice choice = new LocalPickupChoice(path, nearbyItemCount, squaredBlockDistance(start, path.target()));

            if (best == null || isBetterLocalPickupChoice(choice, best)) {
                best = choice;
            }
        }

        return best == null ? null : best.path();
    }

    private static boolean isBetterLocalPickupChoice(LocalPickupChoice candidate, LocalPickupChoice currentBest) {
        int candidateCost = candidate.path().cost();
        int bestCost = currentBest.path().cost();

        if (candidate.nearbyItemCount() != currentBest.nearbyItemCount()) {
            if (candidate.nearbyItemCount() > currentBest.nearbyItemCount()
                    && candidateCost <= bestCost + LOCAL_PICKUP_CLUSTER_COST_TOLERANCE) {
                return true;
            }

            if (candidate.nearbyItemCount() < currentBest.nearbyItemCount()
                    && bestCost <= candidateCost + LOCAL_PICKUP_CLUSTER_COST_TOLERANCE) {
                return false;
            }
        }

        if (candidateCost != bestCost) {
            return candidateCost < bestCost;
        }

        int distanceCompare = Double.compare(candidate.directDistanceSq(), currentBest.directDistanceSq());
        if (distanceCompare != 0) {
            return distanceCompare < 0;
        }

        return Long.compare(candidate.path().target(), currentBest.path().target()) < 0;
    }

    private static int countNearbyTargets(long centerTargetPacked, long[] targetsSnapshot, int radiusXZ, int radiusY) {
        if (centerTargetPacked == NO_POS) {
            return 0;
        }

        BlockPos center = BlockPos.fromLong(centerTargetPacked);
        int count = 0;

        for (long packed : targetsSnapshot) {
            if (packed == NO_POS || isTemporarilyIgnoredTarget(packed)) {
                continue;
            }

            BlockPos target = BlockPos.fromLong(packed);

            int dx = Math.abs(target.getX() - center.getX());
            int dy = Math.abs(target.getY() - center.getY());
            int dz = Math.abs(target.getZ() - center.getZ());

            if (dx <= radiusXZ && dz <= radiusXZ && dy <= radiusY) {
                count++;
            }
        }

        return count;
    }

    private static double squaredBlockDistance(BlockPos from, long targetPacked) {
        BlockPos target = BlockPos.fromLong(targetPacked);

        int dx = target.getX() - from.getX();
        int dy = target.getY() - from.getY();
        int dz = target.getZ() - from.getZ();

        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isBetterTargetChoice(
            ScoredPath candidate,
            int candidateTotalCost,
            ScoredPath currentBest,
            int currentBestTotalCost,
            int candidateFirstCost,
            int currentBestFirstCost,
            long stickyTarget
    ) {
        int candidateRankCost = stickyAdjustedCost(candidateTotalCost, candidate.target(), stickyTarget);
        int bestRankCost = stickyAdjustedCost(currentBestTotalCost, currentBest.target(), stickyTarget);

        if (candidateRankCost != bestRankCost) {
            return candidateRankCost < bestRankCost;
        }

        boolean candidateIsSticky = candidate.target() == stickyTarget;
        boolean bestIsSticky = currentBest.target() == stickyTarget;

        if (candidateIsSticky != bestIsSticky) {
            return candidateIsSticky;
        }

        if (candidateFirstCost != currentBestFirstCost) {
            return candidateFirstCost < currentBestFirstCost;
        }

        return Long.compare(candidate.target(), currentBest.target()) < 0;
    }

    private static int stickyAdjustedCost(int cost, long target, long stickyTarget) {
        return stickyTarget != NO_POS && target == stickyTarget
                ? cost - TARGET_STICKY_COST_BONUS
                : cost;
    }

    private static long[] includeStickyTargetCandidate(
            BlockPos start,
            long[] allTargets,
            long[] candidates,
            long stickyTarget,
            int maxTargets
    ) {
        if (stickyTarget == NO_POS || isTemporarilyIgnoredTarget(stickyTarget)) {
            return candidates;
        }

        boolean exists = false;
        for (long target : allTargets) {
            if (target == stickyTarget) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            return candidates;
        }

        for (long candidate : candidates) {
            if (candidate == stickyTarget) {
                return candidates;
            }
        }

        BlockPos target = BlockPos.fromLong(stickyTarget);

        int dx = Math.abs(target.getX() - start.getX());
        int dy = Math.abs(target.getY() - start.getY());
        int dz = Math.abs(target.getZ() - start.getZ());

        if (dx > MAX_BFS_XZ + 8 || dz > MAX_BFS_XZ + 8 || dy > MAX_BFS_Y + 8) {
            return candidates;
        }

        LongArrayList result = new LongArrayList();
        result.add(stickyTarget);

        for (long candidate : candidates) {
            if (candidate == stickyTarget) {
                continue;
            }

            if (result.size() >= maxTargets) {
                break;
            }

            result.add(candidate);
        }

        return result.toLongArray();
    }

    private static ScoredPath findScoredPathToAnyTarget(
            PathWorldView world,
            BlockPos start,
            long[] targetCandidates,
            double reach,
            double eyeHeight,
            PathSearchBudget budget
    ) {
        return findScoredPathToAnyTarget(world, start, targetCandidates, reach, eyeHeight, DEFAULT_SEARCH_BOUNDS, budget);
    }

    private static ScoredPath findScoredPathToAnyTarget(
            PathWorldView world,
            BlockPos start,
            long[] targetCandidates,
            double reach,
            double eyeHeight,
            SearchBounds bounds,
            PathSearchBudget budget
    ) {
        if (targetCandidates.length == 0 || budget.expired()) {
            return null;
        }

        PathSearchCache cache = new PathSearchCache();

        Long2LongOpenHashMap reachableGoals = buildReachableGoals(
                world,
                start,
                targetCandidates,
                reach,
                eyeHeight,
                cache,
                bounds,
                budget
        );

        if (budget.expired() || reachableGoals == null || reachableGoals.isEmpty()) {
            return null;
        }

        PathResult result = findPathAStar(world, start, reachableGoals, targetCandidates, cache, bounds, budget);

        if (budget.expired() || result.path().length == 0 || result.target() == NO_POS) {
            return null;
        }

        long[] rawPath = result.path();
        int cost = pathCost(world, rawPath, cache);
        long[] smoothedPath = smoothPath(world, rawPath, cache, budget);

        return new ScoredPath(smoothedPath, result.target(), cost);
    }

    private static int pathCost(PathWorldView world, long[] path, PathSearchCache cache) {
        if (path.length <= 1) {
            return 0;
        }

        int total = 0;

        for (int i = 0; i < path.length - 1; i++) {
            BlockPos from = BlockPos.fromLong(path[i]);
            BlockPos to = BlockPos.fromLong(path[i + 1]);

            total += movementCost(world, from, to, cache);

            if (i > 0) {
                BlockPos previous = BlockPos.fromLong(path[i - 1]);
                total += turnWallPenalty(world, previous, from, to, cache);
            }
        }

        return total;
    }

    private static int centerMassAwayPenalty(long[] path, ItemCenterMass centerMass) {
        if (path == null
                || path.length <= 1
                || centerMass == null
                || !centerMass.valid()
                || centerMass.count() < CENTER_MASS_MIN_ITEM_COUNT) {
            return 0;
        }

        double previousDistance = horizontalDistanceToCenterMass(BlockPos.fromLong(path[0]), centerMass);
        double rawPenalty = 0.0;

        for (int i = 1; i < path.length; i++) {
            BlockPos pos = BlockPos.fromLong(path[i]);

            double distance = horizontalDistanceToCenterMass(pos, centerMass);
            double movedAway = distance - previousDistance;

            if (movedAway > 0.0) {
                rawPenalty += movedAway * CENTER_MASS_AWAY_PENALTY_PER_BLOCK;
            }

            previousDistance = distance;
        }

        return rawPenalty <= 0.0 ? 0 : Math.min(CENTER_MASS_MAX_EXTRA_COST, (int) Math.ceil(rawPenalty));
    }

    private static double horizontalDistanceToCenterMass(BlockPos pos, ItemCenterMass centerMass) {
        double dx = pos.getX() + 0.5 - centerMass.x();
        double dz = pos.getZ() + 0.5 - centerMass.z();

        return Math.sqrt(dx * dx + dz * dz);
    }

    private static BlockPos resolvePathStart(PathWorldView world, Vec3d playerPos, BlockPos fallback) {
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        int minY = world.getBottomY() + 1;
        int maxY = world.getTopYInclusive() - 2;

        for (int dy = -2; dy <= 1; dy++) {
            int y = fallback.getY() + dy;
            if (y < minY || y > maxY) {
                continue;
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos candidate = fallback.add(dx, dy, dz);

                    if (!isLoaded(world, candidate)) {
                        continue;
                    }

                    double feetY = getStandingFeetY(world, candidate);
                    if (Double.isNaN(feetY)) {
                        continue;
                    }

                    double cx = candidate.getX() + 0.5;
                    double cz = candidate.getZ() + 0.5;

                    double horizontalDx = playerPos.x - cx;
                    double horizontalDz = playerPos.z - cz;
                    double verticalDy = playerPos.y - feetY;

                    if (Math.abs(verticalDy) > 1.75) {
                        continue;
                    }

                    double score = horizontalDx * horizontalDx
                            + horizontalDz * horizontalDz
                            + verticalDy * verticalDy * 0.35
                            + Math.abs(dx) * 0.03
                            + Math.abs(dz) * 0.03
                            + Math.abs(dy) * 0.08;

                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }

        return best;
    }

    private static long[] getClosestTargetsByDistance(BlockPos start, long[] targets, int maxTargets) {
        return getClosestTargetsByDistance(start, targets, maxTargets, NO_POS, MAX_BFS_XZ + 8, MAX_BFS_Y + 8);
    }

    private static long[] getClosestTargetsByDistance(BlockPos start, long[] targets, int maxTargets, long excludedTarget) {
        return getClosestTargetsByDistance(start, targets, maxTargets, excludedTarget, MAX_BFS_XZ + 8, MAX_BFS_Y + 8);
    }

    private static long[] getClosestTargetsByDistance(
            BlockPos start,
            long[] targets,
            int maxTargets,
            long excludedTarget,
            int maxTargetXZ,
            int maxTargetY
    ) {
        return getClosestTargetsByDistance(start, targets, maxTargets, new long[]{excludedTarget}, maxTargetXZ, maxTargetY);
    }

    private static long[] getClosestTargetsByDistance(
            BlockPos start,
            long[] targets,
            int maxTargets,
            long[] excludedTargets,
            int maxTargetXZ,
            int maxTargetY
    ) {
        if (maxTargets <= 0) {
            return new long[0];
        }

        long[] best = new long[maxTargets];
        double[] bestDist = new double[maxTargets];

        Arrays.fill(best, NO_POS);
        Arrays.fill(bestDist, Double.POSITIVE_INFINITY);

        for (long packed : targets) {
            if (isExcludedTarget(packed, excludedTargets) || isTemporarilyIgnoredTarget(packed)) {
                continue;
            }

            BlockPos target = BlockPos.fromLong(packed);

            int dx = Math.abs(target.getX() - start.getX());
            int dy = Math.abs(target.getY() - start.getY());
            int dz = Math.abs(target.getZ() - start.getZ());

            if (dx > maxTargetXZ || dz > maxTargetXZ || dy > maxTargetY) {
                continue;
            }

            double dist = dx * dx + dy * dy + dz * dz;

            for (int i = 0; i < maxTargets; i++) {
                if (isBetterDistanceCandidate(packed, dist, best[i], bestDist[i])) {
                    for (int j = maxTargets - 1; j > i; j--) {
                        bestDist[j] = bestDist[j - 1];
                        best[j] = best[j - 1];
                    }

                    bestDist[i] = dist;
                    best[i] = packed;
                    break;
                }
            }
        }

        LongArrayList result = new LongArrayList();
        for (long packed : best) {
            if (packed != NO_POS) {
                result.add(packed);
            }
        }

        return result.toLongArray();
    }

    private static boolean isExcludedTarget(long packed, long[] excludedTargets) {
        if (excludedTargets == null) {
            return false;
        }

        for (long excluded : excludedTargets) {
            if (excluded != NO_POS && packed == excluded) {
                return true;
            }
        }

        return false;
    }

    private static boolean isBetterDistanceCandidate(long candidatePacked, double candidateDist, long currentPacked, double currentDist) {
        int distCompare = Double.compare(candidateDist, currentDist);

        if (distCompare < 0) {
            return true;
        }

        if (distCompare > 0) {
            return false;
        }

        return currentPacked == NO_POS || Long.compare(candidatePacked, currentPacked) < 0;
    }

    private static boolean shouldReuseCurrentPath(MinecraftClient client, ClientWorld world) {
        if (client.player == null || world == null) {
            return false;
        }

        long[] path = PATH_POSITIONS_SNAPSHOT;
        long targetPacked = PATH_TARGET_SNAPSHOT;

        if (path.length == 0 || targetPacked == NO_POS) {
            return false;
        }

        if (!isPathTargetStillValid(world, targetPacked)) {
            return false;
        }

        return isPlayerNearExistingPath(
                world,
                client.player.getPos(),
                path,
                PATH_REUSE_DISTANCE,
                PATH_REUSE_VERTICAL_TOLERANCE
        );
    }

    private static boolean isPathTargetStillValid(ClientWorld world, long targetPacked) {
        if (isTemporarilyIgnoredTarget(targetPacked)) {
            return false;
        }

        return isItemHeadAtLive(world, BlockPos.fromLong(targetPacked));
    }

    private static boolean isPathTargetStillValid(PathWorldView world, long targetPacked) {
        if (isTemporarilyIgnoredTarget(targetPacked)) {
            return false;
        }

        return world.isItemHeadAt(BlockPos.fromLong(targetPacked));
    }

    static boolean isItemHeadAtLive(ClientWorld world, BlockPos pos) {
        if (!isLoaded(world, pos)) {
            return false;
        }

        BlockState state = world.getBlockState(pos);
        if (!isPlayerHeadBlock(state)) {
            return false;
        }

        if (!(world.getBlockEntity(pos) instanceof SkullBlockEntity skull)) {
            return false;
        }

        ProfileComponent owner = skull.getOwner();
        return owner != null && owner.name().orElse("").equals("item");
    }

    private static boolean isItemHeadAt(PathWorldView world, BlockPos pos) {
        return world.isItemHeadAt(pos);
    }

    private static boolean isPlayerNearExistingPath(
            ClientWorld world,
            Vec3d playerPos,
            long[] path,
            int maxHorizontalDistance,
            double maxVerticalDistance
    ) {
        if (path == null || path.length == 0) {
            return false;
        }

        double maxHorizontalDistanceSq = maxHorizontalDistance * maxHorizontalDistance;

        if (path.length == 1) {
            BlockPos node = BlockPos.fromLong(path[0]);

            double nodeFeetY = getNodeFeetY(world, node);
            double verticalDistance = Math.abs(playerPos.y - nodeFeetY);

            if (verticalDistance > maxVerticalDistance) {
                return false;
            }

            double dx = node.getX() + 0.5 - playerPos.x;
            double dz = node.getZ() + 0.5 - playerPos.z;

            return dx * dx + dz * dz <= maxHorizontalDistanceSq;
        }

        for (int i = 1; i < path.length; i++) {
            BlockPos aNode = BlockPos.fromLong(path[i - 1]);
            BlockPos bNode = BlockPos.fromLong(path[i]);

            double aY = getNodeFeetY(world, aNode);
            double bY = getNodeFeetY(world, bNode);

            Vec3d a = new Vec3d(aNode.getX() + 0.5, aY, aNode.getZ() + 0.5);
            Vec3d b = new Vec3d(bNode.getX() + 0.5, bY, bNode.getZ() + 0.5);

            double t = horizontalProjectionT(playerPos, a, b);
            double segmentFeetY = a.y + (b.y - a.y) * t;
            double verticalDistance = Math.abs(playerPos.y - segmentFeetY);

            if (verticalDistance > maxVerticalDistance) {
                continue;
            }

            double horizontalDistanceSq = horizontalDistanceToSegmentSq(playerPos, a, b);
            if (horizontalDistanceSq <= maxHorizontalDistanceSq) {
                return true;
            }
        }

        return false;
    }

    private static double getNodeFeetY(ClientWorld world, BlockPos node) {
        double standingY = getStandingFeetY(world, node);
        return Double.isNaN(standingY) ? node.getY() : standingY;
    }

    private static boolean isPlayerVerticallyNearNode(MinecraftClient client, BlockPos node, double maxVerticalDistance) {
        if (client.player == null || client.world == null) {
            return false;
        }

        double nodeFeetY = getNodeFeetY(client.world, node);
        return Math.abs(client.player.getY() - nodeFeetY) <= maxVerticalDistance;
    }

    private static PathResult findPathAStar(
            PathWorldView world,
            BlockPos start,
            Long2LongOpenHashMap reachableGoals,
            long[] targetCandidates,
            PathSearchCache cache,
            SearchBounds bounds,
            PathSearchBudget budget
    ) {
        Long2IntOpenHashMap gScore = new Long2IntOpenHashMap();
        gScore.defaultReturnValue(Integer.MAX_VALUE);

        PriorityQueue<AStarNode> open = new PriorityQueue<>(
                Comparator
                        .comparingInt(AStarNode::f)
                        .thenComparingInt(AStarNode::h)
                        .thenComparingLong(AStarNode::packed)
        );

        long startPacked = start.asLong();
        int startH = heuristicToTargets(start, targetCandidates);

        cache.parent.put(startPacked, NO_POS);
        gScore.put(startPacked, 0);
        open.add(new AStarNode(startPacked, 0, startH, startH));

        int visited = 0;

        while (!open.isEmpty() && visited++ < MAX_BFS_NODES) {
            if ((visited & 31) == 0 && budget.expired()) {
                return new PathResult(new long[0], NO_POS);
            }

            AStarNode node = open.poll();

            if (node.g() != gScore.get(node.packed())) {
                continue;
            }

            long touchedTarget = reachableGoals.get(node.packed());
            if (touchedTarget != NO_POS) {
                return reconstructPath(node.packed(), touchedTarget, cache.parent);
            }

            addWalkingNeighborsAStar(
                    world,
                    start,
                    BlockPos.fromLong(node.packed()),
                    node.packed(),
                    node.g(),
                    open,
                    gScore,
                    cache,
                    targetCandidates,
                    bounds,
                    budget
            );
        }

        return new PathResult(new long[0], NO_POS);
    }

    private static int heuristicToTargets(BlockPos pos, long[] targets) {
        int best = Integer.MAX_VALUE;

        for (long packed : targets) {
            BlockPos target = BlockPos.fromLong(packed);

            int dx = Math.abs(pos.getX() - target.getX());
            int dy = Math.abs(pos.getY() - target.getY());
            int dz = Math.abs(pos.getZ() - target.getZ());

            int minXZ = Math.min(dx, dz);
            int maxXZ = Math.max(dx, dz);

            int horizontal = 14 * minXZ + 10 * (maxXZ - minXZ);
            int vertical = Math.max(0, dy - 2) * 10;
            int h = horizontal + vertical - 40;

            if (h < best) {
                best = h;
            }
        }

        return best == Integer.MAX_VALUE ? 0 : Math.max(0, best);
    }

    private static void addWalkingNeighborsAStar(
            PathWorldView world,
            BlockPos start,
            BlockPos current,
            long currentPacked,
            int currentG,
            PriorityQueue<AStarNode> open,
            Long2IntOpenHashMap gScore,
            PathSearchCache cache,
            long[] targetCandidates,
            SearchBounds bounds,
            PathSearchBudget budget
    ) {
        for (int[] offset : WALK_OFFSETS) {
            if (budget.expired()) {
                return;
            }

            int dx = offset[0];
            int dz = offset[1];

            BlockPos flat = current.add(dx, 0, dz);

            if (tryAStarNeighbor(world, start, current, flat, currentPacked, currentG, open, gScore, cache, targetCandidates, bounds, budget)) {
                continue;
            }

            BlockPos up = flat.up();
            if (tryAStarNeighbor(world, start, current, up, currentPacked, currentG, open, gScore, cache, targetCandidates, bounds, budget)) {
                continue;
            }

            for (int drop = 1; drop <= MAX_DROP; drop++) {
                if (budget.expired()) {
                    return;
                }

                BlockPos down = flat.down(drop);

                if (!inSearchBounds(world, start, down, bounds) || !isLoadedCached(world, down, cache)) {
                    break;
                }

                if (tryAStarNeighbor(world, start, current, down, currentPacked, currentG, open, gScore, cache, targetCandidates, bounds, budget)) {
                    break;
                }

                if (!isFallColumnClear(world, down)) {
                    break;
                }
            }
        }
    }

    private static boolean tryAStarNeighbor(
            PathWorldView world,
            BlockPos start,
            BlockPos current,
            BlockPos next,
            long currentPacked,
            int currentG,
            PriorityQueue<AStarNode> open,
            Long2IntOpenHashMap gScore,
            PathSearchCache cache,
            long[] targetCandidates,
            SearchBounds bounds,
            PathSearchBudget budget
    ) {
        if (budget.expired()) {
            return false;
        }

        if (!inSearchBounds(world, start, next, bounds)) {
            return false;
        }

        long nextPacked = next.asLong();

        if (!isLoadedCached(world, next, cache)) {
            return false;
        }

        if (!isWalkableCached(world, next, cache)) {
            return false;
        }

        if (!canMoveBetweenCached(world, current, next, cache)) {
            return false;
        }

        int moveCost = movementCost(world, current, next, cache);

        long previousPacked = cache.parent.get(currentPacked);
        if (previousPacked != NO_POS) {
            moveCost += turnWallPenalty(world, BlockPos.fromLong(previousPacked), current, next, cache);
        }

        int newG = currentG + moveCost;

        if (newG >= gScore.get(nextPacked)) {
            return false;
        }

        gScore.put(nextPacked, newG);
        cache.parent.put(nextPacked, currentPacked);

        int h = heuristicToTargets(next, targetCandidates);
        open.add(new AStarNode(nextPacked, newG, h, newG + h));

        return true;
    }

    private static int movementCost(PathWorldView world, BlockPos from, BlockPos to, PathSearchCache cache) {
        double fromY = getStandingFeetYCached(world, from, cache);
        double toY = getStandingFeetYCached(world, to, cache);

        if (Double.isNaN(fromY) || Double.isNaN(toY)) {
            return 1000;
        }

        double rise = toY - fromY;
        int cost;

        if (rise > STEP_HEIGHT) {
            cost = MOVE_COST_JUMP_UP;
        } else if (rise > 0.05) {
            cost = MOVE_COST_STEP_UP;
        } else if (rise < -0.5) {
            cost = MOVE_COST_DROP;
        } else {
            cost = MOVE_COST_FLAT;
        }

        if (isDiagonalMove(from, to)) {
            cost = (int) Math.round(cost * 1.41421356237);
        }

        if (isWaterAtFeetNode(world, from) || isWaterAtFeetNode(world, to)) {
            cost += WATER_WALK_PENALTY;
        }

        int wallPenalty = nodeWallPenalty(world, to, cache);
        cost += wallPenalty;

        if (wallPenalty > 0 && isDiagonalMove(from, to)) {
            cost += PATH_DIAGONAL_WALL_PENALTY;
        }

        return cost;
    }

    private static int nodeWallPenalty(PathWorldView world, BlockPos feetPos, PathSearchCache cache) {
        double feetY = getStandingFeetYCached(world, feetPos, cache);
        if (Double.isNaN(feetY)) {
            return 0;
        }

        double x = feetPos.getX() + 0.5;
        double z = feetPos.getZ() + 0.5;

        return isPlayerBoxClearAt(world, x, feetY, z, PATH_NODE_WALL_PROBE_CLEARANCE)
                ? 0
                : PATH_NODE_WALL_PENALTY;
    }

    private static int turnWallPenalty(
            PathWorldView world,
            BlockPos previous,
            BlockPos current,
            BlockPos next,
            PathSearchCache cache
    ) {
        int dx1 = Integer.compare(current.getX() - previous.getX(), 0);
        int dz1 = Integer.compare(current.getZ() - previous.getZ(), 0);
        int dx2 = Integer.compare(next.getX() - current.getX(), 0);
        int dz2 = Integer.compare(next.getZ() - current.getZ(), 0);

        if ((dx1 == 0 && dz1 == 0) || (dx2 == 0 && dz2 == 0)) {
            return 0;
        }

        if (dx1 == dx2 && dz1 == dz2) {
            return 0;
        }

        int currentPenalty = nodeWallPenalty(world, current, cache);
        int nextPenalty = nodeWallPenalty(world, next, cache);

        return currentPenalty > 0 || nextPenalty > 0
                ? PATH_TURN_WALL_PENALTY
                : 0;
    }

    private static PathResult reconstructPath(long goalPacked, long targetPacked, Long2LongOpenHashMap parent) {
        LongArrayList reversed = new LongArrayList();

        long cursor = goalPacked;
        while (cursor != NO_POS) {
            reversed.add(cursor);
            cursor = parent.get(cursor);
        }

        long[] path = new long[reversed.size()];
        for (int i = 0; i < path.length; i++) {
            path[i] = reversed.getLong(path.length - 1 - i);
        }

        return new PathResult(path, targetPacked);
    }

    private static long[] smoothPath(PathWorldView world, long[] rawPath, PathSearchCache cache, PathSearchBudget budget) {
        if (rawPath == null || rawPath.length <= 2 || budget.expired()) {
            return rawPath;
        }

        LongArrayList smoothed = new LongArrayList();
        int index = 0;

        smoothed.add(rawPath[0]);

        while (index < rawPath.length - 1) {
            if (budget.expired()) {
                return rawPath;
            }

            int best = index + 1;
            int max = Math.min(rawPath.length - 1, index + PATH_SMOOTH_MAX_LOOKAHEAD);

            for (int candidate = max; candidate > index + 1; candidate--) {
                if (canSmoothPathRange(world, rawPath, index, candidate, cache, budget)) {
                    best = candidate;
                    break;
                }
            }

            smoothed.add(rawPath[best]);
            index = best;
        }

        return smoothed.toLongArray();
    }

    private static boolean canSmoothPathRange(
            PathWorldView world,
            long[] path,
            int fromIndex,
            int toIndex,
            PathSearchCache cache,
            PathSearchBudget budget
    ) {
        if (toIndex <= fromIndex + 1) {
            return true;
        }

        if (budget.expired()) {
            return false;
        }

        BlockPos from = BlockPos.fromLong(path[fromIndex]);
        BlockPos to = BlockPos.fromLong(path[toIndex]);

        double fromY = getStandingFeetYCached(world, from, cache);
        double toY = getStandingFeetYCached(world, to, cache);

        if (Double.isNaN(fromY) || Double.isNaN(toY)) {
            return false;
        }

        if (Math.abs(toY - fromY) > PATH_SMOOTH_MAX_HEIGHT_DELTA) {
            return false;
        }

        double previousY = fromY;

        for (int i = fromIndex + 1; i <= toIndex; i++) {
            if ((i & 3) == 0 && budget.expired()) {
                return false;
            }

            BlockPos node = BlockPos.fromLong(path[i]);
            double y = getStandingFeetYCached(world, node, cache);

            if (Double.isNaN(y)) {
                return false;
            }

            double delta = y - previousY;

            if (delta < -PATH_SMOOTH_MAX_DOWN_STEP) {
                return false;
            }

            if (Math.abs(delta) > PATH_SMOOTH_MAX_HEIGHT_DELTA) {
                return false;
            }

            previousY = y;
        }

        return isStraightWalkSegmentClear(world, from, to, fromY, toY, budget);
    }

    private static boolean isStraightWalkSegmentClear(
            PathWorldView world,
            BlockPos from,
            BlockPos to,
            double fromFeetY,
            double toFeetY,
            PathSearchBudget budget
    ) {
        double fromX = from.getX() + 0.5;
        double fromZ = from.getZ() + 0.5;
        double toX = to.getX() + 0.5;
        double toZ = to.getZ() + 0.5;

        double dx = toX - fromX;
        double dz = toZ - fromZ;

        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance < 0.0001) {
            return true;
        }

        int samples = Math.max(2, (int) Math.ceil(horizontalDistance / PATH_SMOOTH_SAMPLE_SPACING));
        samples = Math.min(samples, PATH_SMOOTH_MAX_SAMPLES);

        for (int i = 1; i <= samples; i++) {
            if ((i & 7) == 0 && budget.expired()) {
                return false;
            }

            double t = i / (double) samples;

            double x = fromX + (toX - fromX) * t;
            double y = fromFeetY + (toFeetY - fromFeetY) * t;
            double z = fromZ + (toZ - fromZ) * t;

            if (playerBoxTouchesHandOpenableDoor(world, x, y, z)) {
                return false;
            }

            if (trapdoorIntersectsPlayerBoxAt(world, x, y, z)) {
                return false;
            }

            if (!isPlayerBoxClearAt(world, x, y, z, PATH_SMOOTH_EXTRA_WALL_CLEARANCE)) {
                return false;
            }

            BlockPos feetCell = new BlockPos(
                    MathHelper.floor(x),
                    MathHelper.floor(y + PATH_SMOOTH_Y_FLOOR_EPSILON),
                    MathHelper.floor(z)
            );

            if (!isLoaded(world, feetCell)) {
                return false;
            }

            double actualFeetY = getStandingFeetY(world, feetCell);

            if (Double.isNaN(actualFeetY)) {
                return false;
            }

            if (Math.abs(actualFeetY - y) > PATH_SMOOTH_FEET_Y_TOLERANCE) {
                return false;
            }
        }

        return true;
    }

    private static boolean playerBoxTouchesHandOpenableDoor(PathWorldView world, double centerX, double feetY, double centerZ) {
        Box playerBox = makePlayerCollisionBox(centerX, feetY, centerZ).expand(PATH_SMOOTH_DOOR_SCAN_PADDING);

        int minX = MathHelper.floor(playerBox.minX);
        int maxX = MathHelper.floor(playerBox.maxX - COLLISION_EPSILON);
        int minY = MathHelper.floor(playerBox.minY);
        int maxY = MathHelper.floor(playerBox.maxY - COLLISION_EPSILON);
        int minZ = MathHelper.floor(playerBox.minZ);
        int maxZ = MathHelper.floor(playerBox.maxZ - COLLISION_EPSILON);

        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);

                    if (!isLoaded(world, pos)) {
                        return true;
                    }

                    if (isHandOpenableDoor(world.getBlockState(pos))) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean inSearchBounds(PathWorldView world, BlockPos start, BlockPos pos, SearchBounds bounds) {
        int dx = Math.abs(pos.getX() - start.getX());
        int dy = Math.abs(pos.getY() - start.getY());
        int dz = Math.abs(pos.getZ() - start.getZ());

        if (dx > bounds.maxXz() || dz > bounds.maxXz() || dy > bounds.maxY()) {
            return false;
        }

        int y = pos.getY();
        return y >= world.getBottomY() + 1 && y <= world.getTopYInclusive() - 2;
    }

    private static boolean isLoaded(ClientWorld world, BlockPos pos) {
        return world.getChunkManager().getChunk(
                pos.getX() >> 4,
                pos.getZ() >> 4,
                ChunkStatus.FULL,
                false
        ) != null;
    }

    private static boolean isLoaded(PathWorldView world, BlockPos pos) {
        return world.isLoaded(pos);
    }

    private static boolean isLoadedCached(PathWorldView world, BlockPos pos, PathSearchCache cache) {
        long chunkKey = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);

        if (cache.loadedChunks.contains(chunkKey)) {
            return true;
        }

        if (cache.unloadedChunks.contains(chunkKey)) {
            return false;
        }

        boolean loaded = world.isLoaded(pos);

        if (loaded) {
            cache.loadedChunks.add(chunkKey);
        } else {
            cache.unloadedChunks.add(chunkKey);
        }

        return loaded;
    }

    private static boolean isWalkableCached(PathWorldView world, BlockPos feetPos, PathSearchCache cache) {
        return !Double.isNaN(getStandingFeetYCached(world, feetPos, cache));
    }

    private static double getStandingFeetY(ClientWorld world, BlockPos feetPos) {
        return getStandingFeetY(new LivePathWorld(world), feetPos);
    }

    private static double getStandingFeetYCached(PathWorldView world, BlockPos feetPos, PathSearchCache cache) {
        long key = feetPos.asLong();
        double cached = cache.feetY.get(key);

        if (cached != UNKNOWN_FEET_Y) {
            return cached;
        }

        double value = getStandingFeetY(world, feetPos);
        cache.feetY.put(key, value);
        return value;
    }

    private static boolean canMoveBetweenCached(PathWorldView world, BlockPos from, BlockPos to, PathSearchCache cache) {
        double fromFeetY = getStandingFeetYCached(world, from, cache);
        double toFeetY = getStandingFeetYCached(world, to, cache);

        if (Double.isNaN(fromFeetY) || Double.isNaN(toFeetY)) {
            return false;
        }

        return canMoveBetweenWithFeetY(world, from, to, fromFeetY, toFeetY);
    }

    private static boolean canMoveBetweenWithFeetY(
            PathWorldView world,
            BlockPos from,
            BlockPos to,
            double fromFeetY,
            double toFeetY
    ) {
        double rise = toFeetY - fromFeetY;

        if (rise > JUMP_HEIGHT) {
            return false;
        }

        if (rise > STEP_HEIGHT) {
            if (!hasJumpHeadroom(world, from, fromFeetY)) {
                return false;
            }

            if (isJumpBlockedByStairHighFace(world, from, to, toFeetY)) {
                return false;
            }
        }

        if (isDiagonalMove(from, to) && Math.abs(rise) > HEIGHT_CHANGE_EPSILON) {
            return false;
        }

        if (rise < -HEIGHT_CHANGE_EPSILON) {
            return isDropMoveClear(world, from, to, fromFeetY, toFeetY);
        }

        if (hasTrapdoorCollisionAlongMove(world, from, to, fromFeetY, toFeetY)) {
            return false;
        }

        return isMovementSweepClear(world, from, to, fromFeetY, toFeetY);
    }

    private static boolean isJumpBlockedByStairHighFace(PathWorldView world, BlockPos from, BlockPos to, double toFeetY) {
        int supportY = MathHelper.floor(toFeetY - COLLISION_EPSILON);
        BlockPos supportPos = new BlockPos(to.getX(), supportY, to.getZ());

        if (!isLoaded(world, supportPos)) {
            return true;
        }

        BlockState state = world.getBlockState(supportPos);

        if (!(state.getBlock() instanceof StairsBlock)) {
            return false;
        }

        VoxelShape shape = state.getCollisionShape(world, supportPos);
        if (shape == null || shape.isEmpty()) {
            return false;
        }

        double localLandingY = toFeetY - supportPos.getY();

        if (localLandingY < 1.0 - 0.02) {
            return false;
        }

        int dx = Integer.compare(to.getX() - from.getX(), 0);
        int dz = Integer.compare(to.getZ() - from.getZ(), 0);

        if (dx == 0 && dz == 0) {
            return false;
        }

        if (dx != 0 && dz != 0) {
            return stairLandingFaceBlocked(shape, localLandingY, dx, true)
                    && stairLandingFaceBlocked(shape, localLandingY, dz, false);
        }

        return dx != 0
                ? stairLandingFaceBlocked(shape, localLandingY, dx, true)
                : stairLandingFaceBlocked(shape, localLandingY, dz, false);
    }

    private static boolean stairLandingFaceBlocked(VoxelShape shape, double localLandingY, int directionIntoBlock, boolean xAxis) {
        double eps = COLLISION_EPSILON * 8.0;

        for (Box box : shape.getBoundingBoxes()) {
            if (Math.abs(box.maxY - localLandingY) > 0.02) {
                continue;
            }

            if (xAxis) {
                if (directionIntoBlock > 0 && box.minX <= eps) {
                    return true;
                }

                if (directionIntoBlock < 0 && box.maxX >= 1.0 - eps) {
                    return true;
                }
            } else {
                if (directionIntoBlock > 0 && box.minZ <= eps) {
                    return true;
                }

                if (directionIntoBlock < 0 && box.maxZ >= 1.0 - eps) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isDiagonalMove(BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getZ() - from.getZ());

        return dx == 1 && dz == 1;
    }

    private static boolean isDropMoveClear(PathWorldView world, BlockPos from, BlockPos to, double fromFeetY, double toFeetY) {
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getZ() - from.getZ());

        if (dx + dz != 1) {
            return false;
        }

        double fromX = from.getX() + 0.5;
        double fromZ = from.getZ() + 0.5;
        double toX = to.getX() + 0.5;
        double toZ = to.getZ() + 0.5;

        for (int i = 1; i <= MOVE_SWEEP_SAMPLES; i++) {
            double t = i / (double) MOVE_SWEEP_SAMPLES;

            double x = fromX + (toX - fromX) * t;
            double z = fromZ + (toZ - fromZ) * t;

            if (!isPlayerBoxClearAt(world, x, fromFeetY, z)) {
                return false;
            }

            if (trapdoorIntersectsPlayerBoxAt(world, x, fromFeetY, z)) {
                return false;
            }
        }

        double dropDistance = fromFeetY - toFeetY;
        int fallSamples = Math.max(2, (int) Math.ceil(dropDistance * 2.0));

        for (int i = 0; i <= fallSamples; i++) {
            double t = i / (double) fallSamples;
            double y = fromFeetY + (toFeetY - fromFeetY) * t;

            if (!isPlayerBoxClearAt(world, toX, y, toZ)) {
                return false;
            }

            if (trapdoorIntersectsPlayerBoxAt(world, toX, y, toZ)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isMovementSweepClear(PathWorldView world, BlockPos from, BlockPos to, double fromFeetY, double toFeetY) {
        double fromX = from.getX() + 0.5;
        double fromZ = from.getZ() + 0.5;
        double toX = to.getX() + 0.5;
        double toZ = to.getZ() + 0.5;

        double checkFeetY = Math.max(fromFeetY, toFeetY);

        for (int i = 1; i < MOVE_SWEEP_SAMPLES; i++) {
            double t = i / (double) MOVE_SWEEP_SAMPLES;

            double x = fromX + (toX - fromX) * t;
            double z = fromZ + (toZ - fromZ) * t;

            if (!isPlayerBoxClearAt(world, x, checkFeetY, z)) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasJumpHeadroom(PathWorldView world, BlockPos from, double fromFeetY) {
        return isPlayerBoxClearAt(
                world,
                from.getX() + 0.5,
                fromFeetY + JUMP_HEADROOM_RISE,
                from.getZ() + 0.5
        );
    }

    private static boolean isFallColumnClear(PathWorldView world, BlockPos pos) {
        return isPlayerBoxClearAt(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static double getStandingFeetY(PathWorldView world, BlockPos feetPos) {
        if (!isLoaded(world, feetPos)) {
            return Double.NaN;
        }

        double centerX = feetPos.getX() + 0.5;
        double centerZ = feetPos.getZ() + 0.5;

        double best = Double.NaN;

        BlockState sameState = world.getBlockState(feetPos);
        boolean sameCellHasTrapdoor = sameState.getBlock() instanceof TrapdoorBlock;

        BlockPos below = feetPos.down();
        if (isLoaded(world, below)) {
            BlockState belowState = world.getBlockState(below);

            if (!sameCellHasTrapdoor && !isHandOpenableDoor(belowState)) {
                VoxelShape belowShape = belowState.getCollisionShape(world, below);

                for (Box localBox : belowShape.getBoundingBoxes()) {
                    if (!overlapsPlayerFootprint(localBox, below, centerX, centerZ)) {
                        continue;
                    }

                    if (localBox.maxY >= 1.0 - COLLISION_EPSILON && localBox.maxY <= 1.0 + COLLISION_EPSILON) {
                        best = tryStandingCandidate(
                                world,
                                centerX,
                                below.getY() + localBox.maxY,
                                centerZ,
                                best
                        );
                    }
                }
            }
        }

        if (!isHandOpenableDoor(sameState)) {
            if (sameCellHasTrapdoor && !isClosedBottomTrapdoor(sameState)) {
                return best;
            }

            VoxelShape sameShape = sameState.getCollisionShape(world, feetPos);

            for (Box localBox : sameShape.getBoundingBoxes()) {
                if (!overlapsPlayerFootprint(localBox, feetPos, centerX, centerZ)) {
                    continue;
                }

                if (localBox.maxY > COLLISION_EPSILON && localBox.maxY < 1.0 - COLLISION_EPSILON) {
                    best = tryStandingCandidate(
                            world,
                            centerX,
                            feetPos.getY() + localBox.maxY,
                            centerZ,
                            best
                    );
                }
            }
        }

        return best;
    }

    private static double tryStandingCandidate(
            PathWorldView world,
            double centerX,
            double feetY,
            double centerZ,
            double currentBest
    ) {
        if (!isPlayerBoxClearAt(world, centerX, feetY, centerZ)) {
            return currentBest;
        }

        return Double.isNaN(currentBest) || feetY > currentBest ? feetY : currentBest;
    }

    private static boolean overlapsPlayerFootprint(Box localBox, BlockPos shapePos, double centerX, double centerZ) {
        double minX = shapePos.getX() + localBox.minX;
        double maxX = shapePos.getX() + localBox.maxX;
        double minZ = shapePos.getZ() + localBox.minZ;
        double maxZ = shapePos.getZ() + localBox.maxZ;

        return maxX > centerX - PLAYER_HALF_WIDTH
                && minX < centerX + PLAYER_HALF_WIDTH
                && maxZ > centerZ - PLAYER_HALF_WIDTH
                && minZ < centerZ + PLAYER_HALF_WIDTH;
    }

    private static boolean isPlayerBoxClearAt(PathWorldView world, double centerX, double feetY, double centerZ) {
        return isPlayerBoxClearAt(world, centerX, feetY, centerZ, 0.0);
    }

    private static boolean isPlayerBoxClearAt(
            PathWorldView world,
            double centerX,
            double feetY,
            double centerZ,
            double extraHorizontalClearance
    ) {
        Box playerBox = makePlayerCollisionBox(centerX, feetY, centerZ, extraHorizontalClearance);

        int minX = MathHelper.floor(playerBox.minX);
        int maxX = MathHelper.floor(playerBox.maxX - COLLISION_EPSILON);
        int minY = MathHelper.floor(playerBox.minY);
        int maxY = MathHelper.floor(playerBox.maxY - COLLISION_EPSILON);
        int minZ = MathHelper.floor(playerBox.minZ);
        int maxZ = MathHelper.floor(playerBox.maxZ - COLLISION_EPSILON);

        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);

                    if (!isLoaded(world, pos)) {
                        return false;
                    }

                    BlockState state = world.getBlockState(pos);

                    if (isHandOpenableDoor(state)) {
                        continue;
                    }

                    VoxelShape collision = state.getCollisionShape(world, pos);
                    if (collision.isEmpty()) {
                        continue;
                    }

                    for (Box localBox : collision.getBoundingBoxes()) {
                        Box worldBox = localBox.offset(pos.getX(), pos.getY(), pos.getZ());

                        if (worldBox.intersects(playerBox)) {
                            return false;
                        }
                    }
                }
            }
        }

        return true;
    }

    private static boolean isHandOpenableDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock && DoorBlock.canOpenByHand(state);
    }

    private static boolean isClosedBottomTrapdoor(BlockState state) {
        return state.getBlock() instanceof TrapdoorBlock
                && state.contains(TrapdoorBlock.OPEN)
                && state.contains(TrapdoorBlock.HALF)
                && !state.get(TrapdoorBlock.OPEN)
                && state.get(TrapdoorBlock.HALF) == BlockHalf.BOTTOM;
    }

    private static boolean isWaterAtFeetNode(PathWorldView world, BlockPos feetPos) {
        if (!isLoaded(world, feetPos)) {
            return false;
        }

        return world.getFluidState(feetPos).isOf(Fluids.WATER)
                || world.getFluidState(feetPos).isOf(Fluids.FLOWING_WATER);
    }

    private static boolean hasTrapdoorCollisionAlongMove(
            PathWorldView world,
            BlockPos from,
            BlockPos to,
            double fromFeetY,
            double toFeetY
    ) {
        double fromX = from.getX() + 0.5;
        double fromZ = from.getZ() + 0.5;
        double toX = to.getX() + 0.5;
        double toZ = to.getZ() + 0.5;

        for (int i = 0; i <= TRAPDOOR_MOVE_SWEEP_SAMPLES; i++) {
            double t = i / (double) TRAPDOOR_MOVE_SWEEP_SAMPLES;

            double x = fromX + (toX - fromX) * t;
            double y = fromFeetY + (toFeetY - fromFeetY) * t;
            double z = fromZ + (toZ - fromZ) * t;

            if (trapdoorIntersectsPlayerBoxAt(world, x, y, z)) {
                return true;
            }
        }

        return false;
    }

    private static boolean trapdoorIntersectsPlayerBoxAt(PathWorldView world, double centerX, double feetY, double centerZ) {
        Box playerBox = makePlayerCollisionBox(centerX, feetY, centerZ);

        int minX = MathHelper.floor(playerBox.minX);
        int maxX = MathHelper.floor(playerBox.maxX - COLLISION_EPSILON);
        int minY = MathHelper.floor(playerBox.minY);
        int maxY = MathHelper.floor(playerBox.maxY - COLLISION_EPSILON);
        int minZ = MathHelper.floor(playerBox.minZ);
        int maxZ = MathHelper.floor(playerBox.maxZ - COLLISION_EPSILON);

        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);

                    if (!isLoaded(world, pos)) {
                        return true;
                    }

                    BlockState state = world.getBlockState(pos);

                    if (!(state.getBlock() instanceof TrapdoorBlock)) {
                        continue;
                    }

                    VoxelShape collision = state.getCollisionShape(world, pos);
                    if (collision.isEmpty()) {
                        continue;
                    }

                    for (Box localBox : collision.getBoundingBoxes()) {
                        Box worldBox = localBox.offset(pos.getX(), pos.getY(), pos.getZ());

                        if (worldBox.intersects(playerBox)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private static Box makePlayerCollisionBox(double centerX, double feetY, double centerZ) {
        return makePlayerCollisionBox(centerX, feetY, centerZ, 0.0);
    }

    private static Box makePlayerCollisionBox(
            double centerX,
            double feetY,
            double centerZ,
            double extraHorizontalClearance
    ) {
        double halfWidth = PLAYER_HALF_WIDTH + Math.max(0.0, extraHorizontalClearance);

        return new Box(
                centerX - halfWidth,
                feetY + COLLISION_EPSILON,
                centerZ - halfWidth,
                centerX + halfWidth,
                feetY + PLAYER_HEIGHT - COLLISION_EPSILON,
                centerZ + halfWidth
        );
    }

    private static void drawPath(
            ClientWorld world,
            MatrixStack matrices,
            VertexConsumer lines,
            Vec3d camPos,
            long[] path,
            long targetPacked
    ) {
        if (path.length == 0) {
            return;
        }

        for (int i = 0; i < path.length - 1; i++) {
            BlockPos a = BlockPos.fromLong(path[i]);
            BlockPos b = BlockPos.fromLong(path[i + 1]);

            drawPathSegment(world, matrices, lines, camPos, a, b);
        }

        if (targetPacked != NO_POS) {
            BlockPos last = BlockPos.fromLong(path[path.length - 1]);
            BlockPos target = BlockPos.fromLong(targetPacked);

            double lastY = getRenderFeetY(world, last);

            drawLine(
                    matrices,
                    lines,
                    last.getX() + 0.5 - camPos.x,
                    lastY + 0.12 - camPos.y,
                    last.getZ() + 0.5 - camPos.z,
                    target.getX() + 0.5 - camPos.x,
                    target.getY() + 0.5 - camPos.y,
                    target.getZ() + 0.5 - camPos.z,
                    PATH_TARGET_COLOR
            );
        }
    }

    private static void drawPathSegment(
            ClientWorld world,
            MatrixStack matrices,
            VertexConsumer lines,
            Vec3d camPos,
            BlockPos from,
            BlockPos to
    ) {
        double fromY = getRenderFeetY(world, from);
        double toY = getRenderFeetY(world, to);

        double fromX = from.getX() + 0.5;
        double fromZ = from.getZ() + 0.5;
        double toX = to.getX() + 0.5;
        double toZ = to.getZ() + 0.5;

        boolean cardinal = Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ()) == 1;
        boolean drop = cardinal && toY < fromY - HEIGHT_CHANGE_EPSILON;

        if (drop) {
            drawLine(
                    matrices,
                    lines,
                    fromX - camPos.x,
                    fromY + 0.12 - camPos.y,
                    fromZ - camPos.z,
                    toX - camPos.x,
                    fromY + 0.12 - camPos.y,
                    toZ - camPos.z,
                    PATH_COLOR
            );

            drawLine(
                    matrices,
                    lines,
                    toX - camPos.x,
                    fromY + 0.12 - camPos.y,
                    toZ - camPos.z,
                    toX - camPos.x,
                    toY + 0.12 - camPos.y,
                    toZ - camPos.z,
                    PATH_COLOR
            );

            return;
        }

        drawLine(
                matrices,
                lines,
                fromX - camPos.x,
                fromY + 0.12 - camPos.y,
                fromZ - camPos.z,
                toX - camPos.x,
                toY + 0.12 - camPos.y,
                toZ - camPos.z,
                PATH_COLOR
        );
    }

    private static double getRenderFeetY(ClientWorld world, BlockPos pos) {
        double feetY = getStandingFeetY(world, pos);
        return Double.isNaN(feetY) ? pos.getY() : feetY;
    }

    private static void drawLine(
            MatrixStack matrices,
            VertexConsumer lines,
            double x1,
            double y1,
            double z1,
            double x2,
            double y2,
            double z2,
            int argb
    ) {
        float dx = (float) (x2 - x1);
        float dy = (float) (y2 - y1);
        float dz = (float) (z2 - z1);

        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= 0.0001f) {
            return;
        }

        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;

        MatrixStack.Entry entry = matrices.peek();

        lines.vertex(entry, (float) x1, (float) y1, (float) z1)
                .color(argb)
                .normal(entry, nx, ny, nz);

        lines.vertex(entry, (float) x2, (float) y2, (float) z2)
                .color(argb)
                .normal(entry, nx, ny, nz);
    }

    private static Long2LongOpenHashMap buildReachableGoals(
            PathWorldView world,
            BlockPos start,
            long[] targetsSnapshot,
            double reach,
            double eyeHeight,
            PathSearchCache cache,
            SearchBounds bounds,
            PathSearchBudget budget
    ) {
        Long2LongOpenHashMap goals = new Long2LongOpenHashMap();
        goals.defaultReturnValue(NO_POS);

        int scanXZ = (int) Math.ceil(reach + 1.0);
        int scanY = (int) Math.ceil(reach + 2.0);

        int checked = 0;

        for (long targetPacked : targetsSnapshot) {
            if (isTemporarilyIgnoredTarget(targetPacked)) {
                continue;
            }

            if ((++checked & 63) == 0 && budget.expired()) {
                return null;
            }

            BlockPos target = BlockPos.fromLong(targetPacked);

            if (!isLoadedCached(world, target, cache)) {
                continue;
            }

            if (!isItemHeadAt(world, target)) {
                continue;
            }

            for (int dx = -scanXZ; dx <= scanXZ; dx++) {
                for (int dy = -scanY; dy <= scanY; dy++) {
                    for (int dz = -scanXZ; dz <= scanXZ; dz++) {
                        if ((++checked & 63) == 0 && budget.expired()) {
                            return null;
                        }

                        BlockPos feet = target.add(dx, dy, dz);

                        if (!inSearchBounds(world, start, feet, bounds)) {
                            continue;
                        }

                        if (!isLoadedCached(world, feet, cache)) {
                            continue;
                        }

                        if (!isWalkableCached(world, feet, cache)) {
                            continue;
                        }

                        if (!canReachTargetFromFeetCached(world, feet, target, reach, eyeHeight, cache)) {
                            continue;
                        }

                        long feetPacked = feet.asLong();

                        if (!goals.containsKey(feetPacked)) {
                            goals.put(feetPacked, targetPacked);
                        }
                    }
                }
            }
        }

        return goals;
    }

    private static boolean canReachTargetFromFeetCached(
            PathWorldView world,
            BlockPos feetPos,
            BlockPos targetPos,
            double reach,
            double eyeHeight,
            PathSearchCache cache
    ) {
        double feetY = getStandingFeetYCached(world, feetPos, cache);
        if (Double.isNaN(feetY)) {
            return false;
        }

        return canReachTargetFromFeetY(world, feetPos, targetPos, reach, eyeHeight, feetY);
    }

    private static boolean canReachTargetFromFeetY(
            PathWorldView world,
            BlockPos feetPos,
            BlockPos targetPos,
            double reach,
            double eyeHeight,
            double feetY
    ) {
        Vec3d eye = new Vec3d(
                feetPos.getX() + 0.5,
                feetY + eyeHeight,
                feetPos.getZ() + 0.5
        );

        Vec3d targetPoint = getTargetReachPoint(world, targetPos);

        if (eye.squaredDistanceTo(targetPoint) > reach * reach) {
            return false;
        }

        return hasReachLine(world, eye, targetPoint, targetPos);
    }

    private static Vec3d getTargetReachPoint(BlockView world, BlockPos targetPos) {
        BlockState state = world.getBlockState(targetPos);
        VoxelShape shape = state.getOutlineShape(world, targetPos);

        if (shape != null && !shape.isEmpty()) {
            Box bb = shape.getBoundingBox();

            return new Vec3d(
                    targetPos.getX() + (bb.minX + bb.maxX) * 0.5,
                    targetPos.getY() + (bb.minY + bb.maxY) * 0.5,
                    targetPos.getZ() + (bb.minZ + bb.maxZ) * 0.5
            );
        }

        return Vec3d.ofCenter(targetPos);
    }

    private static boolean hasReachLine(BlockView world, Vec3d from, Vec3d to, BlockPos targetPos) {
        BlockHitResult hit = world.raycast(new RaycastContext(
                from,
                to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                ShapeContext.absent()
        ));

        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }

        return hit.getBlockPos().equals(targetPos);
    }

    private static void tickAutoMode(MinecraftClient client, ClientWorld world) {
        if (client.player == null || client.interactionManager == null || world == null) {
            releaseAutoMovement(client);
            return;
        }

        if (client.currentScreen != null) {
            releaseAutoMovement(client);
            return;
        }

        if (autoClickCooldown > 0) {
            autoClickCooldown--;
        }

        long targetPacked = PATH_TARGET_SNAPSHOT;
        long[] path = PATH_POSITIONS_SNAPSHOT;

        if (targetPacked == NO_POS || path.length == 0) {
            releaseAutoMovement(client);
            return;
        }

        if (!isPathTargetStillValid(world, targetPacked)) {
            releaseAutoMovement(client);
            FORCE_PATH_REBUILD = true;
            pathRefreshCountdown = 0;
            return;
        }

        long reachableTargetPacked = findReachableTargetFromPlayer(client, world, targetPacked);
        if (reachableTargetPacked != NO_POS) {
            BlockPos reachableTargetPos = BlockPos.fromLong(reachableTargetPacked);

            releaseAutoMovement(client);
            lookAt(client, getTargetReachPoint(world, reachableTargetPos));

            if (autoClickCooldown <= 0) {
                rightClickTarget(client, world, reachableTargetPos);
                autoClickCooldown = AUTO_CLICK_COOLDOWN_TICKS;
            }

            return;
        }

        BlockPos next = getNextAutoWaypoint(client, path, targetPacked);
        if (next == null) {
            releaseAutoMovement(client);
            return;
        }

        if (tryCloseOpenDoorBlockingMove(client, world, next)) {
            return;
        }

        if (tryUseDoorOnPath(client, world, path, next)) {
            return;
        }

        Vec3d aimPoint = getAutoAimPoint(client, path, next);
        float yawError = facePointYawOnlyPrecise(client, aimPoint);
        driveTowardWaypoint(client, world, path, yawError);
    }

    private static BlockPos getNextAutoWaypoint(MinecraftClient client, long[] path, long targetPacked) {
        if (client.player == null || path.length == 0) {
            return null;
        }

        long pathEnd = path[path.length - 1];

        boolean pathChanged = targetPacked != AUTO_LAST_TARGET
                || pathEnd != AUTO_LAST_PATH_END
                || path != AUTO_LAST_PATH_REF;

        if (pathChanged) {
            AUTO_LAST_TARGET = targetPacked;
            AUTO_LAST_PATH_END = pathEnd;
            AUTO_LAST_PATH_REF = path;

            AUTO_WAYPOINT_INDEX = findBestAutoWaypointIndex(client, path);

            if (AUTO_WAYPOINT_INDEX < 0) {
                FORCE_PATH_REBUILD = true;
                pathRefreshCountdown = 0;
                return null;
            }
        } else if (isAutoWaypointMisaligned(client, path)) {
            int bestIndex = findBestAutoWaypointIndex(client, path);

            if (bestIndex < 0) {
                FORCE_PATH_REBUILD = true;
                pathRefreshCountdown = 0;
                return null;
            }

            AUTO_WAYPOINT_INDEX = Math.max(AUTO_WAYPOINT_INDEX, bestIndex);
        }

        if (AUTO_WAYPOINT_INDEX < 0) {
            FORCE_PATH_REBUILD = true;
            pathRefreshCountdown = 0;
            return null;
        }

        if (AUTO_WAYPOINT_INDEX >= path.length) {
            AUTO_WAYPOINT_INDEX = path.length - 1;
        }

        Vec3d playerPos = client.player.getPos();

        while (AUTO_WAYPOINT_INDEX < path.length - 1) {
            BlockPos currentNode = BlockPos.fromLong(path[AUTO_WAYPOINT_INDEX]);
            BlockPos nextNode = BlockPos.fromLong(path[AUTO_WAYPOINT_INDEX + 1]);

            Vec3d currentCenter = nodeCenter(currentNode);
            Vec3d nextCenter = nodeCenter(nextNode);

            double distToCurrentSq = horizontalDistanceSq(playerPos, currentCenter);

            boolean verticalOk = isPlayerVerticallyNearNode(client, currentNode, AUTO_PATH_VERTICAL_TOLERANCE);

            boolean closeEnoughToCurrent = verticalOk && distToCurrentSq <= WAYPOINT_ADVANCE_DISTANCE_SQ;
            boolean passedCurrent = verticalOk
                    && distToCurrentSq <= WAYPOINT_PASS_DISTANCE_SQ
                    && hasPassedNodeHorizontally(playerPos, currentCenter, nextCenter);

            if (closeEnoughToCurrent || passedCurrent) {
                AUTO_WAYPOINT_INDEX++;
            } else {
                break;
            }
        }

        if (isAutoWaypointMisaligned(client, path)) {
            int bestIndex = findBestAutoWaypointIndex(client, path);

            if (bestIndex < 0) {
                FORCE_PATH_REBUILD = true;
                pathRefreshCountdown = 0;
                return null;
            }

            AUTO_WAYPOINT_INDEX = Math.max(AUTO_WAYPOINT_INDEX, bestIndex);
        }

        return BlockPos.fromLong(path[AUTO_WAYPOINT_INDEX]);
    }

    private static boolean isAutoWaypointMisaligned(MinecraftClient client, long[] path) {
        if (client.player == null || path.length <= 1) {
            return false;
        }

        if (client.world != null && isAutoInDropTransition(client, client.world, path)) {
            return false;
        }

        int currentIndex = MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);
        int bestIndex = findBestAutoWaypointIndex(client, path);

        if (bestIndex < 0) {
            return true;
        }

        if (bestIndex <= currentIndex) {
            return false;
        }

        double currentDistSq = distanceToAutoSegmentSq(client, path, currentIndex);
        double bestDistSq = distanceToAutoSegmentSq(client, path, bestIndex);

        if (currentDistSq > AUTO_REALIGN_DISTANCE_SQ) {
            return true;
        }

        return bestDistSq + AUTO_REALIGN_IMPROVEMENT_SQ < currentDistSq;
    }

    private static int findBestAutoWaypointIndex(MinecraftClient client, long[] path) {
        if (client.player == null || client.world == null || path.length == 0) {
            return -1;
        }

        if (path.length == 1) {
            BlockPos only = BlockPos.fromLong(path[0]);
            return isPlayerVerticallyNearNode(client, only, AUTO_PATH_VERTICAL_TOLERANCE) ? 0 : -1;
        }

        if (isAutoInDropTransition(client, client.world, path)) {
            return MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);
        }

        Vec3d playerPos = client.player.getPos();

        double bestScore = Double.POSITIVE_INFINITY;
        double bestT = 0.0;
        int bestSegmentEndIndex = -1;

        for (int i = 1; i < path.length; i++) {
            Vec3d a = nodeCenter(BlockPos.fromLong(path[i - 1]));
            Vec3d b = nodeCenter(BlockPos.fromLong(path[i]));

            double t = horizontalProjectionT(playerPos, a, b);

            double segmentFeetY = a.y + (b.y - a.y) * t;
            double verticalDistance = Math.abs(playerPos.y - segmentFeetY);

            if (verticalDistance > AUTO_PATH_VERTICAL_TOLERANCE) {
                continue;
            }

            double horizontalDistanceSq = horizontalDistanceToSegmentSq(playerPos, a, b);
            double score = horizontalDistanceSq
                    + verticalDistance * verticalDistance * AUTO_VERTICAL_SEGMENT_SCORE_WEIGHT;

            if (score < bestScore) {
                bestScore = score;
                bestT = t;
                bestSegmentEndIndex = i;
            }
        }

        if (bestSegmentEndIndex < 0) {
            return -1;
        }

        int nextIndex = bestSegmentEndIndex;

        if (bestT >= AUTO_SEGMENT_ADVANCE_T && bestSegmentEndIndex < path.length - 1) {
            nextIndex = bestSegmentEndIndex + 1;
        }

        return MathHelper.clamp(nextIndex, 1, path.length - 1);
    }

    private static double distanceToAutoSegmentSq(MinecraftClient client, long[] path, int waypointIndex) {
        if (client.player == null || client.world == null || path.length == 0) {
            return Double.POSITIVE_INFINITY;
        }

        Vec3d playerPos = client.player.getPos();

        if (path.length == 1) {
            BlockPos node = BlockPos.fromLong(path[0]);

            if (!isPlayerVerticallyNearNode(client, node, AUTO_PATH_VERTICAL_TOLERANCE)) {
                return Double.POSITIVE_INFINITY;
            }

            return horizontalDistanceSq(playerPos, nodeCenter(node));
        }

        int segmentEnd = MathHelper.clamp(waypointIndex, 1, path.length - 1);

        Vec3d a = nodeCenter(BlockPos.fromLong(path[segmentEnd - 1]));
        Vec3d b = nodeCenter(BlockPos.fromLong(path[segmentEnd]));

        double t = horizontalProjectionT(playerPos, a, b);
        double segmentFeetY = a.y + (b.y - a.y) * t;
        double verticalDistance = Math.abs(playerPos.y - segmentFeetY);

        if (verticalDistance > AUTO_PATH_VERTICAL_TOLERANCE) {
            return Double.POSITIVE_INFINITY;
        }

        double horizontalDistanceSq = horizontalDistanceToSegmentSq(playerPos, a, b);
        return horizontalDistanceSq + verticalDistance * verticalDistance * AUTO_VERTICAL_SEGMENT_SCORE_WEIGHT;
    }

    private static double horizontalProjectionT(Vec3d p, Vec3d a, Vec3d b) {
        double abX = b.x - a.x;
        double abZ = b.z - a.z;

        double lenSq = abX * abX + abZ * abZ;
        if (lenSq < 0.0001) {
            return 0.0;
        }

        double apX = p.x - a.x;
        double apZ = p.z - a.z;

        double t = (apX * abX + apZ * abZ) / lenSq;

        if (t < 0.0) {
            return 0.0;
        }

        if (t > 1.0) {
            return 1.0;
        }

        return t;
    }

    private static double horizontalDistanceToSegmentSq(Vec3d p, Vec3d a, Vec3d b) {
        double t = horizontalProjectionT(p, a, b);

        double closestX = a.x + (b.x - a.x) * t;
        double closestZ = a.z + (b.z - a.z) * t;

        double dx = p.x - closestX;
        double dz = p.z - closestZ;

        return dx * dx + dz * dz;
    }

    private static Vec3d nodeCenter(BlockPos pos) {
        double feetY = pos.getY();

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.world != null) {
            double standingY = getStandingFeetY(client.world, pos);
            if (!Double.isNaN(standingY)) {
                feetY = standingY;
            }

            return wallAvoidedNodeCenter(client.world, pos, feetY);
        }

        return new Vec3d(pos.getX() + 0.5, feetY, pos.getZ() + 0.5);
    }

    private static Vec3d wallAvoidedNodeCenter(ClientWorld world, BlockPos pos, double feetY) {
        PathWorldView pathWorld = new LivePathWorld(world);

        double centerX = pos.getX() + 0.5;
        double centerZ = pos.getZ() + 0.5;

        int pushX = 0;
        int pushZ = 0;

        if (!isPlayerBoxClearAt(pathWorld, centerX - AUTO_WALL_AVOIDANCE_PROBE, feetY, centerZ)) {
            pushX++;
        }

        if (!isPlayerBoxClearAt(pathWorld, centerX + AUTO_WALL_AVOIDANCE_PROBE, feetY, centerZ)) {
            pushX--;
        }

        if (!isPlayerBoxClearAt(pathWorld, centerX, feetY, centerZ - AUTO_WALL_AVOIDANCE_PROBE)) {
            pushZ++;
        }

        if (!isPlayerBoxClearAt(pathWorld, centerX, feetY, centerZ + AUTO_WALL_AVOIDANCE_PROBE)) {
            pushZ--;
        }

        if (pushX == 0 && pushZ == 0) {
            return new Vec3d(centerX, feetY, centerZ);
        }

        double len = Math.sqrt(pushX * pushX + pushZ * pushZ);
        double offsetX = AUTO_WALL_AVOIDANCE_OFFSET * pushX / len;
        double offsetZ = AUTO_WALL_AVOIDANCE_OFFSET * pushZ / len;

        double nudgedX = centerX + offsetX;
        double nudgedZ = centerZ + offsetZ;

        if (isPlayerBoxClearAt(pathWorld, nudgedX, feetY, nudgedZ)) {
            return new Vec3d(nudgedX, feetY, nudgedZ);
        }

        if (pushX != 0) {
            double xOnly = centerX + AUTO_WALL_AVOIDANCE_OFFSET * Math.signum(pushX);
            if (isPlayerBoxClearAt(pathWorld, xOnly, feetY, centerZ)) {
                return new Vec3d(xOnly, feetY, centerZ);
            }
        }

        if (pushZ != 0) {
            double zOnly = centerZ + AUTO_WALL_AVOIDANCE_OFFSET * Math.signum(pushZ);
            if (isPlayerBoxClearAt(pathWorld, centerX, feetY, zOnly)) {
                return new Vec3d(centerX, feetY, zOnly);
            }
        }

        return new Vec3d(centerX, feetY, centerZ);
    }

    private static double horizontalDistanceSq(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static boolean hasPassedNodeHorizontally(Vec3d playerPos, Vec3d currentCenter, Vec3d nextCenter) {
        double segmentX = nextCenter.x - currentCenter.x;
        double segmentZ = nextCenter.z - currentCenter.z;

        double segmentLenSq = segmentX * segmentX + segmentZ * segmentZ;

        if (segmentLenSq < 0.0001) {
            return false;
        }

        double playerX = playerPos.x - currentCenter.x;
        double playerZ = playerPos.z - currentCenter.z;

        double dot = playerX * segmentX + playerZ * segmentZ;

        return dot > 0.18;
    }

    private static Vec3d getAutoAimPoint(MinecraftClient client, long[] path, BlockPos fallbackWaypoint) {
        if (client.player == null || path == null || path.length < 2) {
            return nodeCenter(fallbackWaypoint);
        }

        int index = MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);

        Vec3d playerPos = client.player.getPos();

        BlockPos fromNode = BlockPos.fromLong(path[index - 1]);
        BlockPos toNode = BlockPos.fromLong(path[index]);

        Vec3d a = nodeCenter(fromNode);
        Vec3d b = nodeCenter(toNode);

        double abX = b.x - a.x;
        double abZ = b.z - a.z;
        double len = Math.sqrt(abX * abX + abZ * abZ);

        if (len < 0.0001) {
            return b;
        }

        boolean dropSegment = client.world != null && isAutoDropSegment(client.world, fromNode, toNode);

        double t = horizontalProjectionT(playerPos, a, b);

        double lookaheadDistance = dropSegment ? AUTO_DROP_LOOKAHEAD_DISTANCE : AUTO_LOOKAHEAD_DISTANCE;
        double lookaheadT = lookaheadDistance / len;
        double targetT = MathHelper.clamp(t + lookaheadT, 0.0, 1.0);

        if (dropSegment) {
            return lerp(a, b, targetT);
        }

        if (targetT >= 0.999 && index < path.length - 1) {
            Vec3d c = nodeCenter(BlockPos.fromLong(path[index + 1]));

            if (isHorizontalTurn(a, b, c)
                    && horizontalDistanceSq(playerPos, b) > AUTO_CORNER_LOOKAHEAD_DISTANCE_SQ) {
                return b;
            }

            double bcX = c.x - b.x;
            double bcZ = c.z - b.z;
            double nextLen = Math.sqrt(bcX * bcX + bcZ * bcZ);

            if (nextLen > 0.0001) {
                double nextT = MathHelper.clamp(AUTO_LOOKAHEAD_DISTANCE / nextLen, 0.0, 1.0);
                return lerp(b, c, nextT);
            }
        }

        return lerp(a, b, targetT);
    }

    private static boolean isHorizontalTurn(Vec3d a, Vec3d b, Vec3d c) {
        double abX = b.x - a.x;
        double abZ = b.z - a.z;
        double bcX = c.x - b.x;
        double bcZ = c.z - b.z;

        double abLen = Math.sqrt(abX * abX + abZ * abZ);
        double bcLen = Math.sqrt(bcX * bcX + bcZ * bcZ);

        if (abLen < 0.0001 || bcLen < 0.0001) {
            return false;
        }

        double normalizedCross = Math.abs((abX * bcZ - abZ * bcX) / (abLen * bcLen));
        return normalizedCross > 0.25;
    }

    private static Vec3d lerp(Vec3d a, Vec3d b, double t) {
        return new Vec3d(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t
        );
    }

    private static boolean isAutoDropSegment(ClientWorld world, BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getZ() - from.getZ());

        if (dx + dz != 1) {
            return false;
        }

        double fromY = getNodeFeetY(world, from);
        double toY = getNodeFeetY(world, to);

        return toY < fromY - HEIGHT_CHANGE_EPSILON;
    }

    private static boolean isAutoInDropTransition(MinecraftClient client, ClientWorld world, long[] path) {
        if (!AUTO_ENABLED || client.player == null || world == null || path == null || path.length < 2) {
            return false;
        }

        int index = MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);

        BlockPos from = BlockPos.fromLong(path[index - 1]);
        BlockPos to = BlockPos.fromLong(path[index]);

        if (!isAutoDropSegment(world, from, to)) {
            return false;
        }

        Vec3d playerPos = client.player.getPos();
        Vec3d a = nodeCenter(from);
        Vec3d b = nodeCenter(to);

        double minY = Math.min(a.y, b.y) - AUTO_DROP_VERTICAL_PADDING;
        double maxY = Math.max(a.y, b.y) + AUTO_DROP_VERTICAL_PADDING;

        if (playerPos.y < minY || playerPos.y > maxY) {
            return false;
        }

        return horizontalDistanceToSegmentSq(playerPos, a, b) <= AUTO_DROP_REUSE_HORIZONTAL_DISTANCE_SQ;
    }

    private static float facePointYawOnlyPrecise(MinecraftClient client, Vec3d target) {
        if (client.player == null) {
            return 180.0f;
        }

        Vec3d playerPos = client.player.getPos();

        double dx = target.x - playerPos.x;
        double dz = target.z - playerPos.z;

        double horizontalSq = dx * dx + dz * dz;

        if (horizontalSq < 0.001) {
            return 0.0f;
        }

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float currentYaw = client.player.getYaw();

        float yawError = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float yawStep = MathHelper.clamp(
                yawError,
                -AUTO_MAX_YAW_CHANGE_PER_TICK,
                AUTO_MAX_YAW_CHANGE_PER_TICK
        );

        client.player.setYaw(currentYaw + yawStep);
        client.player.setPitch(0.0f);

        return Math.abs(MathHelper.wrapDegrees(targetYaw - client.player.getYaw()));
    }

    private static void driveTowardWaypoint(MinecraftClient client, ClientWorld world, long[] path, float yawError) {
        if (client.player == null || path.length == 0) {
            return;
        }

        AUTO_WAS_DRIVING = true;

        client.options.backKey.setPressed(false);
        client.options.sprintKey.setPressed(true);

        boolean yawReady = Math.abs(yawError) <= AUTO_MOVE_YAW_TOLERANCE;
        client.options.forwardKey.setPressed(yawReady);

        if (!yawReady) {
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            return;
        }

        applySideCorrection(client, path);

        BlockPos waypoint = BlockPos.fromLong(path[AUTO_WAYPOINT_INDEX]);
        boolean shouldJump = client.player.isOnGround() && client.player.horizontalCollision;

        double waypointFeetY = getStandingFeetY(world, waypoint);
        if (!Double.isNaN(waypointFeetY)) {
            Vec3d waypointCenter = new Vec3d(
                    waypoint.getX() + 0.5,
                    waypointFeetY,
                    waypoint.getZ() + 0.5
            );

            double rise = waypointFeetY - client.player.getY();
            double horizontalDistSq = horizontalDistanceSq(client.player.getPos(), waypointCenter);

            if (rise > STEP_HEIGHT + JUMP_RISE_EPSILON && horizontalDistSq <= JUMP_TRIGGER_DISTANCE_SQ) {
                shouldJump = true;
            }
        }

        client.options.jumpKey.setPressed(shouldJump);
    }

    private static void releaseAutoMovement(MinecraftClient client) {
        if (!AUTO_WAS_DRIVING || client == null || client.options == null) {
            return;
        }

        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);

        resetSideCorrection();
        AUTO_WAS_DRIVING = false;
    }

    private static long findReachableTargetFromPlayer(MinecraftClient client, ClientWorld world, long preferredTargetPacked) {
        if (client.player == null) {
            return NO_POS;
        }

        Vec3d eye = client.player.getEyePos();
        double reach = client.player.getBlockInteractionRange() + REACH_PADDING;
        double reachSq = reach * reach;

        long best = NO_POS;
        double bestDistSq = Double.POSITIVE_INFINITY;

        for (long packed : HEAD_POSITIONS_SNAPSHOT) {
            if (packed == NO_POS || isTemporarilyIgnoredTarget(packed)) {
                continue;
            }

            if (!isPathTargetStillValid(world, packed)) {
                continue;
            }

            BlockPos pos = BlockPos.fromLong(packed);
            Vec3d targetPoint = getTargetReachPoint(world, pos);

            double distSq = eye.squaredDistanceTo(targetPoint);
            if (distSq > reachSq) {
                continue;
            }

            if (!hasReachLine(world, eye, targetPoint, pos)) {
                continue;
            }

            if (isBetterReachableClickCandidate(packed, distSq, best, bestDistSq, preferredTargetPacked)) {
                best = packed;
                bestDistSq = distSq;
            }
        }

        return best;
    }

    private static boolean isBetterReachableClickCandidate(
            long candidatePacked,
            double candidateDistSq,
            long currentPacked,
            double currentDistSq,
            long preferredTargetPacked
    ) {
        if (currentPacked == NO_POS) {
            return true;
        }

        boolean candidateIsPlannedTarget = candidatePacked == preferredTargetPacked;
        boolean currentIsPlannedTarget = currentPacked == preferredTargetPacked;

        if (candidateIsPlannedTarget != currentIsPlannedTarget) {
            return candidateIsPlannedTarget;
        }

        int distCompare = Double.compare(candidateDistSq, currentDistSq);

        if (distCompare != 0) {
            return distCompare < 0;
        }

        return Long.compare(candidatePacked, currentPacked) < 0;
    }

    private static void rightClickTarget(MinecraftClient client, ClientWorld world, BlockPos targetPos) {
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        Vec3d eye = client.player.getEyePos();
        Vec3d targetPoint = getTargetReachPoint(world, targetPos);

        BlockHitResult hit = world.raycast(new RaycastContext(
                eye,
                targetPoint,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                ShapeContext.absent()
        ));

        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(targetPos)) {
            hit = new BlockHitResult(targetPoint, Direction.UP, targetPos, false);
        }

        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);

        markTargetClicked(targetPos.asLong());
    }

    private static void markTargetClicked(long targetPacked) {
        RECENTLY_CLICKED_TARGET = targetPacked;
        recentlyClickedTargetTicks = CLICKED_TARGET_IGNORE_TICKS;
        FORCE_PATH_REBUILD = true;
        pathRefreshCountdown = 0;

        clearPathSnapshot();
        resetAutoPathState();
    }

    private static boolean tryCloseOpenDoorBlockingMove(MinecraftClient client, ClientWorld world, BlockPos waypoint) {
        if (client.player == null || client.interactionManager == null || autoClickCooldown > 0) {
            return false;
        }

        BlockPos doorBase = findOpenDoorBlockingPlayerMove(world, client.player.getPos(), waypoint);

        if (doorBase == null) {
            return false;
        }

        Vec3d hitPos = getDoorClickPoint(doorBase);

        double reach = client.player.getBlockInteractionRange() + REACH_PADDING;
        if (client.player.getEyePos().squaredDistanceTo(hitPos) > reach * reach) {
            return false;
        }

        releaseAutoMovement(client);
        lookAt(client, hitPos);
        rightClickBlock(client, world, doorBase, hitPos);

        autoClickCooldown = AUTO_CLICK_COOLDOWN_TICKS;

        FORCE_PATH_REBUILD = true;
        pathRefreshCountdown = 0;

        return true;
    }

    private static BlockPos findOpenDoorBlockingPlayerMove(ClientWorld world, Vec3d playerFeetPos, BlockPos waypoint) {
        double toFeetY = getStandingFeetY(world, waypoint);
        if (Double.isNaN(toFeetY)) {
            toFeetY = waypoint.getY();
        }

        double toX = waypoint.getX() + 0.5;
        double toZ = waypoint.getZ() + 0.5;

        Box scanBox = sweptPlayerBox(
                playerFeetPos.x,
                playerFeetPos.y,
                playerFeetPos.z,
                toX,
                toFeetY,
                toZ
        ).expand(1.0);

        int minX = MathHelper.floor(scanBox.minX);
        int maxX = MathHelper.floor(scanBox.maxX - COLLISION_EPSILON);
        int minY = MathHelper.floor(scanBox.minY);
        int maxY = MathHelper.floor(scanBox.maxY - COLLISION_EPSILON);
        int minZ = MathHelper.floor(scanBox.minZ);
        int maxZ = MathHelper.floor(scanBox.maxZ - COLLISION_EPSILON);

        LongOpenHashSet checkedDoorBases = new LongOpenHashSet();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);

                    if (!isLoaded(world, pos)) {
                        continue;
                    }

                    BlockState state = world.getBlockState(pos);

                    if (!isOpenHandOpenableDoor(state)) {
                        continue;
                    }

                    BlockPos base = getDoorBasePos(pos.toImmutable(), state);

                    if (!checkedDoorBases.add(base.asLong())) {
                        continue;
                    }

                    if (openDoorIntersectsPlayerMove(
                            world,
                            base,
                            playerFeetPos.x,
                            playerFeetPos.y,
                            playerFeetPos.z,
                            toX,
                            toFeetY,
                            toZ
                    )) {
                        return base;
                    }
                }
            }
        }

        return null;
    }

    private static boolean openDoorIntersectsPlayerMove(
            ClientWorld world,
            BlockPos doorBase,
            double fromX,
            double fromFeetY,
            double fromZ,
            double toX,
            double toFeetY,
            double toZ
    ) {
        for (int i = 0; i <= OPEN_DOOR_CLOSE_SWEEP_SAMPLES; i++) {
            double t = i / (double) OPEN_DOOR_CLOSE_SWEEP_SAMPLES;

            double x = fromX + (toX - fromX) * t;
            double y = fromFeetY + (toFeetY - fromFeetY) * t;
            double z = fromZ + (toZ - fromZ) * t;

            Box playerBox = makePlayerCollisionBox(x, y, z);

            if (openDoorIntersectsPlayerBox(world, doorBase, playerBox)) {
                return true;
            }
        }

        return false;
    }

    private static boolean openDoorIntersectsPlayerBox(ClientWorld world, BlockPos doorBase, Box playerBox) {
        return openDoorPartIntersectsPlayerBox(world, doorBase, playerBox)
                || openDoorPartIntersectsPlayerBox(world, doorBase.up(), playerBox);
    }

    private static boolean openDoorPartIntersectsPlayerBox(ClientWorld world, BlockPos pos, Box playerBox) {
        if (!isLoaded(world, pos)) {
            return false;
        }

        BlockState state = world.getBlockState(pos);

        if (!isOpenHandOpenableDoor(state)) {
            return false;
        }

        VoxelShape collision = state.getCollisionShape(world, pos);
        if (collision.isEmpty()) {
            return false;
        }

        for (Box localBox : collision.getBoundingBoxes()) {
            Box worldBox = localBox.offset(pos.getX(), pos.getY(), pos.getZ());

            if (worldBox.intersects(playerBox)) {
                return true;
            }
        }

        return false;
    }

    private static Box sweptPlayerBox(
            double fromX,
            double fromFeetY,
            double fromZ,
            double toX,
            double toFeetY,
            double toZ
    ) {
        Box from = makePlayerCollisionBox(fromX, fromFeetY, fromZ);
        Box to = makePlayerCollisionBox(toX, toFeetY, toZ);

        return new Box(
                Math.min(from.minX, to.minX),
                Math.min(from.minY, to.minY),
                Math.min(from.minZ, to.minZ),
                Math.max(from.maxX, to.maxX),
                Math.max(from.maxY, to.maxY),
                Math.max(from.maxZ, to.maxZ)
        );
    }

    private static boolean tryUseDoorOnPath(MinecraftClient client, ClientWorld world, long[] path, BlockPos waypoint) {
        if (client.player == null || client.interactionManager == null || autoClickCooldown > 0) {
            return false;
        }

        Direction pathDirection = getCurrentAutoPathDirection(client, path, waypoint);
        if (pathDirection == null) {
            return false;
        }

        BlockPos playerFeet = client.player.getBlockPos();
        BlockPos doorBase = findClosedDoorOnPathSegment(world, playerFeet, waypoint, pathDirection);

        if (doorBase == null) {
            return false;
        }

        Vec3d hitPos = getDoorClickPoint(doorBase);

        double reach = client.player.getBlockInteractionRange() + REACH_PADDING;
        if (client.player.getEyePos().squaredDistanceTo(hitPos) > reach * reach) {
            return false;
        }

        releaseAutoMovement(client);
        lookAt(client, hitPos);
        rightClickBlock(client, world, doorBase, hitPos);

        autoClickCooldown = AUTO_CLICK_COOLDOWN_TICKS;
        return true;
    }

    private static Direction getCurrentAutoPathDirection(MinecraftClient client, long[] path, BlockPos waypoint) {
        if (client.player == null) {
            return null;
        }

        if (path != null && path.length >= 2) {
            int index = MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);

            for (int i = index; i > 0; i--) {
                BlockPos from = BlockPos.fromLong(path[i - 1]);
                BlockPos to = BlockPos.fromLong(path[i]);

                Direction direction = horizontalDirectionBetween(from, to);
                if (direction != null) {
                    return direction;
                }
            }
        }

        Vec3d playerPos = client.player.getPos();

        double dx = waypoint.getX() + 0.5 - playerPos.x;
        double dz = waypoint.getZ() + 0.5 - playerPos.z;

        if (Math.abs(dx) < 0.05 && Math.abs(dz) < 0.05) {
            return null;
        }

        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0.0 ? Direction.EAST : Direction.WEST;
        }

        return dz > 0.0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static Direction horizontalDirectionBetween(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();

        if (dx == 0 && dz == 0) {
            return null;
        }

        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }

        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static BlockPos findClosedDoorOnPathSegment(
            ClientWorld world,
            BlockPos from,
            BlockPos waypoint,
            Direction pathDirection
    ) {
        BlockPos door;

        door = getClosedDoorBaseAt(world, waypoint, pathDirection);
        if (door != null) {
            return door;
        }

        door = getClosedDoorBaseAt(world, waypoint.up(), pathDirection);
        if (door != null) {
            return door;
        }

        door = getClosedDoorBaseAt(world, waypoint.down(), pathDirection);
        if (door != null) {
            return door;
        }

        door = getClosedDoorBaseAt(world, from, pathDirection);
        if (door != null) {
            return door;
        }

        door = getClosedDoorBaseAt(world, from.up(), pathDirection);
        if (door != null) {
            return door;
        }

        return getClosedDoorBaseAt(world, from.down(), pathDirection);
    }

    private static BlockPos getClosedDoorBaseAt(ClientWorld world, BlockPos pos, Direction pathDirection) {
        if (!isLoaded(world, pos)) {
            return null;
        }

        BlockState state = world.getBlockState(pos);

        if (!isClosedDoorBlockingPath(state, pathDirection)) {
            return null;
        }

        BlockPos base = getDoorBasePos(pos, state);

        if (!isLoaded(world, base)) {
            return null;
        }

        BlockState baseState = world.getBlockState(base);

        if (!isClosedDoorBlockingPath(baseState, pathDirection)) {
            return null;
        }

        return base;
    }

    private static boolean isClosedDoorBlockingPath(BlockState state, Direction pathDirection) {
        return isClosedHandOpenableDoor(state)
                && state.contains(DoorBlock.FACING)
                && state.get(DoorBlock.FACING).getAxis() == pathDirection.getAxis();
    }

    private static boolean isClosedHandOpenableDoor(BlockState state) {
        return isHandOpenableDoor(state)
                && state.contains(DoorBlock.OPEN)
                && !state.get(DoorBlock.OPEN)
                && state.contains(DoorBlock.HALF);
    }

    private static boolean isOpenHandOpenableDoor(BlockState state) {
        return isHandOpenableDoor(state)
                && state.contains(DoorBlock.OPEN)
                && state.get(DoorBlock.OPEN)
                && state.contains(DoorBlock.HALF);
    }

    private static BlockPos getDoorBasePos(BlockPos pos, BlockState state) {
        return state.contains(DoorBlock.HALF) && state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                ? pos.down()
                : pos;
    }

    private static Vec3d getDoorClickPoint(BlockPos doorBase) {
        return new Vec3d(doorBase.getX() + 0.5, doorBase.getY() + 0.75, doorBase.getZ() + 0.5);
    }

    private static void rightClickBlock(MinecraftClient client, ClientWorld world, BlockPos blockPos, Vec3d hitPos) {
        if (client.player == null || client.interactionManager == null) {
            return;
        }

        BlockHitResult hit = world.raycast(new RaycastContext(
                client.player.getEyePos(),
                hitPos,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                ShapeContext.absent()
        ));

        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(blockPos)) {
            hit = new BlockHitResult(hitPos, Direction.UP, blockPos, false);
        }

        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private static void lookAt(MinecraftClient client, Vec3d target) {
        if (client.player == null) {
            return;
        }

        Vec3d eye = client.player.getEyePos();

        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;

        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));

        pitch = MathHelper.clamp(pitch, -90.0f, 90.0f);

        client.player.setYaw(yaw);
        client.player.setPitch(pitch);
    }

    private static void applySideCorrection(MinecraftClient client, long[] path) {
        if (client.player == null) {
            return;
        }

        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);

        if (path.length < 2) {
            resetSideCorrection();
            return;
        }

        int currentIndex = MathHelper.clamp(AUTO_WAYPOINT_INDEX, 1, path.length - 1);

        if (AUTO_SIDE_CORRECTION_SEGMENT != currentIndex) {
            AUTO_SIDE_CORRECTION_SEGMENT = currentIndex;
            AUTO_SIDE_CORRECTION_DIR = 0;
        }

        BlockPos previous = BlockPos.fromLong(path[currentIndex - 1]);
        BlockPos current = BlockPos.fromLong(path[currentIndex]);

        int dxBlocks = Math.abs(current.getX() - previous.getX());
        int dzBlocks = Math.abs(current.getZ() - previous.getZ());

        if (dxBlocks != 0 && dzBlocks != 0) {
            resetSideCorrection();
            return;
        }

        Vec3d a = nodeCenter(previous);
        Vec3d b = nodeCenter(current);
        Vec3d p = client.player.getPos();

        double segmentX = b.x - a.x;
        double segmentZ = b.z - a.z;

        double lenSq = segmentX * segmentX + segmentZ * segmentZ;
        if (lenSq < 0.0001) {
            resetSideCorrection();
            return;
        }

        double playerX = p.x - a.x;
        double playerZ = p.z - a.z;

        double side = (segmentX * playerZ - segmentZ * playerX) / Math.sqrt(lenSq);

        if (AUTO_SIDE_CORRECTION_DIR == 0) {
            if (side > PATH_SIDE_CORRECTION_ENGAGE) {
                AUTO_SIDE_CORRECTION_DIR = -1;
            } else if (side < -PATH_SIDE_CORRECTION_ENGAGE) {
                AUTO_SIDE_CORRECTION_DIR = 1;
            }
        } else if (Math.abs(side) < PATH_SIDE_CORRECTION_RELEASE) {
            AUTO_SIDE_CORRECTION_DIR = 0;
        } else if (AUTO_SIDE_CORRECTION_DIR == -1 && side < -PATH_SIDE_CORRECTION_SWITCH) {
            AUTO_SIDE_CORRECTION_DIR = 1;
        } else if (AUTO_SIDE_CORRECTION_DIR == 1 && side > PATH_SIDE_CORRECTION_SWITCH) {
            AUTO_SIDE_CORRECTION_DIR = -1;
        }

        if (AUTO_SIDE_CORRECTION_DIR < 0) {
            client.options.leftKey.setPressed(true);
        } else if (AUTO_SIDE_CORRECTION_DIR > 0) {
            client.options.rightKey.setPressed(true);
        }
    }

    private static void resetSideCorrection() {
        AUTO_SIDE_CORRECTION_DIR = 0;
        AUTO_SIDE_CORRECTION_SEGMENT = -1;
    }
}
