package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;

//attack the closest hostile that is in reach or can be walked to. sits in the
//chain twice: awake enemies preempt everything ("fight"), but a sleeping one
//isn't bothering anybody, so walking over to it can wait until after looting
//("cull") - otherwise chasing it through sight-breaking grass ping-pongs with
//Loot forever. an adjacent sleeper is still struck at once: that's a free
//surprise hit, not a trek
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
            if (!threat(mob)) continue;
            //the game refuses to attack invisible chars (Hero.actAttack), and
            //trying anyway livelocks; leave them alone until they fade back in
            if (mob.invisible > 0) continue;
            if (Bot.isBlacklisted(mob.pos)) continue;
            if (!hero.canAttack(mob)) continue;
            float score = mob.surprisedBy(hero, true) ? 2f : hitChance(hero, mob);
            if (score > bestScore) {
                bestScore = score;
                best = mob;
            }
        }
        if (best != null) {
            return issueHandle(hero, name(), best.pos);
        }

        //nothing in reach: close in on the nearest reachable enemy.
        //one standing in a harmful cloud is left alone - walking up to it means
        //wading in; a hunter will come out on its own, a sleeper can wait
        int bestDist = Integer.MAX_VALUE;
        for (Mob mob : hero.getVisibleEnemies()) {
            if (!threat(mob)) continue;
            if (mob.invisible > 0) continue;
            if (Bot.isBlacklisted(mob.pos) || s.hazard[mob.pos]) continue;
            if (!s.reachable(mob.pos)) continue;
            if (s.dist[mob.pos] < bestDist) {
                bestDist = s.dist[mob.pos];
                best = mob;
            }
        }
        return best != null && issueHandle(hero, name(), best.pos);
    }
}
