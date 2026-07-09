package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Healing;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotItems;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.items.Waterskin;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;

//drink healing when badly hurt and not already healing over time
public class Heal extends BotBrain.Behavior {
    @Override
    public String name() {
        return "heal";
    }

    @Override
    public boolean essential() {
        return true;
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        if (hero.HP > hero.HT * BotItems.HEAL_AT || hero.buff(Healing.class) != null) {
            return false;
        }

        PotionOfHealing potion = BotItems.healingPotion(hero);
        if (potion != null) {
            Bot.log("heal -> potion of healing");
            potion.execute(hero, Potion.AC_DRINK);
            return true;
        }

        Waterskin skin = BotItems.drinkableWaterskin(hero);
        if (skin != null) {
            Bot.log("heal -> waterskin");
            skin.execute(hero, "DRINK");
            return true;
        }
        return false;
    }
}