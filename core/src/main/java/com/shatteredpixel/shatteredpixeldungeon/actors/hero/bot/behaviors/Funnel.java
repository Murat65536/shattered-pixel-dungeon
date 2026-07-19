package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;

import java.util.ArrayList;
import java.util.List;

//when outnumbered, back into a chokepoint - a corridor, doorway or dead-end -
//so the pack can only engage one at a time, then hold there and let Fight cut
//down whoever steps up. losing a few hits on the way there beats trading
//blows with two or three enemies at once in the open
public class Funnel extends BotBrain.Behavior {

    private static final int MAX_TREK = 8;
    private static final int MAX_WAITS = 12;
    //how long a flanking route around the hero may be before it stops counting
    //as an attack slot: anything longer can't land within a full hold
    private static final int FLANK_STEPS = MAX_WAITS;

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
        //waits survives only across consecutive holds; every other path clears it
        int held = waits;
        waits = 0;

        //only kicks in against a pack that is actively coming for the hero
        List<Mob> hunters = new ArrayList<>();
        for (Mob mob : hero.getVisibleEnemies()) {
            if (!threat(mob) ||
                    mob.state != mob.HUNTING ||
                    !hero.canAttack(mob)) {
                continue;
            }
            hunters.add(mob);
        }
        if (hunters.size() < 2) return false;

        //funneled means the pack can only ever bring one attacker to bear: it
        //must not be able to reach more than one cell beside the hero
        if (BotPaths.reachableAttackSlots(hero.pos, hunters, FLANK_STEPS) <= 1) {
            //already funneled: strike whoever is in reach, otherwise hold
            //position and make them file in one by one
            for (Mob mob : hero.getVisibleEnemies()) {
                if (threat(mob) && hero.canAttack(mob)) {
                    return false; //Fight picks the best in-reach target
                }
            }
            if (held >= MAX_WAITS) return false; //they aren't coming; go to them
            if (held == 0) Bot.log("funnel: holding against %d hunters", hunters.size());
            waits = held + 1;
            hero.rest(false);
            return true;
        }

        int spot = BotPaths.chokePoint(hero, s, hunters, MAX_TREK, FLANK_STEPS);
        return spot != -1 && issueMove(hero, name(), spot, s);
    }
}
