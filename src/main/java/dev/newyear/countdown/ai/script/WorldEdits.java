package dev.newyear.countdown.ai.script;

import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.McUtil;
import dev.newyear.countdown.ai.script.lang.Interpreter;
import dev.newyear.countdown.ai.script.lang.ScriptError;
import dev.newyear.countdown.ai.script.lang.Values.JsObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 원래 세계를 바꾸는 소원: 블록 하나 바꾸기부터 언덕·호수·분화구·평탄화, 구·상자 채우기, 블록 바꿔치기, 자연 나무 키우기까지.
 * 모든 변경은 {@link Policy#checkBlock} 을 지나고(빈 공간·자연 지형만, 지은 것과 그 옆은 보호) ScriptRecord.blocks 에 기록되어
 * 롤백할 수 있다. 큰 변경은 작업(EditJob)으로 쌓아 두고 틱마다 조금씩 적용한다(서버 렉 없이, 재시작해도 이어서).
 */
public final class WorldEdits {
    private WorldEdits() {}

    public static final int MAX_JOBS_PER_SCRIPT = WorldEditQueue.MAX_PENDING_JOBS;

    // ---------------------------------------------------------------- 한 칸

    /**
     * 정책을 확인하고 블록을 바꾼 뒤 기록한다. gradual=true 면 틱당 한도를 세지 않는다 (작업 실행기가 속도를 정한다).
     * 모의 실행에서는 계획에만 넣는다.
     */
    public static boolean place(ScriptInstance inst, Interpreter in, ServerLevel l, BlockPos p, BlockState st, boolean temporary, double seconds, boolean gradual) {
        RuntimeConstruction.guard(inst,McUtil.dimId(l),p,McUtil.blockString(st));
        Policy.checkBlock(in, inst, l, p, st, gradual);
        String dim = McUtil.dimId(l);
        if (inst.dry) {
            inst.effect("setBlock " + McUtil.blockId(st) + " at " + p.toShortString());
            inst.plan(dim, p.getX(), p.getY(), p.getZ(), l.isLoaded(p) ? McUtil.blockString(l.getBlockState(p)) : "minecraft:air", McUtil.blockString(st), temporary || seconds > 0);
            return true;
        }
        BlockState before = l.getBlockState(p);
        ScriptRecord.BlockChange existing = null;
        if (inst.ownsBlock(dim, p.getX(), p.getY(), p.getZ())) {
            for (ScriptRecord.BlockChange b : inst.rec.blocks) if (b.x == p.getX() && b.y == p.getY() && b.z == p.getZ() && dim.equals(b.dimension)) existing = b;
        }
        boolean temp = temporary || seconds > 0;
        if (existing == null) {
            ScriptRecord.BlockChange ch = new ScriptRecord.BlockChange();
            ch.dimension = dim;
            ch.x = p.getX();
            ch.y = p.getY();
            ch.z = p.getZ();
            ch.before = McUtil.blockString(before);
            ch.temporary = temp;
            existing = ch;
            inst.rec.blocks.add(ch);
            inst.noteBlock(ch);
        }
        existing.after = McUtil.blockString(st);
        if (temp) existing.temporary = true;
        boolean ok = l.setBlock(p, st, 3);
        if (seconds > 0) WishEngine.get().scripts().scheduleRevert(inst.rec.id, existing, (long) (Math.min(3600, seconds) * 20));
        return ok;
    }

    // ---------------------------------------------------------------- 작업 만들기

    static final class Builder {
        final ScriptRecord.EditJob job = new ScriptRecord.EditJob();
        final Map<String, Integer> pal = new HashMap<>();
        final List<Integer> ops = new ArrayList<>();
        final int max;

        Builder(String kind, String dim) {
            job.kind = kind;
            job.dimension = dim;
            max = AiConfig.get().scriptMaxBlocks;
        }

        void add(int x, int y, int z, String state) {
            if (ops.size() / 4 >= max) throw new ScriptError(ScriptError.Kind.BUDGET, "This change is too big (over " + max + " blocks); make it smaller", 0, 0);
            Integer i = pal.get(state);
            if (i == null) {
                i = job.palette.size();
                pal.put(state, i);
                job.palette.add(state);
            }
            ops.add(x);
            ops.add(y);
            ops.add(z);
            ops.add(i);
        }

        ScriptRecord.EditJob build() {
            job.ops = new int[ops.size()];
            for (int k = 0; k < ops.size(); k++) job.ops[k] = ops.get(k);
            return job;
        }
    }

    /** 작업을 걸어 둔다 (모의 실행이면 전부 계획에 넣는다). 반환: 작업 id. */
    static String submit(ScriptInstance inst, Interpreter in, ServerLevel l, Builder b, JsObject opts) {
        ScriptRecord.EditJob job = b.build();
        job.id = "e" + (++inst.seq);
        job.temporary = dev.newyear.countdown.ai.script.api.Args.optBool(opts, "temporary", false);
        job.sparkleEvery = dev.newyear.countdown.ai.script.api.Args.optBool(opts, "sparkle", true) ? 6 : 0;
        // Even an empty job must complete through the FIFO so callers receive world_edit_done.
        AiConfig cfg = AiConfig.get();
        if (!inst.dry) for (int k=0;k<job.total();k++) RuntimeConstruction.guard(inst,job.dimension,
                new BlockPos(job.ops[k*4],job.ops[k*4+1],job.ops[k*4+2]),job.palette.get(job.ops[k*4+3]));
        // Reserve the entire pending plan before either previewing or executing it.
        // The tick loop processes this persistent FIFO one job at a time.
        WorldEditQueue.enqueue(inst.rec, job, cfg.scriptMaxBlocks);
        if (inst.dry) {
            int ok = 0;
            for (int k = 0; k < job.total(); k++) {
                BlockPos p = new BlockPos(job.ops[k * 4], job.ops[k * 4 + 1], job.ops[k * 4 + 2]);
                if (!matches(job, l, p, inst)) continue;
                try {
                    BlockState target = state(in, job, job.ops[k * 4 + 3]);
                    if (l.isLoaded(p) && l.getBlockState(p) == target) {
                        // No mutation is needed now, but this cell is still part of the promised design.
                        String block=McUtil.blockString(target);
                        inst.plan(job.dimension,p.getX(),p.getY(),p.getZ(),block,block,job.temporary);
                        continue;
                    }
                    place(inst, in, l, p, target, job.temporary, 0, true);
                    ok++;
                } catch (ScriptError e) {
                    if (e.kind != ScriptError.Kind.PERMISSION) throw e;
                    inst.blockedBuildCells++;
                    if (inst.blockedBuildReasons.size() < 8) inst.blockedBuildReasons.add(p.toShortString() + ": " + e.getMessage());
                }
            }
            inst.effect(job.kind + " " + ok + "/" + job.total() + " blocks (gradual)");
            return job.id;
        }
        return job.id;
    }

    private static final Map<String, BlockState> PARSED = new HashMap<>();

    static BlockState state(Interpreter in, ScriptRecord.EditJob job, int index) {
        String s = job.palette.get(index);
        BlockState st = PARSED.get(s);
        if (st == null) {
            st = McUtil.parseBlockString(s);
            if (st == null) st = Blocks.AIR.defaultBlockState();
            if (PARSED.size() < 4096) PARSED.put(s, st);
        }
        return st;
    }

    /** replace/clear 의 조건: 지금 그 칸이 바꿀 대상인지. */
    static boolean matches(ScriptRecord.EditJob job, ServerLevel l, BlockPos p, ScriptInstance inst) {
        if (job.match == null) return true;
        if (!l.isLoaded(p)) return false;
        BlockState cur = l.getBlockState(p);
        if (job.match.equals("natural")) return !cur.isAir() && !cur.liquid() && NaturalBlocks.isNatural(l, p, cur);
        return McUtil.blockId(cur).equals(job.match);
    }

    public static Builder builder(String kind, String dim) {
        return new Builder(kind, dim);
    }

    /** 구 (hollow 면 껍질만). */
    public static Builder sphere(String dim, double[] c, double r, String state, boolean hollow) {
        Builder b = new Builder("sphere", dim);
        int R = (int) Math.ceil(r);
        BlockPos cp = McUtil.blockPos(c);
        // 아래에서 위로 쌓는다 (모래·물이 받침 없이 쏟아지지 않게)
        for (int dy = -R; dy <= R; dy++) for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > r + 0.01 || (hollow && d < r - 1)) continue;
            b.add(cp.getX() + dx, cp.getY() + dy, cp.getZ() + dz, state);
        }
        return b;
    }

    public static Builder box(String dim, BlockPos a, BlockPos z, String state, boolean hollow) {
        Builder b = new Builder("box", dim);
        int x0 = Math.min(a.getX(), z.getX()), x1 = Math.max(a.getX(), z.getX());
        int y0 = Math.min(a.getY(), z.getY()), y1 = Math.max(a.getY(), z.getY());
        int z0 = Math.min(a.getZ(), z.getZ()), z1 = Math.max(a.getZ(), z.getZ());
        boolean digging = state.startsWith("minecraft:air");
        for (int i = 0; i <= y1 - y0; i++) {
            int y = digging ? y1 - i : y0 + i;      // 파낼 때는 위에서부터
            for (int x = x0; x <= x1; x++) for (int zz = z0; zz <= z1; zz++) {
                if (hollow && x != x0 && x != x1 && y != y0 && y != y1 && zz != z0 && zz != z1) continue;
                b.add(x, y, zz, state);
            }
        }
        return b;
    }

    /**
     * 지형 다듬기. shape: hill(언덕) / crater(분화구) / lake(호수) / flatten(평탄화). 높이는 가운데가 가장 크고 가장자리로 부드럽게 줄어든다.
     * 실제로 바뀌는 칸은 정책이 허락한 칸뿐이다 (지은 것과 그 옆은 그대로).
     */
    public static Builder terraform(ServerLevel l, double[] c, int r, int h, String shape, String surface, String fill, String liquid) {
        Builder b = new Builder("terraform:" + shape, McUtil.dimId(l));
        int cx = (int) Math.floor(c[0]), cz = (int) Math.floor(c[2]);
        int centerGround = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz) - 1;
        int waterline = Integer.MAX_VALUE;
        if (shape.equals("lake")) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                waterline = Math.min(waterline, l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx + dx, cz + dz) - 2);
            }
        }
        List<int[]> fills = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d2 = (dx * dx + dz * dz) / (double) (r * r);
                if (d2 > 1) continue;
                int x = cx + dx, z = cz + dz;
                int ground = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                int dh = (int) Math.round(h * (1 - d2));
                switch (shape) {
                    case "hill" -> {
                        for (int y = ground + 1; y <= ground + dh; y++) b.add(x, y, z, y == ground + dh ? surface : fill);
                    }
                    case "crater", "lake" -> {
                        if (dh <= 0) continue;
                        for (int y = ground; y > ground - dh; y--) b.add(x, y, z, "minecraft:air");
                        int bottom = ground - dh;
                        if (shape.equals("lake")) {
                            for (int y = bottom + 1; y <= Math.min(waterline, ground); y++) fills.add(new int[]{x, y, z});
                        } else {
                            b.add(x, bottom, z, surface);
                        }
                    }
                    default -> {     // flatten
                        if (ground > centerGround) {
                            for (int y = ground; y > centerGround; y--) b.add(x, y, z, "minecraft:air");
                            b.add(x, centerGround, z, surface);
                        } else if (ground < centerGround) {
                            for (int y = ground + 1; y <= centerGround; y++) b.add(x, y, z, y == centerGround ? surface : fill);
                        }
                    }
                }
            }
        }
        fills.sort((p, q) -> Integer.compare(p[1], q[1]));
        for (int[] f : fills) b.add(f[0], f[1], f[2], liquid);
        return b;
    }

    // ---------------------------------------------------------------- 나무

    /** 바닐라 나무 생성기로 진짜 나무를 키운다. 바뀐 칸은 모두 기록되어 롤백된다. 반환: 바뀐 칸 수 (실패하면 0). */
    public static int growTree(ScriptInstance inst, Interpreter in, ServerLevel l, BlockPos pos, String kind) {
        if(!inst.dry && RuntimeConstruction.plan(inst.rec)!=null)
            throw in.error("A saved build must use exact cells; put the tree's logs and leaves in the blueprint instead of generating a random tree");
        TreeGrower grower = switch (kind) {
            case "birch" -> TreeGrower.BIRCH;
            case "spruce" -> TreeGrower.SPRUCE;
            case "jungle" -> TreeGrower.JUNGLE;
            case "acacia" -> TreeGrower.ACACIA;
            case "cherry" -> TreeGrower.CHERRY;
            case "dark_oak" -> TreeGrower.DARK_OAK;
            case "pale_oak" -> TreeGrower.PALE_OAK;
            case "mangrove" -> TreeGrower.MANGROVE;
            case "azalea" -> TreeGrower.AZALEA;
            case "poplar" -> TreeGrower.POPLAR;
            case "oak" -> TreeGrower.OAK;
            default -> throw in.error("Unknown tree '" + kind + "'. Trees: oak, birch, spruce, jungle, acacia, cherry, dark_oak, pale_oak, mangrove, azalea, poplar");
        };
        Policy.checkBlock(in, inst, l, pos, Blocks.OAK_SAPLING.defaultBlockState(), true);
        if (inst.dry) {
            inst.approximateBuild=true;
            inst.effect("grow " + kind + " tree at " + pos.toShortString());
            for (int y = 0; y < 5; y++) inst.plan(McUtil.dimId(l), pos.getX(), pos.getY() + y, pos.getZ(), "minecraft:air", "minecraft:oak_log", false);
            return 5;
        }
        // 나무가 차지할 수 있는 상자를 미리 찍어 두고, 자란 뒤 달라진 칸만 기록한다
        BlockPos lo = pos.offset(-7, -1, -7), hi = pos.offset(7, 30, 7);
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) if (l.isLoaded(p)) before.put(p.immutable(), l.getBlockState(p));
        Block sapling = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(kind + "_sapling"));
        BlockState sap = sapling == null || sapling == Blocks.AIR ? Blocks.OAK_SAPLING.defaultBlockState() : sapling.defaultBlockState();
        l.setBlock(pos, sap, 3);
        boolean grown = grower.growTree(l, l.getChunkSource().getGenerator(), pos, sap, l.getRandom());
        String dim = McUtil.dimId(l);
        int changed = 0;
        for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
            BlockState now = l.getBlockState(e.getKey());
            if (now == e.getValue()) continue;
            BlockPos p = e.getKey();
            BlockState was = e.getValue();
            // 나무 생성기가 혹시 지은 것을 덮었다면 되돌린다
            if (!(was.isAir() || was.canBeReplaced() || was.liquid() || NaturalBlocks.isNatural(l, p, was)) || was.hasBlockEntity()) {
                l.setBlock(p, was, 3);
                continue;
            }
            if (!inst.ownsBlock(dim, p.getX(), p.getY(), p.getZ())) {
                ScriptRecord.BlockChange ch = new ScriptRecord.BlockChange();
                ch.dimension = dim;
                ch.x = p.getX();
                ch.y = p.getY();
                ch.z = p.getZ();
                ch.before = McUtil.blockString(was);
                ch.after = McUtil.blockString(now);
                inst.rec.blocks.add(ch);
                inst.noteBlock(ch);
            }
            changed++;
        }
        if (!grown) l.setBlock(pos, before.getOrDefault(pos, Blocks.AIR.defaultBlockState()), 3);
        return grown ? changed : 0;
    }

    // ---------------------------------------------------------------- 실행 (서버 틱)

    /** 이 스크립트의 작업을 budget 칸만큼 진행한다. 반환: 처리한 칸 수. 끝난 작업은 world_edit_done 이벤트를 보낸다. */
    public static int tick(ScriptManager mgr, ScriptInstance inst, int budget) {
        int used = 0;
        WishEngine eng = WishEngine.get();
        while (used < budget && !inst.rec.edits.isEmpty() && inst.rec.state.live()) {
            ScriptRecord.EditJob job = inst.rec.edits.get(0);
            ServerLevel l = McUtil.level(eng.server(), job.dimension);
            boolean approved=RuntimeConstruction.owns(inst,job);
            if(approved && !RuntimeConstruction.plan(inst.rec).pending()){inst.rec.edits.clear();return used;}
            if(approved && RuntimeConstruction.plan(inst.rec).needsApproval(inst.rec)) return used;
            if(approved && l==null){RuntimeConstruction.waiting(RuntimeConstruction.plan(inst.rec),"WAITING_SITE","건축 차원이 준비되지 않았습니다.",inst);return used;}
            while (used < budget && job.next < job.total() && l != null) {
                int k = job.next++;
                used++;
                BlockPos p = new BlockPos(job.ops[k * 4], job.ops[k * 4 + 1], job.ops[k * 4 + 2]);
                if(approved && !l.isLoaded(p)){job.next--;RuntimeConstruction.waiting(RuntimeConstruction.plan(inst.rec),"WAITING_SITE",p.toShortString()+": 청크 로드를 기다립니다.",inst);return used;}
                if (!matches(job, l, p, inst)) {
                    job.skipped++;
                    continue;
                }
                try {
                    BlockState st = state(inst.in, job, job.ops[k * 4 + 3]);
                    if (l.getBlockState(p) == st) continue;
                    if(approved && BuildPlan.matchesPlaced(McUtil.blockString(st),McUtil.blockString(l.getBlockState(p)))) continue;
                    if(approved && RuntimeConstruction.occupied(l,p,st)){
                        job.next--;
                        RuntimeConstruction.waiting(RuntimeConstruction.plan(inst.rec),"WAITING_SITE",p.toShortString()+": 청크 로드 또는 사람·생물이 비켜나기를 기다립니다.",inst);
                        return used;
                    }
                    if (!place(inst, inst.in, l, p, st, job.temporary, 0, true)) {
                        if(approved){job.next--;RuntimeConstruction.waiting(RuntimeConstruction.plan(inst.rec),"WAITING_SITE",p.toShortString()+": 블록 배치를 다시 시도할 때까지 기다립니다.",inst);return used;}
                        job.skipped++; continue;
                    }
                    if(approved){var plan=RuntimeConstruction.plan(inst.rec);plan.status="BUILDING";plan.reason="";}
                    job.placed++;
                    if (job.sparkleEvery > 0 && job.placed % job.sparkleEvery == 0) {
                        l.sendParticles(ParticleTypes.END_ROD, p.getX() + 0.5, p.getY() + 0.8, p.getZ() + 0.5, 1, 0.2, 0.2, 0.2, 0.01);
                    }
                } catch (ScriptError e) {
                    if(approved){job.next--;RuntimeConstruction.waiting(RuntimeConstruction.plan(inst.rec),"WAITING_SITE",p.toShortString()+": "+e.getMessage(),inst);return used;}
                    job.skipped++;      // 지은 것·범위 밖·로드 안 된 칸은 건너뛴다
                }
            }
            if (l == null || job.next >= job.total()) {
                if (l == null) { job.skipped += job.total() - job.next; job.next = job.total(); }
                inst.rec.edits.remove(0);
                if(approved) RuntimeConstruction.completed(inst);
                mgr.emit("world_edit_done", WorldEditQueue.completion(job),
                        new ScriptManager.Ctx(null, null, null, null, inst.rec.id));
            }
        }
        return used;
    }
}
