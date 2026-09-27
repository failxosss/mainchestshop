package org.simpleshop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

public class ShopListener implements Listener {

    // How many "item" columns the GUI has available per row (27/54 slots = rows of 9,
    // the outer columns 0 and 8 are always the border).
    private static final int ITEMS_PER_ROW = 7;
    // Maximum number of GUI rows (54 slots = double chest). Min. 3 (one row for goods).
    private static final int MAX_ROWS = 6;
    // Maximum number of distinct item types the shop can hold.
    private static final int MAX_ITEM_TYPES = (MAX_ROWS - 2) * ITEMS_PER_ROW;

    private final SimpleShop plugin;
    private final NamespacedKey KEY_ITEM;
    private final NamespacedKey KEY_OWNER;
    private final NamespacedKey KEY_MODE;
    private final NamespacedKey KEY_PRICE;

    public ShopListener(SimpleShop plugin) {
        this.plugin = plugin;
        this.KEY_ITEM = new NamespacedKey(plugin, "item");
        this.KEY_OWNER = new NamespacedKey(plugin, "owner");
        this.KEY_MODE = new NamespacedKey(plugin, "mode");
        this.KEY_PRICE = new NamespacedKey(plugin, "price");
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        String line0 = event.getLine(0) == null ? "" : ChatColor.stripColor(event.getLine(0)).trim();
        if (!line0.equalsIgnoreCase("[shop]")) {
            return;
        }
        Player player = event.getPlayer();
        String raw = event.getLine(1) == null ? "" : event.getLine(1).replace(",", ".").trim();
        double price;
        try {
            price = Double.parseDouble(raw);
            if (price <= 0.0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "The second line must be a positive number (price), e.g. 100");
            this.cancelSign(event);
            return;
        }
        String modeRaw = event.getLine(2) == null ? "" : event.getLine(2).trim();
        if (!modeRaw.equalsIgnoreCase("B") && !modeRaw.equalsIgnoreCase("S")) {
            player.sendMessage(ChatColor.RED + "The third line must be 'B' (buy from players) or 'S' (sell to players)");
            this.cancelSign(event);
            return;
        }
        boolean buyFromPlayer = modeRaw.equalsIgnoreCase("B");
        Block chestBlock = this.getAttachedContainer(event.getBlock());
        if (chestBlock == null || !(chestBlock.getState() instanceof Chest)) {
            player.sendMessage(ChatColor.RED + "The sign must be placed on a chest or attached to its front side.");
            this.cancelSign(event);
            return;
        }
        Chest chest = (Chest) chestBlock.getState();

        List<ItemStack> found = this.collectDistinctItems(chest.getInventory());
        if (found.isEmpty()) {
            player.sendMessage(ChatColor.RED + "First put at least 1 item into the chest (you can also add several different types, even a shulker full of items) that you want to "
                    + (buyFromPlayer ? "buy." : "sell.") + " Then place the sign again.");
            this.cancelSign(event);
            return;
        }
        if (found.size() > MAX_ITEM_TYPES) {
            player.sendMessage(ChatColor.YELLOW + "The shop can handle a maximum of " + MAX_ITEM_TYPES + " different item types, using the first " + MAX_ITEM_TYPES + ".");
            found = new ArrayList<>(found.subList(0, MAX_ITEM_TYPES));
        }

        String serialized = this.serializeItems(found);
        event.setLine(0, ChatColor.GREEN + "[Shop]");
        event.setLine(1, this.formatPrice(price));
        event.setLine(2, (buyFromPlayer ? ChatColor.GOLD : ChatColor.AQUA) + (buyFromPlayer ? "BUY (B)" : "SELL (S)"));
        event.setLine(3, this.formatShopLabel(found));

        double finalPrice = price;
        boolean finalBuyFromPlayer = buyFromPlayer;
        Block signBlock = event.getBlock();
        UUID ownerId = player.getUniqueId();
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (signBlock.getState() instanceof Sign) {
                Sign sign = (Sign) signBlock.getState();
                sign.getPersistentDataContainer().set(this.KEY_ITEM, PersistentDataType.STRING, serialized);
                sign.getPersistentDataContainer().set(this.KEY_OWNER, PersistentDataType.STRING, ownerId.toString());
                sign.getPersistentDataContainer().set(this.KEY_MODE, PersistentDataType.STRING, finalBuyFromPlayer ? "B" : "S");
                sign.getPersistentDataContainer().set(this.KEY_PRICE, PersistentDataType.DOUBLE, finalPrice);
                sign.update(true, false);
            }
        });
        player.sendMessage(ChatColor.GREEN + "Shop created: " + (buyFromPlayer ? "buying " : "selling ")
                + this.formatShopLabel(found) + " for " + this.formatPrice(price) + " / item");
    }

    /**
     * Goes through the chest contents and returns a list of distinct item types (by isSimilar),
     * each as a single unit (amount 1). The order matches the order in which each type first
     * appears in the chest.
     */
    private List<ItemStack> collectDistinctItems(Inventory chestInventory) {
        List<ItemStack> found = new ArrayList<>();
        for (ItemStack item : chestInventory.getContents()) {
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            boolean already = false;
            for (ItemStack existing : found) {
                if (existing.isSimilar(item)) {
                    already = true;
                    break;
                }
            }
            if (!already) {
                ItemStack clone = item.clone();
                clone.setAmount(1);
                found.add(clone);
            }
        }
        return found;
    }

    private void cancelSign(SignChangeEvent event) {
        event.setLine(0, ChatColor.RED + "[Error]");
        event.setLine(1, "");
        event.setLine(2, "");
        event.setLine(3, "");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof Sign)) {
            return;
        }
        Sign sign = (Sign) block.getState();
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        if (!pdc.has(this.KEY_ITEM, PersistentDataType.STRING)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        String itemData = pdc.get(this.KEY_ITEM, PersistentDataType.STRING);
        String ownerUuidStr = pdc.get(this.KEY_OWNER, PersistentDataType.STRING);
        String modeRaw = pdc.get(this.KEY_MODE, PersistentDataType.STRING);
        Double price = pdc.get(this.KEY_PRICE, PersistentDataType.DOUBLE);
        if (itemData == null || ownerUuidStr == null || modeRaw == null || price == null) {
            player.sendMessage(ChatColor.RED + "This sign is corrupted.");
            return;
        }
        List<ItemStack> templates = this.deserializeItems(itemData);
        if (templates.isEmpty()) {
            player.sendMessage(ChatColor.RED + "This sign is corrupted.");
            return;
        }
        UUID ownerId = UUID.fromString(ownerUuidStr);
        boolean buyFromPlayer = "B".equals(modeRaw);
        ShopHolder holder = new ShopHolder(block, templates, buyFromPlayer, price, ownerId);

        int rows = this.computeRows(templates.size());
        int[] slots = this.computeSlots(rows, templates.size());
        String title = (buyFromPlayer ? ChatColor.GOLD : ChatColor.AQUA) + (buyFromPlayer ? "Buy: " : "Sell: ") + this.formatShopLabel(templates);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, title);
        holder.setInventory(inv);
        holder.setSlotMapping(slots);
        this.fillBorders(inv);
        this.refreshDisplay(inv, holder);
        player.openInventory(inv);
    }

    /**
     * How many rows (3-6, i.e. 27-54 slots) the GUI needs for the given number of item types.
     * Each row (except top/bottom borders) can hold up to ITEMS_PER_ROW items.
     */
    private int computeRows(int itemCount) {
        int interiorRowsNeeded = (int) Math.ceil(itemCount / (double) ITEMS_PER_ROW);
        if (interiorRowsNeeded < 1) {
            interiorRowsNeeded = 1;
        }
        int rows = interiorRowsNeeded + 2;
        if (rows > MAX_ROWS) {
            rows = MAX_ROWS;
        }
        if (rows < 3) {
            rows = 3;
        }
        return rows;
    }

    /**
     * Calculates which slots (excluding the border) the items are placed into, row by row.
     */
    private int[] computeSlots(int rows, int itemCount) {
        List<Integer> slots = new ArrayList<>();
        for (int r = 1; r <= rows - 2 && slots.size() < itemCount; r++) {
            for (int c = 1; c <= ITEMS_PER_ROW && slots.size() < itemCount; c++) {
                slots.add(r * 9 + c);
            }
        }
        int[] result = new int[slots.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = slots.get(i);
        }
        return result;
    }

    private void fillBorders(Inventory inv) {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
    }

    private void refreshDisplay(Inventory inv, ShopHolder holder) {
        List<ItemStack> templates = holder.getTemplates();
        int[] slots = holder.getSlotMapping();

        Inventory chestInv = null;
        Block chestBlock = this.getAttachedContainer(holder.getSignBlock());
        if (chestBlock != null && chestBlock.getState() instanceof Chest) {
            chestInv = ((Chest) chestBlock.getState()).getInventory();
        }

        for (int i = 0; i < templates.size() && i < slots.length; i++) {
            ItemStack template = templates.get(i);
            ItemStack display = template.clone();
            ItemMeta meta = display.getItemMeta();
            List<String> lore = new ArrayList<>();
            if (meta != null && meta.hasLore() && meta.getLore() != null) {
                lore.addAll(meta.getLore());
                lore.add("");
            }

            int stock = chestInv != null ? this.countMatching(chestInv, template) : -1;
            lore.add(ChatColor.GRAY + "Price: " + ChatColor.WHITE + this.formatPrice(holder.getPrice()) + " / item");
            if (holder.isBuyFromPlayer()) {
                lore.add(ChatColor.YELLOW + "Click to sell 1 item");
                lore.add(ChatColor.YELLOW + "Shift+click to sell a full stack");
            } else {
                if (stock >= 0) {
                    lore.add(ChatColor.WHITE + "Stock: " + stock + " items");
                }
                lore.add(ChatColor.YELLOW + "Click to buy 1 item");
                lore.add(ChatColor.YELLOW + "Shift+click to buy a full stack");
                if (this.isShulkerBox(template)) {
                    lore.add(ChatColor.YELLOW + "Right-click to preview contents");
                }
            }
            if (meta != null) {
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            inv.setItem(slots[i], display);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (topInventory.getHolder() instanceof ShulkerPreviewHolder) {
            event.setCancelled(true);
            return;
        }
        if (!(topInventory.getHolder() instanceof ShopHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(topInventory)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        ShopHolder holder = (ShopHolder) topInventory.getHolder();
        Integer templateIndex = holder.getTemplateIndexForSlot(event.getSlot());
        if (templateIndex == null) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        Block chestBlock = this.getAttachedContainer(holder.getSignBlock());
        if (chestBlock == null || !(chestBlock.getState() instanceof Chest)) {
            player.sendMessage(ChatColor.RED + "This shop's chest is missing or has been destroyed.");
            player.closeInventory();
            return;
        }
        Chest chest = (Chest) chestBlock.getState();
        Inventory chestInv = chest.getInventory();
        OfflinePlayer owner = Bukkit.getOfflinePlayer(holder.getOwnerId());
        Economy econ = SimpleShop.getEconomy();
        ItemStack template = holder.getTemplates().get(templateIndex);
        ClickType click = event.getClick();

        if (!holder.isBuyFromPlayer() && this.isShulkerBox(template) && (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT)) {
            this.openShulkerPreview(player, template);
            return;
        }

        int amount = click.isShiftClick() ? template.getMaxStackSize() : 1;
        if (holder.isBuyFromPlayer()) {
            this.handlePlayerSells(player, owner, econ, chestInv, template, holder.getPrice(), amount);
        } else {
            this.handlePlayerBuys(player, owner, econ, chestInv, template, holder.getPrice(), amount);
        }
        this.refreshDisplay(topInventory, holder);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShopHolder) {
            event.setCancelled(true);
        }
    }

    private boolean isShulkerBox(ItemStack item) {
        return item != null && item.getType().name().endsWith("SHULKER_BOX");
    }

    private void openShulkerPreview(Player player, ItemStack shulkerItem) {
        ItemMeta meta = shulkerItem.getItemMeta();
        if (!(meta instanceof BlockStateMeta)) {
            player.sendMessage(ChatColor.RED + "This item cannot be previewed.");
            return;
        }
        BlockState state = ((BlockStateMeta) meta).getBlockState();
        if (!(state instanceof ShulkerBox)) {
            player.sendMessage(ChatColor.RED + "This item cannot be previewed.");
            return;
        }
        ShulkerBox shulkerBox = (ShulkerBox) state;
        Inventory shulkerInv = shulkerBox.getInventory();
        ShulkerPreviewHolder previewHolder = new ShulkerPreviewHolder();
        Inventory preview = Bukkit.createInventory(previewHolder, shulkerInv.getSize(),
                ChatColor.DARK_PURPLE + "Contents: " + this.formatItemName(shulkerItem));
        previewHolder.setInventory(preview);
        ItemStack[] contents = shulkerInv.getContents();
        for (int i = 0; i < contents.length && i < preview.getSize(); i++) {
            preview.setItem(i, contents[i] == null ? null : contents[i].clone());
        }
        player.openInventory(preview);
    }

    private void handlePlayerBuys(Player player, OfflinePlayer owner, Economy econ, Inventory chestInv, ItemStack template, double price, int amount) {
        int available = this.countMatching(chestInv, template);
        if (available <= 0) {
            player.sendMessage(ChatColor.RED + "The shop is sold out.");
            return;
        }
        amount = Math.min(amount, available);
        double total = price * (double) amount;
        if (econ.getBalance(player) < total) {
            player.sendMessage(ChatColor.RED + "You don't have enough money. You need " + this.formatPrice(total));
            return;
        }
        econ.withdrawPlayer(player, total);
        econ.depositPlayer(owner, total);
        this.removeMatching(chestInv, template, amount);
        ItemStack giveStack = template.clone();
        giveStack.setAmount(amount);
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(giveStack);
        for (ItemStack item : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        player.sendMessage(ChatColor.GREEN + "You bought " + amount + "x " + this.formatItemName(template) + " for " + this.formatPrice(total));
    }

    private void handlePlayerSells(Player player, OfflinePlayer owner, Economy econ, Inventory chestInv, ItemStack template, double price, int amount) {
        int playerHas = this.countMatching(player.getInventory(), template);
        if (playerHas <= 0) {
            player.sendMessage(ChatColor.RED + "You have nothing to sell - you need " + this.formatItemName(template));
            return;
        }
        amount = Math.min(amount, playerHas);
        double total = price * (double) amount;
        if (econ.getBalance(owner) < total) {
            player.sendMessage(ChatColor.RED + "The shop owner doesn't have enough money to buy this.");
            return;
        }
        int space = this.freeSpaceForTemplate(chestInv, template);
        if (space <= 0) {
            player.sendMessage(ChatColor.RED + "The shop's chest is full, cannot sell.");
            return;
        }
        amount = Math.min(amount, space);
        total = price * (double) amount;
        this.removeMatching(player.getInventory(), template, amount);
        ItemStack addStack = template.clone();
        addStack.setAmount(amount);
        chestInv.addItem(addStack);
        econ.withdrawPlayer(owner, total);
        econ.depositPlayer(player, total);
        player.sendMessage(ChatColor.GREEN + "You sold " + amount + "x " + this.formatItemName(template) + " for " + this.formatPrice(total));
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block.getState() instanceof Sign)) {
            return;
        }
        Sign sign = (Sign) block.getState();
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        if (!pdc.has(this.KEY_ITEM, PersistentDataType.STRING)) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isOp() || player.hasPermission("simpleshop.admin")) {
            return;
        }
        String ownerUuidStr = pdc.get(this.KEY_OWNER, PersistentDataType.STRING);
        if (ownerUuidStr != null && ownerUuidStr.equals(player.getUniqueId().toString())) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(ChatColor.RED + "Only the owner can break this shop sign.");
    }

    private Block getAttachedContainer(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof WallSign) {
            WallSign wallSign = (WallSign) data;
            return block.getRelative(wallSign.getFacing().getOppositeFace());
        }
        return block.getRelative(BlockFace.DOWN);
    }

    private int countMatching(Inventory inventory, ItemStack template) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item == null || !item.isSimilar(template)) {
                continue;
            }
            count += item.getAmount();
        }
        return count;
    }

    private void removeMatching(Inventory inventory, ItemStack template, int amount) {
        int remaining = amount;
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || !item.isSimilar(template)) {
                continue;
            }
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            if (item.getAmount() <= 0) {
                inventory.setItem(i, null);
            } else {
                inventory.setItem(i, item);
            }
            remaining -= take;
        }
    }

    private int freeSpaceForTemplate(Inventory inventory, ItemStack template) {
        int space = 0;
        int maxStack = template.getMaxStackSize();
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType() == Material.AIR) {
                space += maxStack;
                continue;
            }
            if (!item.isSimilar(template)) {
                continue;
            }
            space += maxStack - item.getAmount();
        }
        return space;
    }

    private String formatPrice(double price) {
        if (price == Math.floor(price)) {
            return String.valueOf((long) price);
        }
        return String.format("%.2f", price);
    }

    private String formatItemName(ItemStack item) {
        String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? ChatColor.stripColor(item.getItemMeta().getDisplayName())
                : item.getType().name().replace("_", " ").toLowerCase();
        if (name.length() > 14) {
            name = name.substring(0, 14);
        }
        return name;
    }

    /**
     * Text used on the 4th line of the sign and in the GUI title - the item's name if there is
     * only one type, otherwise the number of item types.
     */
    private String formatShopLabel(List<ItemStack> templates) {
        if (templates.size() == 1) {
            return this.formatItemName(templates.get(0));
        }
        return templates.size() + " item types";
    }

    private String serializeItems(List<ItemStack> items) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeInt(items.size());
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }
            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to save items to the sign", e);
        }
    }

    private List<ItemStack> deserializeItems(String data) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            int count = dataInput.readInt();
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                items.add((ItemStack) dataInput.readObject());
            }
            dataInput.close();
            return items;
        } catch (Exception e) {
            // Backward compatibility with old signs still stored as a single item (no count prefix).
            try {
                ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
                BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
                ItemStack single = (ItemStack) dataInput.readObject();
                dataInput.close();
                List<ItemStack> items = new ArrayList<>();
                items.add(single);
                return items;
            } catch (Exception legacyFailure) {
                throw new RuntimeException("Failed to load items from the sign", legacyFailure);
            }
        }
    }
}
