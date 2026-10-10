package dev.cinemar2.client;

import dev.cinemar2.CinemaEntity;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.entity.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

public final class CinemaRenderer implements BlockEntityRenderer<CinemaEntity> {
    public CinemaRenderer(BlockEntityRendererFactory.Context context) {}
    public boolean rendersOutsideBoundingBox(CinemaEntity entity) { return true; }
    public int getRenderDistance() { return 96; }
    public void render(CinemaEntity entity, float delta, MatrixStack matrices, VertexConsumerProvider providers, int light, int overlay) {
        var view = CinemaClient.views.get(entity.getPos());
        float width = view == null ? 12 : view.state().width();
        Identifier texture = Identifier.of("cinemar2", "textures/block/black.png");
        if (CinemaClient.player != null && view != null && view.state().url().equals(CinemaClient.player.url)
                && (view.state().playing() == CinemaClient.player.playing || CinemaClient.pending != null)
                && (entity.getPos().equals(CinemaClient.active) || view.state().live()
                    || sameTimeline(view.state()))) {
            var video = CinemaClient.player.uploadFrame(); if (video != null) texture = video;
        }
        matrices.push(); matrices.translate(0.5, 1, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-entity.getCachedState().get(Properties.HORIZONTAL_FACING).asRotation()));
        matrices.translate(0, 0, 0.51);
        var vertices = providers.getBuffer(RenderLayer.getEntityTranslucent(texture));
        var entry = matrices.peek();
        float left = -width / 2, right = width / 2, top = width * 9 / 16;
        vertex(vertices, entry, left, 0, 0, 1, overlay);
        vertex(vertices, entry, right, 0, 1, 1, overlay);
        vertex(vertices, entry, right, top, 1, 0, overlay);
        vertex(vertices, entry, left, top, 0, 0, overlay);
        matrices.pop();
    }
    // Network buffering must not hide already decoded frames. Compare two screen
    // timelines when sharing a decoder, rather than the decoder's delayed output.
    private boolean sameTimeline(dev.cinemar2.Packets.State state) {
        var activeView = CinemaClient.views.get(CinemaClient.active);
        return activeView != null && Math.abs(CinemaClient.target(state) - CinemaClient.target(activeView.state())) < 2000;
    }
    private void vertex(VertexConsumer v, MatrixStack.Entry entry, float x, float y, float u, float w, int overlay) {
        v.vertex(entry, x, y, 0).color(255, 255, 255, 255).texture(u, w).overlay(overlay).light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(entry, 0, 0, 1);
    }
}
