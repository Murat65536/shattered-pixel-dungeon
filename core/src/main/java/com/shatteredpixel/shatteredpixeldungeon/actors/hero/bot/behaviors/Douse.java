package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Ooze;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;

//walk to water to wash off debuffs it removes (burning and ooze - the only two
//that detach on a wet cell), but only when that actually saves HP: both tick
//damage every turn of the trek too, so the walk is worth it exactly when its
//expected damage undercuts letting the debuffs run their course in place
public class Douse extends BotBrain.Behavior {

    //don't bother detouring for less than this much expected HP saved
    private static final float MIN_SAVINGS = 1f;

    @Override
    public String name() {
        return "douse";
    }

    @Override
    public boolean essential() {
        return true;
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        //water never washes a flying hero, and standing in it already means the
        //buff detaches on its next tick without any help
        if (hero.flying || Dungeon.level.water[hero.pos]) return false;

        Burning burning = hero.buff(Burning.class);
        Ooze ooze = hero.buff(Ooze.class);
        if (burning == null && ooze == null) return false;

        int cell = BotPaths.waterCell(hero, s);
        if (cell == -1) return false;

        float trekTurns = s.dist[cell] / hero.speed();
        float saved = 0;
        if (burning != null) {
            saved += burnDamage(burning.left()) - burnDamage(Math.min(burning.left(), trekTurns));
        }
        if (ooze != null) {
            saved += oozeDamage(ooze.left()) - oozeDamage(Math.min(ooze.left(), trekTurns));
        }
        if (saved < MIN_SAVINGS) return false;

        Bot.log("douse: ~%.1f hp saved over %d steps", saved, s.dist[cell]);
        return issueMove(hero, name(), cell, s);
    }

    //expected hp lost to burning over this many turns: each tick rolls
    //NormalIntRange(1, 3 + depth/4), whose mean is 2 + depth/8
    private static float burnDamage( float turns ) {
        return Math.max(0, turns) * (2f + Dungeon.scalingDepth() / 8f);
    }

    //expected hp lost to ooze over this many turns: 1 + depth/5 per tick past
    //depth 5, a flat 1 at depth 5, and a coin flip for 1 in the sewers
    private static float oozeDamage( float turns ) {
        int depth = Dungeon.scalingDepth();
        float perTick;
        if (depth > 5)       perTick = 1 + depth / 5f;
        else if (depth == 5) perTick = 1;
        else                 perTick = 0.5f;
        return Math.max(0, turns) * perTick;
    }
}
