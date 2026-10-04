package dev.newyear.countdown.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.newyear.countdown.SyncPayload;
import dev.newyear.countdown.bell.BellPackets;
import dev.newyear.countdown.bell.BellUsers;
import dev.newyear.countdown.bell.BosingakBellBlockEntity;
import dev.newyear.countdown.bell.ModBlocks;
import dev.newyear.countdown.omikuji.OmikujiBlockEntity;
import dev.newyear.countdown.omikuji.OmikujiBlocks;
import dev.newyear.countdown.omikuji.OmikujiPackets;
import dev.newyear.countdown.wish.WishEntities;
import dev.newyear.countdown.wish.WishPackets;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import dev.newyear.countdown.ModReg;

public class NewYearCountdownClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(ModReg.id("main"));
    private static KeyMapping openSettings;
    private static KeyMapping openWishes;
    private static KeyMapping sebae;

    @Override
    public void onInitializeClient() {
        ClientConfig.load();

        ClientPlayNetworking.registerGlobalReceiver(SyncPayload.ID, (payload, context) -> ClientCountdown.onSync(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientCountdown.reset();
            BellSession.stop(client, false);
            BellCamera.reset();
            BellUsers.clear();
            OmikujiBook.clear();
            OmikujiHud.sync();
            WishBook.clear();
            SebaePose.clear();
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> DebugAccess.sendAuth());

        HudElementRegistry.addLast(ModReg.id("countdown"), CountdownHud::render);
        HudElementRegistry.addLast(ModReg.id("bell"), BellSession::renderHud);
        HudElementRegistry.addLast(ModReg.id("omikuji"), OmikujiHud::render);

        // 오미쿠지 칸: 한 번이라도 받았다면 인벤토리 옆에 나타난다
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen) {
                net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> hs = (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) screen;
                if (OmikujiBook.size() > 0) Screens.getWidgets(screen).add(new OmikujiSlotWidget(hs));
                if (WishBook.size() > 0) Screens.getWidgets(screen).add(new WishSlotWidget(hs));
            }
        });

        // 보신각: 렌더러 + 패킷 처리
        BlockEntityRenderers.register(ModBlocks.BELL_BE, BellRenderer::new);
        BlockEntityRenderers.register(OmikujiBlocks.OMIKUJI_BE, OmikujiRenderer::new);
        BlockEntityRenderers.register(dev.newyear.countdown.gacha.GachaBlocks.GACHA_BE, GachaRenderer::new);
        BlockEntityRenderers.register(dev.newyear.countdown.gacha.Souvenirs.YUT_BE, YutBoardRenderer::new);
        ItemAnim.register();   // 캡슐/기념품 모델 상태 전환 (흔들림·열림·불꽃…)
        ClientPlayNetworking.registerGlobalReceiver(OmikujiPackets.AnimS2C.ID, (payload, context) -> {
            Minecraft mc = context.client();
            if (mc.level != null && mc.level.getBlockEntity(payload.pos()) instanceof OmikujiBlockEntity be) {
                be.animStart = mc.level.getGameTime();
                be.animResult = payload.result();
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(OmikujiPackets.ResultS2C.ID, (payload, context) -> {
            OmikujiBook.Entry e = new OmikujiBook.Entry(payload.result(), payload.number(), payload.time());
            int idx = OmikujiBook.add(e);               // 시간순 인덱스: 0 = 첫 뽑기, 1.. = 추가 뽑기 회차
            context.client().gui.setScreen(new OmikujiResultScreen(e, idx));
        });
        ClientPlayNetworking.registerGlobalReceiver(OmikujiPackets.BookS2C.ID, (payload, context) -> {
            java.util.List<OmikujiBook.Entry> list = new java.util.ArrayList<>();
            int n = Math.min(payload.results().size(), Math.min(payload.numbers().size(), payload.times().size()));
            for (int i = 0; i < n; i++) {
                list.add(new OmikujiBook.Entry(payload.results().get(i), payload.numbers().get(i), payload.times().get(i)));
            }
            OmikujiBook.replace(list);
            OmikujiHud.sync();
        });
        // 오미쿠지 관리자 콘솔 (뽑기대 뒤편 버튼)
        ClientPlayNetworking.registerGlobalReceiver(dev.newyear.countdown.omikuji.OmikujiAdmin.OpenS2C.ID,
                (payload, context) -> context.client().gui.setScreen(new OmikujiAdminScreen()));
        ClientPlayNetworking.registerGlobalReceiver(dev.newyear.countdown.omikuji.OmikujiAdmin.ListS2C.ID,
                (payload, context) -> OmikujiAdminScreen.onList(payload));
        // 컬링
        EntityRendererRegistry.register(dev.newyear.countdown.curling.Curling.STONE, CurlingStoneRenderer::new);
        BlockEntityRenderers.register(dev.newyear.countdown.curling.Curling.HOUSE_BE, CurlingHouseRenderer::new);
        // 소원 연등
        EntityRendererRegistry.register(WishEntities.LANTERN, WishLanternRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(WishPackets.OpenS2C.ID, (payload, context) -> context.client().gui.setScreen(new WishScreen()));
        ClientPlayNetworking.registerGlobalReceiver(WishPackets.BookS2C.ID, (payload, context) -> {
            java.util.List<WishBook.Wish> list = new java.util.ArrayList<>();
            int n = Math.min(payload.texts().size(), payload.times().size());
            for (int i = 0; i < n; i++) list.add(new WishBook.Wish(payload.texts().get(i), payload.times().get(i)));
            WishBook.replace(list);
        });
        ClientPlayNetworking.registerGlobalReceiver(BellPackets.EngageS2C.ID,
                (payload, context) -> BellSession.start(context.client(), payload.pos()));
        ClientPlayNetworking.registerGlobalReceiver(BellPackets.ForceExitS2C.ID,
                (payload, context) -> BellSession.stop(context.client(), false));
        ClientPlayNetworking.registerGlobalReceiver(BellPackets.AngleS2C.ID, (payload, context) -> {
            BosingakBellBlockEntity bell = bellAt(context.client(), payload.pos());
            if (bell != null && !bell.localControlled) bell.strikerTarget = payload.angle();
        });
        ClientPlayNetworking.registerGlobalReceiver(BellPackets.RingS2C.ID, (payload, context) -> {
            BosingakBellBlockEntity bell = bellAt(context.client(), payload.pos());
            if (bell != null) bell.onRingClient(payload.strength());
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(BellPreview::render);
        LevelRenderEvents.COLLECT_SUBMITS.register(CurlingPreview::render);
        ClientTickEvents.START_CLIENT_TICK.register(BellSession::tick);
        ClientTickEvents.END_CLIENT_TICK.register(BellSession::follow);

        // 조작(Controls)에 등록되는 키. 기본값은 미지정.
        openSettings = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.newyearcountdown.settings", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(),
                CATEGORY));
        openWishes = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.newyearcountdown.wishes", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(),
                CATEGORY));
        sebae = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.newyearcountdown.sebae", InputConstants.Type.KEYBOARD, InputConstants.KEY_B,
                CATEGORY));
        ClientPlayNetworking.registerGlobalReceiver(dev.newyear.countdown.sebae.Sebae.StateS2C.ID, (payload, context) -> {
            if (payload.bowing()) SebaePose.start(payload.player());
            else SebaePose.stop(payload.player());
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CountdownTicker.tick(client);
            while (openWishes.consumeClick()) {
                if (WishBook.size() > 0 && client.gui.screen() == null) client.gui.setScreen(new WishBookScreen());
            }
            while (sebae.consumeClick()) {
                if (client.gui.screen() == null && client.player != null) {
                    ClientPlayNetworking.send(new dev.newyear.countdown.sebae.Sebae.StartC2S());
                }
            }
            while (openSettings.consumeClick()) {
                client.gui.setScreen(new SettingsScreen(client.gui.screen()));
            }
        });
    }

    private static BosingakBellBlockEntity bellAt(Minecraft mc, BlockPos pos) {
        if (mc.level == null) return null;
        BlockEntity be = mc.level.getBlockEntity(pos);
        return be instanceof BosingakBellBlockEntity b ? b : null;
    }
}
