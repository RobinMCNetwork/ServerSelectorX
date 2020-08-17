package xyz.derkades.serverselectorx;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import de.tr7zw.changeme.nbtapi.NBTItem;
import xyz.derkades.derkutils.bukkit.Colors;
import xyz.derkades.derkutils.bukkit.ItemBuilder;
import xyz.derkades.derkutils.bukkit.menu.IconMenu;
import xyz.derkades.derkutils.bukkit.menu.MenuCloseEvent;
import xyz.derkades.derkutils.bukkit.menu.OptionClickEvent;
import xyz.derkades.serverselectorx.actions.Action;
import xyz.derkades.serverselectorx.placeholders.GlobalPlaceholder;
import xyz.derkades.serverselectorx.placeholders.Placeholder;
import xyz.derkades.serverselectorx.placeholders.PlayerPlaceholder;
import xyz.derkades.serverselectorx.placeholders.Server;

public class Menu extends IconMenu {

	private final FileConfiguration config;

	private boolean closed = false;

	public Menu(final Player player, final FileConfiguration config, final String configName) {
		super(Main.getPlugin(), Colors.parseColors(config.getString("title", UUID.randomUUID().toString())), config.getInt("rows", 6), player);

		this.config = config;

		try {
			if (config.contains("sound")) {
				player.playSound(player.getLocation(), Sound.valueOf(config.getString("sound")), 1.0f, 1.0f);
			}
		} catch (final IllegalArgumentException e) {
			Main.getPlugin().getLogger().warning("Invalid sound name in config file '" + configName + "'");
			Main.getPlugin().getLogger().warning("https://github.com/ServerSelectorX/ServerSelectorX/wiki/Sound-names");
		}

		if (this.config == null ||
				this.config.getConfigurationSection("menu") == null) {
			this.player.sendMessage("The configuration file failed to load, probably due to a syntax error.");
			this.player.sendMessage("Take a look at the console for any YAML errors, or paste your config in http://www.yamllint.com/");
			this.player.sendMessage("Check for identation and balanced quotes. If you want to use quotation marks in strings, they must be escaped properly by putting two quotation marks (for example \"\" or '').");
			this.player.sendMessage("Menu name: " + configName);
			return;
		}

		final int updateInterval = config.getInt("update-interval", 100);

		new BukkitRunnable() {
			@Override
			public void run() {
				if (Menu.this.closed || // menu has been closed
						Bukkit.getPlayer(player.getName()) == null // player is offline
						) {
					this.cancel();
					return;
				}

				final long start = System.nanoTime();
				addItems();
				if (Main.LAG_DEBUG) {
					final long diff = System.nanoTime() - start;
					System.out.println("(Re)loaded menu for player " + player.getName() + " in " + diff / 1000 + "μs. (one tick is 50ms)");
				}

				if (config.getBoolean("disable-updates", false)) {
					this.cancel();
				}
			}
		}.runTaskTimer(Main.getPlugin(), 0, updateInterval);
	}

	private void addItems() {
		itemLoop:
		for (final String key : this.config.getConfigurationSection("menu").getKeys(false)) {
			if (!this.config.isConfigurationSection("menu." + key)) {
				this.player.sendMessage("Invalid item " + key + ", check indentation.");
				continue;
			}
			
			final ConfigurationSection section = this.config.getConfigurationSection("menu." + key);

			List<String> actions;
			List<String> rightActions;
			ItemBuilder builder;

			if (section.contains("permission") && !this.player.hasPermission(section.getString("permission"))) {
				// Use no-permission section
				final ConfigurationSection noPermissionSection = section.getConfigurationSection("no-permission");

				if (noPermissionSection == null) {
					this.player.sendMessage("Missing no-permission section for item " + key + ". Remove the permission option or add a no-permission section.");
					return;
				}

				if (!noPermissionSection.contains("material")) {
					this.player.sendMessage("No permission section is missing the material option");
					return;
				}

				if (Arrays.asList("NONE", "AIR").contains(noPermissionSection.getString("material"))) {
					continue itemLoop;
				}

				builder = Main.getItemBuilderFromItemSection(this.player, noPermissionSection);
				actions = noPermissionSection.getStringList("actions");
				rightActions = noPermissionSection.getStringList("actions-right");
			} else {
				// Player has permission, use other sections
				if (section.contains("connector")) {
					// Advanced server section. Use online, offline, dynamic sections.
					final String serverName = section.getString("connector");
					final Server server = Server.getServer(serverName);

					if (server.isOnline()) {
						// to avoid "may not have been initialized" errors later
						builder = null;
						actions = null;
						rightActions = null;

						if (Main.getConfigurationManager().misc.contains("server-name") &&
								serverName.equalsIgnoreCase(Main.getConfigurationManager().misc.getString("server-name")) &&
								section.isConfigurationSection("connected")) {
							final ConfigurationSection connectedSection = section.getConfigurationSection("connected");
							builder = Main.getItemBuilderFromItemSection(this.player, connectedSection);
							actions = connectedSection.getStringList("actions");
							rightActions = connectedSection.getStringList("actions-right");
						} else {
							if (section.contains("dynamic")) {
								for (final String dynamicKey : section.getConfigurationSection("dynamic").getKeys(false)) {
									int colons = 0;
									for (final char c : dynamicKey.toCharArray()) {
										if (c == ':') {
											colons++;
										}
									}

									if (colons != 1) {
										this.player.sendMessage("Invalid dynamic section '" + dynamicKey + "'. Dynamic section identifiers should contain exactly one colon, this one contains " + colons + ".");
										return;
									}

									final String placeholderKeyInConfig = dynamicKey.split(":")[0];
									final String placeholderValueInConfig = dynamicKey.split(":")[1];

									final Placeholder placeholder = server.getPlaceholder(placeholderKeyInConfig);
									if (placeholder == null) {
										Main.getPlugin().getLogger().warning("Dynamic feature contains rule with placeholder " + placeholder + " which has not been received from the server.");
										continue;
									}

									final String placeholderValueFromConnector;

									if (placeholder instanceof GlobalPlaceholder) {
										final GlobalPlaceholder global = (GlobalPlaceholder) placeholder;
										placeholderValueFromConnector = global.getValue();
									} else {
										final PlayerPlaceholder playerPlaceholder = (PlayerPlaceholder) placeholder;
										placeholderValueFromConnector = playerPlaceholder.getValue(this.player);
									}

									final ConfigurationSection dynamicSection = section.getConfigurationSection("dynamic." + dynamicKey);

									final String mode = dynamicSection.getString("mode", "equals");

									if (
											mode.equals("equals") && placeholderValueInConfig.equals(placeholderValueFromConnector) ||
											mode.equals("less") && Double.parseDouble(placeholderValueInConfig) > Double.parseDouble(placeholderValueFromConnector) ||
											mode.equals("more") && Double.parseDouble(placeholderValueInConfig) < Double.parseDouble(placeholderValueFromConnector)
											) {

										if (!dynamicSection.contains("material")) {
											this.player.sendMessage("Dynamic section '" + dynamicKey + "' is missing the material option");
											return;
										}

										if (Arrays.asList("NONE", "AIR").contains(dynamicSection.getString("material"))) {
											continue itemLoop;
										}

										builder = Main.getItemBuilderFromItemSection(this.player, dynamicSection);
										actions = dynamicSection.getStringList("actions");
										rightActions = dynamicSection.getStringList("actions-right");
										break;
									}
								}
							}

							if (builder == null) {
								//No dynamic rule matched, fall back to online
								if (!section.isConfigurationSection("online")) {
									this.player.sendMessage("Error for item " + key);
									this.player.sendMessage("Online section does not exist");
									return;
								}

								final ConfigurationSection onlineSection = section.getConfigurationSection("online");

								if (!onlineSection.contains("material")) {
									this.player.sendMessage("Error for item " + key);
									this.player.sendMessage("Online section does not have a material option");
								}

								if (Arrays.asList("NONE", "AIR").contains(onlineSection.getString("material"))) {
									continue itemLoop;
								}

								builder = Main.getItemBuilderFromItemSection(this.player, onlineSection);
								actions = onlineSection.getStringList("actions");
								rightActions = onlineSection.getStringList("actions-right");
							}
						}

						final Map<String, String> placeholders = new HashMap<>();

						// Add global placeholders to list (uuid = null)
						for (final Placeholder placeholder : server.getPlaceholders()) {
							final String value;
							if (placeholder instanceof GlobalPlaceholder) {
								final GlobalPlaceholder global = (GlobalPlaceholder) placeholder;
								value = global.getValue();
							} else {
								final PlayerPlaceholder playerPlaceholder = (PlayerPlaceholder) placeholder;
								value = playerPlaceholder.getValue(this.player);
							}
							placeholders.put("{" + placeholder.getKey() + "}", value);
						}

						// Now parse the collected placeholders and papi placeholders in name and lore
						builder.namePlaceholders(placeholders).lorePlaceholders(placeholders);

						// Set item amount if dynamic item count is enabled
						if (section.getBoolean("dynamic-item-count", false)) {
							final int amount = server.getOnlinePlayers();
							builder.amount(amount < 1 || amount > 64 ? 1 : amount);
						}
					} else {
						//Server is offline
						if (!section.isConfigurationSection("offline")) {
							this.player.sendMessage("Offline section does not exist");
							return;
						}

						final ConfigurationSection offlineSection = section.getConfigurationSection("offline");

						if (!offlineSection.contains("material")) {
							this.player.sendMessage("Error for item " + key);
							this.player.sendMessage("Offline section does not have a material option");
						}

						if (Arrays.asList("NONE", "AIR").contains(offlineSection.getString("material"))) {
							continue;
						}

						builder = Main.getItemBuilderFromItemSection(this.player, offlineSection);
						actions = offlineSection.getStringList("actions");
						rightActions = offlineSection.getStringList("actions-right");
					}
				} else {
					// Simple section
					if (section.getString("material") == null) {
						this.player.sendMessage("Error for item " + key);
						this.player.sendMessage("Missing material option. Remember, this is not an advanced section, so don't use online/offline/dynamic sections in the config.");
						this.player.sendMessage("If you want to use these sections, add a connector option.");
						this.player.sendMessage("Read more here: https://github.com/ServerSelectorX/ServerSelectorX/wiki/Menu-items-v2");
					}

					if (Arrays.asList("NONE", "AIR").contains(section.getString("material"))) {
						continue itemLoop;
					}

					builder = Main.getItemBuilderFromItemSection(this.player, section);
					actions = section.getStringList("actions");
					rightActions = section.getStringList("actions-right");
				}
			}

			builder.papi(this.player)
				.placeholder("{player}", this.player.getName())
				.placeholder("{globalOnline}", ServerSelectorX.getGlobalPlayerCount() + "");

			// Add actions to item as NBT
			final NBTItem nbt = new NBTItem(builder.create());
			nbt.setObject("SSXActions", actions);
			nbt.setObject("SSXActionsRight", rightActions);

			final ItemStack item = nbt.getItem();
			
			if (key.equals("fill") || key.equals("-1")) { // -1 for backwards compatibility
				// Fill all blank slots
				for (int i = 0; i < this.getInventory().getSize(); i++) {
					if (!this.hasItem(i)) {
						this.addItem(i, item);
					}
				}
			} else {
				for (final String split : key.split(",")) {
					int slot;
					try {
						slot = Integer.valueOf(split);
					} catch (final NumberFormatException e) {
						this.player.sendMessage("Invalid slot number " + split);
						return;
					}
					
					if (slot >= this.getInventory().getSize() || slot < 0) {
						this.player.sendMessage("You put an item in slot " + slot + ", which is higher than the maximum number of slots in your menu.");
						this.player.sendMessage("Use numbers 0 to " + (this.getInventory().getSize() - 1) + "or increase the number of rows in the config");
						return;
					}
					
					this.addItem(slot, item);
				}
			}
		}
	}

	@Override
	public boolean onOptionClick(final OptionClickEvent event) {
		final Player player = event.getPlayer();

		final NBTItem nbt = new NBTItem(event.getItemStack());

		@SuppressWarnings("unchecked")
		final List<String> actions = nbt.getObject("SSXActions", List.class);
		@SuppressWarnings("unchecked")
		final List<String> rightActions = nbt.getObject("SSXActionsRight", List.class);
		
		if (event.getClickType() == ClickType.RIGHT || event.getClickType() == ClickType.SHIFT_RIGHT) {
			if (rightActions.isEmpty()) {
				return Action.runActions(player, actions);
			} else {
				return Action.runActions(player, rightActions);
			}
		} else {
			return Action.runActions(player, actions);
		}
	}

	@Override
	public void onClose(final MenuCloseEvent event) {
		this.closed = true;
	}

}
