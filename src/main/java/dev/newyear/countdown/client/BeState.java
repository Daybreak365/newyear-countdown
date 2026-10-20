package dev.newyear.countdown.client;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 블록 엔티티 렌더 상태: 이 모드의 렌더러는 애니메이션 값이 많아 블록 엔티티를 그대로 참조해 그린다
 * (추출과 그리기가 같은 렌더 스레드에서 차례로 일어난다).
 */
public class BeState<T extends BlockEntity> extends BlockEntityRenderState {
    public T be;
    public float partialTicks;
}
