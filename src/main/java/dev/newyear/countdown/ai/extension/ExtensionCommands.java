package dev.newyear.countdown.ai.extension;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import dev.newyear.countdown.ai.WishEngine;

public final class ExtensionCommands {
    private ExtensionCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wishai").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("extension")
                .executes(c -> run(c, manager -> WishEngine.get().state().extensions.values().stream()
                    .map(d -> d.id + " " + d.name + " " + d.status).collect(java.util.stream.Collectors.joining("\n"))))
                .then(Commands.argument("id", StringArgumentType.word())
                    .executes(c -> run(c, m -> m.status(null, id(c)).toString()))
                    .then(Commands.literal("test").executes(c -> run(c, m -> { m.test(id(c)); return "격리 테스트 작업을 시작했습니다: " + id(c); })))
                    .then(Commands.literal("approve").then(Commands.argument("sha256", StringArgumentType.word()).executes(c -> run(c, m -> {
                        m.approve(id(c), StringArgumentType.getString(c, "sha256"), c.getSource().getTextName());
                        return m.status(null, id(c)).get("status").getAsString();
                    }))))
                    .then(Commands.literal("stage").executes(c -> run(c, m -> "재시작용 모드 배치 완료: " + m.stage(id(c)))))
                    .then(Commands.literal("disable").executes(c -> run(c, m -> { m.disable(id(c)); return "확장 비활성화: " + id(c); }))))));
    }
    private static String id(CommandContext<CommandSourceStack> c) { return StringArgumentType.getString(c,"id"); }
    private static int run(CommandContext<CommandSourceStack> c, java.util.function.Function<NativeExtensions,String> action) {
        try {
            WishEngine engine = WishEngine.get();
            if (engine == null) throw new IllegalStateException("Wish engine is not running");
            String text = action.apply(engine.extensions());
            c.getSource().sendSuccess(() -> Component.literal(text.isEmpty() ? "확장 없음" : text), false);
            return 1;
        } catch (RuntimeException e) { c.getSource().sendFailure(Component.literal(String.valueOf(e.getMessage()))); return 0; }
    }
}
