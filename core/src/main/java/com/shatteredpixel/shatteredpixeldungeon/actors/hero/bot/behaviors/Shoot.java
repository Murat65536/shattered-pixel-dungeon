package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotItems;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
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
            if (attackable(hero, mob) && hero.canAttack(mob)) {
                return false;
            }
        }

        //an unknown wand takes the occasional shot: if it turns out to be a
        //damage wand the zap chips like any other, and either way it works
        //toward the identify. only at solid HP - the first zap of a cursed wand
        //backfires - and spaced out so a dud wand doesn't eat every shooting turn
        if (hero.HP >= hero.HT * 0.5f && Actor.now() >= BotItems.nextIdZapAt) {
            Wand unknown = BotItems.idZapWand(hero);
            if (unknown != null) {
                Mob best = nearestTarget(hero, unknown.getClass());
                int aim = best == null ? -1 : QuickSlotButton.autoAim(best, unknown);
                if (aim != -1) {
                    BotItems.nextIdZapAt = Actor.now() + 10;
                    return Bot.requestUseAt(unknown, Wand.AC_ZAP, aim,
                            String.format("zap-id %s at %s", unknown.name(), best.name()));
                }
            }
        }

        BotItems.RangedAttack ranged = BotItems.rangedAttack(hero);
        if (ranged == null) return false;
        Mob best = nearestTarget(hero, ranged.effect);
        if (best == null) return false;

        //the exact cell to aim at so the projectile connects (may be angled past
        //the target); -1 means no line of fire from where the hero stands
        int aim = QuickSlotButton.autoAim(best, ranged.item);
        if (aim == -1) return false;

        return Bot.requestUseAt(ranged.item, ranged.action, aim,
                String.format("shoot %s at %s", ranged.item.name(), best.name()));
    }

    private static Mob nearestTarget( Hero hero, Class<?> effect ) {
        Mob best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Mob mob : hero.getVisibleEnemies()) {
            if (!attackable(mob, effect) || Bot.isBlacklisted(mob.pos)) continue;
            int dist = Dungeon.level.distance(hero.pos, mob.pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = mob;
            }
        }
        return best;
    }
}
