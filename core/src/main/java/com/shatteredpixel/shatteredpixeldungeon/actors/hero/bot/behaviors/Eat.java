package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotItems;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;

//save food until starvation and low health make eating necessary
public class Eat extends BotBrain.Behavior {
    @Override
    public String name() {
        return "eat";
    }

    @Override
    public boolean essential() {
        return true;
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        Hunger hunger = hero.buff(Hunger.class);
        if (hunger == null || !hunger.isStarving() || hero.HP >= hero.HT * 0.5f) return false;

        Food food = BotItems.pickFood(hero, true);
        if (food == null) return false;

        Bot.log("eat -> %s", food.name());
        food.execute(hero, Food.AC_EAT);
        return true;
    }
}
