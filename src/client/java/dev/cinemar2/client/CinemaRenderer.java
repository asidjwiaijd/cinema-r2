package dev.cinemar2.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.cinemar2.CinemaEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import java.util.List;
import java.util.ArrayList;

public final class CinemaRenderer implements BlockEntityRenderer<CinemaEntity,CinemaRenderer.State> {
    private static final Identifier BLACK=Identifier.fromNamespaceAndPath("cinemar2","textures/block/black.png");
    private static final RenderPipeline SCREEN=RenderPipelines.register(RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("cinemar2","pipeline/screen"))
        .withVertexShader("core/position_tex").withFragmentShader(Identifier.fromNamespaceAndPath("cinemar2","core/screen"))
        // Occlude clouds behind the display while respecting blocks in front.
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .withSampler("Sampler0").withVertexFormat(DefaultVertexFormat.ENTITY,VertexFormat.Mode.QUADS).withCull(false).build());
    private static final java.util.Map<Identifier,RenderType> types=new java.util.LinkedHashMap<>();
    private record RemoteScreen(net.minecraft.core.BlockPos pos,float width,float yaw,MediaPlayer player) {}
    private static final RenderStateDataKey<List<RemoteScreen>> REMOTE=RenderStateDataKey.create();
    private static long remoteDrawCalls;
    private static boolean lastRemoteTextured;
    public static final class State extends BlockEntityRenderState {
        float width=12,yaw;
        MediaPlayer player;
        boolean remote;
    }
    public CinemaRenderer(BlockEntityRendererProvider.Context context) {}
    public State createRenderState(){return new State();}
    public boolean shouldRenderOffScreen(){return true;}
    public int getViewDistance(){return dev.cinemar2.CinemaCoverage.MAX_DISTANCE;}
    public static void installRemoteRendering(){
        LevelRenderEvents.END_EXTRACTION.register(context->{
            List<RemoteScreen> screens=new ArrayList<>();
            Vec3 camera=context.camera().position();
            for(var entry:CinemaClient.geometry.entrySet()){
                var pos=entry.getKey();var view=CinemaClient.views.get(pos);var g=entry.getValue();
                if(view==null||camera.distanceToSqr(pos.getCenter())>(double)g.viewDistance()*g.viewDistance())continue;
                screens.add(new RemoteScreen(pos,view.state().width(),g.yaw(),playerFor(pos,view)));
            }
            context.levelState().setData(REMOTE,List.copyOf(screens));
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(context->{
            var camera=context.levelState().cameraRenderState.pos;
            var matrices=new PoseStack();
            for(var screen:context.levelState().getDataOrDefault(REMOTE,List.of())){
                matrices.pushPose();matrices.translate(screen.pos.getX()-camera.x,screen.pos.getY()-camera.y,screen.pos.getZ()-camera.z);
                lastRemoteTextured=draw(screen.width,screen.yaw,screen.player,matrices,context.submitNodeCollector());
                remoteDrawCalls++;matrices.popPose();
            }
        });
    }
    public void extractRenderState(CinemaEntity entity,State state,float delta,Vec3 camera,ModelFeatureRenderer.CrumblingOverlay overlay){
        BlockEntityRenderer.super.extractRenderState(entity,state,delta,camera,overlay);
        var view=CinemaClient.views.get(entity.getBlockPos());
        state.width=view==null?12:view.state().width();
        state.yaw=entity.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
        state.remote=CinemaClient.geometry.containsKey(entity.getBlockPos());
        state.player=playerFor(entity.getBlockPos(),view);
    }
    private static MediaPlayer playerFor(net.minecraft.core.BlockPos pos,CinemaClient.View view){
        var player=CinemaClient.player;
        if(player!=null&&view!=null&&view.state().url().equals(player.url)
                &&(view.state().playing()==player.playing||CinemaClient.pending!=null)
                &&(pos.equals(CinemaClient.active)||view.state().live()||sameTimeline(view.state())))return player;
        return null;
    }
    public void submit(State state,PoseStack matrices,SubmitNodeCollector nodes,CameraRenderState camera){
        if(!state.remote)draw(state.width,state.yaw,state.player,matrices,nodes);
    }
    private static boolean draw(float width,float yaw,MediaPlayer player,PoseStack matrices,SubmitNodeCollector nodes){
        Identifier texture=player==null?null:player.uploadFrame();
        matrices.pushPose();matrices.translate(0.5,1,0.5);
        matrices.mulPose(Axis.YP.rotationDegrees(-yaw));matrices.translate(0,0,0.51);
        float left=-width/2,right=width/2,top=width*9/16;
        // The screen is opaque: rgb0's undefined padding byte must not affect blending.
        nodes.submitCustomGeometry(matrices,screenType(texture==null?BLACK:texture),(pose,vertices)->{
            vertex(vertices,pose,left,0,0,1);vertex(vertices,pose,right,0,1,1);
            vertex(vertices,pose,right,top,1,0);vertex(vertices,pose,left,top,0,0);
        });
        matrices.popPose();
        return texture!=null;
    }
    private static boolean sameTimeline(dev.cinemar2.Packets.State state){
        var activeView=CinemaClient.views.get(CinemaClient.active);
        return activeView!=null&&Math.abs(CinemaClient.target(state)-CinemaClient.target(activeView.state()))<2000;
    }
    private static RenderType screenType(Identifier texture){
        var type=types.get(texture);
        if(type==null){
            type=RenderType.create("cinema_screen",RenderSetup.builder(SCREEN).withTexture("Sampler0",texture).createRenderSetup());
            types.put(texture,type);
            if(types.size()>8)types.remove(types.keySet().iterator().next());
        }
        return type;
    }
    private static void vertex(VertexConsumer vertices,PoseStack.Pose pose,float x,float y,float u,float v){
        vertices.addVertex(pose,x,y,0).setColor(255,255,255,255).setUv(u,v).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(0x00f000f0).setNormal(pose,0,0,1);
    }
}
