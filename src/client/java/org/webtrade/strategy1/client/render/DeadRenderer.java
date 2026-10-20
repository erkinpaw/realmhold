package org.webtrade.strategy1.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.zombie.ZombieModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.entity.AbstractZombieRenderer;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3f;
import org.webtrade.strategy1.entity.DeadZombie;
import org.webtrade.strategy1.registry.Parts;

/**
 * The siege's dead with a look of their own: a zombie, a husk's or a drowned one's skin, and what each carries drawn on
 * its body (it moves with it): the plague-bearer's boils and the mushrooms growing out of him, the ram-bearer's log,
 * the crawler's guts (and the crawler on its belly, legless, dragging itself on its hands); the giant is just big.
 */
public class DeadRenderer extends AbstractZombieRenderer<Zombie, DeadRenderer.State, DeadRenderer.Model> {

    public enum Kind {PLAGUE, RAM, CRAWLER, GIANT}

    private static final Identifier[] SKINS = {
            Identifier.withDefaultNamespace("textures/entity/zombie/zombie.png"),
            Identifier.withDefaultNamespace("textures/entity/zombie/husk.png"),
            Identifier.withDefaultNamespace("textures/entity/zombie/drowned.png")};
    private static final BlockDisplayContext DISPLAY = BlockDisplayContext.create();

    private final Kind kind;
    private final BlockModelResolver blocks;

    public DeadRenderer(EntityRendererProvider.Context context, Kind kind) {
        super(context, new Model(context.bakeLayer(ModelLayers.ZOMBIE), kind), new Model(context.bakeLayer(ModelLayers.ZOMBIE_BABY), kind),
                ArmorModelSet.bake(ModelLayers.ZOMBIE_ARMOR, context.getModelSet(), p -> new Model(p, kind)),
                ArmorModelSet.bake(ModelLayers.ZOMBIE_BABY_ARMOR, context.getModelSet(), p -> new Model(p, kind)));
        this.kind = kind;
        this.blocks = context.getBlockModelResolver();
        addLayer(new Gear(this, kind));
    }

    public static class State extends ZombieRenderState {
        int skin;
        final BlockModelRenderState gear = new BlockModelRenderState();
        final BlockModelRenderState red = new BlockModelRenderState();
        final BlockModelRenderState brown = new BlockModelRenderState();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(Zombie entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.skin = entity instanceof DeadZombie d ? Math.floorMod(d.skin(), SKINS.length) : 0;
        switch (kind) {
            case PLAGUE -> {
                blocks.update(state.gear, Parts.state(Parts.PLAGUE_BOILS), DISPLAY);
                blocks.update(state.red, Blocks.RED_MUSHROOM.defaultBlockState(), DISPLAY);
                blocks.update(state.brown, Blocks.BROWN_MUSHROOM.defaultBlockState(), DISPLAY);
            }
            case RAM -> blocks.update(state.gear, Parts.state(Parts.RAM_LOG), DISPLAY);
            case CRAWLER -> blocks.update(state.gear, Parts.state(Parts.CRAWLER_GUTS), DISPLAY);
            default -> state.gear.clear();
        }
    }

    @Override
    public Identifier getTextureLocation(State state) {
        return SKINS[state.skin];
    }

    @Override
    protected void setupRotations(State state, PoseStack pose, float bodyRot, float entityScale) {
        super.setupRotations(state, pose, bodyRot, entityScale);
        if (kind != Kind.CRAWLER || state.deathTime > 0) return;
        // on his belly, the stump of his waist low over the ground, his head forward
        pose.translate(0, 0.16F, 0);
        pose.mulPose(Axis.XP.rotationDegrees(90));
        pose.translate(0, -0.75F, 0);
    }

    /** The zombie's own model, but a crawler's legs gone and its arms reaching ahead, one after the other. */
    public static class Model extends ZombieModel<State> {
        private final Kind kind;

        Model(ModelPart root, Kind kind) {
            super(root);
            this.kind = kind;
        }

        @Override
        public void setupAnim(State state) {
            super.setupAnim(state);
            if (kind != Kind.CRAWLER) return;
            leftLeg.visible = false;
            rightLeg.visible = false;
            // (it lies face down: the head lifted to look ahead; the hands reach out over it and drag it on)
            head.xRot = 1.25F + head.xRot * 0.3F;
            float pull = Mth.sin(state.walkAnimationPos * 0.9F) * Math.min(1, state.walkAnimationSpeed * 2) * 0.55F;
            rightArm.xRot = 3.3F + pull;
            leftArm.xRot = 3.3F - pull;
            rightArm.yRot = -0.15F;
            leftArm.yRot = 0.15F;
            rightArm.zRot = 0;
            leftArm.zRot = 0;
        }
    }

    /** What each carries, on its body. */
    private static final class Gear extends RenderLayer<State, Model> {
        private final Kind kind;

        Gear(RenderLayerParent<State, Model> parent, Kind kind) {
            super(parent);
            this.kind = kind;
        }

        @Override
        public void submit(PoseStack pose, SubmitNodeCollector out, int light, State state, float yRot, float xRot) {
            if (state.isInvisible || state.isBaby) return;
            int overlay = LivingEntityRenderer.getOverlayCoords(state, 0);
            if (!state.gear.isEmpty()) {
                pose.pushPose();
                getParentModel().body.translateAndRotate(pose);
                pose.scale(-1, -1, 1);
                // (the ram thrust forward as he strikes)
                if (kind == Kind.RAM) pose.translate(0, 0, -0.45F * Mth.sin(state.attackTime * Mth.PI));
                int part = kind == Kind.PLAGUE ? Parts.PLAGUE_BOILS : kind == Kind.RAM ? Parts.RAM_LOG : Parts.CRAWLER_GUTS;
                Vector3f at = Parts.at(part);
                pose.translate(at.x, at.y, at.z);
                state.gear.submit(pose, out, light, overlay, state.outlineColor);
                pose.popPose();
            }
            if (kind == Kind.PLAGUE) {
                // mushrooms growing out of his shoulders and his back, and one out of his head
                mushroom(pose, out, light, overlay, state, state.red, false, 0.22F, 0.0F, 0.02F, 0.8F, 20);
                mushroom(pose, out, light, overlay, state, state.brown, false, -0.24F, 0.0F, 0.0F, 0.75F, -35);
                mushroom(pose, out, light, overlay, state, state.brown, false, 0.08F, -0.32F, 0.2F, 0.7F, 60);
                mushroom(pose, out, light, overlay, state, state.red, false, -0.12F, -0.55F, 0.2F, 0.6F, -70);
                mushroom(pose, out, light, overlay, state, state.red, true, -0.1F, 0.5F, 0.05F, 0.7F, 10);
                mushroom(pose, out, light, overlay, state, state.brown, true, 0.14F, 0.5F, -0.08F, 0.5F, 40);
            }
        }

        private void mushroom(PoseStack pose, SubmitNodeCollector out, int light, int overlay, State state, BlockModelRenderState model,
                              boolean onHead, float x, float y, float z, float size, float turn) {
            if (model.isEmpty()) return;
            pose.pushPose();
            (onHead ? getParentModel().head : getParentModel().body).translateAndRotate(pose);
            pose.scale(-1, -1, 1);
            pose.translate(x, y, z);
            pose.mulPose(Axis.YP.rotationDegrees(turn));
            pose.scale(size, size, size);
            pose.translate(-0.5F, 0, -0.5F);
            model.submit(pose, out, light, overlay, state.outlineColor);
            pose.popPose();
        }
    }
}
