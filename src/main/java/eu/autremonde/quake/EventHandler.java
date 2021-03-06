/*
 * Copyright © ReasonDev 2014
 * All Rights Reserved
 * No part of this project or any of its contents may be reproduced, copied, modified or adapted, without the prior written consent of SirReason.
 */

package eu.autremonde.quake;

import eu.autremonde.quake.config.Lang;
import eu.autremonde.quake.config.Settings;
import eu.autremonde.quake.lobby.Lobby;
import eu.autremonde.quake.lobby.LobbyHandler;
import eu.autremonde.quake.match.Stage;
import eu.autremonde.quake.protocol.ProtocolHandler;
import eu.autremonde.quake.railgun.RailgunHandler;
import eu.autremonde.quake.util.Messaging;
import eu.autremonde.quake.util.PlayerUtil;
import org.bukkit.Bukkit;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

public class EventHandler implements Listener {

    private AutreQuake plugin;

    public EventHandler(AutreQuake plugin) {
        this.plugin = plugin;
    }

    public void registerEvents() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        Messaging.printInfo("Events successfully registered!");
    }

    @org.bukkit.event.EventHandler (priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent e) {
        e.setJoinMessage(null);
        PlayerUtil.resetPlayer(e.getPlayer(), true, true);
    }

    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoinMonitor(final PlayerJoinEvent e) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (e.getPlayer() != null && e.getPlayer().isOnline())
                    PlayerUtil.updatePlayerBoard(e.getPlayer());
            }
        }.runTaskLater(plugin, 60);
    }

    @org.bukkit.event.EventHandler (priority = EventPriority.HIGHEST)
    public void onPlayerLeave(PlayerQuitEvent e) {
        Lobby lobby = LobbyHandler.getLobbyFromPlayer(e.getPlayer());
        if(lobby != null) lobby.removePlayer(e.getPlayer());
        e.setQuitMessage(null);
    }

    @org.bukkit.event.EventHandler (priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent e) {
        e.getEntity().getInventory().clear();
        e.getEntity().getInventory().setArmorContents(null);
        e.setNewTotalExp(0);
        e.setDeathMessage(null);
        if(Settings.FORCE_RESPAWN_DELAY.asInt() < 0) return;
        final Player player = e.getEntity();
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if(player == null || !player.isDead()) return;
                ProtocolHandler.forceRespawn(player);
            }
        }, Settings.FORCE_RESPAWN_DELAY.asInt() * 20);
    }

    @org.bukkit.event.EventHandler (priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        Lobby lobby = LobbyHandler.getLobbyFromPlayer(e.getPlayer());
        if(lobby == null) e.setRespawnLocation(Bukkit.getWorld(Settings.SPAWN_WORLD.asString()).getSpawnLocation());
        else {
            final Player player = e.getPlayer();
            e.setRespawnLocation(lobby.getActiveArena().getNextSpawnLoc());
            RailgunHandler.setRespawnTime(player);
            if(lobby.getStage() != Stage.RUNNING) return;
            Bukkit.getScheduler().runTaskLater(AutreQuake.getPlugin(), new Runnable() {
                @Override
                public void run() {
                    if(player == null || !player.isOnline() || player.isDead()) return;
                    PlayerUtil.resetPlayer(player, false, false);
                    RailgunHandler.getRailgun("DEFAULT").giveRailGun(player);
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, true));
                }
            }, 10);
        }
    }

    @org.bukkit.event.EventHandler (priority = EventPriority.HIGHEST)
    public void onPlayerDamage(EntityDamageEvent e) {
        if(!(e.getEntity() instanceof Player) || e.getCause() == EntityDamageEvent.DamageCause.CUSTOM) return;
        e.setCancelled(true);
    }

    @org.bukkit.event.EventHandler (priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent e) {
        if(e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock().getState() instanceof Sign) {
            Lobby lobby = LobbyHandler.getLobbyFromSign(e.getClickedBlock().getLocation());
            if(lobby != null && lobby.canAddPlayer(e.getPlayer())) lobby.addPlayer(e.getPlayer());

        } else if ((e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_AIR) && e.getItem() != null) {
            if(RailgunHandler.getRailgun("DEFAULT").isSimilar(e.getItem()) && e.getPlayer().getTotalExperience() <= 0)
                RailgunHandler.getRailgun("DEFAULT").shoot(e.getPlayer()); //TODO This is not dynamic
        }
    }

    @org.bukkit.event.EventHandler (priority = EventPriority.HIGHEST)
    public void onSignChange(SignChangeEvent e) {
        if(!e.getLine(0).equalsIgnoreCase("[AutreQuake]")) return;
        Lobby lobby = LobbyHandler.getLobby(e.getLine(1));
        if(lobby != null) {
            lobby.addLobbySign(e.getBlock().getLocation());
            Messaging.send(e.getPlayer(), Lang.Messages.LOBBY_SIGN_ADDED.toString().replace("%lobby%", lobby.getLobbyID()));
        } else {
            e.getBlock().breakNaturally();
            Messaging.send(e.getPlayer(), Lang.Messages.INVALID_LOBBY_ID);
        }
    }

    @org.bukkit.event.EventHandler (priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent e) {
        if(!(e.getBlock().getState() instanceof Sign)) return;
        Lobby lobby = LobbyHandler.getLobbyFromSign(e.getBlock().getLocation());
        if(lobby != null) {
            if (!e.getPlayer().hasPermission("autrequake.admin")) e.setCancelled(true);
            else {
                lobby.removeLobbySign(e.getBlock().getLocation());
                Messaging.send(e.getPlayer(), Lang.Messages.LOBBY_SIGN_REMOVED.toString().replace("%lobby%", lobby.getLobbyID()));
            }
        }
    }

    @org.bukkit.event.EventHandler (priority = EventPriority.HIGHEST)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Lobby lobby = LobbyHandler.getLobbyFromPlayer(e.getPlayer());
        if(lobby != null && lobby.getActiveArena() != null)
            if(e.getFrom().getName().equalsIgnoreCase(lobby.getActiveArena().getMainSpawn().getWorld().getName()))
                lobby.removePlayer(e.getPlayer());
    }

    @org.bukkit.event.EventHandler (priority = EventPriority.HIGHEST)
    public void onFoodChange(FoodLevelChangeEvent e) {
        e.setCancelled(true);
    }

    @org.bukkit.event.EventHandler (priority = EventPriority.HIGHEST)
    public void onItemDrop(PlayerDropItemEvent e) {
        e.setCancelled(true);
    }
}
