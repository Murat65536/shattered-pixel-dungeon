package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;

//remembered gas (see BotPaths.hazards) keeps its cells off-limits until they
//are seen again, but most clouds disperse on their own. when there is nothing
//better to do, walk somewhere with a view of a memory that has gone stale so
//it gets cleared or re-confirmed - without this, gas remembered over the
//stairs or a heap would block descending and looting forever
public class Scout extends BotBrain.Behavior {

    //how old a hazard memory must be before it is worth a second look
    public static float STALE_AGE = 30f;

    @Override
    public String name() {
        return "scout";
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        int cell = BotPaths.scoutSpot(hero, s, STALE_AGE);
        return cell != -1 && issueHandle(hero, name(), cell);
    }
}
