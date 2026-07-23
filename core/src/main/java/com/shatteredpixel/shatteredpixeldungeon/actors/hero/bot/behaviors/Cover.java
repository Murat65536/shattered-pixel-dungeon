package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;

import java.util.ArrayList;
import java.util.List;

//against ranged attackers, charging across the open eats a shot every step of
//the way. before Fight closes in, tally what the walk is expected to cost:
//steps to melee, times each shooter's chance to land one, times its damage.
//when that bill runs past what the hero can afford, duck behind los-blocking
//terrain instead and wait - a shooter with no line has to walk over, and the
//shorter its remaining approach, the cheaper meeting it becomes
public class Cover extends BotBrain.Behavior {

    //fraction of current HP the walk to a shooter may be expected to cost;
    //whatever survives the approach still has to win the melee that follows
    public static float MAX_HP_SPEND = 0.35f;

    //cover further away than this is not cover, it is fleeing past other dangers
    private static final int MAX_TREK = 10;
    //a hunting shooter closes about a cell a turn from at most ~8 away
    private static final int MAX_WAITS = 20;
    //a shooter that keeps regaining its line (circling the same pillar) wins
    //the footwork game; stop dancing and charge
    private static final int MAX_COVER_MOVES = 10;

    private Mob target = null;
    private Mob givenUpOn = null;
    private int waits = 0;
    private int coverMoves = 0;

    @Override
    public String name() {
        return "cover";
    }

    @Override
    public boolean essential() {
        return true;
    }

    @Override
    public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
        if (givenUpOn != null && !givenUpOn.isAlive()) givenUpOn = null;

        //drop the mark once it dies, calms down, or leaves the floor
        if (target != null && (!target.isAlive() || !Dungeon.level.mobs.contains(target)
                || !(target.state == target.HUNTING || target.state == target.WANDERING))) {
            reset();
        }

        List<Mob> shooters = shooters(hero);

        if (shooters.isEmpty() && target == null) return false;

        //the hero can shoot back from right here: that is a fair trade for
        //Fight to make, not something to hide from
        for (Mob mob : shooters) {
            if (attackable(hero, mob) && hero.canAttack(mob)) {
                reset();
                return false;
            }
        }

        if (!shooters.isEmpty()) {
            Mob nearest = null;
            for (Mob mob : shooters) {
                if (nearest == null || s.dist[mob.pos] < s.dist[nearest.pos]) nearest = mob;
            }
            float bill = approachCost(hero, s, shooters, nearest);
            if (bill < hero.HP * MAX_HP_SPEND) {
                //cheap enough to just walk over; Fight takes it from here
                reset();
                return false;
            }
            if (target == null) {
                target = nearest;
                Bot.log("cover: closing on %s would cost ~%.0f of %d hp, taking cover",
                        nearest.name(), bill, hero.HP);
            }
        }

        //anything already in reach is Fight's problem before hiding is worth it
        for (Mob mob : hero.getVisibleEnemies()) {
            if (attackable(hero, mob) && hero.canAttack(mob)) {
                return false;
            }
        }

        //the held mark keeps counting as a threat even while it can't currently
        //shoot - that is exactly when the hero is in cover and it is walking over
        List<Mob> threats = new ArrayList<>(shooters);
        if (target != null && !threats.contains(target)) threats.add(target);

        if (BotPaths.exposedTo(threats, hero.pos)) {
            int spot = BotPaths.coverSpot(hero, s, threats, MAX_TREK);
            if (spot == -1) {
                giveUp("nothing to hide behind");
                return false;
            }
            if (++coverMoves > MAX_COVER_MOVES) {
                giveUp("it keeps its line on me");
                return false;
            }
            return issueMove(hero, name(), spot, s);
        }

        //in cover: no line means no shot, and the shooter has to come to melee
        if (++waits > MAX_WAITS) {
            giveUp("it isn't coming");
            return false;
        }
        Bot.log("cover: waiting");
        hero.rest(false);
        return true;
    }

    //visible enemies that can hit the hero from beyond arm's reach right now.
    //only hunters: a wanderer isn't shooting yet, and once it notices the hero
    //it turns hunter and gets counted the next turn
    private List<Mob> shooters( Hero hero ) {
        List<Mob> shooters = new ArrayList<>();
        for (Mob mob : hero.getVisibleEnemies()) {
            if (!threat(mob) || mob.state != mob.HUNTING) continue;
            if (mob == givenUpOn) continue;
            if (Bot.isBlacklisted(mob.pos)) continue;
            if (Dungeon.level.adjacent(hero.pos, mob.pos)) continue;
            if (mob.canAttackTarget(hero)) shooters.add(mob);
        }
        return shooters;
    }

    //expected damage taken walking into melee with the chosen mark: every step
    //of the approach, each shooter fires as often as its attack delay allows
    private static float approachCost( Hero hero, BotPaths.Snapshot s, List<Mob> shooters, Mob charged ) {
        //a shooter that can't be walked to (across a chasm, say) can never be
        //silenced by charging - no bill is worth quoting
        if (!s.reachable(charged.pos)) return Float.MAX_VALUE;
        float turns = Math.max(0, s.dist[charged.pos] - 1) / hero.speed();
        float total = 0;
        for (Mob mob : shooters) {
            total += (turns / mob.attackDelay()) * rangedHitChance(mob, hero) * avgDamage(mob);
        }
        return total;
    }

    //uses Char.hitChance() to get the exact hit probability
    private static float rangedHitChance( Mob mob, Hero hero ) {
        return Char.hitChance(mob, hero, 2f);
    }

    private void reset() {
        target = null;
        waits = 0;
        coverMoves = 0;
    }

    private void giveUp( String why ) {
        if (target != null) {
            Bot.log("cover: giving up on %s, %s", target.name(), why);
            givenUpOn = target;
        }
        reset();
    }
}
