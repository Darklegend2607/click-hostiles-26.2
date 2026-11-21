package com.clickhostiles;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

public class ClickHostiles implements ModInitializer {
    public static final String MOD_ID = "clickhostiles";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final String KEY = "clickhostiles_mob";

    private static final Set<EntityType<?>> BLOCKED =
            Set.of(EntityTypes.ENDER_DRAGON, EntityTypes.WITHER, EntityTypes.WARDEN);

    @Override
    public void onInitialize() {
        MobHeads.load();
        UseEntityCallback.EVENT.register(this::onUseEntity);
        UseBlockCallback.EVENT.register(this::onUseBlock);
        UseItemCallback.EVENT.register(this::onUseItem);
    }

    private static void overlay(Player player, String text) {
        if (player instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal(text), true);
        }
    }

    // ---- pick up -------------------------------------------------------------------------------

    private InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity,
                                          EntityHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown() || player.isSpectator()) {
            return InteractionResult.PASS;
        }
        if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        if (entity.getType().getCategory() != MobCategory.MONSTER || BLOCKED.contains(entity.getType())) {
            return InteractionResult.PASS;
        }

        if (level.isClientSide()) return InteractionResult.SUCCESS;
        ServerLevel serverLevel = (ServerLevel) level;

        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, serverLevel.registryAccess());
        if (!entity.save(out)) {
            overlay(player, "Can't pick this mob up right now.");
            return InteractionResult.FAIL;
        }
        CompoundTag mob = out.buildResult();
        mob.remove("UUID");

        // Head that looks like the mob (vanilla head or textured player head)
        ItemStack stack = MobHeads.headFor(entity.getType());

        CompoundTag root = new CompoundTag();
        root.put(KEY, mob);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Captured ").append(entity.getName())
                .withStyle(s -> s.withItalic(false).withColor(0xFFFF55)));
        if (entity instanceof LivingEntity living) {
            int hp = Math.round(living.getHealth());
            int max = Math.round(living.getMaxHealth());
            stack.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("Health: " + hp + " / " + max)
                            .withStyle(s -> s.withItalic(false).withColor(0xAAAAAA)))));
        }

        serverLevel.sendParticles(ParticleTypes.POOF, entity.getX(), entity.getY(0.5), entity.getZ(),
                10, 0.3, 0.3, 0.3, 0.02);
        level.playSound(null, entity.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1.0f, 1.0f);

        entity.discard();
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        return InteractionResult.SUCCESS;
    }

    // ---- place back ----------------------------------------------------------------------------

    private InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (player.isSpectator()) return InteractionResult.PASS;
        ItemStack stack = player.getItemInHand(hand);
        CompoundTag data = getMobData(stack);
        if (data == null) return InteractionResult.PASS;

        // Always swallow the click so the head is not placed as a block.
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        ServerLevel serverLevel = (ServerLevel) level;

        BlockPos clicked = hit.getBlockPos();
        BlockState state = level.getBlockState(clicked);
        BlockPos target = state.canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
        if (!level.mayInteract(player, target)) return InteractionResult.FAIL;

        Vec3 pos = Vec3.atBottomCenterOf(target);
        float yaw = player.getYRot() + 180.0f;
        CompoundTag copy = data.copy();
        copy.remove("UUID");

        ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING, serverLevel.registryAccess(), copy);
        Entity mob = EntityType.loadEntityRecursive(in, level, EntitySpawnReason.SPAWN_ITEM_USE, e -> {
            e.snapTo(pos.x, pos.y, pos.z, yaw, 0.0f);
            e.setDeltaMovement(Vec3.ZERO);
            return e;
        });
        if (mob == null || !serverLevel.addFreshEntityWithPassengers(mob)) {
            overlay(player, "Couldn't place the mob here.");
            return InteractionResult.FAIL;
        }

        level.gameEvent(player, GameEvent.ENTITY_PLACE, target);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        return InteractionResult.SUCCESS;
    }

    /** Stop captured heads from being worn by right-clicking in the air. */
    private InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (getMobData(player.getItemInHand(hand)) != null) return InteractionResult.FAIL;
        return InteractionResult.PASS;
    }

    private static CompoundTag getMobData(ItemStack stack) {
        if (stack.isEmpty()) return null;
        CustomData comp = stack.get(DataComponents.CUSTOM_DATA);
        if (comp == null) return null;
        return comp.copyTag().getCompound(KEY).orElse(null);
    }
}
