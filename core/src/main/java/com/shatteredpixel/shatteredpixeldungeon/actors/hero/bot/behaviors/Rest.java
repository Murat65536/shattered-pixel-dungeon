package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotItems;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;

//nap after clearing a floor to head downstairs at full strength.
//while resting the bot is dormant; regen at full HP, damage, or a newly
//visible enemy all interrupt back into Hero.ready() and wake it
public class Rest extends BotBrain.Behavior {
    @Override
    public String name() {
        return "rest";
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        if (hero.HP > hero.HT * BotItems.REST_AT) return false;
        if (hero.visibleEnemies() > 0) return false;
        //regen does not tick while starving; resting would never end
        if (hero.isStarving()) return false;
        if (Dungeon.bossLevel()) return false;

        Bot.log("rest");
        hero.rest(true);
        return true;
    }
}
