package dev.newyear.countdown;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Set;
import java.util.function.Function;

/** 블록·아이템·블록 엔티티·엔티티 등록 도우미 (등록 키를 속성에 먼저 넣어야 한다). */
public final class ModReg {
    private ModReg() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NewYearCountdown.MOD_ID, path);
    }

    public static <B extends Block> B block(String name, Function<BlockBehaviour.Properties, B> factory, BlockBehaviour.Properties props) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id(name));
        return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(props.setId(key)));
    }

    public static <I extends Item> I item(String name, Function<Item.Properties, I> factory, Item.Properties props) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
        return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(props.setId(key)));
    }

    /** 블록을 놓는 아이템 (이름은 블록 번역 키를 쓴다). */
    public static <I extends Item> I blockItem(String name, Function<Item.Properties, I> factory, Item.Properties props) {
        return item(name, factory, props.useBlockDescriptionPrefix());
    }

    public static <T extends BlockEntity> BlockEntityType<T> blockEntity(String name, BlockEntityType.BlockEntitySupplier<T> factory, Block block) {
        return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id(name), new BlockEntityType<>(factory, Set.of(block)));
    }

    public static <T extends Entity> EntityType<T> entity(String name, EntityType.Builder<T> builder) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id(name));
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
    }
}
