package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.KindOfWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfForce;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.watabou.utils.PathFinder;


//against hard-to-hit enemies, don't trade misses: slip behind a closed door
//(which blocks their sight), wait beside it, and strike as they step through.
//an enemy that hasn't seen the hero is surprised, and surprise attacks never miss
public class Ambush extends BotBrain.Behavior {

    //whether hiding beats brawling, weighing the mob's remaining health and the
    //walk to the door against the miss chance. killing needs hitsLeft more landed
    //hits (its current HP over the hero's average damage). brawling, each lands
    //with probability p (hero accuracy vs mob evasion, see hitChance), so the
    //kill takes hitsLeft/p swings; ambushing, every hit is guaranteed but costs
    //the trek to the spot first: trek + hitsLeft turns. hiding wins while
    //trek < hitsLeft/p - hitsLeft, so that is the longest walk a spot may be.
    //a nearly-dead or easily-hit mob justifies a door right beside the fight
    //at most; a healthy wraith-like justifies crossing half the floor
    private static final int TREK_CAP = 25;

    //the walk is not free: the chaser swings at the hero's back each step, and
    //each swing lands with probability q (mob accuracy vs hero evasion). the
    //hero can endure ~ENDURE_HITS landed hits at full health, less when hurt,
    //so the trek is also capped at the steps expected to cost that many hits
    private static final float ENDURE_HITS = 6f;

    static int maxTrek(Hero hero, Mob mob ) {
        float p = hitChance(hero, mob);
        if (p <= 0) return TREK_CAP;
        float hitsLeft = Math.max(1, (float)Math.ceil(mob.HP / avgDamage(hero)));
        float saved = hitsLeft * (1f / p - 1f);
        float q = Math.max(0.05f, hitChance(hero, mob));
        float endurable = ENDURE_HITS * (hero.HP / (float)hero.HT) / q;
        return (int)Math.min(TREK_CAP, Math.min(saved, endurable));
    }

    //average damage per landed hit, without the side effects of a real
    //damageRoll; the mob's armor is ignored (evasive enemies barely have any)
    private static float avgDamage( Hero hero ) {
        KindOfWeapon wep = hero.belongings.attackingWeapon();
        if (wep != null && !RingOfForce.fightingUnarmed(hero)) {
            return Math.max(1f, (wep.min() + wep.max()) / 2f);
        }
        return Math.max(1f, (1 + Math.max(hero.STR() - 8, 1)) / 2f);
    }

    private static final int MAX_WAITS = 12;
    private static final int MAX_HIDE_MOVES = 12;

    private Mob target = null;
    private Mob givenUpOn = null;
    private int waits = 0;
    private int hideMoves = 0;
    //doors only shut when something walks off them, so a mark killed on the
    //doorway props the door open; walking onto it and back off re-arms the trap
    private int rearmDoor = -1;
    private int rearmFrom = -1;

    @Override
    public String name() {
        return "ambush";
    }

    @Override
    public boolean essential() {
        return true;
    }

    @Override
    public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
        if (givenUpOn != null && !givenUpOn.isAlive()) givenUpOn = null;

        //drop the mark once it dies, flees, or falls asleep; wandering is kept -
        //a mark that noticed the hero only after being acquired hunts soon enough
        if (target != null && (!target.isAlive() || !Dungeon.level.mobs.contains(target)
                || !(target.state == target.HUNTING || target.state == target.WANDERING))) {
            target = null;
            waits = 0;
            hideMoves = 0;
            rearmDoor = rearmFrom = -1;
        }

        if (target == null) {
            acquire(hero, s);
            if (target == null) return false;
            Bot.log("ambush: %s is hard to hit (%.0f%%), setting a trap",
                    target.name(), 100 * hitChance(hero, target));
        }

        //the mark is in reach and actually visible (it can sit adjacent yet unseen,
        //diagonally behind the closed door - attacking then would just open it)
        boolean seen = hero.fieldOfView != null && hero.fieldOfView.length == Dungeon.level.length()
                && hero.fieldOfView[target.pos];
        if (seen && (hero.canAttack(target) || Dungeon.level.adjacent(hero.pos, target.pos))) {
            //unaware: spring the trap, the hit is guaranteed
            if (target.surprisedBy(hero, true)) {
                Mob struck = target;
                target = null;
                waits = 0;
                hideMoves = 0;
                return issueHandle(hero, name(), struck.pos);
            }
            //aware (e.g. wraiths spawning around a grave): standing ground just
            //trades misses, even right next to a door - what matters is being on
            //the far side of one, where its closing breaks the mob's sight. walk
            //there; it will follow and come through blind
            int spot = spotFor(hero, target, s);
            if (spot == -1) {
                giveUp("no door close enough");
                return false;
            }
            if (spot != hero.pos) {
                return hideMove(hero, spot);
            }
        }

        //never sit in ambush while something better fought head-on is in reach;
        //fellow slippery chasers don't count, or wraith packs would break the plan
        for (Mob mob : hero.getVisibleEnemies()) {
            if (mob != target && mob.alignment == Char.Alignment.ENEMY
                    && mob.state != mob.PASSIVE && !waterBound(mob) && hero.canAttack(mob)
                    && (mob.surprisedBy(hero, true) || !canSetUp(hero, mob, s))) {
                return false;
            }
        }

        //mid re-arm, standing on the opened door: step back off, it shuts behind.
        //even a mark right at the far side gets the door closed in its face
        if (hero.pos == rearmDoor && rearmFrom != -1) {
            int back = rearmFrom;
            rearmDoor = rearmFrom = -1;
            return issueHandle(hero, name() + "-rearm", back);
        }

        //the trap is sprung by striking the mark on the doorway, so a kill there
        //props the door open, spoiling the ambush for the rest of its pack; while
        //they are still steps away there is time to walk onto it and back off
        int propped = propOpenDoorBeside(hero);
        if (propped != -1 && nearestHunterDist(hero, s) >= 4) {
            rearmDoor = propped;
            rearmFrom = hero.pos;
            return issueHandle(hero, name() + "-rearm", propped);
        }

        if (hidden(hero)) {
            if (++waits > MAX_WAITS) {
                giveUp("it isn't taking the bait");
                return false;
            }
            Bot.log("ambush: waiting");
            hero.rest(false);
            return true;
        }

        int spot = spotFor(hero, target, s);
        if (spot == -1) {
            //beside a closed door with no better spot: the mark "seeing" this cell
            //may just be stale sight from before the door shut (a mob's fov only
            //refreshes on its own turn); hold position rather than give up
            if (besideClosedDoor(hero.pos)) {
                if (++waits > MAX_WAITS) {
                    giveUp("it isn't taking the bait");
                    return false;
                }
                Bot.log("ambush: waiting");
                hero.rest(false);
                return true;
            }
            giveUp("no door close enough");
            return false;
        }
        if (spot == hero.pos) {
            //already in place, the mob just hasn't lost sight yet
            if (++waits > MAX_WAITS) {
                giveUp("it isn't taking the bait");
                return false;
            }
            Bot.log("ambush: waiting");
            hero.rest(false);
            return true;
        }
        return hideMove(hero, spot);
    }

    //relocating costs turns the mark spends swinging at the hero's back, so a
    //mark that keeps every hiding spot in sight isn't worth chasing them for
    private boolean hideMove( Hero hero, int spot ) {
        if (++hideMoves > MAX_HIDE_MOVES) {
            giveUp("it keeps me in sight");
            return false;
        }
        return issueHandle(hero, name() + "-hide", spot);
    }

    //out of the mark's sight, beside a closed door it will have to come through
    private boolean hidden( Hero hero ) {
        if (target.fieldOfView != null && target.fieldOfView.length == Dungeon.level.length()
                && target.fieldOfView[hero.pos]) {
            return false;
        }
        return besideClosedDoor(hero.pos);
    }

    private boolean besideClosedDoor( int pos ) {
        for (int offset : PathFinder.NEIGHBOURS8) {
            int d = pos + offset;
            if (d >= 0 && d < Dungeon.level.length()
                    && Dungeon.level.map[d] == Terrain.DOOR) {
                return true;
            }
        }
        return false;
    }

    //an adjacent open door that would shut if walked over: nothing standing on
    //it, and no heap holding it open. -1 when there is none
    private int propOpenDoorBeside( Hero hero ) {
        for (int offset : PathFinder.NEIGHBOURS8) {
            int d = hero.pos + offset;
            if (d >= 0 && d < Dungeon.level.length()
                    && Dungeon.level.map[d] == Terrain.OPEN_DOOR
                    && Actor.findChar(d) == null
                    && Dungeon.level.heaps.get(d) == null) {
                return d;
            }
        }
        return -1;
    }

    //walking distance of the closest thing hunting the hero; re-arming takes two
    //turns, so it only happens with enough of a head start
    private int nearestHunterDist( Hero hero, BotPaths.Snapshot s ) {
        int nearest = s.dist[target.pos];
        for (Mob mob : hero.getVisibleEnemies()) {
            if (mob.alignment == Char.Alignment.ENEMY && mob.state != mob.PASSIVE
                    && !waterBound(mob) && s.dist[mob.pos] < nearest) {
                nearest = s.dist[mob.pos];
            }
        }
        return nearest;
    }

    private void acquire( Hero hero, BotPaths.Snapshot s ) {
        int bestDist = Integer.MAX_VALUE;
        for (Mob mob : hero.getVisibleEnemies()) {
            if (mob.alignment != Char.Alignment.ENEMY || mob.state == mob.PASSIVE) continue;
            if (waterBound(mob)) continue;
            //only ambush what chases, or will: a wanderer close enough to notice
            //the hero (they are in each other's sight) starts hunting right away
            boolean willChase = mob.state == mob.HUNTING
                    || (mob.state == mob.WANDERING
                    && Dungeon.level.distance(hero.pos, mob.pos) <= mob.viewDistance);
            if (!willChase || mob == givenUpOn) continue;
            if (Bot.isBlacklisted(mob.pos)) continue;
            if (!hero.canAttack(mob) && !s.reachable(mob.pos)) continue;
            //surprised and in reach (or asleep): a plain attack collects the free
            //hit right now. a distant awake mob is another story - it notices the
            //hero mid-charge and the surprise is gone on arrival, so still ambush
            if (mob.surprisedBy(hero, true)
                    && (hero.canAttack(mob) || mob.state == mob.SLEEPING)) continue;
            if (!canSetUp(hero, mob, s)) continue;
            int dist = hero.canAttack(mob) ? 0 : s.dist[mob.pos];
            if (dist < bestDist) {
                bestDist = dist;
                target = mob;
            }
        }
    }

    //a hiding spot exists within the walk this mob's evasion justifies
    static boolean worthAmbushing( Hero hero, Mob mob, BotPaths.Snapshot s ) {
        return spotFor(hero, mob, s) != -1;
    }

    //like worthAmbushing, but a propped-open door beside the hero also counts:
    //re-arming it takes two turns, the same as a hiding spot two steps away
    private boolean canSetUp( Hero hero, Mob mob, BotPaths.Snapshot s ) {
        if (worthAmbushing(hero, mob, s)) return true;
        return propOpenDoorBeside(hero) != -1 && maxTrek(hero, mob) >= 2;
    }

    static int spotFor( Hero hero, Mob mob, BotPaths.Snapshot s ) {
        int trek = maxTrek(hero, mob);
        if (trek <= 0) return -1;
        return BotPaths.ambushSpot(hero, mob, s, trek);
    }

    private void giveUp( String why ) {
        Bot.log("ambush: giving up on %s, %s", target.name(), why);
        givenUpOn = target;
        target = null;
        waits = 0;
        hideMoves = 0;
        rearmDoor = rearmFrom = -1;
    }
}
