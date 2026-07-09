package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotItems;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;

//swap to clearly better identified gear while things are calm
public class Equip extends BotBrain.Behavior {
    @Override
    public String name() {
        return "equip";
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        if (hero.visibleEnemies() > 0) return false;

        MeleeWeapon weapon = BotItems.betterWeapon(hero);
        if (weapon != null) {
            Bot.log("equip -> %s", weapon.name());
            if (weapon.doEquip(hero)) return true;
        }

        Armor armor = BotItems.betterArmor(hero);
        if (armor != null) {
            Bot.log("equip -> %s", armor.name());
            return armor.doEquip(hero);
        }

        return false;
    }
}

