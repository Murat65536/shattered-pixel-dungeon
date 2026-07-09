package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;

//walk toward the nearest spot that reveals unexplored map
public class Explore extends BotBrain.Behavior {
    @Override
    public String name() {
        return "explore";
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        int cell = BotPaths.nearestFrontier(hero, s);
        return cell != -1 && issueHandle(hero, name(), cell);
    }
}
