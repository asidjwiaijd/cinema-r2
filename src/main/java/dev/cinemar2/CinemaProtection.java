package dev.cinemar2;

import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;
import net.minecraft.item.*;
import net.minecraft.entity.*;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.vehicle.*;

public final class CinemaProtection {
    public static boolean enabled(){return CinemaMod.settings!=null&&CinemaMod.settings.protectCinema;}
    public static boolean protectedWorld(World world){return enabled()&&!world.isClient;}
    public static boolean denyBuild(net.minecraft.entity.player.PlayerEntity player){return enabled()&&player instanceof ServerPlayerEntity p&&!CinemaPermissions.canBuild(p);}
    public static boolean dangerous(ItemStack stack){String id=net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();return stack.getItem() instanceof SpawnEggItem||stack.getItem() instanceof BucketItem||id.contains("tnt")||id.contains("minecart")||id.contains("boat")||id.contains("spawn_egg")||java.util.Set.of("end_crystal","flint_and_steel","fire_charge","armor_stand","firework_rocket","ender_pearl","splash_potion","lingering_potion","wind_charge","command_block","chain_command_block","repeating_command_block","structure_block","jigsaw","debug_stick").contains(id);}
    public static boolean denySpawn(World world,Entity entity){return protectedWorld(world)&&(entity instanceof net.minecraft.entity.ItemEntity||entity instanceof TntEntity||entity instanceof ProjectileEntity||entity instanceof MobEntity||entity instanceof LightningEntity||entity instanceof EndCrystalEntity||entity instanceof BoatEntity||entity instanceof AbstractMinecartEntity);}
    public static void initialize(){
        AttackBlockCallback.EVENT.register((p,w,h,pos,d)->denyBuild(p)?ActionResult.FAIL:ActionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((w,p,pos,state,be)->!denyBuild(p));
        UseBlockCallback.EVENT.register((p,w,h,hit)->protectedWorld(w)&&(denyBuild(p)||dangerous(p.getStackInHand(h)))?ActionResult.FAIL:ActionResult.PASS);
        UseItemCallback.EVENT.register((p,w,h)->protectedWorld(w)&&(denyBuild(p)||dangerous(p.getStackInHand(h)))?TypedActionResult.fail(p.getStackInHand(h)):TypedActionResult.pass(p.getStackInHand(h)));
        AttackEntityCallback.EVENT.register((p,w,h,e,hit)->protectedWorld(w)?ActionResult.FAIL:ActionResult.PASS);
        UseEntityCallback.EVENT.register((p,w,h,e,hit)->denyBuild(p)?ActionResult.FAIL:ActionResult.PASS);
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{if(enabled())handler.player.changeGameMode(GameMode.CREATIVE);});
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->CinemaPermissions.disconnect(handler.player.getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(server->{if(!enabled())return;ArenaBuilder.tick(server);if(server.getTicks()%20!=0)return;
            var world=server.getOverworld();world.setTimeOfDay(12000);world.setWeather(120000,0,false,false);
            for(var p:server.getPlayerManager().getPlayerList()){
                if(p.interactionManager.getGameMode()!=GameMode.CREATIVE)p.changeGameMode(GameMode.CREATIVE);
                if(p.getServerWorld()!=world||Math.abs(p.getX())>62||p.getZ()<-46||p.getZ()>78||p.getY()<94||p.getY()>140)p.teleport(world,0.5,ArenaBuilder.built()?105:100,32.5,180,0);
            }
        });
    }
    public static void configure(MinecraftServer server){if(!enabled())return;var rules=server.getGameRules();
        for(var key:java.util.List.of(GameRules.DO_DAYLIGHT_CYCLE,GameRules.DO_WEATHER_CYCLE,GameRules.DO_FIRE_TICK,GameRules.DO_MOB_GRIEFING,GameRules.DO_MOB_SPAWNING,GameRules.DO_INSOMNIA,GameRules.DO_PATROL_SPAWNING,GameRules.DO_TRADER_SPAWNING))rules.get(key).set(false,server);
        rules.get(GameRules.KEEP_INVENTORY).set(true,server);rules.get(GameRules.DISABLE_RAIDS).set(true,server);
        server.setDefaultGameMode(GameMode.CREATIVE);server.setPvpEnabled(false);var world=server.getOverworld();world.setTimeOfDay(12000);world.setWeather(120000,0,false,false);world.getWorldBorder().setCenter(0,16);world.getWorldBorder().setSize(512);
        // The rear entrance is outside the controller's spawn chunk. Keep only
        // the main screen chunk loaded so web control works before viewers join.
        world.setChunkForced(0,-1,true);
    }
}
