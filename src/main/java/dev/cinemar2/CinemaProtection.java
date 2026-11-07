package dev.cinemar2;

import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

public final class CinemaProtection {
    public static boolean enabled(){return CinemaMod.settings!=null&&CinemaMod.settings.protectCinema;}
    public static boolean protectedWorld(Level world){return enabled()&&!world.isClientSide();}
    public static boolean denyBuild(net.minecraft.world.entity.player.Player player){return enabled()&&player instanceof ServerPlayer p&&!CinemaPermissions.canBuild(p);}
    public static boolean dangerous(ItemStack stack){String id=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();return stack.getItem() instanceof SpawnEggItem||stack.getItem() instanceof BucketItem||id.contains("tnt")||id.contains("minecart")||id.contains("boat")||id.contains("spawn_egg")||java.util.Set.of("end_crystal","flint_and_steel","fire_charge","armor_stand","firework_rocket","ender_pearl","splash_potion","lingering_potion","wind_charge","command_block","chain_command_block","repeating_command_block","structure_block","jigsaw","debug_stick").contains(id);}
    public static boolean denySpawn(Level world,Entity entity){return protectedWorld(world)&&(entity instanceof net.minecraft.world.entity.item.ItemEntity||entity instanceof PrimedTnt||entity instanceof Projectile||entity instanceof Mob||entity instanceof LightningBolt||entity instanceof EndCrystal||entity instanceof Boat||entity instanceof AbstractMinecart);}
    public static void initialize(){
        AttackBlockCallback.EVENT.register((p,w,h,pos,d)->denyBuild(p)?InteractionResult.FAIL:InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((w,p,pos,state,be)->!denyBuild(p));
        UseBlockCallback.EVENT.register((p,w,h,hit)->protectedWorld(w)&&(denyBuild(p)||dangerous(p.getItemInHand(h)))?InteractionResult.FAIL:InteractionResult.PASS);
        UseItemCallback.EVENT.register((p,w,h)->protectedWorld(w)&&(denyBuild(p)||dangerous(p.getItemInHand(h)))?InteractionResult.FAIL:InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((p,w,h,e,hit)->protectedWorld(w)?InteractionResult.FAIL:InteractionResult.PASS);
        UseEntityCallback.EVENT.register((p,w,h,e,hit)->denyBuild(p)?InteractionResult.FAIL:InteractionResult.PASS);
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{if(enabled()&&!handler.player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))handler.player.setGameMode(GameType.CREATIVE);});
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->CinemaPermissions.disconnect(handler.player.getUUID()));
        ServerTickEvents.END_SERVER_TICK.register(server->{if(!enabled())return;ArenaBuilder.tick(server);if(server.getTickCount()%20!=0)return;
            var world=server.overworld();server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"time set 12000");server.setWeatherParameters(120000,0,false,false);
            for(var p:server.getPlayerList().getPlayers()){
                if(!p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)&&p.gameMode.getGameModeForPlayer()!=GameType.CREATIVE)p.setGameMode(GameType.CREATIVE);
                if(p.level()!=world||Math.abs(p.getX())>62||p.getZ()<-46||p.getZ()>78||p.getY()<94||p.getY()>140)p.teleportTo(world,0.5,ArenaBuilder.built()?105:100,32.5,java.util.Set.of(),180,0,true);
            }
        });
    }
    public static void configure(MinecraftServer server){if(!enabled())return;var rules=server.getGlobalGameRules();
        for(var key:java.util.List.of(GameRules.ADVANCE_TIME,GameRules.ADVANCE_WEATHER,GameRules.MOB_GRIEFING,GameRules.SPAWN_MOBS,GameRules.SPAWN_PHANTOMS,GameRules.SPAWN_PATROLS,GameRules.SPAWN_WANDERING_TRADERS))rules.set(key,false,server);
        rules.set(GameRules.KEEP_INVENTORY,true,server);rules.set(GameRules.RAIDS,false,server);rules.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER,0,server);rules.set(GameRules.PVP,false,server);
        server.setDefaultGameType(GameType.CREATIVE);var world=server.overworld();server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"time set 12000");server.setWeatherParameters(120000,0,false,false);world.getWorldBorder().setCenter(0,16);world.getWorldBorder().setSize(512);
        // The rear entrance is outside the controller's spawn chunk. Keep only
        // the main screen chunk loaded so web control works before viewers join.
        world.setChunkForced(0,-1,true);
    }
}
