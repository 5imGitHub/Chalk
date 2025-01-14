package io.github.mortuusars.chalk.items;

import com.mojang.datafixers.util.Pair;
import io.github.mortuusars.chalk.blocks.MarkSymbol;
import io.github.mortuusars.chalk.core.ChalkMark;
import io.github.mortuusars.chalk.menus.ChalkBoxItemStackHandler;
import io.github.mortuusars.chalk.menus.ChalkBoxMenu;
import io.github.mortuusars.chalk.setup.ModSoundEvents;
import io.github.mortuusars.chalk.setup.ModTags;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class ChalkBoxItem extends Item {

    public ChalkBoxItem(Properties properties) {
        super(properties.setNoRepair());
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @Nullable Level pLevel, @NotNull List<Component> tooltipComponents, @NotNull TooltipFlag isAdvanced) {
        Pair<ItemStack, Integer> firstChalkStack = getFirstChalkStack(stack);
        if (firstChalkStack != null) {
            tooltipComponents.add(Component.translatable("item.chalk.chalk_box.tooltip.drawing_with").withStyle(ChatFormatting.GRAY)
                    .append( ((MutableComponent) firstChalkStack.getFirst().getHoverName()).withStyle(ChatFormatting.ITALIC).withStyle(ChatFormatting.WHITE)));
        }
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        // Mark will be drawn even if block can be activated (if shift is held)
        if (context.getPlayer() != null && context.getPlayer().isSecondaryUseActive())
            return useOn(context);

        return InteractionResult.PASS;
    }

    @Override
    public @NotNull InteractionResult useOn(UseOnContext context) {
        ItemStack chalkBoxStack = context.getItemInHand();
        if (!chalkBoxStack.is(this))
            return InteractionResult.FAIL;

        Player player = context.getPlayer();
        if (player == null)
            return InteractionResult.PASS;

        if (context.getHand() == InteractionHand.OFF_HAND && (player.getMainHandItem().is(ModTags.Items.CHALK) || player.getMainHandItem().is(this)) )
            return InteractionResult.FAIL; // Skip drawing from offhand if chalks in both hands.

        Level level = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        Direction clickedFace = context.getClickedFace();

        Pair<ItemStack, Integer> chalkStack = getFirstChalkStack(chalkBoxStack);

        if ( chalkStack == null || !ChalkMark.canBeDrawnAt(clickedPos.relative(clickedFace), clickedPos, clickedFace, level) )
            return InteractionResult.FAIL;

        MarkSymbol symbol = context.isSecondaryUseActive() ? MarkSymbol.CROSS : MarkSymbol.NONE;
        DyeColor chalkColor = ((ChalkItem) chalkStack.getFirst().getItem()).getColor();
        final boolean isGlowing = ChalkBox.getGlowingUses(chalkBoxStack) > 0;

        if (ChalkMark.draw(symbol, chalkColor, isGlowing, clickedPos, clickedFace, context.getClickLocation(), level) == InteractionResult.SUCCESS) {
            if ( !player.isCreative() ) {
                ItemStack chalkItemStack = chalkStack.getFirst();

                if (chalkItemStack.isDamageableItem()) {
                    chalkItemStack.setDamageValue(chalkItemStack.getDamageValue() + 1);
                    if (chalkItemStack.getDamageValue() >= chalkItemStack.getMaxDamage()){
                        chalkItemStack = ItemStack.EMPTY;
                        Vec3 playerPos = player.position();
                        level.playSound(player, playerPos.x, playerPos.y, playerPos.z, ModSoundEvents.CHALK_BROKEN.get(),
                                SoundSource.PLAYERS, 0.9f, 0.9f + level.random.nextFloat() * 0.2f);
                    }
                }

                ChalkBox.setSlot(chalkBoxStack, chalkStack.getSecond(), chalkItemStack);

                if (isGlowing)
                    ChalkBox.useGlow(chalkBoxStack);
            }

            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }

        return InteractionResult.FAIL;
    }

    // Called when not looking at a block
    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(@NotNull Level level, Player player, @NotNull InteractionHand usedHand) {
        ItemStack usedStack = player.getItemInHand(usedHand);

        if (!usedStack.is(this))
            return InteractionResultHolder.pass(usedStack);

        if (player.isSecondaryUseActive()) {
            changeSelectedChalk(usedStack);
            level.playSound(player, player.position().x, player.position().y, player.position().z, ModSoundEvents.CHALK_BOX_CHANGE.get(), SoundSource.PLAYERS,
                    0.9f, 0.9f + level.random.nextFloat() * 0.2f);
        }
        else {
            if (!level.isClientSide) {
                NetworkHooks.openGui((ServerPlayer) player,
                        new SimpleMenuProvider( (containerID, playerInventory, playerEntity) ->
                                new ChalkBoxMenu(containerID, playerInventory, usedStack, new ChalkBoxItemStackHandler(usedStack)),
                                usedStack.getHoverName()), buffer -> buffer.writeItem(usedStack.copy()));
            }
            level.playSound(player, player.position().x, player.position().y, player.position().z, ModSoundEvents.CHALK_BOX_OPEN.get(), SoundSource.PLAYERS,
                    0.9f, 0.9f + level.random.nextFloat() * 0.2f);
        }

        return InteractionResultHolder.sidedSuccess(usedStack, level.isClientSide);
    }

    /**
     * Shifts stacks until first slot is changed to another chalk.
     */
    private void changeSelectedChalk(ItemStack usedStack) {
        List<ItemStack> stacks = new ArrayList<>(16);
        int chalks = 0;
        for (int slot = 0; slot < ChalkBox.CHALK_SLOTS; slot++) {
            ItemStack slotStack = ChalkBox.getItemInSlot(usedStack, slot);
            stacks.add(slotStack);
            if (!slotStack.isEmpty())
                chalks++;
        }

        if (chalks >= 2) {
            DyeColor selectedColor = ((ChalkItem) getFirstChalkStack(usedStack).getFirst().getItem()).getColor();
            ItemStack firstStack = stacks.get(0);

            for (int i = 0; i < 8; i++) {
                ItemStack stack = stacks.get(0);
                stacks.remove(stack);
                stacks.add(stack);

                stack = stacks.get(0);

                if (stack.is(ModTags.Items.CHALK) && !stack.equals(firstStack, false)
                        && !((ChalkItem)stack.getItem()).getColor().equals(selectedColor)) {
                    break;
                }
            }

            ChalkBox.setContents(usedStack, stacks);
        }
    }

    private @Nullable Pair<ItemStack, Integer> getFirstChalkStack(ItemStack chalkBoxStack) {
        for (int slot = 0; slot < ChalkBox.CHALK_SLOTS; slot++) {
            ItemStack itemInSlot = ChalkBox.getItemInSlot(chalkBoxStack, slot);
            if (itemInSlot.is(ModTags.Items.CHALK)) {
                return Pair.of(itemInSlot, slot);
            }
        }

        return null;
    }

    // Used by ItemOverrides to determine what chalk to display with the item texture.
    public float getSelectedChalkColor(ItemStack stack){
        if (stack.hasTag()) {
            for (int i = 0; i < ChalkBox.CHALK_SLOTS; i++) {
                if (ChalkBox.getItemInSlot(stack, i).getItem() instanceof ChalkItem chalkItem)
                    return chalkItem.getColor().getId() + 1;
            }
        }

        return 0f;
    }

    @Override
    public boolean isRepairable(@NotNull ItemStack stack) {
        return false;
    }

    @Override
    public boolean isEnchantable(@NotNull ItemStack stack) {
        return false;
    }

    @Override
    public boolean isBookEnchantable(ItemStack stack, ItemStack book) {
        return false;
    }

    @Override
    public boolean canApplyAtEnchantingTable(ItemStack stack, Enchantment enchantment) {
        return false;
    }
}
