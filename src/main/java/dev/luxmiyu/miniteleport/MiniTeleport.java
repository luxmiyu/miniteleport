package dev.luxmiyu.miniteleport;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.api.ModInitializer;

import net.minecraft.world.GameRules;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.WorldProperties;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryKey;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.network.packet.s2c.play.PositionFlag;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Predicate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

public class MiniTeleport implements ModInitializer {
    static final String MOD_ID = "miniteleport";
    static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static final Predicate<ServerCommandSource> PERMISSIONS_NORMAL = source -> source.hasPermissionLevel(0);
    static final Predicate<ServerCommandSource> PERMISSIONS_ADMIN = source -> source.hasPermissionLevel(4);

    static final long REQUEST_TIMEOUT_MS = 60_000; // 60 seconds

    record Warp(String name, int x, int y, int z, String dimension) {
    }

    record TeleportRequest(UUID sender, UUID receiver, boolean here, long expiry) {
    }

    final List<TeleportRequest> pendingRequests = new CopyOnWriteArrayList<>();

    // ------ DATA -----------------------------------------------------------------------------------------------

    Path getDir(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve(MOD_ID);
    }

    File getFile(MinecraftServer server, @Nullable UUID uuid) {
        Path worldDir = getDir(server);
        Path path = (uuid == null) ? worldDir.resolve("warps.json") : worldDir.resolve("homes/" + uuid + ".json");
        return path.toFile();
    }

    void createDir(MinecraftServer server) {
        try {
            Files.createDirectories(getDir(server).resolve("homes"));
        } catch (IOException e) {
            LOGGER.error("Failed to create data directory", e);
        }
    }

    // ------ WARPS ----------------------------------------------------------------------------------------------

    Warp[] getWarps(File file) {
        if (!file.exists()) return new Warp[0];

        try (FileReader reader = new FileReader(file)) {
            return GSON.fromJson(reader, Warp[].class);
        } catch (IOException e) {
            LOGGER.error("Failed to load warps from {}", file, e);
            return new Warp[0];
        }
    }

    @Nullable Warp getWarp(MinecraftServer server, String name, @Nullable UUID uuid) {
        for (Warp warp : getWarps(getFile(server, uuid))) {
            if (warp.name().equals(name)) return warp;
        }
        return null;
    }

    void writeFile(File file, Object object) {
        try {
            Files.createDirectories(file.getParentFile().toPath());

            Path tempFile = Files.createTempFile(file.getParentFile().toPath(), "tmp-", ".json");
            try (FileWriter writer = new FileWriter(tempFile.toFile())) {
                GSON.toJson(object, writer);
            }

            Files.move(
                tempFile,
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException e) {
            LOGGER.error("Failed to save warps to {}", file, e);
        }
    }

    void setWarp(String name, ServerPlayerEntity player, @Nullable UUID uuid) {
        MinecraftServer server = player.getEntityWorld().getServer();
        ArrayList<Warp> warps = new ArrayList<>(List.of(getWarps(getFile(server, uuid))));
        String dimension = player.getEntityWorld().getRegistryKey().getValue().toString();
        Warp warp = new Warp(name, (int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
            (int) Math.floor(player.getZ()), dimension);

        boolean warpExists = false;
        for (int i = 0; i < warps.size(); i++) {
            if (warps.get(i).name().equals(name)) {
                warps.set(i, warp);
                warpExists = true;
            }
        }

        if (!warpExists) {
            warps.add(warp);
        }

        CompletableFuture.runAsync(() -> writeFile(getFile(server, uuid), warps));
    }

    void delWarp(String name, ServerPlayerEntity player, @Nullable UUID uuid) {
        MinecraftServer server = player.getEntityWorld().getServer();
        ArrayList<Warp> warps = new ArrayList<>(List.of(getWarps(getFile(server, uuid))));

        int delIndex = -1;
        for (int i = 0; i < warps.size(); i++) {
            if (warps.get(i).name().equals(name)) {
                delIndex = i;
                break;
            }
        }

        if (delIndex == -1) {
            player.sendMessage(Text.literal("Warp " + name + " does not exist!").formatted(Formatting.RED), false);
            return;
        }

        warps.remove(delIndex);

        CompletableFuture.runAsync(() -> writeFile(getFile(server, uuid), warps));
    }

    void doTeleportEffect(ServerWorld world, ServerPlayerEntity player) {
        world.playSound(
            null,
            player.getBlockX() + 0.5,
            player.getBlockY() + 0.5,
            player.getBlockZ() + 0.5,
            SoundEvents.ENTITY_ENDERMAN_TELEPORT,
            SoundCategory.PLAYERS,
            1.0f,
            1.0f
        );

        world.spawnParticles(
            ParticleTypes.PORTAL,
            player.getBlockX() + 0.5,
            player.getBlockY() + 0.5,
            player.getBlockZ() + 0.5,
            25,
            0.25, 0.25, 0.25,
            0.0
        );
    }

    int warpPlayer(ServerPlayerEntity player, @Nullable Warp warp) {
        if (warp == null) {
            player.sendMessage(Text.literal("That warp doesn't exist!").formatted(Formatting.RED), false);
            return 0;
        }

        ServerWorld world = player.getEntityWorld().getServer()
            .getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(warp.dimension())));
        if (world == null) {
            player.sendMessage(Text.literal("That dimension doesn't exist!").formatted(Formatting.RED), false);
            return 0;
        }

        setWarp("back", player, player.getUuid());

        player.teleport(world, warp.x() + 0.5, warp.y() + 0.1, warp.z() + 0.5, EnumSet.noneOf(PositionFlag.class),
            player.getYaw(), player.getPitch(), true);

        doTeleportEffect(world, player);

        if (List.of("home", "back").contains(warp.name())) {
            player.sendMessage(
                Text.literal(String.format("Teleported %s!", warp.name())).formatted(Formatting.AQUA),
                false
            );
        } else {
            player.sendMessage(
                Text.literal(String.format("Teleported to %s!", warp.name())).formatted(Formatting.AQUA),
                false
            );
        }

        return 1;
    }

    Text listWarps(MinecraftServer server, @Nullable UUID uuid) {
        Warp[] warps = getWarps(getFile(server, uuid));

        if (warps.length == 0) {
            return Text.literal(uuid == null ? "There are no warps." : "You have no homes.").formatted(Formatting.RED);
        }

        MutableText text = Text.literal(uuid == null ? "Warps:" : "Homes:");
        for (Warp warp : warps) {
            text
                .append(Text.literal(" "))
                .append(Text.literal(warp.name()).formatted(Formatting.GOLD).styled(style -> style
                        .withClickEvent(new ClickEvent.RunCommand((uuid == null ? "/warp " : "/home ") + warp.name()))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal("Teleport to " + warp.name())))
                    )
                );
        }
        return text;
    }

    // ------ REQUESTS -------------------------------------------------------------------------------------------

    void addRequest(TeleportRequest request) {
        // remove duplicate pairs
        pendingRequests.removeIf(r -> r.sender().equals(request.sender()) && r.receiver().equals(request.receiver()));
        pendingRequests.add(request);
    }

    void removeRequest(TeleportRequest request) {
        pendingRequests.remove(request);
    }

    TeleportRequest getMostRecentRequest(UUID receiver) {
        return pendingRequests.stream().filter(r -> r.receiver().equals(receiver))
            .max(Comparator.comparingLong(TeleportRequest::expiry)).orElse(null);
    }

    TeleportRequest getRequest(UUID receiver, UUID sender) {
        return pendingRequests.stream().filter(r -> r.receiver().equals(receiver) && r.sender().equals(sender))
            .findFirst().orElse(null);
    }

    void cleanupExpiredRequests() {
        long now = System.currentTimeMillis();
        pendingRequests.removeIf(r -> r.expiry() < now);
    }

    void sendTeleportRequest(ServerPlayerEntity sender, ServerPlayerEntity receiver, boolean here) {
        cleanupExpiredRequests();

        long expiry = System.currentTimeMillis() + REQUEST_TIMEOUT_MS;
        TeleportRequest request = new TeleportRequest(sender.getUuid(), receiver.getUuid(), here, expiry);
        addRequest(request);

        Text message = Text.literal(
                String.format("%s wants to teleport %s. ", sender.getName().getString(), here ? "you to them" : "to you")
            )
            .formatted(Formatting.YELLOW).append(Text.literal("[Accept]").formatted(Formatting.GREEN).styled(
                style -> style.withClickEvent(new ClickEvent.RunCommand("/tpaccept " + sender.getName().getString()))
                    .withHoverEvent(new HoverEvent.ShowText(
                        Text.literal("Accept teleport request from " + sender.getName().getString()))))

            )
            .append(Text.literal(" "))
            .append(Text.literal("[Deny]").formatted(Formatting.RED).styled(
                style -> style.withClickEvent(new ClickEvent.RunCommand("/tpdeny " + sender.getName().getString()))
                    .withHoverEvent(new HoverEvent.ShowText(
                        Text.literal("Deny teleport request from " + sender.getName().getString())))));

        receiver.sendMessage(message, false);
        sender.sendMessage(
            Text.literal("Teleport request sent to " + receiver.getName().getString()).formatted(Formatting.AQUA),
            false);
    }

    void cancelTeleportRequest(ServerPlayerEntity sender) {
        cleanupExpiredRequests();

        List<TeleportRequest> requests =
            pendingRequests.stream().filter(r -> r.sender().equals(sender.getUuid())).toList();

        if (requests.isEmpty()) {
            sender.sendMessage(Text.literal("You have no pending teleport requests.").formatted(Formatting.RED), false);
            return;
        }

        for (TeleportRequest request : requests) {
            ServerPlayerEntity receiver =
                sender.getEntityWorld().getServer().getPlayerManager().getPlayer(request.receiver());

            if (receiver != null) {
                receiver.sendMessage(
                    Text.literal(sender.getName().getString() + " cancelled their teleport request.")
                        .formatted(Formatting.YELLOW),
                    false
                );
            }

            removeRequest(request);
        }

        sender.sendMessage(Text.literal("Teleport request cancelled.").formatted(Formatting.YELLOW), false);
    }

    void acceptTeleportRequest(ServerPlayerEntity receiver, @Nullable ServerPlayerEntity sender) {
        cleanupExpiredRequests();

        TeleportRequest request;

        if (sender != null) {
            request = getRequest(receiver.getUuid(), sender.getUuid());
        } else {
            request = getMostRecentRequest(receiver.getUuid());
        }

        if (request == null) {
            receiver.sendMessage(Text.literal("Teleport request expired or doesn't exist.").formatted(Formatting.RED),
                false);
            return;
        }

        ServerPlayerEntity actualSender =
            receiver.getEntityWorld().getServer().getPlayerManager().getPlayer(request.sender());
        if (actualSender == null) {
            receiver.sendMessage(Text.literal("Request sender is no longer online.").formatted(Formatting.RED), false);
            removeRequest(request);
            return;
        }

        if (request.here()) {
            warpPlayer(receiver,
                new Warp(actualSender.getName().getString(), (int) actualSender.getX(), (int) actualSender.getY(),
                    (int) actualSender.getZ(), actualSender.getEntityWorld().getRegistryKey().getValue().toString()));
            actualSender.sendMessage(Text.literal("Teleport request accepted!").formatted(Formatting.AQUA), false);
        } else {
            warpPlayer(actualSender,
                new Warp(receiver.getName().getString(), (int) receiver.getX(), (int) receiver.getY(),
                    (int) receiver.getZ(), receiver.getEntityWorld().getRegistryKey().getValue().toString()));
            receiver.sendMessage(Text.literal("Teleport request accepted!").formatted(Formatting.AQUA), false);
        }

        removeRequest(request);
    }

    void denyTeleportRequest(ServerPlayerEntity receiver, @Nullable ServerPlayerEntity sender) {
        cleanupExpiredRequests();

        TeleportRequest request;

        if (sender != null) {
            request = getRequest(receiver.getUuid(), sender.getUuid());
        } else {
            request = getMostRecentRequest(receiver.getUuid());
        }

        if (request == null) {
            receiver.sendMessage(Text.literal("Teleport request expired or doesn't exist.").formatted(Formatting.RED),
                false);
            return;
        }

        ServerPlayerEntity actualSender =
            receiver.getEntityWorld().getServer().getPlayerManager().getPlayer(request.sender());
        if (actualSender == null) {
            receiver.sendMessage(Text.literal("Request sender is no longer online.").formatted(Formatting.RED), false);
            removeRequest(request);
            return;
        }

        removeRequest(request);
    }

    // ------ COMMANDS ----------------------------------------------------------------------------------------

    MinecraftServer getServer(CommandContext<ServerCommandSource> context) {
        return context.getSource().getServer();
    }

    ServerPlayerEntity getPlayer(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("You must be a player to use this command."));
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create();
        }
        return player;
    }

    SuggestionProvider<ServerCommandSource> suggestWarps(boolean player) {
        return (context, builder) -> {
            MinecraftServer server = getPlayer(context.getSource()).getEntityWorld().getServer();
            UUID uuid = null;

            if (player) uuid = getPlayer(context.getSource()).getUuid();

            for (Warp warp : getWarps(getFile(server, uuid))) {
                builder.suggest(warp.name());
            }
            return builder.buildFuture();
        };
    }

    SuggestionProvider<ServerCommandSource> suggestPlayers() {
        return (context, builder) -> {
            ServerPlayerEntity sender = getPlayer(context.getSource());

            List<ServerPlayerEntity> players = sender.getEntityWorld().getServer().getPlayerManager().getPlayerList();

            for (ServerPlayerEntity player : players) {
                if (!sender.getUuid().equals(player.getUuid())) {
                    builder.suggest(player.getName().getString());
                }
            }

            return builder.buildFuture();
        };
    }

    void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("sethome")
            .requires(PERMISSIONS_NORMAL)
            .then(CommandManager.argument("name", StringArgumentType.word())
                .executes(context -> {
                    ServerPlayerEntity player = getPlayer(context.getSource());

                    String homeName = StringArgumentType.getString(context, "name");
                    setWarp(homeName, player, player.getUuid());

                    player.sendMessage(Text.literal(String.format("Home %s set!", homeName)).formatted(Formatting.AQUA),
                        false);
                    return 1;
                })
            )
            .executes(context -> {
                ServerPlayerEntity player = getPlayer(context.getSource());
                setWarp("home", player, player.getUuid());
                player.sendMessage(Text.literal("Home set!").formatted(Formatting.AQUA), false);
                return 1;
            })
        );

        dispatcher.register(CommandManager.literal("delhome")
            .requires(PERMISSIONS_NORMAL)
            .then(CommandManager.argument("name", StringArgumentType.word())
                .suggests(suggestWarps(true))
                .executes(context -> {
                    ServerPlayerEntity player = getPlayer(context.getSource());

                    String homeName = StringArgumentType.getString(context, "name");
                    delWarp(homeName, player, player.getUuid());

                    player.sendMessage(
                        Text.literal(String.format("Home %s deleted!", homeName)).formatted(Formatting.AQUA),
                        false);
                    return 1;
                })
            )
            .executes(context -> {
                ServerPlayerEntity player = getPlayer(context.getSource());
                delWarp("home", player, player.getUuid());
                player.sendMessage(Text.literal("Home deleted!").formatted(Formatting.AQUA), false);
                return 1;
            })
        );

        dispatcher.register(CommandManager.literal("home")
            .requires(PERMISSIONS_NORMAL)
            .then(CommandManager.argument("name", StringArgumentType.word())
                .suggests(suggestWarps(true))
                .executes(context -> {
                    ServerPlayerEntity player = getPlayer(context.getSource());
                    String homeName = StringArgumentType.getString(context, "name");
                    return warpPlayer(player, getWarp(getServer(context), homeName, player.getUuid()));
                })
            ).executes(context -> {
                ServerPlayerEntity player = getPlayer(context.getSource());

                return warpPlayer(player, getWarp(getServer(context), "home", player.getUuid()));
            })
        );

        dispatcher.register(CommandManager.literal("homes")
            .requires(PERMISSIONS_NORMAL)
            .executes(context -> {
                ServerPlayerEntity player = getPlayer(context.getSource());
                player.sendMessage(listWarps(getServer(context), player.getUuid()), false);
                return 1;
            })
        );

        dispatcher.register(CommandManager.literal("back")
            .requires(PERMISSIONS_NORMAL)
            .executes(context -> {
                ServerPlayerEntity player = getPlayer(context.getSource());
                return warpPlayer(player, getWarp(getServer(context), "back", player.getUuid()));
            })
        );

        dispatcher.register(CommandManager.literal("setwarp")
            .requires(PERMISSIONS_ADMIN)
            .then(CommandManager.argument("name", StringArgumentType.word()).executes(context -> {
                ServerPlayerEntity player = getPlayer(context.getSource());

                String warpName = StringArgumentType.getString(context, "name");
                setWarp(warpName, player, null);

                player.sendMessage(Text.literal(String.format("Warp %s set!", warpName)).formatted(Formatting.AQUA),
                    false);
                return 1;
            }))
        );

        dispatcher.register(CommandManager.literal("delwarp")
            .requires(PERMISSIONS_ADMIN)
            .then(CommandManager.argument("name", StringArgumentType.word())
                .suggests(suggestWarps(false))
                .executes(context -> {
                    ServerPlayerEntity player = getPlayer(context.getSource());

                    String warpName = StringArgumentType.getString(context, "name");
                    delWarp(warpName, player, null);

                    player.sendMessage(
                        Text.literal(String.format("Warp %s deleted!", warpName)).formatted(Formatting.AQUA),
                        false);
                    return 1;
                })
            )
        );

        dispatcher.register(CommandManager.literal("warp")
            .requires(PERMISSIONS_NORMAL)
            .then(CommandManager.argument("name", StringArgumentType.word())
                .suggests(suggestWarps(false))
                .executes(context -> {
                    ServerPlayerEntity player = getPlayer(context.getSource());
                    String warpName = StringArgumentType.getString(context, "name");
                    return warpPlayer(player, getWarp(getServer(context), warpName, null));
                })
            )
        );

        dispatcher.register(CommandManager.literal("warps")
            .requires(PERMISSIONS_NORMAL)
            .executes(context -> {
                ServerPlayerEntity player = getPlayer(context.getSource());
                player.sendMessage(listWarps(getServer(context), null), false);
                return 1;
            }));

        dispatcher.register(CommandManager.literal("setspawn")
            .requires(PERMISSIONS_ADMIN)
            .executes(context -> {
                ServerPlayerEntity player = getPlayer(context.getSource());
                setWarp("spawn", player, null);

                ServerWorld world = player.getEntityWorld();
                world.setSpawnPoint(WorldProperties.SpawnPoint.create(
                    player.getEntityWorld().getRegistryKey(),
                    player.getBlockPos(),
                    0,
                    0
                ));
                world.getServer().getGameRules().get(GameRules.SPAWN_RADIUS).set(0, world.getServer());

                player.sendMessage(Text.literal("Spawn set!").formatted(Formatting.AQUA), false);
                return 1;
            })
        );

        dispatcher.register(CommandManager.literal("spawn")
            .requires(PERMISSIONS_NORMAL)
            .executes(context -> {
                ServerPlayerEntity player = getPlayer(context.getSource());
                return warpPlayer(player, getWarp(getServer(context), "spawn", null));
            })
        );

        dispatcher.register(CommandManager.literal("tpa")
            .then(CommandManager.argument("target", EntityArgumentType.player())
                .requires(PERMISSIONS_NORMAL)
                .suggests(suggestPlayers())
                .executes(context -> {
                    ServerPlayerEntity sender = getPlayer(context.getSource());
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "target");

                    if (sender.equals(target)) {
                        sender.sendMessage(
                            Text.literal("You cannot teleport to yourself!").formatted(Formatting.RED),
                            false
                        );
                        return 0;
                    }

                    sendTeleportRequest(sender, target, false);
                    return 1;
                })
            )
        );

        dispatcher.register(CommandManager.literal("tpahere")
            .then(CommandManager.argument("target", EntityArgumentType.player())
                .requires(PERMISSIONS_NORMAL)
                .suggests(suggestPlayers())
                .executes(context -> {
                    ServerPlayerEntity sender = getPlayer(context.getSource());
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "target");

                    if (sender.equals(target)) {
                        sender.sendMessage(
                            Text.literal("You cannot teleport to yourself!").formatted(Formatting.RED),
                            false
                        );
                        return 0;
                    }

                    sendTeleportRequest(sender, target, true);
                    return 1;
                })
            )
        );

        dispatcher.register(CommandManager.literal("tpcancel")
            .requires(PERMISSIONS_NORMAL)
            .executes(context -> {
                ServerPlayerEntity sender = getPlayer(context.getSource());
                cancelTeleportRequest(sender);
                return 1;
            })
        );

        dispatcher.register(CommandManager.literal("tpaccept")
            .requires(PERMISSIONS_NORMAL)
            .executes(context -> {
                ServerPlayerEntity receiver = getPlayer(context.getSource());
                acceptTeleportRequest(receiver, null);
                return 1;
            })
            .then(CommandManager.argument("sender", EntityArgumentType.player())
                .suggests(suggestPlayers())
                .executes(context -> {
                    ServerPlayerEntity receiver = getPlayer(context.getSource());
                    ServerPlayerEntity sender = EntityArgumentType.getPlayer(context, "sender");
                    acceptTeleportRequest(receiver, sender);
                    return 1;
                })
            )
        );

        dispatcher.register(CommandManager.literal("tpdeny")
            .requires(PERMISSIONS_NORMAL)
            .executes(context -> {
                ServerPlayerEntity receiver = getPlayer(context.getSource());
                denyTeleportRequest(receiver, null);
                return 1;
            })
            .then(CommandManager.argument("sender", EntityArgumentType.player())
                .suggests(suggestPlayers())
                .executes(context -> {
                    ServerPlayerEntity receiver = getPlayer(context.getSource());
                    ServerPlayerEntity sender = EntityArgumentType.getPlayer(context, "sender");
                    denyTeleportRequest(receiver, sender);
                    return 1;
                })
            )
        );
    }

    // ------ INITIALIZE ----------------------------------------------------------------------------------

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> registerCommands(dispatcher)
        );

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, cause) -> {
            if (entity instanceof ServerPlayerEntity player) {
                setWarp("back", player, player.getUuid());
            }
        });

        ServerWorldEvents.LOAD.register((server, world) -> createDir(server));

        LOGGER.info("Initialized!");
    }

    // -----------------------------------------------------------------------------------------------------------
}
