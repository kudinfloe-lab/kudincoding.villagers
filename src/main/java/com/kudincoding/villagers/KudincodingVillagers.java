package com.kudincoding.villagers;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class KudincodingVillagers implements ModInitializer {
    public static final String MOD_ID = "kudincoding-villagers";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final List<String> villagerNames = new ArrayList<>();
    private final Set<String> usedNames = new HashSet<>();
    private final Random random = new Random();

    // Track villager professions and levels to detect promotions and job changes
    private final Map<UUID, String> lastKnownProfession = new HashMap<>();
    private final Map<UUID, Integer> lastKnownLevel = new HashMap<>();
    private int tickCounter = 0;

    // Shout timer (2400 ticks = 2 minutes) & radius (48 blocks)
    private static final int SHOUT_INTERVAL_TICKS = 2400;
    private static final double SHOUT_RADIUS = 48.0;

    private static final String[] SHOUT_TEMPLATES = {
            "\"Step right up! Today I'm parting with %s for just %d emerald(s)!\"",
            "\"Fresh stock just arrived! Who needs %s? Only %d emerald(s)!\"",
            "\"You won't find a better deal on %s in this whole province! Starting at %d emerald(s)!\"",
            "\"Don't walk into the wild unprepared! Grab %s while supplies last! (%d emeralds)\"",
            "\"Special discount today on %s! Going for %d emerald(s)!\""
    };

    private static MinecraftServer currentServer = null;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void onInitialize() {
        loadNamesFromFile();
        loadUsedNamesFromFile();
        logEvent(null, "=== Mod initialized ===");

        // Store server instance and clean up memory on start/stop
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            currentServer = server;
            loadUsedNamesFromFile();
            lastKnownProfession.clear();
            lastKnownLevel.clear();
            logEvent(server, "--- World starting: Loaded " + usedNames.size() + " permanently used names ---");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            currentServer = null;
        });

        // 1. EVENT: Villager loads or spawns
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof Villager villager) {
                String currentProf = getProfessionName(villager);
                int currentLvl = villager.getVillagerData().level();

                lastKnownProfession.put(villager.getUUID(), currentProf);
                lastKnownLevel.put(villager.getUUID(), currentLvl);

                if (villager.hasCustomName() && villager.getCustomName() != null) {
                    String existingName = villager.getCustomName().getString();
                    if (usedNames.add(existingName)) {
                        saveUsedNamesToFile();
                    }
                } else {
                    if (assignUniqueName(villager)) {
                        String newName = villager.getCustomName().getString();
                        logEvent(villager.level().getServer(), "NAMED: Villager assigned name '" + newName + "' at " + formatPos(villager)
                                + (villager.isBaby() ? " (Baby)" : " (Profession: " + currentProf + ")"));
                    } else {
                        logEvent(villager.level().getServer(), "UNNAMED: Villager loaded at " + formatPos(villager) + " but all names are currently taken!");
                    }
                }
            }
        });

        // 2. EVENT: Villager dies (Name is permanently retired)
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof Villager villager) {
                String name = getVillagerDisplayName(villager);
                String prof = getProfessionName(villager);
                String deathMsg = damageSource.getLocalizedDeathMessage(villager).getString();

                logEvent(villager.level().getServer(), "DIED: Villager '" + name + "' (" + prof + ") died at " + formatPos(villager)
                        + " | Cause: " + deathMsg + " (Name permanently retired)");

                lastKnownProfession.remove(villager.getUUID());
                lastKnownLevel.remove(villager.getUUID());
            }
        });

        // 3. EVENT: Server Tick for Job Changes, Promotions & Periodic Merchant Shouts
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;

            // Every 1 second (20 ticks): Check jobs & promotions
            if (tickCounter % 20 == 0) {
                for (ServerLevel level : server.getAllLevels()) {
                    for (Villager villager : getAllLoadedVillagers(level)) {
                        UUID id = villager.getUUID();
                        String name = getVillagerDisplayName(villager);
                        String currentProf = getProfessionName(villager);
                        int currentLvl = villager.getVillagerData().level();

                        String oldProf = lastKnownProfession.get(id);
                        Integer oldLvl = lastKnownLevel.get(id);

                        if (oldProf != null && !oldProf.equals(currentProf)) {
                            if (oldProf.equals("none")) {
                                logEvent(server, "GOT JOB: Villager '" + name + "' became a " + currentProf + " at " + formatPos(villager));
                            } else if (currentProf.equals("none")) {
                                logEvent(server, "LOST JOB: Villager '" + name + "' lost their job as a " + oldProf + " at " + formatPos(villager));
                            } else {
                                logEvent(server, "CHANGED JOB: Villager '" + name + "' switched job from " + oldProf + " to " + currentProf + " at " + formatPos(villager));
                            }
                        }

                        if (oldLvl != null && currentLvl > oldLvl) {
                            logEvent(server, "PROMOTED: Villager '" + name + "' (" + currentProf + ") leveled up from Level " + oldLvl + " to Level " + currentLvl + "!");
                        }

                        lastKnownProfession.put(id, currentProf);
                        lastKnownLevel.put(id, currentLvl);
                    }
                }
            }

            // Every 2 minutes: One random merchant shouts their trade to nearby players
            if (tickCounter % SHOUT_INTERVAL_TICKS == 0) {
                broadcastRandomMerchantShout(server);
            }
        });

        // 4. EVENT: Detect Zombie Villager spawns
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof ZombieVillager zv && zv.hasCustomName() && zv.getCustomName() != null) {
                String name = zv.getCustomName().getString();
                if (usedNames.contains(name)) {
                    logEvent(zv.level().getServer(), "ZOMBIFIED: Villager '" + name + "' is now a Zombie Villager at " + formatPos(zv));
                }
            }
        });

        registerCommands();
    }

    private void broadcastRandomMerchantShout(MinecraftServer server) {
        List<Villager> workingVillagers = new ArrayList<>();

        for (ServerLevel level : server.getAllLevels()) {
            for (Villager villager : getAllLoadedVillagers(level)) {
                if (!villager.isBaby() && !getProfessionName(villager).equals("none") && villager.hasCustomName()) {
                    MerchantOffers offers = villager.getOffers();
                    if (offers != null && !offers.isEmpty()) {
                        workingVillagers.add(villager);
                    }
                }
            }
        }

        if (workingVillagers.isEmpty()) return;

        // Pick one random merchant
        Villager merchant = workingVillagers.get(random.nextInt(workingVillagers.size()));
        MerchantOffers offers = merchant.getOffers();
        if (offers == null || offers.isEmpty()) return;

        List<MerchantOffer> validOffers = offers.stream()
                .filter(offer -> !offer.getResult().isEmpty())
                .collect(Collectors.toList());

        if (validOffers.isEmpty()) return;

        MerchantOffer chosenOffer = validOffers.get(random.nextInt(validOffers.size()));
        String itemName = chosenOffer.getResult().getHoverName().getString();
        int price = chosenOffer.getCostA().getCount();
        if (price <= 0) price = 1;

        String template = SHOUT_TEMPLATES[random.nextInt(SHOUT_TEMPLATES.length)];
        String quote = String.format(template, itemName, price);
        String merchantName = getVillagerDisplayName(merchant);
        String prof = getProfessionName(merchant);

        Component shoutComponent = Component.literal("§6[Merchant] §e" + merchantName + " the " + prof + " §fshouts: " + quote);

        // Send shout strictly to players within 48 blocks
        if (merchant.level() instanceof ServerLevel merchantLevel) {
            double radiusSq = SHOUT_RADIUS * SHOUT_RADIUS;
            int heardCount = 0;

            for (ServerPlayer player : merchantLevel.players()) {
                if (player.distanceToSqr(merchant) <= radiusSq) {
                    player.sendSystemMessage(shoutComponent);
                    heardCount++;
                }
            }

            logEvent(server, "MERCHANT SHOUT: '" + merchantName + "' promoted " + itemName + " for " + price + " emeralds (heard by " + heardCount + " player(s)).");
        }
    }

    private void logEvent(MinecraftServer server, String message) {
        Path logFile = FabricLoader.getInstance().getConfigDir().resolve("villager_logs.txt");
        String timestamp = LocalDateTime.now().format(TIME_FORMAT);
        String logLine = "[" + timestamp + "] " + message + System.lineSeparator();

        // 1. Save to text file
        try {
            if (!Files.exists(logFile.getParent())) {
                Files.createDirectories(logFile.getParent());
            }
            Files.writeString(
                    logFile,
                    logLine,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            LOGGER.error("Failed to write to villager_logs.txt", e);
        }

        // 2. Announce in game chat (excluding shout logs to avoid duplicate messages)
        MinecraftServer activeServer = server != null ? server : currentServer;
        if (activeServer != null && !message.startsWith("MERCHANT SHOUT")) {
            activeServer.getPlayerList().broadcastSystemMessage(
                    Component.literal("§7[Villagers] §f" + message),
                    false
            );
        }
    }

    private String getProfessionName(Villager villager) {
        String registeredName = villager.getVillagerData().profession().getRegisteredName();
        int colonIndex = registeredName.indexOf(':');
        return colonIndex != -1 ? registeredName.substring(colonIndex + 1) : registeredName;
    }

    private String getVillagerDisplayName(Villager villager) {
        if (villager.hasCustomName() && villager.getCustomName() != null) {
            return villager.getCustomName().getString();
        }
        return "Unnamed Villager";
    }

    private String formatPos(Entity entity) {
        return String.format("(X: %d, Y: %d, Z: %d)", entity.getBlockX(), entity.getBlockY(), entity.getBlockZ());
    }

    private boolean assignUniqueName(Villager villager) {
        List<String> availableNames = villagerNames.stream()
                .filter(name -> !usedNames.contains(name))
                .collect(Collectors.toList());

        if (availableNames.isEmpty()) {
            return false;
        }

        String chosenName = availableNames.get(random.nextInt(availableNames.size()));
        villager.setCustomName(Component.literal(chosenName));
        villager.setCustomNameVisible(false);
        usedNames.add(chosenName);
        saveUsedNamesToFile();
        return true;
    }

    private List<Villager> getAllLoadedVillagers(ServerLevel level) {
        AABB worldBounds = new AABB(-30000000, -2048, -30000000, 30000000, 2048, 30000000);
        return level.getEntitiesOfClass(Villager.class, worldBounds, Entity::isAlive);
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("villagernames")

                    // /villagernames reload
                    .then(Commands.literal("reload").executes(context -> {
                        loadNamesFromFile();
                        logEvent(context.getSource().getServer(), "COMMAND: Reloaded villager_names.txt (" + villagerNames.size() + " total names).");
                        context.getSource().sendSystemMessage(
                                Component.literal("Reloaded villager_names.txt! Total names in file: " + villagerNames.size())
                        );
                        return 1;
                    }))

                    // /villagernames shout
                    .then(Commands.literal("shout").executes(context -> {
                        broadcastRandomMerchantShout(context.getSource().getServer());
                        return 1;
                    }))

                    // /villagernames nameunnamed
                    .then(Commands.literal("nameunnamed").executes(context -> {
                        int count = 0;
                        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
                            for (Villager villager : getAllLoadedVillagers(level)) {
                                if (!villager.hasCustomName()) {
                                    if (assignUniqueName(villager)) {
                                        count++;
                                        String newName = villager.getCustomName().getString();
                                        logEvent(context.getSource().getServer(), "NAMED (via command): Villager assigned name '" + newName + "' at " + formatPos(villager));
                                    }
                                }
                            }
                        }
                        int remaining = (int) villagerNames.stream().filter(n -> !usedNames.contains(n)).count();
                        context.getSource().sendSystemMessage(
                                Component.literal("Named " + count + " unnamed villagers. (" + remaining + " unique names left)")
                        );
                        return 1;
                    }))

                    // /villagernames renameall
                    .then(Commands.literal("renameall").executes(context -> {
                        usedNames.clear();
                        saveUsedNamesToFile();
                        List<Villager> allVillagers = new ArrayList<>();

                        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
                            for (Villager villager : getAllLoadedVillagers(level)) {
                                villager.setCustomName(null);
                                allVillagers.add(villager);
                            }
                        }

                        int count = 0;
                        for (Villager villager : allVillagers) {
                            if (assignUniqueName(villager)) {
                                count++;
                                String newName = villager.getCustomName().getString();
                                logEvent(context.getSource().getServer(), "RENAMED (via renameall): Villager given new name '" + newName + "' at " + formatPos(villager));
                            }
                        }

                        logEvent(context.getSource().getServer(), "COMMAND: Executed /villagernames renameall. Renamed " + count + " of " + allVillagers.size() + " villagers.");
                        context.getSource().sendSystemMessage(
                                Component.literal("Renamed " + count + " out of " + allVillagers.size() + " loaded villagers!")
                        );
                        return 1;
                    }))
            );
        });
    }

    private void loadNamesFromFile() {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve("villager_names.txt");

        try {
            if (!Files.exists(configFile)) {
                Files.createDirectories(configFile.getParent());
                Files.writeString(configFile, "Arthur, Beatrice, Cedric, Diana, Edward, Fiona, George, Hannah");
                LOGGER.info("Created default villager_names.txt in the config folder.");
            }

            String content = Files.readString(configFile);
            villagerNames.clear();
            villagerNames.addAll(
                    Arrays.stream(content.split(","))
                            .map(String::trim)
                            .filter(name -> !name.isEmpty())
                            .distinct()
                            .collect(Collectors.toList())
            );

            LOGGER.info("Loaded {} villager names from villager_names.txt", villagerNames.size());
        } catch (IOException e) {
            LOGGER.error("Failed to read villager_names.txt", e);
        }
    }

    private void loadUsedNamesFromFile() {
        Path usedFile = FabricLoader.getInstance().getConfigDir().resolve("used_villager_names.txt");

        try {
            usedNames.clear();
            if (Files.exists(usedFile)) {
                String content = Files.readString(usedFile);
                usedNames.addAll(
                        Arrays.stream(content.split(","))
                                .map(String::trim)
                                .filter(name -> !name.isEmpty())
                                .collect(Collectors.toSet())
                );
            }
        } catch (IOException e) {
            LOGGER.error("Failed to read used_villager_names.txt", e);
        }
    }

    private void saveUsedNamesToFile() {
        Path usedFile = FabricLoader.getInstance().getConfigDir().resolve("used_villager_names.txt");

        try {
            if (!Files.exists(usedFile.getParent())) {
                Files.createDirectories(usedFile.getParent());
            }
            String content = String.join(", ", usedNames);
            Files.writeString(usedFile, content);
        } catch (IOException e) {
            LOGGER.error("Failed to write used_villager_names.txt", e);
        }
    }
}