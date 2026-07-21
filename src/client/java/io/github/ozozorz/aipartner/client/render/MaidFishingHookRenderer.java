package io.github.ozozorz.aipartner.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.MaidFishingHookEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.FishingHookRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 使用原版浮标贴图绘制女仆浮标，并把钓线连接到女仆右手附近。
 */
public final class MaidFishingHookRenderer extends EntityRenderer<MaidFishingHookEntity, FishingHookRenderState> {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace(
            "textures/entity/fishing/fishing_hook.png"
    );
    private static final RenderType RENDER_TYPE = RenderTypes.entityCutoutCull(TEXTURE);

    public MaidFishingHookRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRender(
            MaidFishingHookEntity entity,
            Frustum culler,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        return super.shouldRender(entity, culler, cameraX, cameraY, cameraZ)
                && entity.getMaidOwner() != null;
    }

    @Override
    public void submit(
            FishingHookRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera
    ) {
        poseStack.pushPose();
        poseStack.pushPose();
        poseStack.scale(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(camera.orientation);
        submitNodeCollector.submitCustomGeometry(poseStack, RENDER_TYPE, (pose, buffer) -> {
            vertex(buffer, pose, state.lightCoords, 0.0F, 0, 0, 1);
            vertex(buffer, pose, state.lightCoords, 1.0F, 0, 1, 1);
            vertex(buffer, pose, state.lightCoords, 1.0F, 1, 1, 0);
            vertex(buffer, pose, state.lightCoords, 0.0F, 1, 0, 0);
        });
        poseStack.popPose();
        float x = (float) state.lineOriginOffset.x;
        float y = (float) state.lineOriginOffset.y;
        float z = (float) state.lineOriginOffset.z;
        float width = Minecraft.getInstance().gameRenderer
                .getGameRenderState().windowRenderState.appropriateLineWidth;
        submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, buffer) -> {
            for (int step = 0; step < 16; step++) {
                float first = fraction(step, 16);
                float second = fraction(step + 1, 16);
                stringVertex(x, y, z, buffer, pose, first, second, width);
                stringVertex(x, y, z, buffer, pose, second, first, width);
            }
        });
        poseStack.popPose();
        super.submit(state, poseStack, submitNodeCollector, camera);
    }

    @Override
    public FishingHookRenderState createRenderState() {
        return new FishingHookRenderState();
    }

    @Override
    public void extractRenderState(
            MaidFishingHookEntity entity,
            FishingHookRenderState state,
            float partialTicks
    ) {
        super.extractRenderState(entity, state, partialTicks);
        AiPartnerEntity owner = entity.getMaidOwner();
        if (owner == null) {
            state.lineOriginOffset = Vec3.ZERO;
            return;
        }
        float bodyRotation = Mth.lerp(partialTicks, owner.yBodyRotO, owner.yBodyRot)
                * (float) (Math.PI / 180.0);
        double sin = Mth.sin(bodyRotation);
        double cos = Mth.cos(bodyRotation);
        Vec3 hand = owner.getEyePosition(partialTicks).add(-cos * 0.35 - sin * 0.65, -0.45, -sin * 0.35 + cos * 0.65);
        Vec3 hook = entity.getPosition(partialTicks).add(0.0, 0.25, 0.0);
        state.lineOriginOffset = hand.subtract(hook);
    }

    @Override
    protected boolean affectedByCulling(MaidFishingHookEntity entity) {
        return false;
    }

    private static float fraction(int value, int total) {
        return (float) value / total;
    }

    private static void vertex(
            VertexConsumer builder,
            PoseStack.Pose pose,
            int light,
            float x,
            int y,
            int u,
            int v
    ) {
        builder.addVertex(pose, x - 0.5F, y - 0.5F, 0.0F)
                .setColor(-1)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    private static void stringVertex(
            float xDelta,
            float yDelta,
            float zDelta,
            VertexConsumer buffer,
            PoseStack.Pose pose,
            float fraction,
            float nextFraction,
            float width
    ) {
        float x = xDelta * fraction;
        float y = yDelta * (fraction * fraction + fraction) * 0.5F + 0.25F;
        float z = zDelta * fraction;
        float normalX = xDelta * nextFraction - x;
        float normalY = yDelta * (nextFraction * nextFraction + nextFraction) * 0.5F + 0.25F - y;
        float normalZ = zDelta * nextFraction - z;
        float length = Mth.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
        normalX /= length;
        normalY /= length;
        normalZ /= length;
        buffer.addVertex(pose, x, y, z)
                .setColor(-16777216)
                .setNormal(pose, normalX, normalY, normalZ)
                .setLineWidth(width);
    }
}
