package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;

//last resort when nothing is explorable and the way down is missing or cut off:
//hunt for secret doors. see Bot.CHEAT_SECRETS for how honest this is
public class Search extends BotBrain.Behavior {
    @Override
    public String name() {
        return "search";
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        int spot;
        if (Bot.CHEAT_SECRETS) {
            spot = BotPaths.nearestSearchSpot(hero, s);
        } else {
            spot = Bot.hasSearched(hero.pos) ? BotPaths.nearestUnsearched(hero, s) : hero.pos;
        }
        if (spot == -1) return false;

        if (spot == hero.pos) {
            Bot.markSearched(hero.pos);
            Bot.log("search");
            hero.search(true);
            return true;
        }
        return issueHandle(hero, name(), spot);
    }
}
