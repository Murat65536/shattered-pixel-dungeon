package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotItems;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfStrength;

//identify potions the classic way once the floor is quiet: drink them.
//drunk at high HP so the nasty ones are survivable; spaced out so several
//bad effects can't stack
public class DrinkId extends BotBrain.Behavior {
    @Override
    public String name() {
        return "drink-id";
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        if (hero.visibleEnemies() > 0 || hero.isStarving()) return false;

        //strength potions are pure upside; drink as soon as they are known
        PotionOfStrength strength = hero.belongings.getItem(PotionOfStrength.class);
        if (strength != null && strength.isKnown()) {
            Bot.log("drink -> potion of strength");
            strength.execute(hero, Potion.AC_DRINK);
            return true;
        }

        if (hero.HP < hero.HT * 0.8f) return false;
        if (Actor.now() < BotItems.nextIdDrinkAt) return false;

        Potion unknown = BotItems.unknownPotion(hero);
        if (unknown == null) return false;

        BotItems.nextIdDrinkAt = Actor.now() + 30;
        Bot.log("drink-id -> unknown potion");
        unknown.execute(hero, Potion.AC_DRINK);
        return true;
    }
}
