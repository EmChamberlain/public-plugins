package com.lucidplugins.lucidcombat;

import com.google.inject.Provides;
import com.lucidplugins.lucidcombat.api.item.SlottedItem;
import com.lucidplugins.lucidcombat.api.util.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.extern.slf4j.XSlf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.itemcharges.ItemChargeConfig;
import net.runelite.client.ui.overlay.OverlayManager;
import net.unethicalite.api.events.MenuAutomated;
import net.unethicalite.api.game.Combat;
import net.unethicalite.api.game.GameThread;
import net.unethicalite.api.items.Bank;
import net.unethicalite.api.items.Equipment;
import net.unethicalite.api.items.GrandExchange;
import net.unethicalite.api.items.Inventory;
import net.unethicalite.api.packets.MousePackets;
import net.unethicalite.api.widgets.Widgets;
import net.unethicalite.client.managers.InventoryManager;
import org.pf4j.Extension;

import javax.inject.Inject;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.function.Predicate;

import net.unethicalite.api.widgets.Prayers;

@Slf4j
@Extension
@PluginDescriptor(name = "Lucid Combat", description = "Helps with Combat related stuff", enabledByDefault = false)
public class LucidCombatPlugin extends Plugin implements KeyListener
{

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private LucidCombatTileOverlay overlay;

    @Inject
    private LucidCombatPanelOverlay panelOverlay;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ConfigManager configManager;

    @Inject
    private KeyManager keyManager;

    @Inject
    private ItemManager itemManager;

    @Inject
    private LucidCombatConfig config;

    private int nextSolidFoodTick = 0;
    private int nextPotionTick = 0;
    private int nextKarambwanTick = 0;

    private boolean eatingToMaxHp = false;

    private boolean drinkingToMaxPrayer = false;

    private int timesBrewedDown = 0;

    private Random random = new Random();

    private int nextHpToRestoreAt = 0;

    private int nextPrayerLevelToRestoreAt = 0;

    private int lastTickActive = 0;

    private int nextReactionTick = 0;

    private int lastFinisherAttempt = 0;

    //private int nonSpecWeaponId = -1;
    //private int offhandWeaponID = -1;

    private boolean isSpeccing = false;

    private int lastFlickTick = 0;

    @Getter
    private Actor lastTarget = null;

    @Getter
    private boolean autoCombatRunning = false;

    @Getter
    private String secondaryStatus = "Starting...";

    @Getter
    private WorldPoint startLocation = null;

    @Getter
    private Map<LocalPoint, Integer> expectedLootLocations = new HashMap<>();

    private LocalPoint lastLootedTile = null;


    @Getter
    private int forcedLootTick = 0;

    private int lastAlchTick = 0;

    private int teleTabCountAfterTeleport = -1;

    private boolean taskEnded = false;

    private boolean needToOpenInventory = false;

    private List<NPC> npcsKilled = new ArrayList<>();

    private final List<String> prayerRestoreNames = List.of("Tears of elidinis", "Blessed crystal scarab", "Prayer potion", "Super restore", "Sanfew serum", "Blighted super restore", "Moonlight potion");

    private final Predicate<SlottedItem> foodFilterNoBlacklistItems = (item) -> {
        final ItemComposition itemComposition = client.getItemDefinition(item.getItem().getId());
        return itemComposition.getName() != null &&
                (!itemComposition.getName().equals("Cooked karambwan") && !itemComposition.getName().equals("Blighted karambwan")) &&
            !config.foodBlacklist().contains(itemComposition.getName()) &&
            (Arrays.asList(itemComposition.getInventoryActions()).contains("Eat"));
    };

    private final Predicate<SlottedItem> karambwanFilter = (item) -> {
        final ItemComposition itemComposition = client.getItemDefinition(item.getItem().getId());
        return itemComposition.getName() != null &&
                (itemComposition.getName().equals("Cooked karambwan") || itemComposition.getName().equals("Blighted karambwan")) &&
                (Arrays.asList(itemComposition.getInventoryActions()).contains("Eat"));
    };

    @Override
    protected void startUp()
    {
        clientThread.invoke(this::pluginEnabled);
    }

    private void pluginEnabled()
    {
        keyManager.registerKeyListener(this);

        if (!overlayManager.anyMatch(p -> p == overlay))
        {
            overlayManager.add(overlay);
        }

        if (!overlayManager.anyMatch(p -> p == panelOverlay))
        {
            overlayManager.add(panelOverlay);
        }

        expectedLootLocations.clear();
        npcsKilled.clear();
    }

    @Override
    protected void shutDown()
    {
        keyManager.unregisterKeyListener(this);
        autoCombatRunning = false;

        if (overlayManager.anyMatch(p -> p == overlay))
        {
            overlayManager.remove(overlay);
        }

        if (overlayManager.anyMatch(p -> p == panelOverlay))
        {
            overlayManager.remove(panelOverlay);
        }
    }

    @Provides
    LucidCombatConfig getConfig(ConfigManager configManager)
    {
        return configManager.getConfig(LucidCombatConfig.class);
    }

    @Subscribe
    private void onMenuOpened(MenuOpened event)
    {
        if (!config.rightClickMenu())
        {
            return;
        }

        final Optional<MenuEntry> attackEntry = Arrays.stream(event.getMenuEntries()).filter(menu -> menu.getOption().equals("Attack") && menu.getNpc() != null && menu.getNpc().getName() != null).findFirst();

        if (attackEntry.isEmpty())
        {
            return;
        }

        if (!autoCombatRunning)
        {
            client.createMenuEntry(1)
            .setOption("Start Killing")
            .setTarget("<col=ffff00>" + attackEntry.get().getNpc().getName() + "</col>")
            .setType(MenuAction.RUNELITE)
            .onClick((entry) -> {
                clientThread.invoke(() -> configManager.setConfiguration("lucid-combat", "npcToFight", attackEntry.get().getNpc().getName()));
                lastTickActive = client.getTickCount();
                lastAlchTick = client.getTickCount();
                expectedLootLocations.clear();
                npcsKilled.clear();
                startLocation = client.getLocalPlayer().getWorldLocation();
                autoCombatRunning = true;
            });
        }
        else
        {
            if (attackEntry.get().getNpc() == null || attackEntry.get().getNpc().getName() == null)
            {
                return;
            }

            if (isNameInNpcsToFight(attackEntry.get().getNpc().getName()))
            {
                client.createMenuEntry(1)
                .setOption("Stop Killing")
                .setTarget("<col=ffff00>" + attackEntry.get().getNpc().getName() + "</col>")
                .setType(MenuAction.RUNELITE)
                .onClick((entry) -> {
                    autoCombatRunning = false;
                    lastTickActive = client.getTickCount();
                    lastTarget = null;
                    lastLootedTile = null;
                    expectedLootLocations.clear();
                    npcsKilled.clear();
                    startLocation = null;
                });
            }
        }
    }

    private boolean isNameInNpcsToFight(String name)
    {
        if (config.npcToFight().trim().isEmpty())
        {
            return false;
        }

        for (String npcName : config.npcToFight().split(","))
        {
            npcName = npcName.trim();

            if (name.contains(npcName))
            {
                return true;
            }
        }

        return false;
    }

    @Subscribe
    private void onConfigChanged(ConfigChanged event)
    {
        if (!event.getGroup().equals("lucid-combat"))
        {
            return;
        }

        clientThread.invoke(() -> {
            lastTickActive = client.getTickCount();
            taskEnded = false;
            nextHpToRestoreAt = Math.max(1, config.minHp() + (config.minHpBuffer() > 0 ? random.nextInt(config.minHpBuffer() + 1) : 0));
            nextPrayerLevelToRestoreAt = Math.max(1, config.prayerPointsMin() + (config.prayerRestoreBuffer() > 0 ? random.nextInt(config.prayerRestoreBuffer() + 1) : 0));
        });
    }

    @Subscribe
    private void onChatMessage(ChatMessage event)
    {
        if (event.getType() == ChatMessageType.GAMEMESSAGE && (event.getMessage().contains("return to a Slayer master") || event.getMessage().contains("more advanced Slayer Master")))
        {

            if (config.stopOnTaskCompletion() && autoCombatRunning)
            {
                secondaryStatus = "Slayer Task Done";
                startLocation = null;
                autoCombatRunning = false;
                taskEnded = true;
                lastTarget = null;
            }

            if (config.stopUpkeepOnTaskCompletion() && taskEnded)
            {
                expectedLootLocations.clear();
                lastTickActive = 0;
            }

            if (config.teletabOnCompletion() && taskEnded)
            {
                Optional<SlottedItem> teletab = InventoryUtils.getAll(item -> {
                    ItemComposition composition = client.getItemComposition(item.getItem().getId());
                    return Arrays.asList(composition.getInventoryActions()).contains("Break") && composition.getName().toLowerCase().contains("teleport");
                }).stream().findFirst();

                teletab.ifPresent(tab -> InventoryUtils.itemInteract(tab.getItem().getId(), "Break"));
            }
        }

        if (event.getType() == ChatMessageType.GAMEMESSAGE && (event.getMessage().contains("can't take items that other") || event.getMessage().contains("have enough inventory space")))
        {
            expectedLootLocations.keySet().removeIf(tile -> tile.equals(lastLootedTile));
        }

        if (event.getType() == ChatMessageType.GAMEMESSAGE && event.getMessage().contains("can't reach that"))
        {
            lastTarget = null;
            lastLootedTile = null;
            lastTickActive = client.getTickCount();
            nextReactionTick = client.getTickCount() + 1;
        }
    }

    @Subscribe
    private void onClientTick(ClientTick tick)
    {
        if (client.getLocalPlayer().getInteracting() != null && client.getLocalPlayer().getInteracting().isDead())
        {
            if (client.getLocalPlayer().getInteracting() instanceof NPC && !npcsKilled.contains((NPC)client.getLocalPlayer().getInteracting()))
            {
                npcsKilled.add((NPC)client.getLocalPlayer().getInteracting());
            }
        }
    }

    @Subscribe
    private void onGameTick(GameTick tick)
    {
        expectedLootLocations.entrySet().removeIf(i -> client.getTickCount() > i.getValue() + 500);

        if (client.getGameState() != GameState.LOGGED_IN || BankUtils.isOpen())
        {
            return;
        }

        updatePluginVars();

        if (hpFull() && eatingToMaxHp)
        {
            secondaryStatus = "HP Full Now";
            eatingToMaxHp = false;
        }

        if (prayerFull() && drinkingToMaxPrayer)
        {
            secondaryStatus = "Prayer Full Now";
            drinkingToMaxPrayer = false;
        }

        boolean actionTakenThisTick = restorePrimaries();

        // Stop other upkeep besides HP if we haven't animated in the last minute
        if (getInactiveTicks() > 200)
        {
            secondaryStatus = "Idle for > 2 min";
            return;
        }

//        if (config.enablePrayerFlick() && Prayers.isQuickPrayerEnabled())
//        {
//            Prayers.toggleQuickPrayer(false);
//        }


        if (!actionTakenThisTick)
        {
            actionTakenThisTick = restoreStats();

            if (actionTakenThisTick)
            {
                secondaryStatus = "Restoring Stats";
            }
        }

        if (!actionTakenThisTick)
        {
            actionTakenThisTick = restoreBoosts();

            if (actionTakenThisTick)
            {
                secondaryStatus = "Restoring Boosts";
            }
        }

        if (!actionTakenThisTick)
        {
            actionTakenThisTick = handleSlayerFinisher();

            if (actionTakenThisTick)
            {
                secondaryStatus = "Finishing Slayer Monster";
            }
        }

        if (!actionTakenThisTick)
        {
            actionTakenThisTick = handleAutoSpec();

            if (actionTakenThisTick)
            {
                secondaryStatus = "Auto-Spec";
            }
        }

        if (!actionTakenThisTick)
        {
            actionTakenThisTick = handleLooting();

            if (!actionTakenThisTick && lastTarget != null && forcedLootTick > client.getTickCount())
            {
                actionTakenThisTick = handleReAttack();
            }
        }

        if (!actionTakenThisTick)
        {
            actionTakenThisTick = handleAutoCombat();
        }

    }

    public boolean isMoving()
    {
        return client.getLocalPlayer().getPoseAnimation() != client.getLocalPlayer().getIdlePoseAnimation();
    }

    private boolean handleReAttack()
    {
        if (config.alchStuff() && !getAlchableItems().isEmpty())
        {
            return false;
        }

        if (lastTarget == null || isMoving())
        {
            return false;
        }

        if (client.getLocalPlayer().getInteracting() == lastTarget)
        {
            return false;
        }

        if (lastTarget.getInteracting() != client.getLocalPlayer())
        {
            lastTarget = null;
            return false;
        }

        if (lastTarget instanceof Player && isPlayerEligible((Player)lastTarget))
        {
            PlayerUtils.interactPlayer(lastTarget.getName(), "Attack");
            lastTarget = null;
            secondaryStatus = "Re-attacking previous target";
            return true;
        }
        else if (lastTarget instanceof NPC && isNpcEligible((NPC)lastTarget))
        {
            NpcUtils.interact((NPC)lastTarget, "Attack");
            lastTarget = null;
            secondaryStatus = "Re-attacking previous target";
            return true;
        }

        return false;
    }


    private boolean handleSlayerFinisher()
    {
        Actor target = client.getLocalPlayer().getInteracting();
        if (!(target instanceof NPC))
        {
            return false;
        }

        NPC npcTarget = (NPC) target;
        int ratio = npcTarget.getHealthRatio();
        int scale = npcTarget.getHealthScale();

        double targetHpPercent = Math.floor((double) ratio  / (double) scale * 100);
        if (targetHpPercent < config.slayerFinisherHpPercent() && targetHpPercent >= 0)
        {
            Item slayerFinisher = InventoryUtils.getFirstItem(config.slayerFinisherItem().getItemName());
            if (config.autoSlayerFinisher() && slayerFinisher != null &&
                    client.getTickCount() - lastFinisherAttempt > 5)
            {
                InteractionUtils.useItemOnNPC(slayerFinisher, npcTarget);
                lastFinisherAttempt = client.getTickCount();
                return true;
            }
        }
        return false;
    }

    private static boolean equippedItem = false;

    private void invokeAction(MenuAutomated entry, int x, int y)
    {
        GameThread.invoke(() ->
        {
            MousePackets.queueClickPacket(x, y);
            client.invokeMenuAction(entry.getOption(), entry.getTarget(), entry.getIdentifier(),
                    entry.getOpcode().getId(), entry.getParam0(), entry.getParam1(), x, y);
        });
    }

    private boolean handleAutoSpec()
    {
        if (!config.enableAutoSpec() || config.specWeapon().isEmpty() || config.mainWeapon().isEmpty())
        {
            return false;
        }

        if (config.specIfAutocombat() && !autoCombatRunning)
        {
            return false;
        }

        if (!isSpeccing && canStartSpeccing())
        {
            isSpeccing = true;
        }
        else if (isSpeccing && !canSpec())
        {
            isSpeccing = false;
        }

        var mainHand = Inventory.getFirst(x -> x.getName().contains(config.mainWeapon()));
        var offHand = Inventory.getFirst(x -> x.getName().contains(config.offhandWeapon()));
        if (mainHand != null && !isSpeccing)
        {
            lastTarget = client.getLocalPlayer().getInteracting();
            InventoryUtils.itemInteract(mainHand.getId(), "Wield");

            if (offHand != null)
            {
                if (InventoryUtils.itemHasAction(client, offHand.getId(), "Wield"))
                {
                    InventoryUtils.itemInteract(offHand.getId(), "Wield");
                }
                else if (InventoryUtils.itemHasAction(client, offHand.getId(), "Wear"))
                {
                    InventoryUtils.itemInteract(offHand.getId(), "Wear");
                }
            }

            if (!EquipmentUtils.contains(config.specWeapon()))
                equippedItem = false;
            return true;
        }

        if (!isSpeccing)
        {
            return false;
        }


        if (!EquipmentUtils.contains(config.specWeapon()))
        {
            if (!config.specIfEquipped())
            {
                Item specWeapon = InventoryUtils.getFirstItem(config.specWeapon());
                if (specWeapon != null && canSpec())
                {
                    lastTarget = client.getLocalPlayer().getInteracting();

                    if (lastTarget != null)
                    {
                        InventoryUtils.itemInteract(specWeapon.getId(), "Wield");
                        equippedItem = true;
                        return true;
                    }
                }
            }
        }
        else
        {
            if (client.getLocalPlayer().getInteracting() != null || lastTarget != null)
            {
                equippedItem = true;
            }
        }

        if (equippedItem && isSpeccing && !CombatUtils.isSpecEnabled() && Equipment.contains(x -> x.getName().contains(config.specWeapon())))
        {
            CombatUtils.toggleSpec();
            return true;
        }

        return false;
    }

    private boolean canStartSpeccing()
    {
        final int spec = CombatUtils.getSpecEnergy(client);
        return spec >= config.minSpec() && spec >= config.specNeeded();
    }

    private boolean canSpec()
    {
        final int spec = CombatUtils.getSpecEnergy(client);
        return spec >= config.specNeeded();
    }

    private boolean handleLooting()
    {
        if (!autoCombatRunning)
        {
            return false;
        }

        if (forcedLootTick == 0)
        {
            forcedLootTick = client.getTickCount();
        }

        if (forcedLootTick > client.getTickCount() + config.maxTicksBetweenLooting())
        {
            forcedLootTick = client.getTickCount() + config.maxTicksBetweenLooting();
        }

        if (config.onlyLootWithNoTarget())
        {
            if (validInteractingTarget() && forcedLootTick > client.getTickCount())
            {
                return false;
            }
        }


        List<TileItem> lootableItems = getLootableItems();

        if (config.stackableOnly())
        {
            lootableItems.removeIf(loot -> {

                if (config.buryScatter())
                {
                    return (!isStackable(loot.getId()) && !canBuryOrScatter(loot.getId())) || (loot.getId() == ItemID.CURVED_BONE || loot.getId() == ItemID.LONG_BONE);
                }

                return !isStackable(loot.getId());
            });
        }

        if (InventoryUtils.getFreeSlots() == 0 && !config.eatToLoot())
        {
            lootableItems.removeIf(loot -> !isStackable(loot.getId()) || (isStackable(loot.getId()) && InventoryUtils.count(loot.getId()) == 0));
        }

        TileItem nearest = nearestTileItem(lootableItems);

        if (config.enableLooting() && nearest != null)
        {
            if (config.eatToLoot() && InventoryUtils.getFreeSlots() == 0 && (!isStackable(nearest.getId()) || (isStackable(nearest.getId()) && InventoryUtils.count(nearest.getId()) == 0)))
            {
                Item itemToEat = Inventory.getFirst(x -> x.hasAction("Eat"));
                if (itemToEat == null)
                {
                    log.info("Lucid combat no item to eat for making more space so not");
                    return false;
                }
                itemToEat.interact("Eat");
                return true;
            }
            if (client.getLocalPlayer().getInteracting() != null)
            {
                lastTarget = client.getLocalPlayer().getInteracting();
            }

            InteractionUtils.interactWithTileItem(nearest.getId(), "Take");
            lastLootedTile = nearest.getLocalLocation();

            /*if (!client.getLocalPlayer().getLocalLocation().equals(nearest.getLocalLocation()))
            {
                if (config.onlyLootWithNoTarget())
                {
                    if (ignoringTargetLimitation && lootableItems.size() <= 1)
                    {
                        nextLootAttempt = client.getTickCount() + config.maxTicksBetweenLooting();
                    }
                }
                else
                {
                    nextLootAttempt = client.getTickCount() + 2;
                }
            }
            else
            {
                if (config.onlyLootWithNoTarget())
                {
                    if (ignoringTargetLimitation && lootableItems.size() <= 1)
                    {
                        nextLootAttempt = client.getTickCount() + 2;
                    }
                }
            }*/

            if (lootableItems.size() <= 1)
            {
                forcedLootTick = client.getTickCount() + config.maxTicksBetweenLooting();
            }

            secondaryStatus = "Looting!";
            return true;
        }


        if (config.buryScatter())
        {
            List<SlottedItem> itemsToBury = InventoryUtils.getAll(item -> {
                ItemComposition composition = client.getItemDefinition(item.getItem().getId());
                return Arrays.asList(composition.getInventoryActions()).contains("Bury") &&
                        !(composition.getName().contains("Long") || composition.getName().contains("Curved"));
            });

            List<SlottedItem> itemsToScatter = InventoryUtils.getAll(item -> {
                ItemComposition composition = client.getItemDefinition(item.getItem().getId());
                return Arrays.asList(composition.getInventoryActions()).contains("Scatter");
            });

            if (!itemsToBury.isEmpty())
            {
                SlottedItem itemToBury = itemsToBury.get(0);

                if (itemToBury != null)
                {
                    InventoryUtils.itemInteract(itemToBury.getItem().getId(), "Bury");
                    nextReactionTick = client.getTickCount() + randomIntInclusive(1, 3);
                    return true;
                }
            }

            if (!itemsToScatter.isEmpty())
            {
                SlottedItem itemToScatter = itemsToScatter.get(0);

                if (itemToScatter != null)
                {
                    InventoryUtils.itemInteract(itemToScatter.getItem().getId(), "Scatter");
                    nextReactionTick = client.getTickCount() + randomIntInclusive(1, 3);
                    return true;
                }
            }
        }

        return false;
    }

    private List<TileItem> getLootableItems()
    {
        return InteractionUtils.getAllTileItems(tileItem -> {
            ItemComposition composition = client.getItemComposition(tileItem.getId());

            if (composition.getName() == null)
            {
                return false;
            }

            boolean inWhitelist = nameInLootWhiteList(composition.getName()) || (config.lootMinimumEnabled() && meetsMinimumLootValue(tileItem));
            boolean inBlacklist = nameInLootBlackList(composition.getName());

            boolean antiLureActivated = false;

            if (config.antilureProtection())
            {
                antiLureActivated = InteractionUtils.distanceTo2DHypotenuse(tileItem.getWorldLocation(), startLocation) > (config.maxRange() + config.antilureProtectionRange());
            }

            boolean inAnExpectedLocation = (config.lootGoblin() || expectedLootLocations.containsKey(tileItem.getLocalLocation()));

            return (!inBlacklist && inWhitelist) && inAnExpectedLocation &&
                    InteractionUtils.distanceTo2DHypotenuse(tileItem.getWorldLocation(), client.getLocalPlayer().getWorldLocation()) <= config.lootRange() &&
                    !antiLureActivated;
        });
    }

    private boolean nameInLootWhiteList(String name)
    {
        if (config.lootNames().trim().isEmpty())
        {
            return true;
        }

        for (String itemName : config.lootNames().split(","))
        {
            itemName = itemName.trim();

            if (name.length() > 0 && name.contains(itemName))
            {
                return true;
            }
        }

        return false;
    }

    private boolean nameInLootBlackList(String name)
    {
        if (config.lootBlacklist().trim().isEmpty())
        {
            return false;
        }

        for (String itemName : config.lootBlacklist().split(","))
        {
            itemName = itemName.trim();

            if (name.length() > 0 && name.contains(itemName))
            {
                return true;
            }
        }

        return false;
    }

    private boolean meetsMinimumLootValue(TileItem item)
    {
        return itemManager.getItemPrice(item.getId()) >= config.lootMinimum();
    }

    private TileItem nearestTileItem(List<TileItem> items)
    {
        TileItem nearest = null;
        float nearestDist = 999;

        for (TileItem tileItem : items)
        {
            final float dist = InteractionUtils.distanceTo2DHypotenuse(tileItem.getWorldLocation(), client.getLocalPlayer().getWorldLocation());
            if (dist < nearestDist)
            {
                nearest = tileItem;
                nearestDist = dist;
            }
        }

        return nearest;
    }

    private boolean handleAutoCombat()
    {
        if (!autoCombatRunning)
        {
            return false;
        }

        if (!config.enablePrayerFlick() && config.enableQuickPrayer() && !Prayers.isQuickPrayerEnabled())
        {
            Prayers.toggleQuickPrayer(true);
            return false;
        }

        if (config.useSafespot() && !startLocation.equals(client.getLocalPlayer().getWorldLocation()) && !isMoving())
        {
            InteractionUtils.walk(startLocation);
            if(!config.enablePrayerFlick())
                nextReactionTick = client.getTickCount() + getReaction();
            return false;
        }

        if (!canReact() || isMoving())
        {
            return false;
        }

        if (config.alchStuff())
        {
            if (handleAlching())
            {
                needToOpenInventory = true;
                return false;
            }
        }

        secondaryStatus = "Combat";

        if (targetDeadOrNoTarget())
        {
            NPC target = getEligibleTarget();
            if (target != null)
            {
                NpcUtils.interact(target, "Attack");
                if(!config.enablePrayerFlick())
                    nextReactionTick = client.getTickCount() + getReaction();
                secondaryStatus = "Attacking " + target.getName();

                if (getInactiveTicks() > 2)
                {
                    lastTickActive = client.getTickCount();
                }

                return true;
            }
            else
            {
                secondaryStatus = "Nothing to murder";
                if(!config.enablePrayerFlick())
                    nextReactionTick = client.getTickCount() + getReaction();
                return false;
            }
        }
        else
        {
            if (getEligibleNpcInteractingWithUs() != null && client.getLocalPlayer().getInteracting() == null)
            {
                if (isNpcEligible(getEligibleNpcInteractingWithUs()))
                {
                    NpcUtils.interact(getEligibleNpcInteractingWithUs(), "Attack");
                    if(!config.enablePrayerFlick())
                        nextReactionTick = client.getTickCount() + getReaction();
                    secondaryStatus = "Re-attacking " + getEligibleNpcInteractingWithUs().getName();
                }

                if (getInactiveTicks() > 2)
                {
                    lastTickActive = client.getTickCount();
                }
                return true;
            }
        }

        secondaryStatus = "Idle";
        if(!config.enablePrayerFlick())
                nextReactionTick = client.getTickCount() + getReaction();
        if(config.enablePrayerFlick() && !isSpeccing)
        {
            if(!Prayers.isQuickPrayerEnabled() && Prayers.getPoints() > 0 && ((client.getTickCount() - lastFlickTick) > 1))
            {
                Prayers.toggleQuickPrayer(true);

                try {
                    Thread.sleep((long)((Math.random() * 50) + 25));
                } catch (InterruptedException e) {
                    log.info("Sleep failed in lucid combat: {}", e.toString());
                }

                Prayers.toggleQuickPrayer(false);

                try {
                    Thread.sleep((long)((Math.random() * 50) + 25));
                } catch (InterruptedException e) {
                    log.info("Sleep failed in lucid combat: {}", e.toString());
                }

                Prayers.toggleQuickPrayer(true);

                lastFlickTick = client.getTickCount();
                return true;
            }
            else if (!Prayers.isQuickPrayerEnabled() && Prayers.getPoints() > 0 && ((client.getTickCount() - lastFlickTick) == 1))
            {
                Prayers.toggleQuickPrayer(true);
                lastFlickTick = client.getTickCount();
                return true;
            }
            else
            {
                if (Prayers.isQuickPrayerEnabled())
                    Prayers.toggleQuickPrayer(false);
                return true;
            }



            /*try {
                Thread.sleep((long)((Math.random() * 100) + 50));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if(Prayers.isQuickPrayerEnabled() && Prayers.getPoints() > 0) {
                Prayers.toggleQuickPrayer(false);
            }*/
        }

        if(needToOpenInventory)
        {
            needToOpenInventory = false;
            Widget inventoryWidget = Widgets.get(WidgetInfo.RESIZABLE_VIEWPORT_INVENTORY_TAB);
            if (inventoryWidget != null)
            {
                log.info("Attempting to re-open inventory.");
                inventoryWidget.interact(0);
                return true;
            }
            else
            {
                log.info("Inventory widget is null, skipping re-opening inventory");
            }
        }

        return false;
    }

    private boolean handleAlching()
    {
        if (lastAlchTick == 0)
        {
            lastAlchTick = client.getTickCount() + 5;
        }

        if (client.getTickCount() - lastAlchTick < 5)
        {
            return false;
        }

        List<SlottedItem> alchableItems = getAlchableItems();

        if (alchableItems.isEmpty())
        {
            return false;
        }

        SlottedItem itemToAlch = alchableItems.get(0);
        if (isHighAlching())
        {
            if (hasAlchRunes(true))
            {
                if (client.getLocalPlayer().getInteracting() != null)
                {
                    lastTarget = client.getLocalPlayer().getInteracting();
                }

                InventoryUtils.castAlchemyOnItem(itemToAlch.getItem().getId(), true);
                lastAlchTick = client.getTickCount();
                secondaryStatus = "Alching";
                return true;
            }
        }
        else
        {
            if (hasAlchRunes(false))
            {
                if (client.getLocalPlayer().getInteracting() != null)
                {
                    lastTarget = client.getLocalPlayer().getInteracting();
                }

                InventoryUtils.castAlchemyOnItem(itemToAlch.getItem().getId(), false);
                lastAlchTick = client.getTickCount();
                secondaryStatus = "Alching";
                return true;
            }
        }

        return false;
    }

    private int totalCount(int itemId, int runeIndex)
    {
        int count = InventoryUtils.count(itemId);

        if (!hasRunePouchInInventory())
        {
            return count;
        }

        if (idInRunePouch1() == runeIndex)
        {
            count += amountInRunePouch1();
        }

        if (idInRunePouch2() == runeIndex)
        {
            count += amountInRunePouch2();
        }

        if (idInRunePouch3() == runeIndex)
        {
            count += amountInRunePouch3();
        }

        if (idInRunePouch4() == runeIndex)
        {
            count += amountInRunePouch4();
        }

        return count;
    }

    private boolean isHighAlching()
    {
        return client.getBoostedSkillLevel(Skill.MAGIC) >= 55;
    }

    private int idInRunePouch1()
    {
        return client.getVarbitValue(Varbits.RUNE_POUCH_RUNE1);
    }

    private int amountInRunePouch1()
    {
        return client.getVarbitValue(Varbits.RUNE_POUCH_AMOUNT1);
    }

    private int idInRunePouch2()
    {
        return client.getVarbitValue(Varbits.RUNE_POUCH_RUNE2);
    }

    private int amountInRunePouch2()
    {
        return client.getVarbitValue(Varbits.RUNE_POUCH_AMOUNT2);
    }

    private int idInRunePouch3()
    {
        return client.getVarbitValue(Varbits.RUNE_POUCH_RUNE3);
    }

    private int amountInRunePouch3()
    {
        return client.getVarbitValue(Varbits.RUNE_POUCH_AMOUNT3);
    }

    private int idInRunePouch4()
    {
        return client.getVarbitValue(Varbits.RUNE_POUCH_RUNE4);
    }

    private int amountInRunePouch4()
    {
        return client.getVarbitValue(Varbits.RUNE_POUCH_AMOUNT4);
    }

    private boolean hasAlchRunes(boolean highAlch)
    {
        int natCount = totalCount(ItemID.NATURE_RUNE, 10);

        int fireRunes = totalCount(ItemID.FIRE_RUNE, 4);
        int lavaRunes = totalCount(ItemID.LAVA_RUNE, 18);
        int steamRunes = totalCount(ItemID.STEAM_RUNE, 19);
        int smokeRunes = totalCount(ItemID.SMOKE_RUNE, 20);
        int total = (fireRunes + lavaRunes + smokeRunes + steamRunes);

        boolean hasFireRunes = total >= (highAlch ? 5 : 3);
        boolean hasNatures = natCount >= 1;
        boolean hasTome = EquipmentUtils.contains(ItemID.TOME_OF_FIRE);

        return hasNatures && (hasFireRunes || hasTome);
    }

    private boolean hasRunePouchInInventory()
    {
        return InventoryUtils.contains("Rune pouch") || InventoryUtils.contains("Divine rune pouch");
    }


    private List<SlottedItem> getAlchableItems()
    {
        if (config.alchNames().trim().isEmpty())
        {
            return List.of();
        }

        return InventoryUtils.getAll(item ->
        {
            ItemComposition composition = client.getItemComposition(item.getItem().getId());
            boolean nameContains = false;
            for (String itemName : config.alchNames().split(","))
            {
                itemName = itemName.trim();

                if (composition.getName() != null && composition.getName().contains(itemName))
                {
                    nameContains = true;
                    break;
                }
            }

            boolean inBlacklist = false;
            if (!config.lootBlacklist().trim().isEmpty())
            {
                for (String itemName : config.alchBlacklist().split(","))
                {
                    itemName = itemName.trim();

                    if (itemName.length() < 2)
                    {
                        continue;
                    }

                    if (composition.getName() != null && composition.getName().contains(itemName))
                    {
                        inBlacklist = true;
                        break;
                    }
                }
            }

            return nameContains && !inBlacklist;
        });
    }

    public int getReaction()
    {
        int min = config.autocombatStyle().getLowestDelay();
        int max = config.autocombatStyle().getHighestDelay();

        int delay = randomIntInclusive(min, max);

        if (config.autocombatStyle() == PlayStyle.ROBOTIC)
        {
            delay = 0;
        }

        int randomMinDelay = Math.max(0, randomStyle().getLowestDelay());
        int randomMaxDelay = Math.max(randomMinDelay, randomStyle().getHighestDelay());

        int randomDeterminer = randomIntInclusive(0, 49);

        if (config.reactionAntiPattern())
        {
            boolean fiftyFifty = randomIntInclusive(0, 1) == 0;
            int firstNumber = (fiftyFifty ? 5 : 18);
            int secondNumber = (fiftyFifty ? 24 : 48);
            if (randomDeterminer == firstNumber || randomDeterminer == secondNumber)
            {
                delay = randomIntInclusive(randomMinDelay, randomMaxDelay);
                random = new Random();
            }
        }

        return delay;
    }

    public PlayStyle randomStyle()
    {
        return PlayStyle.values()[randomIntInclusive(0, PlayStyle.values().length - 1)];
    }

    public int randomIntInclusive(int min, int max)
    {
        return random.nextInt((max - min) + 1) + min;
    }


    private boolean canReact()
    {
        return ticksUntilNextInteraction() <= 0;
    }

    public int ticksUntilNextInteraction()
    {
        return nextReactionTick - client.getTickCount();
    }


    private NPC getEligibleTarget()
    {
        if (config.npcToFight().isEmpty())
        {
            return null;
        }

        return NpcUtils.getNearest(npc ->
            (npc.getName() != null && isNameInNpcsToFight(npc.getName())) &&
            (((npc.getInteracting() == client.getLocalPlayer())) ||
            (npc.getInteracting() == null && noPlayerFightingNpc(npc)) ||
            (npc.getInteracting() instanceof NPC && noPlayerFightingNpc(npc))) &&

            Arrays.asList(npc.getComposition().getActions()).contains("Attack") &&
            InteractionUtils.isWalkable(npc.getWorldLocation()) &&
            InteractionUtils.distanceTo2DHypotenuse(npc.getWorldLocation(), startLocation) <= config.maxRange() &&
            !npc.isDead()
        );
    }

    private boolean isNpcEligible(NPC npc)
    {
        if (npc == null)
        {
            return false;
        }

        if (npc.getComposition().getActions() == null)
        {
            return false;
        }

        return (npc.getName() != null && isNameInNpcsToFight(npc.getName())) &&

                (((npc.getInteracting() == client.getLocalPlayer() && !npc.isDead())) ||
                (npc.getInteracting() == null && noPlayerFightingNpc(npc)) ||
                (npc.getInteracting() instanceof NPC && noPlayerFightingNpc(npc))) &&

                Arrays.asList(npc.getComposition().getActions()).contains("Attack") &&
                InteractionUtils.isWalkable(npc.getWorldLocation()) &&
                InteractionUtils.distanceTo2DHypotenuse(npc.getWorldLocation(), startLocation) <= config.maxRange();
    }

    private boolean isPlayerEligible(Player player)
    {
        return Arrays.asList(player.getActions()).contains("Attack");
    }

    private boolean noPlayerFightingNpc(NPC npc)
    {
        return PlayerUtils.getNearest(player -> player != client.getLocalPlayer() && player.getInteracting() == npc || npc.getInteracting() == player) == null;
    }

    private boolean targetDeadOrNoTarget()
    {
        NPC interactingWithUs = getEligibleNpcInteractingWithUs();

        if (client.getLocalPlayer().getInteracting() == null && interactingWithUs == null)
        {
            return true;
        }

        if (interactingWithUs != null)
        {
            return false;
        }

        if (client.getLocalPlayer().getInteracting() instanceof NPC)
        {
            NPC npcTarget = (NPC) client.getLocalPlayer().getInteracting();
            int ratio = npcTarget.getHealthRatio();

            return ratio == 0;
        }

        return false;
    }

    private boolean validInteractingTarget()
    {
        if (client.getLocalPlayer().getInteracting() == null)
        {
            return false;
        }

        if (client.getLocalPlayer().getInteracting() instanceof NPC)
        {
            NPC npcTarget = (NPC) client.getLocalPlayer().getInteracting();
            int ratio = npcTarget.getHealthRatio();

            return ratio != 0;
        }

        return true;
    }

    private NPC getEligibleNpcInteractingWithUs()
    {
        return NpcUtils.getNearest((npc) ->
            (npc.getName() != null && isNameInNpcsToFight(npc.getName())) &&
            (npc.getInteracting() == client.getLocalPlayer() && npc.getHealthRatio() != 0) &&
            Arrays.asList(npc.getComposition().getActions()).contains("Attack") &&
            InteractionUtils.isWalkable(npc.getWorldLocation()) &&
            InteractionUtils.distanceTo2DHypotenuse(npc.getWorldLocation(), startLocation) <= config.maxRange()
        );
    }

    @Subscribe
    private void onNpcLootReceived(NpcLootReceived event)
    {
        boolean match = false;
        for (NPC killed : npcsKilled)
        {
            if (event.getNpc() == killed)
            {
                match = true;
                break;
            }
        }

        if (!match)
        {
            return;
        }

        npcsKilled.remove(event.getNpc());

        if (event.getItems().size() > 0)
        {
            List<ItemStack> itemStacks = new ArrayList<>(event.getItems());
            for (ItemStack itemStack : itemStacks)
            {
                if (expectedLootLocations.getOrDefault(itemStack.getLocation(), null) == null)
                {
                    expectedLootLocations.put(itemStack.getLocation(), client.getTickCount());
                }
            }
        }
    }

    private void updatePluginVars()
    {
        if (client.getLocalPlayer().getAnimation() != -1)
        {
            if ((config.stopUpkeepOnTaskCompletion() && !taskEnded) || !config.stopUpkeepOnTaskCompletion())
            {
                lastTickActive = client.getTickCount();
            }
        }

        if (nextHpToRestoreAt <= 0)
        {
            nextHpToRestoreAt = Math.max(1, config.minHp() + (config.minHpBuffer() > 0 ? random.nextInt(config.minHpBuffer() + 1) : 0));
        }

        if (nextPrayerLevelToRestoreAt <= 0)
        {
            nextPrayerLevelToRestoreAt = Math.max(1, config.prayerPointsMin() + (config.prayerRestoreBuffer() > 0 ? random.nextInt(config.prayerRestoreBuffer() + 1) : 0));
        }

        if (config.enabledByConfig())
        {
            configManager.setConfiguration("lucid-combat", "enabledByConfig", false);
            lastTickActive = client.getTickCount();
            lastAlchTick = client.getTickCount();
            expectedLootLocations.clear();
            npcsKilled.clear();
            startLocation = client.getLocalPlayer().getWorldLocation();
            autoCombatRunning = true;
        }

        if (config.disabledByConfig())
        {
            configManager.setConfiguration("lucid-combat", "disabledByConfig", false);
            autoCombatRunning = false;
        }
    }

    private boolean restorePrimaries()
    {
        boolean ateFood = false;
        boolean restoredPrayer = false;
        boolean brewed = false;
        boolean karambwanned = false;

        if (config.enableHpRestore() && needToRestoreHp())
        {
            final List<SlottedItem> foodItems = getFoodItemsNotInBlacklist();
            if (!foodItems.isEmpty() && canRestoreHp())
            {
                if (!eatingToMaxHp && config.restoreHpToMax())
                {
                    eatingToMaxHp = true;
                }

                final SlottedItem firstItem = foodItems.get(0);
                InventoryUtils.itemInteract(firstItem.getItem().getId(), "Eat");

                ateFood = true;
            }

            if ((!ateFood || config.enableTripleEat()) && canPotUp())
            {
                if (!eatingToMaxHp && config.restoreHpToMax())
                {
                    eatingToMaxHp = true;
                }

                Item tickHealPotion = getLowestDosePotion("Nectar ");
                if (tickHealPotion == null)
                    tickHealPotion = getLowestDosePotion("Saradomin brew");
                if (tickHealPotion != null)
                {
                    InventoryUtils.itemInteract(tickHealPotion.getId(), "Drink");
                    brewed = true;
                }
            }
        }

        if (config.enablePrayerRestore() && !brewed && needToRestorePrayer() && canPotUp())
        {
            if (!drinkingToMaxPrayer && config.restorePrayerToMax())
            {
                drinkingToMaxPrayer = true;
            }

            final Item prayerRestore = getLowestDosePrayerRestore();
            if (prayerRestore != null)
            {
                InventoryUtils.itemInteract(prayerRestore.getId(), "Drink");
                restoredPrayer = true;
            }
        }

        if (!restoredPrayer && needToRestoreHp() && canKarambwan())
        {
            boolean shouldEat = false;
            if ((config.enableDoubleEat() || config.enableTripleEat()) && ateFood)
            {
                shouldEat = true;
            }

            if (config.enableHpRestore() && !ateFood && getFoodItemsNotInBlacklist().isEmpty())
            {
                shouldEat = true;
            }

            final SlottedItem karambwan = InventoryUtils.getAll(karambwanFilter).stream().findFirst().orElse(null);

            if (karambwan != null && shouldEat)
            {
                if (!ateFood && !eatingToMaxHp && config.restoreHpToMax())
                {
                    eatingToMaxHp = true;
                }

                InventoryUtils.itemInteract(karambwan.getItem().getId(), "Eat");
                karambwanned = true;
            }
        }

        if (config.enableHpRestore() && needToRestoreHp() && !ateFood && !brewed && !karambwanned)
        {
            int eatCount = Inventory.getCount(x -> x.hasAction("Eat"));

            if (config.stopIfNoFood() && autoCombatRunning && eatCount == 0)
            {
                secondaryStatus = "Ran out of food, stopped combat";
                autoCombatRunning = false;
            }
            if (config.teleTabIfNoFood() && autoCombatRunning && eatCount == 0)
            {
                Item teleTab = Inventory.getFirst(x -> x.hasAction("Break"));
                if (teleTab == null)
                {
                    teleTabCountAfterTeleport = -1;
                    log.info("No tele tab found!");
                    secondaryStatus = "Ran out of food, no tele tab";
                    autoCombatRunning = false;
                }
                else
                {
                    int newCount = Inventory.getCount(true, teleTab.getId());
                    if (teleTabCountAfterTeleport < 0)
                    {
                        teleTabCountAfterTeleport = newCount - 1;
                        secondaryStatus = "Ran out of food, tried to break tele tab";
                        teleTab.interact("Break");
                        log.info("Tried to tele tab!");
                    }
                    else if (teleTabCountAfterTeleport >= newCount)
                    {
                        secondaryStatus = "Ran out of food, successfully broke tele tab";
                        teleTabCountAfterTeleport = -1;
                        autoCombatRunning = false;
                        log.info("Reset auto tele tab");
                    }
                    else
                    {
                        secondaryStatus = "Ran out of food, tried to break tele tab";
                        teleTab.interact("Break");
                        log.info("Tried to tele tab!");
                    }
                }
            }
        }

        if (config.equipBracelets())
        {
            var inventoryBracelet = Inventory.getFirst(x -> x.getId() == ItemID.BRACELET_OF_SLAUGHTER || x.getId() == ItemID.EXPEDITIOUS_BRACELET || x.getName().toLowerCase().contains("slaughter") || x.getName().toLowerCase().contains("expeditious"));
            if (inventoryBracelet != null)
            {
                var equippedBracelet = Equipment.getFirst(x -> x.getId() == ItemID.BRACELET_OF_SLAUGHTER || x.getId() == ItemID.EXPEDITIOUS_BRACELET || x.getName().toLowerCase().contains("slaughter") || x.getName().toLowerCase().contains("expeditious"));

                if (equippedBracelet == null)
                {
                    if (inventoryBracelet.hasAction("Wear"))
                        inventoryBracelet.interact("Wear");
                    else
                        log.info("No Wear action");
                }
                else
                {
                    log.info("Equipped bracelet");
                }
            }
            else
            {
                //log.info("No inventory bracelet");
            }

        }


        if (ateFood)
        {
            nextSolidFoodTick = client.getTickCount() + 3;
            nextHpToRestoreAt = config.minHp() + (config.minHpBuffer() > 0 ? random.nextInt(config.minHpBuffer() + 1) : 0);
        }

        if (restoredPrayer)
        {
            nextPotionTick = client.getTickCount() + 3;
            nextPrayerLevelToRestoreAt = config.prayerPointsMin() + (config.prayerRestoreBuffer() > 0 ? random.nextInt(config.prayerRestoreBuffer() + 1) : 0);
        }

        if (brewed)
        {
            nextPotionTick = client.getTickCount() + 3;
            timesBrewedDown++;
            nextHpToRestoreAt = config.minHp() + (config.minHpBuffer() > 0 ? random.nextInt(config.minHpBuffer() + 1) : 0);
        }

        if (karambwanned)
        {
            nextKarambwanTick = client.getTickCount() + 2;
        }

        return ateFood || restoredPrayer || brewed || karambwanned;
    }

    private boolean needToRestoreHp()
    {
        final int currentHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        return currentHp < nextHpToRestoreAt || eatingToMaxHp;
    }

    private boolean hpFull()
    {
        final int maxHp = client.getRealSkillLevel(Skill.HITPOINTS);
        final int currentHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        return currentHp >= (maxHp - config.maxHpBuffer());
    }


    private boolean needToRestorePrayer()
    {
        final int currentPrayer = client.getBoostedSkillLevel(Skill.PRAYER);
        return currentPrayer < nextPrayerLevelToRestoreAt || drinkingToMaxPrayer;
    }

    private boolean prayerFull()
    {
        final int maxPrayer = client.getRealSkillLevel(Skill.PRAYER);
        final int currentPrayer = client.getBoostedSkillLevel(Skill.PRAYER);
        return currentPrayer >= (maxPrayer - config.maxPrayerBuffer());
    }

    private boolean restoreStats()
    {
        if (timesBrewedDown > 2 && canPotUp())
        {
            Item restore = getLowestDoseRestore();
            if (restore != null)
            {
                InventoryUtils.itemInteract(restore.getId(), "Drink");

                nextPotionTick = client.getTickCount() + 3;

                timesBrewedDown -= 3;

                if (timesBrewedDown < 0)
                {
                    timesBrewedDown = 0;
                }

                return true;
            }
        }

        return false;
    }

    private boolean restoreBoosts()
    {
        boolean meleeBoosted = false;
        boolean rangedBoosted = false;
        boolean magicBoosted = false;

        final int attackBoost = client.getBoostedSkillLevel(Skill.ATTACK) - client.getRealSkillLevel(Skill.ATTACK);
        final int strengthBoost = client.getBoostedSkillLevel(Skill.STRENGTH) - client.getRealSkillLevel(Skill.STRENGTH);
        final int defenseBoost = client.getBoostedSkillLevel(Skill.DEFENCE) - client.getRealSkillLevel(Skill.DEFENCE);

        Item meleePotionToUse = null;

        final Item combatBoostPotion = getCombatBoostingPotion();

        if (attackBoost < config.minMeleeBoost())
        {
            final Item attackBoostingItem = getAttackBoostingItem();

            if (attackBoostingItem != null)
            {
                meleePotionToUse = attackBoostingItem;
            }
            else if (combatBoostPotion != null)
            {
                meleePotionToUse = combatBoostPotion;
            }
        }
        else if (strengthBoost < config.minMeleeBoost())
        {
            final Item strengthBoostingItem = getStrengthBoostingItem();
            if (strengthBoostingItem != null)
            {
                meleePotionToUse = strengthBoostingItem;
            }
            else if (combatBoostPotion != null)
            {
                meleePotionToUse = combatBoostPotion;
            }
        }
        else if (defenseBoost < config.minMeleeBoost())
        {
            final Item defenseBoostingItem = getDefenseBoostingItem();
            if (defenseBoostingItem != null)
            {
                meleePotionToUse = defenseBoostingItem;
            }
            else if (combatBoostPotion != null)
            {
                meleePotionToUse = combatBoostPotion;
            }
        }

        if (config.enableMeleeUpkeep() && meleePotionToUse != null && canPotUp())
        {
            InventoryUtils.itemInteract(meleePotionToUse.getId(), "Drink");
            nextPotionTick = client.getTickCount() + 3;
            meleeBoosted = true;
        }

        final int rangedBoost = client.getBoostedSkillLevel(Skill.RANGED) - client.getRealSkillLevel(Skill.RANGED);
        if (rangedBoost < config.minRangedBoost() && !meleeBoosted)
        {
            Item rangedPotion = getRangedBoostingItem();
            if (config.enableRangedUpkeep() && rangedPotion != null && canPotUp())
            {
                InventoryUtils.itemInteract(rangedPotion.getId(), "Drink");
                nextPotionTick = client.getTickCount() + 3;
                rangedBoosted = true;
            }
        }

        final int magicBoost = client.getBoostedSkillLevel(Skill.MAGIC) - client.getRealSkillLevel(Skill.MAGIC);
        if (magicBoost < config.minMagicBoost() && !meleeBoosted && !rangedBoosted)
        {
            Item magicPotion = getMagicBoostingPotion();
            Item imbuedHeart = InventoryUtils.getFirstItem("Imbued heart");
            Item saturatedHeart = InventoryUtils.getFirstItem("Saturated heart");
            Item heart = imbuedHeart != null ? imbuedHeart : saturatedHeart;

            if (config.enableMagicUpkeep() && magicPotion != null && canPotUp())
            {
                InventoryUtils.itemInteract(magicPotion.getId(), "Drink");
                nextPotionTick = client.getTickCount() + 3;
                magicBoosted = true;
            }
            else if (config.enableMagicUpkeep() && imbuedHeartTicksLeft() == 0 && heart != null)
            {
                InventoryUtils.itemInteract(heart.getId(), "Invigorate");
                magicBoosted = true;
            }
        }

        return meleeBoosted || rangedBoosted || magicBoosted;
    }

    private boolean canRestoreHp()
    {
        return client.getTickCount() > nextSolidFoodTick;
    }

    private boolean canPotUp()
    {
        return client.getTickCount() > nextPotionTick;
    }

    private boolean canKarambwan()
    {
        return client.getTickCount() > nextKarambwanTick;
    }

    private List<SlottedItem> getFoodItemsNotInBlacklist()
    {
        return InventoryUtils.getAll(foodFilterNoBlacklistItems);
    }

    private Item getAttackBoostingItem()
    {
        Item itemToUse = null;

        final Item attackPot = getLowestDosePotion("Attack potion");
        final Item superAttackPot = getLowestDosePotion("Super attack");
        final Item divineSuperAttack = getLowestDosePotion("Divine super attack potion");

        if (attackPot != null)
        {
            itemToUse = attackPot;
        }
        else if (superAttackPot != null)
        {
            itemToUse = superAttackPot;
        }
        else if (divineSuperAttack != null && client.getBoostedSkillLevel(Skill.HITPOINTS) > 10)
        {
            itemToUse = divineSuperAttack;
        }

        return itemToUse;
    }

    private Item getStrengthBoostingItem()
    {
        Item itemToUse = null;

        final Item strengthPot = getLowestDosePotion("Strength potion");
        final Item superStrengthPot = getLowestDosePotion("Super strength");
        final Item divineSuperStrength = getLowestDosePotion("Divine super strength potion");

        if (strengthPot != null)
        {
            itemToUse = strengthPot;
        }
        else if (superStrengthPot != null)
        {
            itemToUse = superStrengthPot;
        }
        else if (divineSuperStrength != null && client.getBoostedSkillLevel(Skill.HITPOINTS) > 10)
        {
            itemToUse = divineSuperStrength;
        }

        return itemToUse;
    }

    private Item getDefenseBoostingItem()
    {
        Item itemToUse = null;

        final Item defensePot = getLowestDosePotion("Defense potion");
        final Item superDefensePot = getLowestDosePotion("Super defense");
        final Item divineSuperDefense = getLowestDosePotion("Divine super defense potion");

        if (defensePot != null)
        {
            itemToUse = defensePot;
        }
        else if (superDefensePot != null)
        {
            itemToUse = superDefensePot;
        }
        else if (divineSuperDefense != null && client.getBoostedSkillLevel(Skill.HITPOINTS) > 10)
        {
            itemToUse = divineSuperDefense;
        }

        return itemToUse;
    }

    private Item getRangedBoostingItem()
    {
        Item itemToUse = null;

        final Item rangingPot = getLowestDosePotion("Ranging potion");
        final Item divineRangingPot = getLowestDosePotion("Divine ranging potion");
        final Item bastionPot = getLowestDosePotion("Bastion potion");
        final Item divineBastionPot = getLowestDosePotion("Divine bastion potion");

        if (rangingPot != null)
        {
            itemToUse = rangingPot;
        }
        else if (divineRangingPot != null && client.getBoostedSkillLevel(Skill.HITPOINTS) > 10)
        {
            itemToUse = divineRangingPot;
        }
        else if (bastionPot != null)
        {
            itemToUse = bastionPot;
        }
        else if (divineBastionPot != null && client.getBoostedSkillLevel(Skill.HITPOINTS) > 10)
        {
            itemToUse = divineBastionPot;
        }

        return itemToUse;
    }

    private Item getMagicBoostingPotion()
    {
        Item itemToUse = null;

        final Item magicEssence = getLowestDosePotion("Magic essence");
        final Item magicPot = getLowestDosePotion("Magic potion");
        final Item divineMagicPot = getLowestDosePotion("Divine magic potion");
        final Item battleMagePot = getLowestDosePotion("Battlemage potion");
        final Item divineBattleMagePot = getLowestDosePotion("Divine battlemage potion");

        if (magicEssence != null)
        {
            itemToUse = magicEssence;
        }
        else if (magicPot != null)
        {
            itemToUse = magicPot;
        }
        else if (divineMagicPot != null && client.getBoostedSkillLevel(Skill.HITPOINTS) > 10)
        {
            itemToUse = divineMagicPot;
        }
        else if (battleMagePot != null)
        {
            itemToUse = battleMagePot;
        }
        else if (divineBattleMagePot != null && client.getBoostedSkillLevel(Skill.HITPOINTS) > 10)
        {
            itemToUse = divineBattleMagePot;
        }

        return itemToUse;
    }

    private int imbuedHeartTicksLeft()
    {
        return client.getVarbitValue(Varbits.IMBUED_HEART_COOLDOWN) * 10;
    }

    private Item getCombatBoostingPotion()
    {
        Item itemToUse = null;

        final Item combatPot = getLowestDosePotion("Combat potion");
        final Item superCombatPot = getLowestDosePotion("Super combat potion");
        final Item divineCombatPot = getLowestDosePotion("Divine super combat potion");
        final Item overloadPot = getLowestDosePotion("Overload ");

        if (combatPot != null)
        {
            itemToUse = combatPot;
        }
        else if (superCombatPot != null)
        {
            itemToUse = superCombatPot;
        }
        else if (divineCombatPot != null && client.getBoostedSkillLevel(Skill.HITPOINTS) > 10)
        {
            itemToUse = divineCombatPot;
        }
        else if (overloadPot != null && client.getBoostedSkillLevel(Skill.HITPOINTS) > 50)
        {
            itemToUse = overloadPot;
        }

        return itemToUse;
    }

    private Item getLowestDosePotion(String name)
    {
        for (int i = 1; i < 5; i++)
        {
            final String fullName = name + "(" + i + ")";

            if (config.foodBlacklist().contains(fullName))
            {
                continue;
            }

            final Item b = InventoryUtils.getFirstItem(fullName);
            if (b != null)
            {
                final ItemComposition itemComposition = client.getItemDefinition(b.getId());
                if ((Arrays.asList(itemComposition.getInventoryActions()).contains("Drink")))
                {
                    return b;
                }
            }
        }
        return null;
    }

    private Item getLowestDoseRestore()
    {
        for (int i = 1; i < 5; i++)
        {

            String fullName = "Tears of elidinis (" + i + ")";

            if (config.foodBlacklist().contains(fullName))
            {
                fullName = "Super restore(" + i + ")";

                if (config.foodBlacklist().contains(fullName))
                {
                    continue;
                }
            }



            final Item b = InventoryUtils.getFirstItem(fullName);
            if (b != null)
            {
                final ItemComposition itemComposition = client.getItemDefinition(b.getId());
                if ((Arrays.asList(itemComposition.getInventoryActions()).contains("Drink")))
                {
                    return b;
                }
            }
        }
        return null;
    }

    private Item getLowestDosePrayerRestore()
    {
        for (int i = 1; i < 5; i++)
        {
            for (String restoreItem : prayerRestoreNames)
            {
                String fullName = restoreItem + "(" + i + ")";

                if (config.foodBlacklist().contains(fullName))
                {
                    continue;
                }

                Item r = InventoryUtils.getFirstItem(fullName);
                if (r != null)
                {
                    ItemComposition itemComposition = client.getItemDefinition(r.getId());
                    if ((Arrays.asList(itemComposition.getInventoryActions()).contains("Drink")))
                    {
                        return r;
                    }
                }
            }
        }
        return null;
    }

    public int getInactiveTicks()
    {
        return client.getTickCount() - lastTickActive;
    }

    public float getDistanceToStart()
    {
        if (startLocation == null)
        {
            return 0;
        }

        return InteractionUtils.distanceTo2DHypotenuse(startLocation, client.getLocalPlayer().getWorldLocation());
    }

    @Override
    public void keyTyped(KeyEvent e)
    {

    }

    @Override
    public void keyPressed(KeyEvent e)
    {
        if (config.autocombatHotkey().matches(e))
        {
            clientThread.invoke(() -> {
                lastTickActive = client.getTickCount();
                autoCombatRunning = !autoCombatRunning;
                expectedLootLocations.clear();
                npcsKilled.clear();

                if (autoCombatRunning)
                {
                    taskEnded = false;
                    startLocation = client.getLocalPlayer().getWorldLocation();
                }
                else
                {
                    startLocation = null;
                }
            });
        }
    }

    private boolean isStackable(int id)
    {
        ItemComposition composition = client.getItemComposition(id);
        return composition.isStackable();
    }

    private boolean canBuryOrScatter(int id)
    {
        ItemComposition composition = client.getItemComposition(id);
        return Arrays.asList(composition.getInventoryActions()).contains("Bury") || Arrays.asList(composition.getInventoryActions()).contains("Scatter");
    }

    @Override
    public void keyReleased(KeyEvent e)
    {

    }
}
