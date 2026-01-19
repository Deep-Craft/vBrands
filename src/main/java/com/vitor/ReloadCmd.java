package com.vitor;

import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class ReloadCmd implements SimpleCommand {
    private final vBrand plugin;

    public ReloadCmd(vBrand plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        plugin.unload();
        plugin.load();

        if (plugin.brandUpdater() != null) {
            plugin.brandUpdater().broadcastBrand();
        }

        invocation.source().sendMessage(
                Component.text("vBrand configuration reloaded!", NamedTextColor.GREEN)
        );
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("vbrand.reload");
    }
}