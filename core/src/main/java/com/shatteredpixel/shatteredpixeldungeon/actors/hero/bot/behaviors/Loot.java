package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.CrystalKey;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.GoldenKey;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;

//grab the closest thing worth grabbing
public class Loot extends BotBrain.Behavior {
    @Override
    public String name() {
        return "loot";
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        Heap best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Heap heap : Dungeon.level.heaps.valueList()) {
            if (!heap.seen || !worthLooting(hero, heap) || Bot.isBlacklisted(heap.pos)) continue;
            //loot inside a harmful cloud keeps; come back once it clears
            if (s.hazard[heap.pos]) continue;
            if (!s.reachable(heap.pos)) continue;
            if (s.dist[heap.pos] < bestDist) {
                bestDist = s.dist[heap.pos];
                best = heap;
            }
        }
        return best != null && issueHandle(hero, name(), best.pos);
    }

    private boolean worthLooting( Hero hero, Heap heap ) {
        switch (heap.type) {
            case HEAP:
            case CHEST:
            case SKELETON:
            case REMAINS:
                return true;
            //graves spawn a pack of wraiths; only disturb them at fighting strength.
            //Rest will top the hero up first, since tombs are skipped while hurt
            case TOMB:
                return hero.HP >= hero.HT * 0.7f;
            //opening a locked chest without its key is a no-op loop; gate on the key
            case LOCKED_CHEST:
                return Notes.keyCount(new GoldenKey(Dungeon.depth)) > 0;
            case CRYSTAL_CHEST:
                return Notes.keyCount(new CrystalKey(Dungeon.depth)) > 0;
            default:
                //FOR_SALE is never bought
                return false;
        }
    }
}
