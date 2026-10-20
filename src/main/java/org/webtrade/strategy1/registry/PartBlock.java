package org.webtrade.strategy1.registry;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** Never set in the world: its states are the engines' parts (see {@link Parts}), shown by display entities. */
public final class PartBlock extends Block {

    public static final IntegerProperty PART = IntegerProperty.create("part", 0, Parts.COUNT - 1);

    public PartBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(PART, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART);
    }
}
