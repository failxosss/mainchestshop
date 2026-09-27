package org.simpleshop;

import java.util.List;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public class ShopHolder implements InventoryHolder {
    private final Block signBlock;
    private final List<ItemStack> templates;
    private final boolean buyFromPlayer;
    private final double price;
    private final UUID ownerId;
    private Inventory inventory;
    private int[] slotForIndex;

    public ShopHolder(Block signBlock, List<ItemStack> templates, boolean buyFromPlayer, double price, UUID ownerId) {
        this.signBlock = signBlock;
        this.templates = templates;
        this.buyFromPlayer = buyFromPlayer;
        this.price = price;
        this.ownerId = ownerId;
    }

    public Inventory getInventory() {
        return this.inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public Block getSignBlock() {
        return this.signBlock;
    }

    public List<ItemStack> getTemplates() {
        return this.templates;
    }

    public boolean isBuyFromPlayer() {
        return this.buyFromPlayer;
    }

    public double getPrice() {
        return this.price;
    }

    public UUID getOwnerId() {
        return this.ownerId;
    }

    public void setSlotMapping(int[] slotForIndex) {
        this.slotForIndex = slotForIndex;
    }

    public int[] getSlotMapping() {
        return this.slotForIndex;
    }

    /**
     * Returns the index of the item (into templates) that belongs to the given GUI slot,
     * or null if there is no shop item in that slot (e.g. it's just a border filler).
     */
    public Integer getTemplateIndexForSlot(int slot) {
        if (this.slotForIndex == null) {
            return null;
        }
        for (int i = 0; i < this.slotForIndex.length; i++) {
            if (this.slotForIndex[i] == slot) {
                return i;
            }
        }
        return null;
    }
}
