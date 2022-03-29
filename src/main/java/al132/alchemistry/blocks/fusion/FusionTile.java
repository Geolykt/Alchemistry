package al132.alchemistry.blocks.fusion;

import al132.alchemistry.Config;
import al132.alchemistry.Registration;
import al132.alchemistry.blocks.AlchemistryBaseTile;
import al132.alchemistry.blocks.PowerStatus;
import al132.alib.tiles.CustomEnergyStorage;
import al132.alib.tiles.CustomStackHandler;
import al132.alib.tiles.EnergyTile;
import al132.chemlib.chemistry.ElementRegistry;
import al132.chemlib.items.ElementItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.RangedWrapper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.BiFunction;

import static al132.alchemistry.Registration.*;
import static al132.alchemistry.blocks.PowerStatus.*;
import static al132.alchemistry.blocks.fusion.FusionControllerBlock.STATUS;

public class FusionTile extends AlchemistryBaseTile implements EnergyTile {

    boolean isValidMultiblock = false;
    ItemStack recipeOutput = ItemStack.EMPTY;
    private int checkMultiblockTicks = 0;

    protected int progressTicks = 0;

    IItemHandler leftInput;
    IItemHandler rightInput;

    boolean firstTick = true;

    public FusionTile(BlockPos pos, BlockState state) {
        super(Registration.FUSION_CONTROLLER_BE.get(), pos, state);
    }


    public void tickServer() {
        if (level.isClientSide) return;
        if (firstTick) {
            refreshRecipe();
            firstTick = false;
        }

        checkMultiblockTicks++;
        if (checkMultiblockTicks >= 20) {
            updateMultiblock();
            checkMultiblockTicks = 0;
        }
        boolean isActive = !this.getInput().getStackInSlot(0).isEmpty() && !this.getInput().getStackInSlot(1).isEmpty();
        BlockState state = this.level.getBlockState(this.getBlockPos());
        if (state.getBlock() != FUSION_CONTROLLER_BLOCK.get()) return;
        PowerStatus currentStatus = state.getValue(STATUS);
        if (this.isValidMultiblock) {
            if (isActive) {
                if (currentStatus != ON) this.level.setBlockAndUpdate(this.getBlockPos(), state.setValue(STATUS, ON));
            } else if (currentStatus != STANDBY)
                level.setBlockAndUpdate(getBlockPos(), state.setValue(STATUS, STANDBY));
        } else if (currentStatus != OFF) level.setBlockAndUpdate(getBlockPos(), state.setValue(STATUS, OFF));
        if (canProcess()) {
            process();
        }
        this.updateGUIEvery(5);
    }

    public boolean canProcess() {
        ItemStack input0 = getInput().getStackInSlot(0);
        ItemStack input1 = getInput().getStackInSlot(1);
        ItemStack output0 = getOutput().getStackInSlot(0);
        return this.isValidMultiblock
                && !input0.isEmpty()
                && !input1.isEmpty()
                && !recipeOutput.isEmpty()
                && (ItemStack.isSame(output0, recipeOutput) || output0.isEmpty())
                && output0.getCount() + recipeOutput.getCount() <= recipeOutput.getMaxStackSize()
                && energy.getEnergyStored() >= Config.FUSION_ENERGY_PER_TICK.get();
    }

    public void process() {
        if (progressTicks < Config.FUSION_TICKS_PER_OPERATION.get()) {
            progressTicks++;
        } else {
            progressTicks = 0;
            getOutput().setOrIncrement(0, recipeOutput.copy());
            getInput().decrementSlot(0, 1); //Will refresh the recipe, clearing the recipeOutputs if only 1 stack is left
            getInput().decrementSlot(1, 1);//Will refresh the recipe, clearing the recipeOutputs if only 1 stack is left
        }
        this.energy.extractEnergy(Config.FUSION_ENERGY_PER_TICK.get(), false);
        setChanged();
    }

    public void refreshRecipe() {
        Item item0 = getInput().getStackInSlot(0).getItem();
        Item item1 = getInput().getStackInSlot(1).getItem();
        if (item0 instanceof ElementItem && item1 instanceof ElementItem) {
            int meta0 = ElementRegistry.elements.inverse().get(item0);
            int meta1 = ElementRegistry.elements.inverse().get(item1);//this.getInput().getStackInSlot(1).metadata;
            ElementItem outputElement = ElementRegistry.elements.get(meta0 + meta1);
            if (outputElement != null) recipeOutput = new ItemStack(outputElement);//outputElement.toItemStack(1);
            else recipeOutput = ItemStack.EMPTY;
        } else recipeOutput = ItemStack.EMPTY;
    }


    @Override
    public void load(CompoundTag compound) {
        super.load(compound);
        this.progressTicks = compound.getInt("progressTicks");
        this.isValidMultiblock = compound.getBoolean("isValidMultiblock");
    }

    @Override
    public void saveAdditional(CompoundTag compound) {
        super.saveAdditional(compound);
        compound.putInt("progressTicks", progressTicks);
        compound.putBoolean("isValidMultiblock", isValidMultiblock);
    }

    public void updateMultiblock() {
        this.isValidMultiblock = validateMultiblock();
    }

    private boolean containsCasing(BlockPos pos) {
        return this.level.getBlockState(pos).getBlock() == FUSION_CASING_BLOCK.get();
    }

    private boolean containsCore(BlockPos pos) {
        return this.level.getBlockState(pos).getBlock() == FUSION_CORE_BLOCK.get();
    }

    private boolean containsFusionPart(BlockPos pos) {
        Block block = this.level.getBlockState(pos).getBlock();
        return block == FUSION_CASING_BLOCK.get() || block == FUSION_CORE_BLOCK.get() || block == FUSION_CONTROLLER_BLOCK.get();
    }

    public boolean validateMultiblock() {
        Direction temp = level.getBlockState(this.getBlockPos()).getValue(FusionControllerBlock.FACING);//.getOpposite();
        if (temp == null) return false;
        Direction multiblockDirection = temp.getOpposite();
        BiFunction<BlockPos, Integer, BlockPos> offsetUp = (BlockPos pos, Integer amt) -> pos.relative(Direction.UP, amt);
        BiFunction<BlockPos, Integer, BlockPos> offsetLeft = (BlockPos pos, Integer amt) -> new BlockPos(pos.relative(multiblockDirection.getClockWise(), amt));//.rotate(Rotation.CLOCKWISE_90));
        BiFunction<BlockPos, Integer, BlockPos> offsetRight = (BlockPos pos, Integer amt) -> new BlockPos(pos.relative(multiblockDirection.getCounterClockWise(), /*-1 **/ amt));//.rotate(Rotation.CLOCKWISE_90));
        BiFunction<BlockPos, Integer, BlockPos> offsetBack = (BlockPos pos, Integer amt) -> pos.relative(multiblockDirection, amt);
        BiFunction<BlockPos, Integer, BlockPos> offsetDown = (BlockPos pos, Integer amt) -> pos.relative(Direction.DOWN, amt);

        BlockPos coreBottom = offsetBack.apply(this.getBlockPos(), 3);
        coreBottom = offsetUp.apply(coreBottom, 1);
        BlockPos coreTop = offsetUp.apply(coreBottom, 2);
        boolean coreMatches = BlockPos.betweenClosedStream(coreBottom, coreTop).allMatch(this::containsCore);


        //A cube of all blocks surrounding the fusion multiblock, checking to ensure no other fusion multiblocks are overlapping/sharing
        BlockPos outsideCorner1 = offsetLeft.apply(this.getBlockPos(), 3);
        outsideCorner1 = offsetDown.apply(outsideCorner1, 1);
        final BlockPos outsideCorner1Final = outsideCorner1; //java doesn't like non-final fields in the lambda below..
        BlockPos outsideCorner2 = offsetRight.apply(outsideCorner1, 6);
        outsideCorner2 = offsetUp.apply(outsideCorner2, 6);
        outsideCorner2 = offsetBack.apply(outsideCorner2, 6);

        long borderingParts = BlockPos.betweenClosedStream(outsideCorner1, outsideCorner2).filter(it -> {
            int sharedAxes = 0;
            if (it.getX() == outsideCorner1Final.getX() || it.getX() == outsideCorner1Final.getX()) sharedAxes++;
            if (it.getY() == outsideCorner1Final.getY() || it.getY() == outsideCorner1Final.getY()) sharedAxes++;
            if (it.getZ() == outsideCorner1Final.getZ() || it.getZ() == outsideCorner1Final.getZ()) sharedAxes++;
            return sharedAxes >= 1;
        }).filter(it -> !it.equals(this.getBlockPos())).filter(this::containsFusionPart).count();


        BlockPos casingCorner1 = offsetLeft.apply(this.getBlockPos(), 2);
        casingCorner1 = offsetBack.apply(casingCorner1, 1);
        final BlockPos casingCorner1Final = casingCorner1;
        BlockPos casingCorner2 = offsetRight.apply(casingCorner1, 4);
        casingCorner2 = offsetBack.apply(casingCorner2, 4);
        casingCorner2 = offsetUp.apply(casingCorner2, 4);
        final BlockPos casingCorner2Final = casingCorner2;

        boolean casingMatches = BlockPos.betweenClosedStream(casingCorner1, casingCorner2).filter(it -> {
            int sharedAxes = 0;
            if (it.getX() == casingCorner1Final.getX() || it.getX() == casingCorner2Final.getX()) sharedAxes++;
            if (it.getY() == casingCorner1Final.getY() || it.getY() == casingCorner2Final.getY()) sharedAxes++;
            if (it.getZ() == casingCorner1Final.getZ() || it.getZ() == casingCorner2Final.getZ()) sharedAxes++;
            return sharedAxes >= 1;
        }).allMatch(this::containsCasing);

        return casingMatches && coreMatches && (borderingParts == 0);
    }


    @Override
    public CustomStackHandler initInput() {
        CustomStackHandler input = new CustomStackHandler(this, 2) {
            @Override
            public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
                return stack.getItem() instanceof ElementItem;
            }

            @Override
            public void onContentsChanged(int slot) {
                ((FusionTile) tile).refreshRecipe();
                super.onContentsChanged(slot);
            }
        };

        leftInput = new RangedWrapper(input, 0, 1);
        rightInput = new RangedWrapper(input, 1, 2);
        return input;
    }

    @Override
    public CustomStackHandler initOutput() {
        return new CustomStackHandler(this, 1) {
            @Nonnull
            @Override
            public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
                return false;
            }
        };
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        if (this.isValidMultiblock) {
            if (cap == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
                if (level != null) {
                    Direction blockSide = level.getBlockState(this.getBlockPos()).getValue(FusionControllerBlock.FACING);
                    if (side == Direction.UP || side == Direction.DOWN) return LazyOptional.of(this::getOutput).cast();
                    else if (side == blockSide.getClockWise()) return LazyOptional.of(() -> leftInput).cast();
                    else if (side == blockSide.getClockWise().getClockWise().getClockWise())
                        return LazyOptional.of(() -> rightInput).cast();
                    else return LazyOptional.of(this::getOutput).cast();
                }
            }
            return super.getCapability(cap, side);
        } else return LazyOptional.empty();
    }

    @Override
    public IEnergyStorage initEnergy() {
        return new CustomEnergyStorage(Config.FUSION_ENERGY_CAPACITY.get());
    }

    @Override
    public IEnergyStorage getEnergy() {
        return energy;
    }

    @Override
    public Component getName() {
        return null;
    }
}