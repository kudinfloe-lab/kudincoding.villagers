package com.kudincoding.villagers;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.world.level.block.entity.SignTextSlot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class KudincodingVillagers implements ModInitializer {
    public static final String MOD_ID = "kudincoding-villagers";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final List<String> villagerNames = new ArrayList<>();
    private final Set<String> usedNames = new HashSet<>();
    private final Set<String> buriedNames = new HashSet<>();
    private final Random random = new Random();

    private final Map<UUID, String> lastKnownProfession = new HashMap<>();
    private final Map<UUID, Integer> lastKnownLevel = new HashMap<>();
    private int tickCounter = 0;

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
    private static final DateTimeFormatter GRAVE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Override
    public void onInitialize() {
        loadNamesFromFile();
        loadUsedNamesFromFile();
        loadBuriedNamesFromFile();
        logEvent(null, "=== Mod initialized ===");

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            currentServer = server;
            loadUsedNamesFromFile();
            loadBuriedNamesFromFile();
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

        // 2. EVENT: Villager dies -> Log it AND spawn a grounded head corpse
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof Villager villager) {
                String name = getVillagerDisplayName(villager);
                String prof = getProfessionName(villager);
                String deathMsg = damageSource.getLocalizedDeathMessage(villager).getString();
                String dateStr = LocalDate.now().format(GRAVE_DATE_FORMAT);

                logEvent(villager.level().getServer(), "DIED: Villager '" + name + "' (" + prof + ") died at " + formatPos(villager)
                        + " | Cause: " + deathMsg + " (Name permanently retired)");

                if (villager.level() instanceof ServerLevel serverLevel) {
                    spawnCorpseEntity(serverLevel, villager.getX(), villager.getY(), villager.getZ(), name, prof, deathMsg, dateStr);
                }

                lastKnownProfession.remove(villager.getUUID());
                lastKnownLevel.remove(villager.getUUID());
            }
        });

        // 3. EVENT: Right-clicking the corpse head on the ground to pick it up
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide() && hand == InteractionHand.MAIN_HAND && entity instanceof ArmorStand stand) {
                ItemStack helmet = stand.getItemBySlot(EquipmentSlot.HEAD);
                if (isCorpseItem(helmet)) {
                    ItemStack copy = helmet.copy();
                    if (!player.getInventory().add(copy)) {
                        Containers.dropItemStack(world, player.getX(), player.getY(), player.getZ(), copy);
                    }
                    stand.discard();
                    if (player instanceof ServerPlayer serverPlayer) {
                        serverPlayer.sendSystemMessage(Component.literal("§7Picked up the remains. Right-click on solid ground to build their tomb."));
                    }
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        });

        // 4. EVENT: Right-clicking the ground with a corpse item to build the tomb
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            ItemStack held = player.getItemInHand(hand);
            if (!isCorpseItem(held)) {
                return InteractionResult.PASS;
            }

            if (world.isClientSide()) {
                return InteractionResult.SUCCESS;
            }

            CustomData customData = held.get(DataComponents.CUSTOM_DATA);
            if (customData == null) return InteractionResult.PASS;

            CompoundTag tag = customData.copyTag();
            String name = tag.getString("VillagerName").orElse("Unknown");
            String prof = tag.getString("VillagerProf").orElse("Good Citizen");
            String cause = tag.getString("DeathCause").orElse("Unknown");
            String date = tag.getString("DeathDate").orElse("01.01.2026");

            ServerLevel serverLevel = (ServerLevel) world;
            BlockPos basePos = hitResult.getBlockPos().above();
            Direction playerFacing = player.getDirection();

            buildTombStructure(serverLevel, basePos, playerFacing, name, prof, cause, date);

            buriedNames.add(name);
            saveBuriedNamesToFile();

            logEvent(serverLevel.getServer(), "BURIED: '" + name + "' (" + prof + ") was laid to rest at "
                    + String.format("(X: %d, Y: %d, Z: %d)", basePos.getX(), basePos.getY(), basePos.getZ()));

            if (!player.getAbilities().instabuild) {
                held.shrink(1);
            }

            return InteractionResult.SUCCESS;
        });

        // 5. EVENT: Server Tick for Job Changes, Promotions & Periodic Merchant Shouts
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;

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

            if (tickCounter % SHOUT_INTERVAL_TICKS == 0) {
                broadcastRandomMerchantShout(server);
            }
        });

        // 6. EVENT: Detect Zombie Villager spawns
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

    // Spawns an invisible Armor Stand wearing the corpse skull right on the ground
    private void spawnCorpseEntity(ServerLevel level, double x, double y, double z, String name, String prof, String cause, String date) {
        ItemStack corpseStack = createCorpseItem(name, prof, cause, date);
        ArmorStand stand = new ArmorStand(level, x, y - 1.35, z);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setSilent(true);
        stand.setCustomName(Component.literal("§cCorpse of " + name + " §7(Right-Click)"));
        stand.setCustomNameVisible(true);
        stand.setItemSlot(EquipmentSlot.HEAD, corpseStack);
        level.addFreshEntity(stand);
    }

    // Creates the Player Head item carrying the villager's metadata
    private ItemStack createCorpseItem(String name, String prof, String cause, String date) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§cCorpse of " + name + " (" + prof + ")"));

        List<Component> loreLines = List.of(
                Component.literal("§7Date: " + date),
                Component.literal("§7Cause: " + cause),
                Component.literal("§eRight-click on ground to place tomb.")
        );
        stack.set(DataComponents.LORE, new ItemLore(loreLines));

        CompoundTag tag = new CompoundTag();
        tag.putBoolean("IsVillagerCorpse", true);
        tag.putString("VillagerName", name);
        tag.putString("VillagerProf", prof);
        tag.putString("DeathCause", cause);
        tag.putString("DeathDate", date);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        return stack;
    }

    private boolean isCorpseItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        return customData != null && customData.copyTag().getBoolean("IsVillagerCorpse").orElse(false);
    }

    // Builds your exact tomb layout:
    // Center: Stone Bricks + Stone Brick Slab on top
    // Left & Right: Stone Brick Stairs sloping inward toward center
    // Front of Center: Wall Sign with Date, Name, Profession, Cause + Poppy flower in front
    private void buildTombStructure(ServerLevel level, BlockPos centerPos, Direction playerFacing, String name, String prof, String cause, String date) {
        Direction front = playerFacing.getOpposite(); // Faces toward the player
        Direction left = playerFacing.getCounterClockWise();
        Direction right = playerFacing.getClockWise();

        // 1. Center Stone Bricks & Top Stone Brick Slab
        level.setBlockAndUpdate(centerPos, Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(centerPos.above(), Blocks.STONE_BRICK_SLAB.defaultBlockState());

        // 2. Left & Right Stone Brick Stairs (sloping up toward the center block)
        level.setBlockAndUpdate(centerPos.relative(left),
                Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, right));
        level.setBlockAndUpdate(centerPos.relative(right),
                Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, left));

        // 3. Wall Sign on the front face of the center Stone Bricks
        BlockPos signPos = centerPos.relative(front);
        level.setBlockAndUpdate(signPos,
                Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, front));

        BlockEntity be = level.getBlockEntity(signPos);
        if (be instanceof SignBlockEntity sign) {
            String shortCause = formatShortCause(name, cause);
            List<Component> lines = List.of(
                    Component.literal("---" + date + "---"),
                    Component.literal(truncate(name, 15)),
                    Component.literal(truncate(capitalize(prof), 15)),
                    Component.literal(truncate(shortCause, 15))
            );
            SignText newSignText = new SignText(lines, lines, DyeColor.BLACK, false);
            sign.setText(newSignText, SignTextSlot.FRONT);
            sign.setChanged();
            level.sendBlockUpdated(signPos, sign.getBlockState(), sign.getBlockState(), 3);
        }

        // 4. Plant a Poppy flower in front of the sign if there is solid ground below
        BlockPos poppyPos = signPos.relative(front);
        if (level.getBlockState(poppyPos).isAir() && level.getBlockState(poppyPos.below()).isSolidRender()) {
            level.setBlockAndUpdate(poppyPos, Blocks.POPPY.defaultBlockState());
        }

        // 5. Play funeral bell chime
        level.playSound(null, centerPos, SoundEvents.BELL_RESONATE, SoundSource.BLOCKS, 1.0f, 0.8f);
    }

    private String formatShortCause(String name, String rawCause) {
        String cleaned = rawCause;
        if (cleaned.startsWith(name + " ")) {
            cleaned = cleaned.substring(name.length() + 1);
        }
        cleaned = cleaned.replace("was slain by ", "Slain by ")
                .replace("was shot by ", "Shot by ")
                .replace("was blown up by ", "Blown up by ")
                .replace("was killed by ", "Killed by ");
        return capitalize(cleaned);
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return "";
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private String truncate(String str, int max) {
        if (str == null) return "";
        return str.length() > max ? str.substring(0, max) : str;
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

        MinecraftServer activeServer = server != null ? server : currentServer;
        // Added activeServer.getPlayerList() != null check here:
        if (activeServer != null && activeServer.getPlayerList() != null && !message.startsWith("MERCHANT SHOUT")) {
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

                    // 1. /villagernames reload
                    .then(Commands.literal("reload").executes(context -> {
                        loadNamesFromFile();
                        logEvent(context.getSource().getServer(), "COMMAND: Reloaded villager_names.txt (" + villagerNames.size() + " total names).");
                        context.getSource().sendSystemMessage(
                                Component.literal("Reloaded villager_names.txt! Total names in file: " + villagerNames.size())
                        );
                        return 1;
                    }))

                    // 2. /villagernames shout
                    .then(Commands.literal("shout").executes(context -> {
                        broadcastRandomMerchantShout(context.getSource().getServer());
                        return 1;
                    }))

                    // 3. /villagernames recoverdead
                    .then(Commands.literal("recoverdead").executes(context -> {
                        ServerPlayer player = context.getSource().getPlayer();
                        if (player == null) {
                            context.getSource().sendSystemMessage(Component.literal("Only a player can run this command."));
                            return 0;
                        }

                        Path logFile = FabricLoader.getInstance().getConfigDir().resolve("villager_logs.txt");
                        if (!Files.exists(logFile)) {
                            context.getSource().sendSystemMessage(Component.literal("§cNo villager_logs.txt found yet!"));
                            return 0;
                        }

                        try {
                            List<String> lines = Files.readAllLines(logFile);
                            Pattern diedPattern = Pattern.compile("^\\[(\\d{4})-(\\d{2})-(\\d{2}).*?\\] DIED: Villager '([^']+)' \\(([^)]+)\\) died at .*? \\| Cause: (.*?)(?: \\(Name permanently retired\\))?$");

                            Map<String, ItemStack> recovered = new LinkedHashMap<>();

                            for (String line : lines) {
                                Matcher m = diedPattern.matcher(line);
                                if (m.matches()) {
                                    String year = m.group(1);
                                    String month = m.group(2);
                                    String day = m.group(3);
                                    String dateFormatted = day + "." + month + "." + year;
                                    String vName = m.group(4);
                                    String vProf = m.group(5);
                                    String vCause = m.group(6).trim();

                                    if (!buriedNames.contains(vName)) {
                                        recovered.put(vName, createCorpseItem(vName, vProf, vCause, dateFormatted));
                                    }
                                }
                            }

                            if (recovered.isEmpty()) {
                                context.getSource().sendSystemMessage(Component.literal("§eNo unburied fallen villagers found in villager_logs.txt!"));
                                return 1;
                            }

                            int count = 0;
                            for (ItemStack corpseItem : recovered.values()) {
                                if (!player.getInventory().add(corpseItem)) {
                                    Containers.dropItemStack(player.level(), player.getX(), player.getY(), player.getZ(), corpseItem);
                                }
                                count++;
                            }

                            context.getSource().sendSystemMessage(
                                    Component.literal("§aRecovered " + count + " fallen villager corpse(s) from the logs! Right-click the ground to place their tombs.")
                            );
                        } catch (IOException e) {
                            LOGGER.error("Failed to parse villager_logs.txt for recoverdead", e);
                            context.getSource().sendSystemMessage(Component.literal("§cError reading villager_logs.txt."));
                        }
                        return 1;
                    }))

                    // 4. /villagernames nameunnamed
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

                    // 5. /villagernames renameall
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

    private void loadBuriedNamesFromFile() {
        Path buriedFile = FabricLoader.getInstance().getConfigDir().resolve("buried_villager_names.txt");

        try {
            buriedNames.clear();
            if (Files.exists(buriedFile)) {
                String content = Files.readString(buriedFile);
                buriedNames.addAll(
                        Arrays.stream(content.split(","))
                                .map(String::trim)
                                .filter(name -> !name.isEmpty())
                                .collect(Collectors.toSet())
                );
            }
        } catch (IOException e) {
            LOGGER.error("Failed to read buried_villager_names.txt", e);
        }
    }

    private void saveBuriedNamesToFile() {
        Path buriedFile = FabricLoader.getInstance().getConfigDir().resolve("buried_villager_names.txt");

        try {
            if (!Files.exists(buriedFile.getParent())) {
                Files.createDirectories(buriedFile.getParent());
            }
            String content = String.join(", ", buriedNames);
            Files.writeString(buriedFile, content);
        } catch (IOException e) {
            LOGGER.error("Failed to write buried_villager_names.txt", e);
        }
    }
}