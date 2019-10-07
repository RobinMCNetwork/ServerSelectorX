package xyz.derkades.serverselectorx;

import static org.bukkit.ChatColor.DARK_AQUA;
import static org.bukkit.ChatColor.DARK_GRAY;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import xyz.derkades.derkutils.Cooldown;
import xyz.derkades.derkutils.bukkit.Colors;
import xyz.derkades.derkutils.bukkit.IllegalItems;
import xyz.derkades.derkutils.bukkit.ItemBuilder;
import xyz.derkades.derkutils.bukkit.PlaceholderUtil;
import xyz.derkades.derkutils.bukkit.PlaceholderUtil.Placeholder;
import xyz.derkades.serverselectorx.configuration.ConfigSync;
import xyz.derkades.serverselectorx.configuration.ConfigurationManager;
import xyz.derkades.serverselectorx.effects.Effects;
import xyz.derkades.serverselectorx.placeholders.Server;

public class Main extends JavaPlugin {

	public static boolean BETA = false;

	public static final String PREFIX = DARK_GRAY + "[" + DARK_AQUA + "ServerSelectorX" + DARK_GRAY + "]";

	private static ConfigurationManager configurationManager;

	private static Main plugin;

	public static WebServer server;

	public static IllegalItems illegalItems;

	public static Main getPlugin(){
		return plugin;
	}

	@Override
	public void onEnable(){
		plugin = this;

		configurationManager = new ConfigurationManager();

		getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

		getCommand("serverselectorx").setExecutor(new ServerSelectorXCommand());

		//Register custom selector commands
		registerCommands();

		if (getDescription().getVersion().contains("beta")) {
			BETA = true;
		}

		// Disable annoying jetty warnings
		if (!configurationManager.getSSXConfig().getBoolean("jetty-debug", false)){
			//System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog");
			System.setProperty("org.eclipse.jetty.LEVEL", "OFF");
		}

		final int port = configurationManager.getSSXConfig().getInt("port");
		server = new WebServer(port);
		server.start();

		new Effects();
		new ConfigSync();
		new Stats();
		new ItemMoveDropCancelListener();

		Bukkit.getPluginManager().registerEvents(new ItemOpenListener(), this);
		Bukkit.getPluginManager().registerEvents(new GiveItemsListener(), this);
		Bukkit.getPluginManager().registerEvents(new BetaMessageJoinListener(), this);

		illegalItems = new IllegalItems(Main.getPlugin());
	}

	@Override
	public void onDisable() {
		if (server != null) {
			server.stop();
			if (configurationManager.getGlobalConfig().getBoolean("freeze-bukkit-thread", true)) {
				// Freeze bukkit thread to give the server time to stop
				try {
					Thread.sleep(3000);
				} catch (final InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * Registers all custom commands by going through all menu files and adding commands
	 */
	private void registerCommands(){
		try {
			final Field bukkitCommandMap = Bukkit.getServer().getClass().getDeclaredField("commandMap");

			bukkitCommandMap.setAccessible(true);
			final CommandMap commandMap = (CommandMap) bukkitCommandMap.get(Bukkit.getServer());

			for (final Map.Entry<String, FileConfiguration> menuConfigEntry : configurationManager.getMenus().entrySet()) {
				final String configName = menuConfigEntry.getKey();
				final FileConfiguration config = menuConfigEntry.getValue();

				if (!config.contains("commands")) {
					continue;
				}

				final List<String> commandNames = config.getStringList("commands");

				for (final String commandName : commandNames) {
					commandMap.register("ssx-custom", new Command(commandName){

						@Override
						public boolean execute(final CommandSender sender, final String label, final String[] args) {
							if (sender instanceof Player){
								final Player player = (Player) sender;
								//Small cooldown to prevent weird bugs
								if (Cooldown.getCooldown(player.getUniqueId() + "doubleopen") > 0)
									return true;

								Cooldown.addCooldown(player.getUniqueId() + "doubleopen", 1000); //Add cooldown for 1 second

								Main.openSelector(player, configName);
							}
							return true;
						}

					});
				}

			}
		} catch (NoSuchFieldException | IllegalAccessException e) {
			e.printStackTrace();
		}
	}

	public static ConfigurationManager getConfigurationManager() {
		return configurationManager;
	}

	/**
	 * Only used by open listener and commands
	 *
	 * Checks for cooldown, checks for permissions, plays sound
	 */
	public static void openSelector(final Player player, final String configName) {
		final FileConfiguration config = configurationManager.getMenus().get(configName);

		// Check for permissions

		final boolean permissionEnabled = config.getBoolean("permission.open", false);
		final String permission = "ssx.open." + configName;

		if (permissionEnabled && !player.hasPermission(permission)) {
			if (config.contains("permission.message")) {
				final String message = config.getString("permission.message", "");
				if (!message.equals("")) {
					player.sendMessage(Colors.parseColors(message));
				}
			}
			return;
		}

		// Play sound

		final String soundString = Main.getPlugin().getConfig().getString("selector-open-sound");
		if (soundString != null && !soundString.equals("NONE")){
			try {
				final Sound sound = Sound.valueOf(soundString);
				player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
			} catch (final IllegalArgumentException e){
				Main.getPlugin().getLogger().log(Level.WARNING, "A sound with the name " + soundString + " could not be found. Make sure that it is the right name for your server version.");
			}
		}

		// Open menu

		new Menu(player, config, configName).open();
	}

	public static void teleportPlayerToServer(final Player player, final String server){
		if (Cooldown.getCooldown("servertp" + player.getName() + server) > 0)
			return;

		Cooldown.addCooldown("servertp" + player.getName() + server, 1000);

		// Send message if enabled
		final FileConfiguration globalConfig = Main.getConfigurationManager().getGlobalConfig();
		if (globalConfig.getBoolean("server-teleport-message-enabled", false)){
			if (globalConfig.getBoolean("chat-clear", false)){
				for (int i = 0; i < 150; i++) {
					player.sendMessage("");
				}
			}

			final String message = Colors.parseColors(globalConfig.getString("server-teleport-message", "error"));
			player.sendMessage(message.replace("{server}", server));
		}

		// Send message to bungeecord
		try (
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				DataOutputStream dos = new DataOutputStream(baos)
			){

	        dos.writeUTF("Connect");
	        dos.writeUTF(server);
	        player.sendPluginMessage(getPlugin(), "BungeeCord", baos.toByteArray());
		} catch (final IOException e){
			e.printStackTrace();
		}
	}

	public static int getGlobalPlayerCount() {
		/*int online = 0;
		for (final Map<UUID, Map<String, String>> serverPlaceholders : Main.PLACEHOLDERS.values()) {
			online += Integer.parseInt(serverPlaceholders.get(null).get("online"));
		}
		return online;*/

		int online = 0;
		for (final Server server : Server.getServers()) {
			online += server.getOnlinePlayers();
		}

		return online;
	}

    public static ItemBuilder getItemBuilderFromItemSection(final Player player, final ConfigurationSection section) {
    	final String materialString = section.getString("material");
    	final ItemBuilder builder = getItemBuilderFromMaterialString(player, materialString);

    	if (section.contains("title")) {
	    	String title = "&r" + section.getString("title");
	    	title = title.replace("{player}", player.getName());
	    	title = PlaceholderUtil.parsePapiPlaceholders(player, title);
	    	builder.coloredName(title);
    	} else {
    		builder.name(" ");
    	}

		if (section.contains("lore")) {
			List<String> lore = section.getStringList("lore");
			lore = lore.stream().map((s) -> "&r" + s).collect(Collectors.toList());
			final Placeholder playerPlaceholder = new Placeholder("{player}", player.getName());
			lore = PlaceholderUtil.parsePapiPlaceholders(player, lore, playerPlaceholder);
			builder.coloredLore(lore);
		}

		if (section.getBoolean("enchanted")) {
			builder.unsafeEnchant(Enchantment.DURABILITY, 1);
		}

		if (section.getBoolean("hide-flags")) {
			builder.hideFlags(63);
		}

		if (section.isInt("amount")) {
			builder.amount(section.getInt("amount"));
		}

		return builder;
    }

    public static ItemBuilder getItemBuilderFromMaterialString(final Player player, final String materialString) {
		final ItemBuilder builder;

		if (materialString.startsWith("head:")) {
			final String owner = materialString.split(":")[1];
			if (owner.equals("auto")) {
				builder = new ItemBuilder(player);
			} else {
				final OfflinePlayer ownerPlayer;
				try {
					ownerPlayer = Bukkit.getOfflinePlayer(UUID.fromString(owner));
				} catch (final IllegalArgumentException e) {
					player.sendMessage("Invalid player uuid (for head item): " + owner);
					e.printStackTrace();
					return new ItemBuilder(Material.COBBLESTONE);
				}

				if (ownerPlayer == null) {
					player.sendMessage("A player with the uuid " + ownerPlayer + " does not exist");
					return new ItemBuilder(Material.COBBLESTONE);
				}

				builder = new ItemBuilder(ownerPlayer);
			}
		} else {
			Material material;
			try {
				material = Material.valueOf(materialString);
			} catch (final IllegalArgumentException e) {
				player.sendMessage("Invalid item name '" + materialString + "'");
				return new ItemBuilder(Material.COBBLESTONE);
			}

			builder = new ItemBuilder(material);
		}

		return builder;
    }

}
