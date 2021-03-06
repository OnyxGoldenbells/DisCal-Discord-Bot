package org.dreamexposure.discal.client;

import discord4j.core.DiscordClient;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.object.data.stored.GuildBean;
import discord4j.core.object.data.stored.MessageBean;
import discord4j.core.object.presence.Activity;
import discord4j.core.object.presence.Presence;
import discord4j.store.api.mapping.MappingStoreService;
import discord4j.store.jdk.JdkStoreService;
import discord4j.store.redis.RedisStoreService;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.dreamexposure.discal.client.listeners.discal.PubSubListener;
import org.dreamexposure.discal.client.listeners.discord.ReadyEventListener;
import org.dreamexposure.discal.client.message.MessageManager;
import org.dreamexposure.discal.client.module.command.*;
import org.dreamexposure.discal.core.database.DatabaseManager;
import org.dreamexposure.discal.core.logger.Logger;
import org.dreamexposure.discal.core.network.google.Authorization;
import org.dreamexposure.discal.core.object.BotSettings;
import org.dreamexposure.novautils.event.EventManager;
import org.dreamexposure.novautils.network.pubsub.PubSubManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

@SpringBootApplication
@EnableRedisHttpSession
public class DisCalClient {
	private static DiscordClient client;

	public static void main(String[] args) throws IOException {
		//Get settings
		Properties p = new Properties();
		p.load(new FileReader(new File("settings.properties")));
		BotSettings.init(p);

		//Init logger
		Logger.getLogger().init();

		//Handle client setup
		client = createClient();

		//Register discord events
		client.getEventDispatcher().on(ReadyEvent.class).subscribe(ReadyEventListener::handle);

		//Register discal events
		EventManager.get().init();
		EventManager.get().getEventBus().register(new PubSubListener());

		//Register commands
		CommandExecutor executor = CommandExecutor.getExecutor().enable();
		executor.registerCommand(new HelpCommand());
		executor.registerCommand(new DisCalCommand());
		executor.registerCommand(new CalendarCommand());
		executor.registerCommand(new AddCalendarCommand());
		executor.registerCommand(new TimeCommand());
		executor.registerCommand(new LinkCalendarCommand());
		executor.registerCommand(new EventListCommand());
		executor.registerCommand(new EventCommand());
		executor.registerCommand(new RsvpCommand());
		executor.registerCommand(new AnnouncementCommand());
		executor.registerCommand(new DevCommand());

		//Connect to MySQL
		DatabaseManager.getManager().connectToMySQL();
		DatabaseManager.getManager().handleMigrations();

		//Start Google authorization daemon
		Authorization.getAuth().init();

		//Load lang files
		MessageManager.reloadLangs();

		//Start Spring
		if (BotSettings.RUN_API.get().equalsIgnoreCase("true")) {
			try {
				SpringApplication.run(DisCalClient.class, args);
			} catch (Exception e) {
				e.printStackTrace();
				Logger.getLogger().exception(null, "'Spring ERROR' by 'PANIC! AT THE Communication'", e, true, DisCalClient.class);
			}
		}

		//Start Redis pub/sub listeners
		PubSubManager.get().init(BotSettings.REDIS_HOSTNAME.get(), Integer.valueOf(BotSettings.REDIS_PORT.get()), "N/a", BotSettings.REDIS_PASSWORD.get());
		//We must register each channel we want to use. This is super important.
		PubSubManager.get().register(clientId(), BotSettings.PUBSUB_PREFIX.get() + "/ToClient/All");

		//Login
		client.login().block();
	}

	/**
	 * Creates the DisCal bot client.
	 *
	 * @return The client if successful, otherwise <code>null</code>.
	 */
	private static DiscordClient createClient() {
		DiscordClientBuilder clientBuilder = new DiscordClientBuilder(BotSettings.TOKEN.get());
		//Handle shard count and index for multiple java instances
		clientBuilder.setShardIndex(Integer.valueOf(BotSettings.SHARD_INDEX.get()));
		clientBuilder.setShardCount(Integer.valueOf(BotSettings.SHARD_COUNT.get()));
		clientBuilder.setInitialPresence(Presence.online(Activity.playing("Booting Up!")));


		//Redis info + store service for caching
		if (BotSettings.USE_REDIS_STORES.get().equalsIgnoreCase("true")) {
			RedisURI uri = RedisURI.Builder
				.redis(BotSettings.REDIS_HOSTNAME.get(), Integer.valueOf(BotSettings.REDIS_PORT.get()))
				.withPassword(BotSettings.REDIS_PASSWORD.get())
				.build();

			RedisStoreService rss = new RedisStoreService(RedisClient.create(uri));

			MappingStoreService mss = MappingStoreService.create()
				.setMappings(rss, GuildBean.class, MessageBean.class)
				.setFallback(new JdkStoreService());

			clientBuilder.setStoreService(mss);
		} else {
			clientBuilder.setStoreService(new JdkStoreService());
		}

		return clientBuilder.build();
	}

	//Public stuffs
	public static DiscordClient getClient() {
		return client;
	}

	public static int clientId() {
		return Integer.valueOf(BotSettings.SHARD_INDEX.get());
	}
}