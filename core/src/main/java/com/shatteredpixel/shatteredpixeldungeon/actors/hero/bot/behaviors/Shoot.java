package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotItems;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.ui.QuickSlotButton;

//an enemy closing in from range eats a shot every turn of its walk: damage the
//melee that follows never has to trade for. shoots the nearest hunter that has
//committed to the hero - already firing, or on its way over - but is not yet in
//arm's reach; once something stands next to the hero, Fight takes it from there.
//only hunters: a sleeper or wanderer hit from afar just wakes the whole room
public class Shoot extends BotBrain.Behavior {

    @Override
    public String name() {
        return "shoot";
    }

    @Override
    public boolean essential() {
        return true;
    }

    @Override
    public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
        //anything already in reach is Fight's problem; swapping to a throw while
        //standing in melee just gives up the better trade
        for (Mob mob : hero.getVisibleEnemies()) {
            if (mob.alignment == Char.Alignment.ENEMY && mob.state != mob.PASSIVE
                    && !waterBound(mob) && hero.canAttack(mob)) {
                return false;
            }
        }

        BotItems.RangedAttack ranged = BotItems.rangedAttack(hero);
        if (ranged == null) return false;

        Mob best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Mob mob : hero.getVisibleEnemies()) {
            if (mob.alignment != Char.Alignment.ENEMY || mob.state != mob.HUNTING) continue;
            if (waterBound(mob) || Bot.isBlacklisted(mob.pos)) continue;
            //committed to the hero: shooting already, or able to walk over.
            //one that can do neither can be safely ignored, not sniped
            if (!mob.canAttackTarget(hero) && !s.reachable(mob.pos)) continue;
            int dist = Dungeon.level.distance(hero.pos, mob.pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = mob;
            }
        }
        if (best == null) return false;

        //the exact cell to aim at so the projectile connects (may be angled past
        //the target); -1 means no line of fire from where the hero stands
        int aim = QuickSlotButton.autoAim(best, ranged.item);
        if (aim == -1) return false;

        return Bot.requestUseAt(ranged.item, ranged.action, aim,
                String.format("shoot %s at %s", ranged.item.name(), best.name()));
    }
}
