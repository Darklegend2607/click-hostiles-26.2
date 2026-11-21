package com.clickhostiles;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Picks the head item that represents a captured mob. */
public final class MobHeads {
    /** entity id path (e.g. "blaze") -> texture hash on textures.minecraft.net */
    private static final Map<String, String> HASHES = new HashMap<>();
    private static final Map<EntityType<?>, Item> VANILLA = new HashMap<>();

    private MobHeads() {}

    public static void load() {
        VANILLA.put(EntityTypes.ZOMBIE, Items.ZOMBIE_HEAD);
        VANILLA.put(EntityTypes.GIANT, Items.ZOMBIE_HEAD);
        VANILLA.put(EntityTypes.SKELETON, Items.SKELETON_SKULL);
        VANILLA.put(EntityTypes.WITHER_SKELETON, Items.WITHER_SKELETON_SKULL);
        VANILLA.put(EntityTypes.CREEPER, Items.CREEPER_HEAD);
        VANILLA.put(EntityTypes.PIGLIN, Items.PIGLIN_HEAD);
        VANILLA.put(EntityTypes.ENDER_DRAGON, Items.DRAGON_HEAD);

        try (InputStream in = MobHeads.class.getResourceAsStream("/clickhostiles_heads.json")) {
            if (in == null) return;
            JsonObject obj = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            obj.entrySet().forEach(e -> HASHES.put(e.getKey(), e.getValue().getAsString()));
        } catch (Exception e) {
            ClickHostiles.LOGGER.warn("Could not load head textures, falling back to zombie head", e);
        }
    }

    /** Fresh head stack for this mob type: vanilla head or textured player head. */
    public static ItemStack headFor(EntityType<?> type) {
        Item vanilla = VANILLA.get(type);
        if (vanilla != null) return new ItemStack(vanilla);

        String hash = HASHES.get(BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath());
        if (hash != null) {
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/" + hash + "\"}}}";
            String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            PropertyMap props = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", value)));
            UUID id = UUID.nameUUIDFromBytes(("clickhostiles:" + hash).getBytes(StandardCharsets.UTF_8));

            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(new GameProfile(id, "clickhostiles", props)));
            return head;
        }
        return new ItemStack(Items.ZOMBIE_HEAD);
    }
}
