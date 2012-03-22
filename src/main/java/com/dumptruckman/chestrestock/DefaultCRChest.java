package com.dumptruckman.chestrestock;

import com.dumptruckman.chestrestock.api.CRChest;
import com.dumptruckman.chestrestock.api.CRPlayer;
import com.dumptruckman.chestrestock.api.ChestRestock;
import com.dumptruckman.chestrestock.util.BlockLocation;
import com.dumptruckman.chestrestock.util.InventoryTools;
import com.dumptruckman.chestrestock.util.Perms;
import com.dumptruckman.minecraft.pluginbase.config.AbstractYamlConfig;
import com.dumptruckman.minecraft.pluginbase.util.Logging;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

class DefaultCRChest extends AbstractYamlConfig<CRChest> implements CRChest {
    
    private ChestRestock plugin;
    private BlockLocation location;
    
    private Map<String, Inventory> playerInventories = new HashMap<String, Inventory>();

    DefaultCRChest(ChestRestock plugin, BlockLocation location, File configFile, Class<? extends CRChest>... configClasses) throws IOException {
        super(plugin, false, configFile, configClasses);
        this.plugin = plugin;
        this.location = location;
        Block block = location.getBlock();
        if (block == null) {
            throw new IllegalStateException("The world '" + location.getWorldName() + "' is not loaded!");
        }
        if (!(block.getState() instanceof InventoryHolder)) {
            plugin.getChestManager().removeChest(location);
            throw new IllegalStateException("The location '" + location.toString() + "' is not a inventory block!");
        }
        save();
    }

    @Override
    public BlockLocation getLocation() {
        return location;
    }

    @Override
    public InventoryHolder getInventoryHolder() {
        Block block = getLocation().getBlock();
        if (block == null || !(block.getState() instanceof InventoryHolder)) {
            plugin.getChestManager().removeChest(getLocation());
            return null;
        }
        return (InventoryHolder) block.getState();
    }

    @Override
    public Inventory getInventory(HumanEntity player) {
        Inventory inventory;
        if (player != null && get(UNIQUE)) {
            inventory = playerInventories.get(player.getName());
            if (inventory == null) {
                if (getInventoryHolder().getInventory().getType() == InventoryType.CHEST) {
                    inventory = Bukkit.createInventory(getInventoryHolder(),
                            getInventoryHolder().getInventory().getSize());
                    Logging.finer("Created new chest inventory for player: " + player.getName());
                } else {
                    inventory = Bukkit.createInventory(getInventoryHolder(),
                            getInventoryHolder().getInventory().getType());
                    Logging.finer("Created new other inventory for player: " + player.getName());
                }
                inventory.setContents(getInventoryHolder().getInventory().getContents());
                playerInventories.put(player.getName(), inventory);
            } else {
                Logging.finer("Got existing unqiue inventory for player: " + player.getName());
            }
        } else {
            Logging.finer("Got non-unique physical inventory");
            inventory = getInventoryHolder().getInventory();
        }
        return inventory;
    }

    @Override
    public void update(HumanEntity player) {
        Inventory inventory = getInventory(player);
        ItemStack[] items = InventoryTools.fillWithAir(new ItemStack[MAX_SIZE]);
        ItemStack[] chestContents = inventory.getContents();
        System.arraycopy(chestContents, 0, items, 0, chestContents.length);
        set(ITEMS, items);
        save();
    }

    @Override
    public CRPlayer getPlayerData(String name) {
        assert(name != null);
        CRPlayer player = get(PLAYERS.specific(name));
        if (player == null) {
            player = Players.newCRPlayer();
        }
        return player;
    }
    
    private void updatePlayerData(String name, CRPlayer player) {
        assert(name != null);
        assert(player != null);
        set(PLAYERS.specific(name), player);
        save();
    }
    
    private Inventory maybeRestock(HumanEntity player, CRPlayer crPlayer) {
        long accessTime = System.currentTimeMillis();
        long lastRestock = get(LAST_RESTOCK);
        Inventory inventory = getInventory(player);
        if (get(UNIQUE)) {
            lastRestock = crPlayer.getLastRestockTime();
        }
        if (get(PLAYER_LIMIT) < 0 || hasLootBypass(player) || crPlayer.getLootCount() < get(PLAYER_LIMIT)) {
            if (accessTime < lastRestock + (get(PERIOD) * 1000)) {
                return inventory;
            }
            int missedPeriods = 1;
            missedPeriods = (int)(accessTime - lastRestock / (get(PERIOD) * 1000));
            if (get(PERIOD_MODE).equalsIgnoreCase(PERIOD_MODE_PLAYER)) {
                if (get(UNIQUE)) {
                    crPlayer.setLastRestockTime(accessTime);
                } else {
                    set(LAST_RESTOCK, accessTime);
                }
            } else {
                if (get(UNIQUE)) {
                    crPlayer.setLastRestockTime(get(LAST_RESTOCK) + (missedPeriods * (get(PERIOD) * 1000)));
                } else {
                    set(LAST_RESTOCK, get(LAST_RESTOCK) + (missedPeriods * (get(PERIOD) * 1000)));
                }
            }
            if (get(RESTOCK_MODE).equalsIgnoreCase(RESTOCK_MODE_REPLACE)) {
                inventory.clear();
            }
            restock(inventory);
            crPlayer.setLootCount(crPlayer.getLootCount() + 1);
            updatePlayerData(player.getName(), crPlayer);
        }
        save();
        return inventory;
    }
    
    public void restock(Inventory inventory) {
        Logging.finer("Restocking " + inventory);
        ItemStack[] restockItems = get(ITEMS);
        if (get(PRESERVE_SLOTS)) {
            for (int i = 0; i < restockItems.length && i < inventory.getSize(); i++) {
                ItemStack existingItem = inventory.getItem(i);
                ItemStack restockItem = restockItems[i];
                if (existingItem != null
                        && existingItem.getType() == restockItem.getType()
                        && existingItem.getDurability() == restockItem.getDurability()
                        && existingItem.getEnchantments().equals(restockItem.getEnchantments())) {
                    int newAmount = existingItem.getAmount();
                    newAmount += restockItem.getAmount();
                    if (newAmount > existingItem.getType().getMaxStackSize()) {
                        newAmount = existingItem.getType().getMaxStackSize();
                    }
                    restockItem.setAmount(newAmount);
                }
                inventory.setItem(i, restockItem);
            }
        } else {
            for (ItemStack item : restockItems) {
                if (item.getType() != Material.AIR) {
                    inventory.addItem(item);
                }
            }
        }
    }

    @Override
    public void restockAllInventories() {
        restock(getInventory(null));
        for (Map.Entry<String, Inventory> entry : playerInventories.entrySet()) {
            restock(entry.getValue());
        }
    }

    private boolean hasLootBypass(HumanEntity player) {
        if (!get(NAME).isEmpty()) {
            return Perms.BYPASS_LOOT_LIMIT.specific(get(NAME)).hasPermission(player);
        } else {
            return Perms.BYPASS_LOOT_LIMIT_ANY.hasPermission(player);
        }
    }

    @Override
    public void openInventory(HumanEntity player) {
        assert(player != null);
        CRPlayer crPlayer = getPlayerData(player.getName());
        Inventory inventory = maybeRestock(player, crPlayer);
        player.openInventory(inventory);
    }
}