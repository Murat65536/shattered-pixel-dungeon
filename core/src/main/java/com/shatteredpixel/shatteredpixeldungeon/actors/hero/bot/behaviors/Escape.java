package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;

//get out of a harmful cloud before doing anything else; nothing below this in
//the chain checks the hero's own cell, so without it the bot would rest or
//brawl while gas ticks away at it
public class Escape extends BotBrain.Behavior {
    @Override
    public String name() {
        return "escape";
    }

    @Override
    public boolean essential() {
        return true;
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        if (!s.hazard[hero.pos]) return false;
        int cell = BotPaths.safeCell(hero, s);
        return cell != -1 && issueHandle(hero, name(), cell);
    }
}
