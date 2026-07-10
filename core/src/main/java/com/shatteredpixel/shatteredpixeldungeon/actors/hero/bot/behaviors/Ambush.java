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
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.AssassinsBlade;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Dagger;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Dirk;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.watabou.utils.PathFinder;

import java.util.HashMap;
import java.util.Map;

public class Ambush extends BotBrain.Behavior {

    //hard ceiling on the walk to a hiding spot
    private static final int TREK_CAP = 25;
    //extra trek allowance: the strike leaves a doorway chokepoint fight (or a rearmable trap) behind
    private static final float DOOR_BONUS = 2f;
    //patience caps before giving up on a mark
    private static final int MAX_WAITS = 12;
    private static final int MAX_HIDE_MOVES = 12;

    private Mob target = null;
    private Mob givenUpOn = null;
    private int waits = 0;
    private int hideMoves = 0;
    //the cell the mark last saw the hero on: what it hunts toward once he
    //ducks out of its sight
    private int lastKnown = -1;
    //a mark killed on the doorway props the door open; walking onto it and back off re-arms the trap
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

        //drop the mark once it dies, flees, or falls asleep; a wandering mark hunts again soon enough
        if (target != null && (!target.isAlive() || !Dungeon.level.mobs.contains(target)
                || target.state != target.HUNTING && target.state != target.WANDERING)) {
            reset();
        }

        if (target == null) {
            int bestDist = Integer.MAX_VALUE;
            for (Mob mob : hero.getVisibleEnemies()) {
                if (!threat(mob) ||
                        mob.invisible > 0 || //can't be struck (see Fight), so can't be ambushed
                        mob.state != mob.HUNTING ||
                        mob == givenUpOn ||
                        Bot.isBlacklisted(mob.pos) ||
                        !hero.canAttack(mob) && !s.reachable(mob.pos) ||
                        mob.surprisedBy(hero, true) && (hero.canAttack(mob) || mob.state == mob.SLEEPING) ||
                        !canSetUp(hero, mob, s)) {
                    continue;
                }
                int dist = s.dist[mob.pos];
                if (dist < bestDist) {
                    bestDist = dist;
                    target = mob;
                }
            }
            if (target == null) {
                return false;
            }
            Bot.log("ambush: %s is hard to hit (%.0f%%), setting a trap",
                    target.name(), 100 * hitChance(hero, target));
        }

        //refreshed while the mark can still see the hero: the best guess at
        //where its own hunt will head once he ducks away
        if (lastKnown == -1 || sees(target, hero.pos)) {
            lastKnown = hero.pos;
        }

        //in reach and truly visible - adjacent yet unseen behind the door means attacking would just open it
        if (sees(hero, target.pos) && (hero.canAttack(target) || Dungeon.level.adjacent(hero.pos, target.pos))) {
            //unaware: spring the trap, the hit is guaranteed
            if (target.surprisedBy(hero, true)) {
                //not reset(): rearm state survives the strike so a doorway kill can still shut the door
                Mob struck = target;
                target = null;
                waits = 0;
                hideMoves = 0;
                lastKnown = -1;
                return issueHandle(hero, name(), struck.pos);
            }
            //aware: standing ground just trades misses - duck out of its sight and it will blunder into reach blind
            int spot = ambushSpot(hero, target, s);
            if (spot == -1) {
                giveUp("nowhere to hide");
                return false;
            }
            if (spot != hero.pos) {
                return hideMove(hero, spot);
            }
        }

        for (Mob mob : hero.getVisibleEnemies()) {
            if (mob == target || !threat(mob)) continue;
            //prefer a fight already in reach over sitting in ambush (fellow slippery chasers don't count)
            if (hero.canAttack(mob)
                    && (mob.surprisedBy(hero, true) || !canSetUp(hero, mob, s))) {
                return false;
            }
            //a chaser aware and lined up is past ambushing - step aside for the fighting behaviors
            if (!mob.surprisedBy(hero, true) && mob.canAttackTarget(hero)
                    && sees(mob, hero.pos)) {
                return false;
            }
        }

        //mid re-arm, standing on the opened door: step back off and it shuts, even in the mark's face
        if (hero.pos == rearmDoor && rearmFrom != -1) {
            int back = rearmFrom;
            rearmDoor = rearmFrom = -1;
            return issueHandle(hero, name() + "-rearm", back);
        }

        //a kill on the doorway propped the door open; with a head start, walk onto it and back off to re-arm
        int propped = propOpenDoorBeside(hero);
        if (propped != -1 && nearestHunterDist(hero, s) >= 4) {
            rearmDoor = propped;
            rearmFrom = hero.pos;
            return issueHandle(hero, name() + "-rearm", propped);
        }

        if (hidden(hero, s)) {
            return holdPosition(hero);
        }

        int spot = ambushSpot(hero, target, s);

        //aware and lined up right now is no stale reading - judged by the mark's own fov, not the hero's
        boolean underFire = !target.surprisedBy(hero, true)
                && target.canAttackTarget(hero)
                && sees(target, hero.pos);

        //behind a shut door, the mark "seeing" this cell is stale pre-close fov - hold and let it catch up
        if (!underFire && spot != hero.pos && inPosition(hero, target)) {
            return holdPosition(hero);
        }

        if (spot == -1) {
            if (underFire) {
                giveUp("it has me lined up");
                return false;
            }
            //beside a closed door with no better spot: possibly stale sight again, hold rather than give up
            if (besideClosedDoor(hero.pos)) {
                return holdPosition(hero);
            }
            giveUp("nowhere to hide");
            return false;
        }
        if (spot == hero.pos) {
            //already in place, the mark just hasn't lost sight yet
            return holdPosition(hero);
        }
        return hideMove(hero, spot);
    }

    //a mark that keeps every hiding spot in sight isn't worth chasing spots for
    private boolean hideMove( Hero hero, int spot ) {
        if (++hideMoves > MAX_HIDE_MOVES) {
            giveUp("it keeps me in sight");
            return false;
        }
        return issueHandle(hero, name() + "-hide", spot);
    }

    //stay put a turn waiting for the mark; gives up once patience runs out
    private boolean holdPosition( Hero hero ) {
        if (++waits > MAX_WAITS) {
            giveUp("it isn't taking the bait");
            return false;
        }
        Bot.log("ambush: waiting");
        hero.rest(false);
        return true;
    }

    private void giveUp( String why ) {
        Bot.log("ambush: giving up on %s, %s", target.name(), why);
        givenUpOn = target;
        reset();
    }

    private void reset() {
        target = null;
        waits = 0;
        hideMoves = 0;
        lastKnown = -1;
        rearmDoor = rearmFrom = -1;
    }

    //out of the mark's sight and set for a strike: beside a closed door it must
    //come through, or hidden in the open with its walk due to pass within reach
    private boolean hidden( Hero hero, BotPaths.Snapshot s ) {
        if (sees(target, hero.pos)) return false;
        if (besideClosedDoor(hero.pos)) return true;
        return worksAsLosAmbush(hero, target, hero.pos, s, freshFov(target), new HashMap<>());
    }

    //beside a shut door with the mark on its far side, ignoring its possibly-stale fov (unlike hidden())
    private static boolean inPosition( Hero hero, Mob mob ) {
        Level level = Dungeon.level;
        for (int offset : PathFinder.NEIGHBOURS8) {
            int d = hero.pos + offset;
            if (d >= 0 && d < level.length()
                    && level.map[d] == Terrain.DOOR
                    && acrossDoor(mob, d, hero.pos)) {
                return true;
            }
        }
        return false;
    }

    //whether this char's fov covers the cell; a mob's only refreshes on its turn, so up to a turn stale
    private static boolean sees( Char ch, int cell ) {
        return ch.fieldOfView != null
                && ch.fieldOfView.length == Dungeon.level.length()
                && ch.fieldOfView[cell];
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

    //an adjacent open door that would shut if walked over (nothing standing or dropped on it), or -1
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

    //walking distance of the closest hunter; re-arming takes two turns, so it needs a head start
    private int nearestHunterDist( Hero hero, BotPaths.Snapshot s ) {
        int nearest = s.dist[target.pos];
        for (Mob mob : hero.getVisibleEnemies()) {
            if (threat(mob) && s.dist[mob.pos] < nearest) {
                nearest = s.dist[mob.pos];
            }
        }
        return nearest;
    }

    //a hiding spot exists within the justified walk, or a propped-open door
    //beside the hero counts as good as one
    private boolean canSetUp( Hero hero, Mob mob, BotPaths.Snapshot s ) {
        //flails and the like never land surprise hits: no trap pays off
        if (!hero.canSurpriseAttack()) return false;
        if (tooFast(hero, mob)) return false;
        if (ambushSpot(hero, mob, s) != -1) return true;
        return propOpenDoorBeside(hero) != -1 && maxTrek(hero, mob) > 0;
    }

    //a mark acting more often than the hero closes the gap mid-trek and can strike through the door unsurprised
    private static boolean tooFast( Hero hero, Mob mob ) {
        return mob.speed() > Math.min(hero.speed(), 1f);
    }

    //longest walk a trap justifies: one guaranteed hit saves 1/p - 1 turns of misses, a kill up to 1/p more.
    //door-side spots get DOOR_BONUS on top of this, judged where spots are picked
    private static int maxTrek( Hero hero, Mob mob ) {
        float p = hitChance(hero, mob);
        if (p <= 0) return TREK_CAP;
        float saved = 1f / p - 1f;
        if (saved < 1f) return 0; //hit more often than not: no trap
        saved += killChance(hero, mob) / p;
        return (int)Math.min(TREK_CAP, saved);
    }

    //chance the strike kills outright; sneak weapons roll from 75% up their range, armor ignored, roll ~uniform
    private static float killChance( Hero hero, Mob mob ) {
        int min, max;
        KindOfWeapon wep = hero.belongings.attackingWeapon();
        if (wep != null && !RingOfForce.fightingUnarmed(hero)) {
            min = wep.min();
            max = wep.max();
        } else {
            min = 1;
            max = Math.max(hero.STR() - 8, 1);
        }
        float floor = min;
        if (wep instanceof Dagger || wep instanceof Dirk || wep instanceof AssassinsBlade) {
            floor = min + 0.75f * (max - min);
        }
        if (mob.HP <= floor) return 1f;
        if (mob.HP > max) return 0f;
        return (max - mob.HP + 1) / (max - floor + 1);
    }

    //nearest spot worth hiding on, door-side or open-ground. door spots count as
    //DOOR_BONUS steps closer than they are: the strike leaves a doorway
    //chokepoint fight (or a rearmable trap) behind
    private int ambushSpot( Hero hero, Mob mob, BotPaths.Snapshot s ) {
        if (tooFast(hero, mob)) return -1;

        int trek = maxTrek(hero, mob);
        if (trek <= 0) return -1;

        //a mark that can strike from range never walks into reach blind - the
        //moment it regains a line it just shoots, so only a door traps it.
        //judged in the moment: no line right now reads as melee, and a wrong
        //read simply ends in giveUp once it opens fire
        boolean rangedMark = !Dungeon.level.adjacent(hero.pos, mob.pos) && mob.canAttackTarget(hero);

        boolean[] mobFov = freshFov(mob);
        Map<Integer, PathFinder.Path> paths = new HashMap<>();

        Level level = Dungeon.level;
        int best = -1;
        float bestCost = Float.MAX_VALUE;
        for (int c = 0; c < s.dist.length; c++) {
            if (!s.pass[c] || s.hazard[c] || Bot.isBlacklisted(c)) continue;
            if (s.dist[c] - DOOR_BONUS >= bestCost) continue;
            if (s.dist[c] > Math.min(TREK_CAP, trek + DOOR_BONUS)) continue;

            int terrain = level.map[c];
            if (terrain == Terrain.DOOR || terrain == Terrain.OPEN_DOOR) continue;

            float cost;
            if (worksAsDoorAmbush(hero, mob, c, s)) {
                cost = s.dist[c] - DOOR_BONUS;
            } else if (!rangedMark && s.dist[c] <= trek
                    && worksAsLosAmbush(hero, mob, c, s, mobFov, paths)) {
                cost = s.dist[c];
            } else {
                continue;
            }
            if (cost < bestCost) {
                best = c;
                bestCost = cost;
            }
        }
        return best;
    }

    //whether c hides from this mob in the open: out of its next-act fov, with
    //the walk it is expected to make ending within striking reach of c and
    //staying blind to c the whole way - a mark that regains its line mid-walk
    //stops chasing ghosts right where it stands instead of strolling into reach
    private boolean worksAsLosAmbush( Hero hero, Mob mob, int c, BotPaths.Snapshot s,
                                      boolean[] mobFov, Map<Integer, PathFinder.Path> paths ) {
        if (mobFov[c]) return false;

        Level level = Dungeon.level;
        //the mark walks to its last knowledge of the hero: the cell it watches
        //him duck out of sight from, or the remembered one if he is already gone
        int dest = mobFov[hero.pos] ? predecessor(c, s) : lastKnown;
        if (dest == -1 || dest == c || !level.adjacent(dest, c)) return false;

        //already within reach and about to go blind: the strike lands next turn
        if (mob.pos == dest || level.adjacent(mob.pos, c)) return true;

        if (!paths.containsKey(dest)) {
            paths.put(dest, PathFinder.find(mob.pos, dest, level.passable));
        }
        PathFinder.Path path = paths.get(dest);
        //no way over, or not before patience runs out
        if (path == null || path.size() > MAX_WAITS) return false;

        for (int cell : path) {
            if (level.distance(cell, c) <= 1) continue; //in reach: the strike lands first
            if (level.distance(cell, c) <= mob.viewDistance
                    && BotPaths.lineFree(cell, c)) {
                return false;
            }
        }
        return true;
    }

    //the cell before c on the hero's shortest walk there - the last place the
    //mark watches the hero on before he ducks out of sight
    private static int predecessor( int c, BotPaths.Snapshot s ) {
        if (!s.reachable(c) || s.dist[c] == 0) return -1;
        for (int offset : PathFinder.NEIGHBOURS8) {
            int n = c + offset;
            if (n >= 0 && n < s.dist.length && s.pass[n] && s.dist[n] == s.dist[c] - 1) {
                return n;
            }
        }
        return -1;
    }

    private static boolean[] scratchFov;

    //the mob's fov as it will be on its next act, recomputed from where it
    //stands now; its own fieldOfView (see sees()) is up to a turn stale - fine
    //for reading what it saw, useless for predicting what it is about to see
    private static boolean[] freshFov( Mob mob ) {
        if (scratchFov == null || scratchFov.length != Dungeon.level.length()) {
            scratchFov = new boolean[Dungeon.level.length()];
        }
        Dungeon.level.updateFieldOfView(mob, scratchFov);
        return scratchFov;
    }

    //whether c hides from this mob behind an adjacent door
    private static boolean worksAsDoorAmbush( Hero hero, Mob mob, int c, BotPaths.Snapshot s ) {
        Level level = Dungeon.level;
        for (int offset : PathFinder.NEIGHBOURS8) {
            int d = c + offset;
            if (d < 0 || d >= level.length()) continue;

            int terrain = level.map[d];
            boolean closed = terrain == Terrain.DOOR;
            //open but unpropped: it shuts once the hero crosses it
            Char blocker = Actor.findChar(d);
            boolean shuts = terrain == Terrain.OPEN_DOOR
                    && level.heaps.get(d) == null
                    && (blocker == null || blocker == hero);
            if (!closed && !shuts) continue;

            //must be the far side of the door - merely out of the mark's short, stale fov just parades past it
            if (!acrossDoor(mob, d, c)) continue;

            //a shut door the mark can still see past is no hiding place
            if (closed && sees(mob, c)) continue;

            //already settled beside a shut door on the far side: the mark has to come through it blind
            if (closed && c == hero.pos) {
                return true;
            }

            //the hero must cross the door himself (dist[d] < dist[c] means a shortest walk does),
            //without the mark plausibly reaching the doorway strictly first
            boolean beatenToDoor = level.distance(mob.pos, d) / mob.speed()
                    < s.dist[d] / hero.speed();
            if (!beatenToDoor && s.dist[d] < s.dist[c]) {
                return true;
            }
        }
        return false;
    }

    //whether mob and c sit on opposite sides of door d, compared along the axis of the passage it serves
    private static boolean acrossDoor( Mob mob, int d, int c ) {
        Level level = Dungeon.level;
        int w = level.width();
        boolean passageVertical = level.solid[d - 1] && level.solid[d + 1];
        int mobSide, cSide;
        if (passageVertical) {
            mobSide = Integer.signum(mob.pos / w - d / w);
            cSide   = Integer.signum(c / w - d / w);
        } else {
            mobSide = Integer.signum(mob.pos % w - d % w);
            cSide   = Integer.signum(c % w - d % w);
        }
        return mobSide != 0 && cSide != 0 && mobSide != cSide;
    }
}
