package com.lucidplugins.lucidcustomprayers.api.util;

import net.runelite.api.Client;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
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
            MessageUtils.addMessage(client, "Lucid Prayer: Couldn't get widget container");
            return;
        }
        Widget[] quickPrayerWidgets = container.getDynamicChildren();
        for (Widget prayerWidget : quickPrayerWidgets)
        {
            if (!prayerWidget.hasAction("Toggle"))
                continue;
            if (!prayerWidget.getName().toLowerCase().contains(prayer.name().toLowerCase()))
                continue;
            MessageUtils.addMessage(client,"Lucid Prayer: Attempting to toggle | " + prayer.name());
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
