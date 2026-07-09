package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Swarm;

//when outnumbered, back into a chokepoint - a corridor, doorway or dead-end -
//so the pack can only engage one at a time, then hold there and let Fight cut
//down whoever steps up. losing a few hits on the way there beats trading
//blows with two or three enemies at once in the open
public class Funnel extends BotBrain.Behavior {

    private static final int MAX_TREK = 8;
    private static final int MAX_WAITS = 12;

    private int waits = 0;

    @Override
    public String name() {
        return "funnel";
    }

    @Override
    public boolean essential() {
        return true;
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        //only kicks in against a pack that is actively coming for the hero
        int hunters = 0;
        for (Mob mob : hero.getVisibleEnemies()) {
            if (mob.alignment != Char.Alignment.ENEMY || mob.state == mob.PASSIVE) continue;
            if (mob.state != mob.HUNTING) continue;
            if (waterBound(mob)) continue;
            if (!hero.canAttack(mob) && !s.reachable(mob.pos)) continue;
            hunters++;
        }
        if (hunters < 2) {
            waits = 0;
            return false;
        }

        if (BotPaths.attackSlots(hero.pos) <= 2) {
            //already funneled: strike whoever is in reach, otherwise hold
            //position and make them file in one by one
            for (Mob mob : hero.getVisibleEnemies()) {
                if (mob.alignment == Char.Alignment.ENEMY && mob.state != mob.PASSIVE
                        && !waterBound(mob) && hero.canAttack(mob)) {
                    waits = 0;
                    return false; //Fight picks the best in-reach target
                }
            }
            if (waits >= MAX_WAITS) return false; //they aren't coming; go to them
            if (waits == 0) Bot.log("funnel: holding against %d hunters", hunters);
            waits++;
            hero.rest(false);
            return true;
        }

        waits = 0;
        int spot = BotPaths.chokePoint(hero, s, MAX_TREK);
        return spot != -1 && issueHandle(hero, name(), spot);
    }
}
