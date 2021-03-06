/**
 * This class was created by <Vazkii>. It's distributed as
 * part of the Botania Mod. Get the Source Code in github:
 * https://github.com/Vazkii/Botania
 *
 * Botania is Open Source and distributed under the
 * Botania License: http://botaniamod.net/license.php
 *
 * File Created @ [Jan 21, 2014, 7:51:36 PM (GMT)]
 */
package vazkii.botania.common.block.tile;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.IItemHandlerModifiable;
import vazkii.botania.api.BotaniaAPI;
import vazkii.botania.api.internal.VanillaPacketDispatcher;
import vazkii.botania.api.item.IPetalApothecary;
import vazkii.botania.api.recipe.IFlowerComponent;
import vazkii.botania.api.recipe.RecipePetals;
import vazkii.botania.api.sound.BotaniaSoundEvents;
import vazkii.botania.api.state.BotaniaStateProps;
import vazkii.botania.api.state.enums.AltarVariant;
import vazkii.botania.client.core.handler.HUDHandler;
import vazkii.botania.client.core.helper.RenderHelper;
import vazkii.botania.common.Botania;
import vazkii.botania.common.block.ModBlocks;
import vazkii.botania.common.network.PacketBotaniaEffect;
import vazkii.botania.common.network.PacketHandler;

import javax.annotation.Nonnull;

public class TileAltar extends TileSimpleInventory implements IPetalApothecary {

	private static final Pattern SEED_PATTERN = Pattern.compile("(?:(?:(?:[A-Z-_.:]|^)seed)|(?:(?:[a-z-_.:]|^)Seed))(?:[sA-Z-_.:]|$)");
	private static final int SET_KEEP_TICKS_EVENT = 0;

	public static final String TAG_HAS_WATER = "hasWater";
	public static final String TAG_HAS_LAVA = "hasLava";
	public static final String TAG_IS_MOSSY = "isMossy";

	public boolean hasWater = false;
	public boolean hasLava = false;

	public boolean isMossy = false;

	List<ItemStack> lastRecipe = null;
	int recipeKeepTicks = 0;

	public boolean collideEntityItem(EntityItem item) {
		ItemStack stack = item.getEntityItem();
		if(world.isRemote || stack.isEmpty() || item.isDead)
			return false;

		if(!isMossy && world.getBlockState(getPos()).getValue(BotaniaStateProps.ALTAR_VARIANT) == AltarVariant.DEFAULT) {
			if(stack.getItem() == Item.getItemFromBlock(Blocks.VINE)) {
				isMossy = true;
				world.updateComparatorOutputLevel(pos, world.getBlockState(pos).getBlock());
				stack.shrink(1);

				return true;
			}
		}

		if(!hasWater() && !hasLava()) {

			if(stack.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, null)) {
				IFluidHandlerItem fluidHandler = stack.getCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, null);

				FluidStack drainWater = fluidHandler.drain(new FluidStack(FluidRegistry.WATER, Fluid.BUCKET_VOLUME), false);
				FluidStack drainLava = fluidHandler.drain(new FluidStack(FluidRegistry.LAVA, Fluid.BUCKET_VOLUME), false);

				if(drainWater != null && drainWater.getFluid() == FluidRegistry.WATER && drainWater.amount == Fluid.BUCKET_VOLUME) {
					setWater(true);
					world.updateComparatorOutputLevel(pos, world.getBlockState(pos).getBlock());
					fluidHandler.drain(new FluidStack(FluidRegistry.WATER, Fluid.BUCKET_VOLUME), true);
					item.setEntityItemStack(fluidHandler.getContainer());
					return true;
				} else if(drainLava != null && drainLava.getFluid() == FluidRegistry.LAVA && drainLava.amount == Fluid.BUCKET_VOLUME) {
					setLava(true);
					world.updateComparatorOutputLevel(pos, world.getBlockState(pos).getBlock());
					fluidHandler.drain(new FluidStack(FluidRegistry.LAVA, Fluid.BUCKET_VOLUME), true);
					item.setEntityItemStack(fluidHandler.getContainer());
					return true;
				}
			}

			return false;
		}

		if(hasLava()) {
			item.setFire(100);
			return true;
		}

		boolean didChange = false;

		if(stack.getItem() instanceof IFlowerComponent && ((IFlowerComponent) stack.getItem()).canFit(stack, this)) {
			if(!itemHandler.getStackInSlot(getSizeInventory() - 1).isEmpty())
				return false;

			for(int i = 0; i < getSizeInventory(); i++)
				if(itemHandler.getStackInSlot(i).isEmpty()) {
					itemHandler.setStackInSlot(i, stack.splitStack(1));
					didChange = true;
					world.playSound(null, pos, SoundEvents.ENTITY_GENERIC_SPLASH, SoundCategory.BLOCKS, 0.1F, 10F);
					break;
				}
		} else if(!stack.isEmpty() && SEED_PATTERN.matcher(stack.getItem().getUnlocalizedName(stack)).find()) {
			for(RecipePetals recipe : BotaniaAPI.petalRecipes) {
				if(recipe.matches(itemHandler)) {
					saveLastRecipe();

					for(int i = 0; i < getSizeInventory(); i++)
						itemHandler.setStackInSlot(i, ItemStack.EMPTY);

					stack.shrink(1);

					ItemStack output = recipe.getOutput().copy();
					EntityItem outputItem = new EntityItem(world, pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5, output);
					world.spawnEntity(outputItem);

					setWater(false);
					world.updateComparatorOutputLevel(pos, world.getBlockState(pos).getBlock());

					craftingFanciness();
					didChange = true;

					break;
				}
			}
		}

		return didChange;
	}

	public void saveLastRecipe() {
		lastRecipe = new ArrayList<>();
		for(int i = 0; i < getSizeInventory(); i++) {
			ItemStack stack = itemHandler.getStackInSlot(i);
			if(stack.isEmpty())
				break;
			lastRecipe.add(stack.copy());
		}
		recipeKeepTicks = 400;
		world.addBlockEvent(getPos(), ModBlocks.altar, SET_KEEP_TICKS_EVENT, 400);
	}

	public void trySetLastRecipe(EntityPlayer player) {
		tryToSetLastRecipe(player, itemHandler, lastRecipe);
		if(!isEmpty())
			VanillaPacketDispatcher.dispatchTEToNearbyPlayers(world, pos);
	}

	public static void tryToSetLastRecipe(EntityPlayer player, IItemHandlerModifiable inv, List<ItemStack> lastRecipe) {
		if(lastRecipe == null || lastRecipe.isEmpty() || player.world.isRemote)
			return;

		int index = 0;
		boolean didAny = false;
		for(ItemStack stack : lastRecipe) {
			if(stack.isEmpty())
				continue;

			for(int i = 0; i < player.inventory.getSizeInventory(); i++) {
				ItemStack pstack = player.inventory.getStackInSlot(i);
				if(!pstack.isEmpty() && pstack.isItemEqual(stack) && ItemStack.areItemStackTagsEqual(stack, pstack)) {
					inv.setStackInSlot(index, pstack.splitStack(1));
					didAny = true;
					index++;
					break;
				}
			}
		}

		if(didAny) {
			player.world.playSound(null, player.posX, player.posY, player.posZ, SoundEvents.ENTITY_GENERIC_SPLASH, SoundCategory.BLOCKS, 0.1F, 10F);
			EntityPlayerMP mp = (EntityPlayerMP) player;
			mp.inventoryContainer.detectAndSendChanges();
		}
	}

	private void craftingFanciness() {
		world.playSound(null, pos, BotaniaSoundEvents.altarCraft, SoundCategory.BLOCKS, 1F, 1F);
		PacketHandler.sendToNearby(world, getPos(),
				new PacketBotaniaEffect(PacketBotaniaEffect.EffectType.APOTHECARY_CRAFT, getPos().getX(), getPos().getY(), getPos().getZ()));
	}

	public boolean isEmpty() {
		for(int i = 0; i < getSizeInventory(); i++)
			if(!itemHandler.getStackInSlot(i).isEmpty())
				return false;

		return true;
	}

	@Override
	public void update() {
		if(!world.isRemote) {
			List<EntityItem> items = world.getEntitiesWithinAABB(EntityItem.class, new AxisAlignedBB(pos.add(0, 1D / 16D * 20D, 0), pos.add(1, 1D / 16D * 32D, 1)));

			boolean didChange = false;
			for(EntityItem item : items)
				didChange = collideEntityItem(item) || didChange;

			if(didChange)
				VanillaPacketDispatcher.dispatchTEToNearbyPlayers(world, pos);
		} else {
			for(int i = 0; i < getSizeInventory(); i++) {
				ItemStack stackAt = itemHandler.getStackInSlot(i);
				if(stackAt.isEmpty())
					break;

				if(Math.random() >= 0.97) {
					Color color = new Color(((IFlowerComponent) stackAt.getItem()).getParticleColor(stackAt));
					float red = color.getRed() / 255F;
					float green = color.getGreen() / 255F;
					float blue = color.getBlue() / 255F;
					if(Math.random() >= 0.75F)
						world.playSound(null, pos, SoundEvents.ENTITY_GENERIC_SPLASH, SoundCategory.BLOCKS, 0.1F, 10F);
					Botania.proxy.sparkleFX(pos.getX() + 0.5 + Math.random() * 0.4 - 0.2, pos.getY() + 1, pos.getZ() + 0.5 + Math.random() * 0.4 - 0.2, red, green, blue, (float) Math.random(), 10);
				}
			}

			if(hasLava()) {
				isMossy = false;
				world.spawnParticle(EnumParticleTypes.SMOKE_NORMAL, pos.getX() + 0.5 + Math.random() * 0.4 - 0.2, pos.getY() + 1, pos.getZ() + 0.5 + Math.random() * 0.4 - 0.2, 0, 0.05, 0);
				if(Math.random() > 0.9)
					world.spawnParticle(EnumParticleTypes.LAVA, pos.getX() + 0.5 + Math.random() * 0.4 - 0.2, pos.getY() + 1, pos.getZ() + 0.5 + Math.random() * 0.4 - 0.2, 0, 0.01, 0);
			}
		}

		if(recipeKeepTicks > 0)
			--recipeKeepTicks;
		else lastRecipe = null;
	}

	@Override
	public void writePacketNBT(NBTTagCompound cmp) {
		super.writePacketNBT(cmp);

		cmp.setBoolean(TAG_HAS_WATER, hasWater());
		cmp.setBoolean(TAG_HAS_LAVA, hasLava());
		cmp.setBoolean(TAG_IS_MOSSY, isMossy);
	}

	@Override
	public void readPacketNBT(NBTTagCompound cmp) {
		super.readPacketNBT(cmp);

		hasWater = cmp.getBoolean(TAG_HAS_WATER);
		hasLava = cmp.getBoolean(TAG_HAS_LAVA);
		isMossy = cmp.getBoolean(TAG_IS_MOSSY);
	}

	@Override
	public void onDataPacket(NetworkManager manager, SPacketUpdateTileEntity packet) {
		boolean lastMossy = isMossy;
		super.onDataPacket(manager, packet);
		if(isMossy != lastMossy)
			world.markBlockRangeForRenderUpdate(pos, pos);
	}

	@Override
	public boolean receiveClientEvent(int id, int param) {
		if(id == SET_KEEP_TICKS_EVENT) {
			recipeKeepTicks = param;
			return true;
		} else {
			return false;
		}
	}

	@Override
	public int getSizeInventory() {
		return 16;
	}

	@Override
	protected SimpleItemStackHandler createItemHandler() {
		return new SimpleItemStackHandler(this, false) {
			@Override
			protected int getStackLimit(int slot, @Nonnull ItemStack stack) {
				return 1;
			}
		};
	}

	@Override
	public void setWater(boolean water) {
		hasWater = water;
		VanillaPacketDispatcher.dispatchTEToNearbyPlayers(world, pos);
	}

	public void setLava(boolean lava) {
		hasLava = lava;
		VanillaPacketDispatcher.dispatchTEToNearbyPlayers(world, pos);
	}

	@Override
	public boolean hasWater() {
		return hasWater;
	}

	public boolean hasLava() {
		return hasLava;
	}

	@SideOnly(Side.CLIENT)
	public void renderHUD(Minecraft mc, ScaledResolution res) {
		int xc = res.getScaledWidth() / 2;
		int yc = res.getScaledHeight() / 2;

		float angle = -90;
		int radius = 24;
		int amt = 0;
		for(int i = 0; i < getSizeInventory(); i++) {
			if(itemHandler.getStackInSlot(i).isEmpty())
				break;
			amt++;
		}

		if(amt > 0) {
			float anglePer = 360F / amt;

			for(RecipePetals recipe : BotaniaAPI.petalRecipes)
				if(recipe.matches(itemHandler)) {
					GlStateManager.color(1F, 1F, 1F, 1F);
					mc.renderEngine.bindTexture(HUDHandler.manaBar);
					RenderHelper.drawTexturedModalRect(xc + radius + 9, yc - 8, 0, 0, 8, 22, 15);

					ItemStack stack = recipe.getOutput();

					net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
					mc.getRenderItem().renderItemIntoGUI(stack, xc + radius + 32, yc - 8);
					mc.getRenderItem().renderItemIntoGUI(new ItemStack(Items.WHEAT_SEEDS), xc + radius + 16, yc + 6);
					net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
					mc.fontRendererObj.drawStringWithShadow("+", xc + radius + 14, yc + 10, 0xFFFFFF);
				}

			net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
			for(int i = 0; i < amt; i++) {
				double xPos = xc + Math.cos(angle * Math.PI / 180D) * radius - 8;
				double yPos = yc + Math.sin(angle * Math.PI / 180D) * radius - 8;
				GlStateManager.translate(xPos, yPos, 0);
				mc.getRenderItem().renderItemIntoGUI(itemHandler.getStackInSlot(i), 0, 0);
				GlStateManager.translate(-xPos, -yPos, 0);

				angle += anglePer;
			}
			net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
		} else if(recipeKeepTicks > 0 && hasWater) {
			String s = I18n.format("botaniamisc.altarRefill0");
			mc.fontRendererObj.drawStringWithShadow(s, xc - mc.fontRendererObj.getStringWidth(s) / 2, yc + 10, 0xFFFFFF);
			s = I18n.format("botaniamisc.altarRefill1");
			mc.fontRendererObj.drawStringWithShadow(s, xc - mc.fontRendererObj.getStringWidth(s) / 2, yc + 20, 0xFFFFFF);
		}
	}

}
