package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;

//attack the best hostile already in reach. Shoot runs first for bows, wands,
//and thrown weapons; enemies the hero cannot currently hit are left alone
public class Fight extends BotBrain.Behavior {
    @Override
    public String name() {
        return "fight";
    }

    @Override
    public boolean essential() {
        return true;
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        //among enemies in reach, hit the one most likely to actually get hit:
        //surprised targets can't dodge at all, wraith-likes are near-hopeless
        Mob best = null;
        float bestScore = -1;
        for (Mob mob : hero.getVisibleEnemies()) {
            if (!attackable(hero, mob)) continue;
            if (Bot.isBlacklisted(mob.pos)) continue;
            if (!hero.canAttack(mob)) continue;
            float score = mob.surprisedBy(hero, true) ? 2f : hitChance(hero, mob);
            if (score > bestScore) {
                bestScore = score;
                best = mob;
            }
        }
        if (best != null) {
            return issueHandle(hero, name(), best.pos, s);
        }
        return false;
    }
}
