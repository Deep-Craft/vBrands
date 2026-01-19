package com.vitor;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Plugin(id = "vbrand", name = "vBrand", version = "1.0.0", description = "Custom brand modifier for Velocity", authors = {"Vitor"})
public class vBrand {
    public static final MinecraftChannelIdentifier BRAND_IDENTIFIER = MinecraftChannelIdentifier.from("minecraft:brand");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private BrandUpdater brandUpdater;
    private Config config;

    @Inject
    public vBrand(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            load();

            // Register command
            CommandManager commandManager = proxy.getCommandManager();
            CommandMeta cmdMeta = commandManager.metaBuilder("vbr")
                    .aliases("vbrand", "vbrandreload")
                    .plugin(this)
                    .build();
            commandManager.register(cmdMeta, new ReloadCmd(this));

            // Register brand channel
            proxy.getChannelRegistrar().register(BRAND_IDENTIFIER);

            logger.info("vBrand has been enabled successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize vBrand: {}", e.getMessage(), e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        unload();
    }

    @Subscribe
    public void onProxyReload(ProxyReloadEvent event) {
        unload();
        load();
    }

    @Subscribe
    public void onPlayerConnect(ServerPostConnectEvent event) {
        if (brandUpdater != null) {
            // Pequeno delay para garantir que o player está completamente conectado
            proxy.getScheduler()
                    .buildTask(this, () -> {
                        // Envia personalizado no connect (não enfileira)
                        brandUpdater.sendBrand(event.getPlayer());
                    })
                    .delay(100, TimeUnit.MILLISECONDS)
                    .schedule();
        }
    }

    public void load() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            File configFile = new File(dataDirectory.toFile(), "config.json");

            if (!configFile.exists()) {
                config = new Config(configFile);
                config.init();
                config.save();
                logger.info("Created default config file");
            } else {
                config = Config.load(configFile);
                logger.info("Loaded config from file");
            }

            brandUpdater = new BrandUpdater(this, proxy, config);

        } catch (Exception e) {
            logger.error("Cannot load config file: {}", e.getMessage());
            // Create a fallback config in memory
            config = new Config(null);
            config.init();
            brandUpdater = new BrandUpdater(this, proxy, config);
        }
    }

    public void unload() {
        if (brandUpdater != null) {
            brandUpdater.stop();
            brandUpdater = null;
        }
    }

    public Logger logger() {
        return logger;
    }

    public Config config() {
        return config;
    }

    public BrandUpdater brandUpdater() {
        return brandUpdater;
    }
}
