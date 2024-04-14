package com.lucidplugins.lucidcustomprayers.api.util;

import net.runelite.api.Client;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.unethicalite.api.events.MenuAutomated;
import net.unethicalite.api.game.GameThread;
import net.unethicalite.api.packets.MousePackets;
import net.unethicalite.api.widgets.Prayers;
import net.unethicalite.api.widgets.Widgets;

public class CombatUtils
{
    public static void togglePrayer(Client client, Prayer prayer)
    {
        if (client == null || (client.getBoostedSkillLevel(Skill.PRAYER) == 0 && !client.isPrayerActive(prayer)))
        {
            return;
        }

        Prayers.toggle(prayer);
    }

    public static void activatePrayer(Client client, Prayer prayer)
    {
        if (client == null || client.getBoostedSkillLevel(Skill.PRAYER) == 0 || client.isPrayerActive(prayer))
        {
            return;
        }

        Prayers.toggle(prayer);
    }

    private static void invokeAction(Client client, MenuAutomated entry, int x, int y)
    {
        GameThread.invoke(() ->
        {
            MousePackets.queueClickPacket(x, y);
            client.invokeMenuAction(entry.getOption(), entry.getTarget(), entry.getIdentifier(),
                    entry.getOpcode().getId(), entry.getParam0(), entry.getParam1(), x, y);
        });
    }

    public static void toggleQuickPrayerByCheckbox(Client client, Prayer prayer)
    {
        Widget container = client.getWidget(ComponentID.QUICK_PRAYER_PRAYERS);
        if (container == null)
        {
            MessageUtils.addMessage(client, "Lucid Prayer: Couldn't get widget container, attempting to open one time");
            Widget quickPrayerOrb = client.getWidget(WidgetInfo.MINIMAP_QUICK_PRAYER_ORB);
            if (quickPrayerOrb != null)
            {
                MessageUtils.addMessage(client, "Lucid Prayer: Attempting a setup re-open");
                invokeAction(client, quickPrayerOrb.getMenu("Setup"), quickPrayerOrb.getCanvasLocation().getX(), quickPrayerOrb.getCanvasLocation().getY());
            }
        }
        if (container == null)
        {
            MessageUtils.addMessage(client, "Lucid Prayer: Still couldn't get widget container");
            return;
        }
        Widget[] quickPrayerWidgets = container.getDynamicChildren();
        MessageUtils.addMessage(client, "Lucid Prayer: Widget container size | " + quickPrayerWidgets.length);
        for (Widget prayerWidget : quickPrayerWidgets)
        {
            String adjustedPrayerName = prayer.name().toLowerCase().replace('_', ' ').strip();
            String adjustedWidgetName = prayerWidget.getName().toLowerCase().strip();
            if (!prayerWidget.hasAction("Toggle"))
            {
                MessageUtils.addMessage(client,"Lucid Prayer: No toggle | " + adjustedPrayerName + " | " + adjustedWidgetName);
                continue;
            }
            if (!adjustedWidgetName.contains(adjustedPrayerName))
            {
                MessageUtils.addMessage(client,"Lucid Prayer: Not a match | " + adjustedPrayerName + " | " + adjustedWidgetName);
                continue;
            }

            MessageUtils.addMessage(client,"Lucid Prayer: Attempting to toggle | " + adjustedPrayerName + " | " + adjustedWidgetName);
            invokeAction(client, prayerWidget.getMenu("Toggle"), prayerWidget.getCanvasLocation().getX(), prayerWidget.getCanvasLocation().getY());
        }
    }

    public static void toggleQuickPrayer(Client client, Prayer prayer)
    {
        if (client == null)
            return;
        if (client.getBoostedSkillLevel(Skill.PRAYER) == 0)
        {
            MessageUtils.addMessage(client, "Lucid Prayer: Out of prayer points");
            return;
        }

        toggleQuickPrayerByCheckbox(client, prayer);
    }

    public static void activateQuickPrayers(Client client)
    {
        if (client == null || (client.getBoostedSkillLevel(Skill.PRAYER) == 0 && !Prayers.isQuickPrayerEnabled()))
        {
            return;
        }

        if (!Prayers.isQuickPrayerEnabled())
        {
            Prayers.toggleQuickPrayer(true);
        }
    }

    public static boolean isQuickPrayersEnabled(Client client)
    {
        return client.getVarbitValue(Varbits.QUICK_PRAYER) == 1;
    }

}
