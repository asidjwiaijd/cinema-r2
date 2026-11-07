package dev.cinemar2.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in test screen that renders a generated colour bar pattern in the world.
 *
 * <p>Enable with {@code -Dcinemar2.debugPattern=true}. It needs no server state,
 * management backend, libmpv or media, so the render path (and the Iris shader
 * path) can be checked on its own. F7 re-places the screen in front of the
 * player, F9 saves a screenshot.</p>
 */
final class CinemaDebug {
    private static final Logger LOGGER=LoggerFactory.getLogger("cinemar2");
    private static final boolean ENABLED=Boolean.getBoolean("cinemar2.debugPattern");
    private static final Identifier PATTERN=Identifier.fromNamespaceAndPath("cinemar2","debug/pattern");
    private static final float WIDTH=8f;

    private static Identifier texture;
    private static BlockPos pos;
    private static float yaw;

    private CinemaDebug() {}

    static void install(){
        if(!ENABLED)return;
        var screenshot=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cinemar2.debug.screenshot",
            InputConstants.Type.KEYSYM,InputConstants.KEY_F9,KeyMapping.Category.DEBUG));
        var place=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cinemar2.debug.place",
            InputConstants.Type.KEYSYM,InputConstants.KEY_F7,KeyMapping.Category.DEBUG));
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            while(screenshot.consumeClick())screenshot(client);
            while(place.consumeClick())place(client);
            if(pos!=null&&texture==null)texture=pattern();
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(context->{
            var screenPosition=pos;
            var screenTexture=texture;
            if(screenPosition==null||screenTexture==null)return;
            var camera=context.levelState().cameraRenderState.pos;
            var matrices=new PoseStack();
            matrices.translate(screenPosition.getX()-camera.x,screenPosition.getY()-camera.y,screenPosition.getZ()-camera.z);
            CinemaRenderer.drawDebug(WIDTH,yaw,screenTexture,matrices,context.submitNodeCollector());
        });
        LOGGER.info("Cinema debug test pattern enabled: F7 place screen, F9 screenshot");
    }

    private static void place(Minecraft client){
        var player=client.player;
        if(player==null)return;
        pos=player.blockPosition().relative(player.getDirection(),8);
        yaw=player.getYRot()+180f;
        LOGGER.info("Placed debug screen at {} (yaw {})",pos,yaw);
    }

    private static void screenshot(Minecraft client){
        Screenshot.grab(client.gameDirectory,client.getMainRenderTarget(),message->{});
    }

    private static Identifier pattern(){
        var image=new NativeImage(256,144,true);
        int[] bars={0xFFFFFFFF,0xFFFFFF00,0xFF00FFFF,0xFF00FF00,0xFFFF00FF,0xFFFF0000,0xFF0000FF};
        int barHeight=100;
        for(int i=0;i<bars.length;i++){
            int x=i*image.getWidth()/bars.length;
            int width=(i+1)*image.getWidth()/bars.length-x;
            image.fillRect(x,0,width,barHeight,bars[i]);
        }
        // Grayscale steps below the bars make brightness/exposure differences obvious.
        for(int i=0;i<8;i++){
            int level=i*255/7;
            int color=0xFF000000|(level<<16)|(level<<8)|level;
            int x=i*image.getWidth()/8;
            int width=(i+1)*image.getWidth()/8-x;
            image.fillRect(x,barHeight,width,image.getHeight()-barHeight,color);
        }
        int border=2;
        image.fillRect(0,0,image.getWidth(),border,0xFFFFFFFF);
        image.fillRect(0,image.getHeight()-border,image.getWidth(),border,0xFFFFFFFF);
        image.fillRect(0,0,border,image.getHeight(),0xFFFFFFFF);
        image.fillRect(image.getWidth()-border,0,border,image.getHeight(),0xFFFFFFFF);
        var generated=new DynamicTexture(()->"cinemar2 debug pattern",image);
        Minecraft.getInstance().getTextureManager().register(PATTERN,generated);
        LOGGER.info("Created debug pattern texture {}",PATTERN);
        return PATTERN;
    }
}
