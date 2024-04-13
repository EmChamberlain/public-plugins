package com.lucidplugins.lucidcustomprayers.api.util;

import net.runelite.api.Client;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
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

    public static void toggleQuickPrayerByCheckbox(Client client, Prayer prayer)
    {
        Widget container = client.getWidget(ComponentID.QUICK_PRAYER_PRAYERS);
        if (container == null)
        {
            System.out.println("Lucid Prayer: Couldn't get widget container");
            return;
        }
        Widget[] quickPrayerWidgets = container.getDynamicChildren();
        for (Widget prayerWidget : quickPrayerWidgets)
        {
            if (!prayerWidget.hasAction("Toggle"))
                continue;
            if (!prayerWidget.getName().toLowerCase().contains(prayer.name().toLowerCase()))
                continue;
            prayerWidget.interact("Toggle");
        }
    }

    public static void toggleQuickPrayer(Client client, Prayer prayer)
    {
        if (client == null || (client.getBoostedSkillLevel(Skill.PRAYER) == 0 || !client.isPrayerActive(prayer)))
        {
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
