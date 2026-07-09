package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;


import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotItems;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;

//step away from adjacent enemies when nearly dead; if cornered, Fight takes over.
//most enemies match the hero's speed, so fleeing can never actually escape them -
//after a few steps of failed kiting, stand and fight instead of running forever
public class Retreat extends BotBrain.Behavior {

    private static final int MAX_CONSECUTIVE = 6;
    private int streak = 0;

    @Override
    public String name() {
        return "retreat";
    }

    @Override
    public boolean essential() {
        return true;
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        if (hero.HP > hero.HT * BotItems.RETREAT_AT) {
            streak = 0;
            return false;
        }

        Mob threat = null;
        for (Mob mob : hero.getVisibleEnemies()) {
            if (mob.alignment == Char.Alignment.ENEMY && mob.state != mob.PASSIVE
                    && Dungeon.level.adjacent(hero.pos, mob.pos)) {
                threat = mob;
                break;
            }
        }
        if (threat == null) {
            streak = 0;
            return false;
        }

        if (streak >= MAX_CONSECUTIVE) return false;

        int step = Dungeon.flee(hero, threat.pos, Dungeon.level.passable, hero.fieldOfView, true);
        if (step == -1 || step == hero.pos) return false;

        if (issueHandle(hero, name(), step)) {
            streak++;
            return true;
        }
        return false;
    }
}
