package dev.newyear.countdown.gacha;

import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/** 윷 세트: 바닥에 까는 윷판 블록 아이템. */
public class YutBoardItem extends BlockItem {
    public YutBoardItem(Block block, Settings settings) {
        super(block, settings);
    }

    @Override
    public String getTranslationKey() {
        return "item.newyearcountdown.yut_set";
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.newyearcountdown.yut_set.desc").formatted(Formatting.GRAY));
        super.appendTooltip(stack, context, tooltip, type);
    }
}
