/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.fabric.plugin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.lucko.spark.common.platform.MetadataProvider;
import me.lucko.spark.common.platform.PlatformInfo;
import me.lucko.spark.common.platform.world.WorldInfoProvider;
import me.lucko.spark.common.sampler.ThreadDumper;
import me.lucko.spark.common.tick.TickHook;
import me.lucko.spark.common.tick.TickReporter;
import me.lucko.spark.fabric.*;
import me.lucko.spark.fabric.mixin.MinecraftClientAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.CommandSource;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class FabricClientSparkPlugin extends FabricSparkPlugin implements SuggestionProvider<CommandSource> {

    public static void register(FabricSparkMod mod, MinecraftClient client) {
        FabricClientSparkPlugin plugin = new FabricClientSparkPlugin(mod, client);
        plugin.enable();
    }

    private final MinecraftClient minecraft;
    private final ThreadDumper.GameThread gameThreadDumper;
    private CommandDispatcher<CommandSource> dispatcher;

    public FabricClientSparkPlugin(FabricSparkMod mod, MinecraftClient minecraft) {
        super(mod);
        this.minecraft = minecraft;
        this.gameThreadDumper = new ThreadDumper.GameThread(() -> ((MinecraftClientAccessor) minecraft).getThread());
    }

    @Override
    public void enable() {
        super.enable();

        // events
        ClientLifecycleEvents.CLIENT_STOPPING.register(this::onDisable);
//        CommandRegistrationCallback.EVENT.register(this::onCommandRegister);
        super.scheduler.scheduleWithFixedDelay(this::checkCommandRegistered, 10, 10, TimeUnit.SECONDS);
    }

    private void onDisable(MinecraftClient stoppingClient) {
        if (stoppingClient == this.minecraft) {
            disable();
        }
    }

    private void checkCommandRegistered() {
        CommandDispatcher<CommandSource> dispatcher = getPlayerCommandDispatcher();
        if (dispatcher == null) {
            return;
        }

        try {
            if (dispatcher != this.dispatcher) {
                this.dispatcher = dispatcher;
                registerCommands(this.dispatcher, c -> Command.SINGLE_SUCCESS, this, "sparkc", "sparkclient");
                FabricSparkGameHooks.INSTANCE.setChatSendCallback(this::onClientChat);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public boolean onClientChat(String chat) {
        String[] args = processArgs(chat, false);
        if (args == null) {
            return false;
        }

//        this.threadDumper.ensureSetup();
        this.platform.executeCommand(new FabricCommandSender(this.minecraft.player, this), args);
        this.minecraft.inGameHud.getChatHud().addToMessageHistory(chat);
        return true;
    }

    private CommandDispatcher<CommandSource> getPlayerCommandDispatcher() {
        return Optional.ofNullable(this.minecraft.player)
                .map(player -> player.networkHandler)
                .map(ClientPlayNetworkHandler::getCommandDispatcher)
                .orElse(null);
    }

    @Override
    public CompletableFuture<Suggestions> getSuggestions(CommandContext<CommandSource> context, SuggestionsBuilder builder) throws CommandSyntaxException {
        String[] args = processArgs(context, true, "/sparkc", "/sparkclient");
        if (args == null) {
            return Suggestions.empty();
        }

        return generateSuggestions(new FabricCommandSender(this.minecraft.player, this), args, builder);
    }

    private static String[] processArgs(String input, boolean tabComplete) {
        String[] split = input.split(" ", tabComplete ? -1 : 0);
        if (split.length == 0 || !split[0].equals("/sparkc") && !split[0].equals("/sparkclient")) {
            return null;
        }

        return Arrays.copyOfRange(split, 1, split.length);
    }

    @Override
    public boolean hasPermission(CommandOutput sender, String permission) {
        return true;
    }

    @Override
    public Stream<FabricCommandSender> getCommandSenders() {
        return Stream.of(new FabricCommandSender(this.minecraft.player, this));
    }

    @Override
    public void executeSync(Runnable task) {
        this.minecraft.execute(task);
    }

    @Override
    public ThreadDumper getDefaultThreadDumper() {
        return this.gameThreadDumper.get();
    }

    @Override
    public TickHook createTickHook() {
        return new FabricTickHook.Client();
    }

    @Override
    public TickReporter createTickReporter() {
        return new FabricTickReporter.Client();
    }

    @Override
    public PlatformInfo getPlatformInfo() {
        return new FabricPlatformInfo(PlatformInfo.Type.CLIENT);
    }

    @Override
    public String getCommandName() {
        return "sparkc";
    }

}
