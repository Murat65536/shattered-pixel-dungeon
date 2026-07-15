package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.levels.DeadEndLevel;

import java.util.HashMap;

public final class AmbushVisibilityCheck {

    public static void main( String[] args ) {
        DeadEndLevel level = new DeadEndLevel();
        level.setSize(31, 31);
        level.blobs = new HashMap<>();
        Dungeon.level = level;

        int w = level.width();
        int source = 10 + 8 * w;
        int ambushSpot = 15 + 14 * w;
        for (int y = 13; y <= 14; y++) {
            for (int x = 13; x <= 14; x++) {
                level.losBlocking[x + y * w] = true;
            }
        }

        Rat mob = new Rat();
        mob.pos = 10 + 20 * w;

        assert !BotPaths.lineFree(source, ambushSpot);
        assert Ambush.freshFov(mob, source)[ambushSpot];
        assert mob.pos == 10 + 20 * w;
    }
}
