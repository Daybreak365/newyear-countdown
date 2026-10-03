package dev.newyear.countdown.client;

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
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

public class NewYearCountdownClient implements ClientModInitializer {
    private static KeyBinding openSettings;
    private static KeyBinding openOmikuji;
    private static KeyBinding openWishes;

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
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> DebugAccess.sendAuth());

        HudRenderCallback.EVENT.register(CountdownHud::render);
        HudRenderCallback.EVENT.register(BellSession::renderHud);
        HudRenderCallback.EVENT.register(OmikujiHud::render);

        // 오미쿠지 칸: 한 번이라도 받았다면 인벤토리 옆에 나타난다
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof InventoryScreen || screen instanceof CreativeInventoryScreen) {
                net.minecraft.client.gui.screen.ingame.HandledScreen<?> hs = (net.minecraft.client.gui.screen.ingame.HandledScreen<?>) screen;
                if (OmikujiBook.size() > 0) Screens.getButtons(screen).add(new OmikujiSlotWidget(hs));
                if (WishBook.size() > 0) Screens.getButtons(screen).add(new WishSlotWidget(hs));
            }
        });

        // 보신각: 렌더러 + 패킷 처리
        BlockEntityRendererFactories.register(ModBlocks.BELL_BE, BellRenderer::new);
        BlockEntityRendererFactories.register(OmikujiBlocks.OMIKUJI_BE, OmikujiRenderer::new);
        BlockEntityRendererFactories.register(dev.newyear.countdown.gacha.GachaBlocks.GACHA_BE, GachaRenderer::new);
        net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.ITEM.register(
                (stack, tint) -> tint == 0 ? 0xFF000000 | dev.newyear.countdown.gacha.CapsuleItem.RGB[dev.newyear.countdown.gacha.CapsuleItem.colorIndex(stack)] : -1,
                dev.newyear.countdown.gacha.GachaBlocks.CAPSULE);
        ClientPlayNetworking.registerGlobalReceiver(OmikujiPackets.AnimS2C.ID, (payload, context) -> {
            MinecraftClient mc = context.client();
            if (mc.world != null && mc.world.getBlockEntity(payload.pos()) instanceof OmikujiBlockEntity be) {
                be.animStart = mc.world.getTime();
                be.animResult = payload.result();
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(OmikujiPackets.ResultS2C.ID, (payload, context) -> {
            OmikujiBook.Entry e = new OmikujiBook.Entry(payload.result(), payload.number(), payload.time());
            int idx = OmikujiBook.add(e);               // 시간순 인덱스: 0 = 첫 뽑기, 1.. = 추가 뽑기 회차
            context.client().setScreen(new OmikujiResultScreen(e, idx));
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
        // 소원 연등
        EntityRendererRegistry.register(WishEntities.LANTERN, WishLanternRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(WishPackets.OpenS2C.ID, (payload, context) -> context.client().setScreen(new WishScreen()));
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
        WorldRenderEvents.AFTER_ENTITIES.register(BellPreview::render);
        ClientTickEvents.START_CLIENT_TICK.register(BellSession::tick);
        ClientTickEvents.END_CLIENT_TICK.register(BellSession::follow);

        // 조작(Controls)에 등록되는 키. 기본값은 미지정.
        openSettings = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.newyearcountdown.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.newyearcountdown"));
        openOmikuji = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.newyearcountdown.omikuji", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.newyearcountdown"));
        openWishes = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.newyearcountdown.wishes", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.newyearcountdown"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CountdownTicker.tick(client);
            while (openWishes.wasPressed()) {
                if (WishBook.size() > 0 && client.currentScreen == null) client.setScreen(new WishBookScreen());
            }
            while (openOmikuji.wasPressed()) {
                if (OmikujiBook.size() > 0 && client.currentScreen == null) client.setScreen(new OmikujiBookScreen());
            }
            while (openSettings.wasPressed()) {
                client.setScreen(new SettingsScreen(client.currentScreen));
            }
        });
    }

    private static BosingakBellBlockEntity bellAt(MinecraftClient mc, BlockPos pos) {
        if (mc.world == null) return null;
        BlockEntity be = mc.world.getBlockEntity(pos);
        return be instanceof BosingakBellBlockEntity b ? b : null;
    }
}
