/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.milkbowl.vault.economy.Economy
 *  org.bukkit.event.Listener
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.plugin.RegisteredServiceProvider
 *  org.bukkit.plugin.java.JavaPlugin
 */
package org.simpleshop;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.simpleshop.ShopListener;

public class SimpleShop
extends JavaPlugin {
    private static Economy economy;

    public void onEnable() {
        if (!this.setupEconomy()) {
            this.getLogger().severe("Vault or an economy plugin (e.g. Essentials) was not found.");
            this.getLogger().severe("SimpleShop is disabling itself - install Vault + an economy plugin and restart the server.");
            this.getServer().getPluginManager().disablePlugin((Plugin)this);
            return;
        }
        this.getServer().getPluginManager().registerEvents((Listener)new ShopListener(this), (Plugin)this);
        this.getLogger().info("SimpleShop has been loaded successfully. Economy in use: " + economy.getName());
    }

    private boolean setupEconomy() {
        if (this.getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider rsp = this.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = (Economy)rsp.getProvider();
        return economy != null;
    }

    public static Economy getEconomy() {
        return economy;
    }
}
