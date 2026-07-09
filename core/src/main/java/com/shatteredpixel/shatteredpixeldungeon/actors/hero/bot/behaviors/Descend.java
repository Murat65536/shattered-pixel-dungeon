package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;

//take the stairs down once there is nothing else left to do
public class Descend extends BotBrain.Behavior {
    @Override
    public String name() {
        return "descend";
    }

    @Override
    public boolean essential() {
        return true;
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        if (Dungeon.level.locked) return false;
        int exit = BotPaths.regularExit();
        if (exit == -1 || Bot.isBlacklisted(exit)) return false;
        //gas over the stairs disperses in a few turns; no need to wade in
        if (s.hazard[exit] && hero.pos != exit) return false;
        if (hero.pos != exit && !s.reachable(exit)) return false;
        return issueHandle(hero, name(), exit);
    }
}
