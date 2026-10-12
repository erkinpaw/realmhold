package org.webtrade.strategy1.client.render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.webtrade.strategy1.entity.SoldierEntity;

/** A soldier: one of the game's default people, in their realm's tabard and their kind's arms and armour. */
public class SoldierRenderer extends HumanoidMobRenderer<SoldierEntity, AvatarRenderState, PlayerModel> {

    private static final String[] SKINS = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};
    private static final PlayerSkin[] PLAYER_SKINS = new PlayerSkin[SKINS.length];

    static {
        for (int i = 0; i < SKINS.length; i++) {
            PLAYER_SKINS[i] = PlayerSkin.insecure(new ClientAsset.ResourceTexture(
                    Identifier.withDefaultNamespace("entity/player/wide/" + SKINS[i])), null, null, PlayerModelType.WIDE);
        }
    }

    public SoldierRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        addLayer(new HumanoidArmorLayer<>(this, ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(),
                part -> new HumanoidModel<AvatarRenderState>(part)), context.getEquipmentRenderer()));
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new AvatarRenderState();
    }

    @Override
    public void extractRenderState(SoldierEntity entity, AvatarRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.skin = PLAYER_SKINS[Math.floorMod(entity.skin(), PLAYER_SKINS.length)];
        state.showHat = true;
        state.showJacket = true;
        state.showLeftSleeve = true;
        state.showRightSleeve = true;
        state.showLeftPants = true;
        state.showRightPants = true;
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return state.skin.body().texturePath();
    }
}
