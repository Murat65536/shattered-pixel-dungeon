package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;


import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.CrystalKey;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;

//spend collected keys on locked doors blocking the way
public class Unlock extends BotBrain.Behavior {
    @Override
    public String name() {
        return "unlock";
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        boolean hasIron = Notes.keyCount(new IronKey(Dungeon.depth)) > 0;
        boolean hasCrystal = Notes.keyCount(new CrystalKey(Dungeon.depth)) > 0;
        if (!hasIron && !hasCrystal) return false;

        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int c = 0; c < s.dist.length; c++) {
            int terrain = Dungeon.level.map[c];
            boolean openable = (terrain == Terrain.LOCKED_DOOR && hasIron)
                    || (terrain == Terrain.CRYSTAL_DOOR && hasCrystal);
            if (!openable) continue;
            if (!(Dungeon.level.visited[c] || Dungeon.level.mapped[c])) continue;
            if (Bot.isBlacklisted(c)) continue;
            int dist = BotPaths.adjacentDist(c, s);
            if (dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        return best != -1 && issueHandle(hero, name(), best);
    }
}
