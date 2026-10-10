package dev.cinemar2;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import java.util.*;
import java.nio.file.*;
import net.fabricmc.loader.api.FabricLoader;

public final class ArenaBuilder {
 private static List<ArenaLayout.Placement> queue=List.of();private static int index;private static String error="";
 private static Path marker(){return FabricLoader.getInstance().getConfigDir().resolve("cinemar2-arena-built.txt");}
 public static boolean built(){return Files.exists(marker());}
 public static void start(MinecraftServer server){if(!CinemaProtection.enabled())throw new IllegalArgumentException("影院保护未开启");if(!queue.isEmpty())throw new IllegalArgumentException("影院正在搭建");queue=ArenaLayout.plan();index=0;error="";CinemaProtection.configure(server);}
 public static void tick(MinecraftServer server){if(queue.isEmpty())return;try{var world=server.getOverworld();int end=Math.min(queue.size(),index+1200);while(index<end){var p=queue.get(index++);var pos=new BlockPos(p.x(),p.y(),p.z());var state=Registries.BLOCK.get(Identifier.ofVanilla(p.block())).getDefaultState();if(state.contains(Properties.PERSISTENT))state=state.with(Properties.PERSISTENT,true);if(!p.facing().isBlank()&&state.contains(Properties.HORIZONTAL_FACING))state=state.with(Properties.HORIZONTAL_FACING,Direction.byName(p.facing()));if(!world.getBlockState(pos).equals(state))world.setBlockState(pos,state,2);}
  if(index==queue.size()){Files.writeString(marker(),"forest-50\n");queue=List.of();world.setSpawnPos(new BlockPos(0,104,36),180);for(var p:server.getPlayerManager().getPlayerList())p.teleport(world,0.5,104,36.5,180,0);server.getPlayerManager().sendToAll(new net.minecraft.network.packet.s2c.play.GameMessageS2CPacket(net.minecraft.text.Text.literal("林地影院已就绪：50 个座位，晴朗傍晚，场地受保护。"),false));}
 }catch(Exception e){queue=List.of();error=e.getMessage()==null?"场景搭建失败":e.getMessage();}}
 public static JsonObject status(){var o=new JsonObject();o.addProperty("protected",CinemaProtection.enabled());o.addProperty("built",built());o.addProperty("building",!queue.isEmpty());o.addProperty("progress",queue.isEmpty()?(built()?100:0):index*100/queue.size());o.addProperty("seats",50);o.addProperty("time",12000);o.addProperty("weather","clear");o.addProperty("gameMode","creative");o.addProperty("error",error);return o;}
}
