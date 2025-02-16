package srgmarket;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.RegisteredServiceProvider;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MarketListener implements Listener {
    // Variables necesarias para el funcionamiento del plugin
    private final srgmarket plugin;
    private final WorldGuardPlugin worldGuardPlugin;
    private final Economy economy;
    private final RegionContainer regionContainer;
    private final Map<UUID, List<String>> playerRegions;
    private final Map<String, Long> rentedRegions;

    // Constructor que inicializa el plugin, WorldGuard, y Vault para manejar la economía
    public MarketListener(srgmarket plugin, WorldGuardPlugin worldGuardPlugin) {
        this.plugin = plugin;
        this.worldGuardPlugin = worldGuardPlugin;
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            this.economy = rsp.getProvider();
            this.regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
            this.playerRegions = new HashMap<>();
            this.rentedRegions = new HashMap<>();
        } else {
            throw new IllegalStateException("No se encontró un proveedor de economía compatible con Vault.");
        }
    }

    // Evento que se ejecuta cuando un jugador cambia un cartel
    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        String[] lines = event.getLines();

        // Si el cartel es para venta, valida la región y actualiza el cartel
        if (lines[0].equalsIgnoreCase("[Market]")) {
            String regionName = lines[1];
            double price;
            try {
                price = Double.parseDouble(lines[2].replace("$", "").trim());
            } catch (NumberFormatException e) {
                price = this.plugin.getConfig().getDouble("general.default_price", 500.0);
            }

            RegionManager regionManager = this.regionContainer.get(BukkitAdapter.adapt(player.getWorld()));
            if (regionManager == null || !regionManager.hasRegion(regionName)) {
                player.sendMessage(ChatColor.RED + "La región '" + regionName + "' no existe en WorldGuard.");
                return;
            }

            ProtectedRegion region = regionManager.getRegion(regionName);
            if (!region.getOwners().contains(player.getUniqueId()) && !player.hasPermission("srgmarket.admin")) {
                player.sendMessage(ChatColor.RED + "No eres el propietario de la región '" + regionName + "' para venderla.");
                return;
            }

            // Cambia las líneas del cartel para reflejar la venta
            event.setLine(0, ChatColor.GREEN + "[ForSale]");
            event.setLine(1, regionName);
            event.setLine(2, ChatColor.YELLOW + "$" + price);
            event.setLine(3, getRegionSize(region));
            player.sendMessage(ChatColor.GREEN + "¡Cartel de venta creado para la región '" + regionName + "'!");
        }
        // Si el cartel es para alquiler, valida la región y actualiza el cartel
        else if (lines[0].equalsIgnoreCase("[Rent]")) {
            String regionName = lines[1];

            double rentPrice;
            try {
                rentPrice = Double.parseDouble(lines[2].replace("$", "").trim());
            } catch (NumberFormatException e) {
                rentPrice = this.plugin.getConfig().getDouble("general.default_rent_price", 100.0);
            }

            RegionManager regionManager = this.regionContainer.get(BukkitAdapter.adapt(player.getWorld()));
            if (regionManager == null || !regionManager.hasRegion(regionName)) {
                player.sendMessage(ChatColor.RED + "La región '" + regionName + "' no existe en WorldGuard.");
                return;
            }

            ProtectedRegion region = regionManager.getRegion(regionName);
            if (!region.getOwners().contains(player.getUniqueId()) && !player.hasPermission("srgmarket.admin")) {
                player.sendMessage(ChatColor.RED + "No eres el propietario de la región '" + regionName + "' para rentarla.");
                return;
            }

            long rentDuration = 86400000L; // 24 horas
            long rentEndTime = System.currentTimeMillis() + rentDuration;
            event.setLine(0, ChatColor.BLUE + "[Rent]");
            event.setLine(1, regionName);
            event.setLine(2, ChatColor.YELLOW + "$" + rentPrice);
            event.setLine(3, "Duración: 24h");
            this.rentedRegions.put(regionName, rentEndTime);
            player.sendMessage(ChatColor.GREEN + "¡Cartel de renta creado para la región '" + regionName + "'!");
        }
        // Si el cartel es de un ascensor, simplemente informa que se ha creado
        else if (lines[0].equalsIgnoreCase("[EElevator]")) {
            player.sendMessage(ChatColor.GREEN + "¡Cartel de ascensor creado correctamente!");
        }
    }

    // Evento que se ejecuta cuando un jugador hace clic en un cartel
    @EventHandler
    public void onSignClick(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Block clickedBlock = event.getClickedBlock();
            if (clickedBlock != null && clickedBlock.getState() instanceof Sign) {
                Sign sign = (Sign) clickedBlock.getState();
                String[] lines = sign.getLines();
                Player player = event.getPlayer();

                // Si el cartel es de venta, permite comprar la región
                if (ChatColor.stripColor(lines[0]).equalsIgnoreCase("[ForSale]")) {
                    String regionName = lines[1];
                    double price;
                    try {
                        price = Double.parseDouble(lines[2].replace("$", "").trim());
                    } catch (NumberFormatException e) {
                        price = this.plugin.getConfig().getDouble("general.default_price", 500.0);
                    }

                    this.buyRegion(player, regionName, price, sign);
                }
                // Si el cartel es de alquiler, permite alquilar la región
                else if (ChatColor.stripColor(lines[0]).equalsIgnoreCase("[Rent]")) {
                    String regionName = lines[1];
                    double rentPrice;
                    try {
                        rentPrice = Double.parseDouble(lines[2].replace("$", "").trim());
                    } catch (NumberFormatException e) {
                        rentPrice = this.plugin.getConfig().getDouble("general.default_rent_price", 100.0);
                    }

                    this.rentRegion(player, regionName, rentPrice, sign);
                }
                // Si el cartel es de ascensor, interactúa con la puerta
                else if (ChatColor.stripColor(lines[0]).equalsIgnoreCase("[EElevator]")) {
                    Block lado = clickedBlock.getRelative(event.getBlockFace());
                    if (lado.getType().name().contains("IRON_DOOR")) {
                        player.sendMessage(ChatColor.GREEN + "Ascensor activado. ¡Puerta abierta!");
                        lado.setType(Material.AIR);

                        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                            lado.setType(Material.IRON_DOOR);
                            player.sendMessage(ChatColor.RED + "La puerta se ha cerrado.");
                        }, 60L); // 60 ticks = 3 segundos
                    } else {
                        player.sendMessage(ChatColor.YELLOW + "No hay puerta de hierro enfrente para abrir.");
                    }
                }
                // Si el cartel es de ChestShop, notifica al jugador
                else if (ChatColor.stripColor(lines[0]).equalsIgnoreCase("[ChestShop]")) {
                    player.sendMessage(ChatColor.AQUA + "Estás interactuando con un cartel de ChestShop.");

                    // Verifica si ChestShop está habilitado
                    if (Bukkit.getServer().getPluginManager().isPluginEnabled("ChestShop")) {
                        player.sendMessage(ChatColor.GREEN + "Para crear una tienda como jugador, coloca un cartel sobre un cofre y usa el siguiente formato:");
                        player.sendMessage(ChatColor.YELLOW + "[ChestShop]");
                        player.sendMessage(ChatColor.YELLOW + "<Cantidad>");
                        player.sendMessage(ChatColor.YELLOW + "<Precio>");
                        player.sendMessage(ChatColor.YELLOW + "<Artículo>");

                        player.sendMessage(ChatColor.GREEN + "Como administrador, puedes configurar tiendas de forma más avanzada con permisos y configuraciones personalizadas.");
                    } else {
                        player.sendMessage(ChatColor.RED + "El plugin ChestShop no está habilitado en este servidor.");
                    }
                }
                else if (Bukkit.getPluginManager().isPluginEnabled("ChestShop") && lines[0].equalsIgnoreCase("[ChestShop]")) {
                    player.sendMessage(ChatColor.GREEN + "¡Cartel de ChestShop creado correctamente!");
                }
            }
        }
    }


    // Función para comprar una región
    private void buyRegion(Player player, String regionName, double price, Sign sign) {
        RegionManager regionManager = this.regionContainer.get(BukkitAdapter.adapt(player.getWorld()));
        if (regionManager != null && regionManager.hasRegion(regionName)) {
            if (this.economy.getBalance(player) < price) {
                player.sendMessage(ChatColor.RED + "No tienes suficiente dinero para comprar esta región.");
                return;
            }

            this.economy.withdrawPlayer(player, price);
            ProtectedRegion region = regionManager.getRegion(regionName);
            if (!region.getOwners().contains(player.getUniqueId())) {
                region.getOwners().clear();
                region.getOwners().addPlayer(player.getUniqueId());
            }

            this.playerRegions.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(regionName);
            player.sendMessage(ChatColor.YELLOW + "¡Has comprado la región '" + regionName + "' por $" + price + "!");
            sign.setLine(0, ChatColor.RED + "[Sold]");
            sign.setLine(3, player.getName());
            sign.setLine(2, getRegionSize(region));
            sign.update();
            player.sendMessage(ChatColor.GREEN + "El cartel ha sido actualizado a 'Vendida'!");
        } else {
            player.sendMessage(ChatColor.RED + "La región '" + regionName + "' no existe.");
        }
    }

    // Función para alquilar una región
    private void rentRegion(Player player, String regionName, double rentPrice, Sign sign) {
        RegionManager regionManager = this.regionContainer.get(BukkitAdapter.adapt(player.getWorld()));
        if (regionManager != null && regionManager.hasRegion(regionName)) {
            if (this.economy.getBalance(player) < rentPrice) {
                player.sendMessage(ChatColor.RED + "No tienes suficiente dinero para alquilar esta región.");
                return;
            }

            this.economy.withdrawPlayer(player, rentPrice);
            ProtectedRegion region = regionManager.getRegion(regionName);
            long rentDuration = 86400000L;
            long rentEndTime = System.currentTimeMillis() + rentDuration;
            this.rentedRegions.put(regionName, rentEndTime);
            region.getOwners().clear();
            region.getOwners().addPlayer(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "¡Has alquilado la región '" + regionName + "' por $" + rentPrice + "!");
            sign.setLine(0, ChatColor.RED + "[Rented]");
            sign.setLine(3, player.getName());
            long remainingTime = getRentTimeLeft(regionName);
            if (remainingTime > 0) {
                sign.setLine(2, "Restan: " + remainingTime / 1000 + " segundos");
            }
            sign.update();
            player.sendMessage(ChatColor.GREEN + "El cartel ha sido actualizado a 'Rented'!");
        } else {
            player.sendMessage(ChatColor.RED + "La región '" + regionName + "' no existe.");
        }
    }

    // Obtiene el tamaño de la región
    private String getRegionSize(ProtectedRegion region) {
        return ChatColor.GRAY + "(" + region.getMembers().size() + " miembros)";
    }

    // Obtiene el tiempo restante para un alquiler
    private long getRentTimeLeft(String regionName) {
        return rentedRegions.getOrDefault(regionName, 0L) - System.currentTimeMillis();
    }
}
