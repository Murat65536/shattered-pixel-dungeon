package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Ambush extends BotBrain.Behavior {

    //hard ceiling on the walk to a hiding spot
    private static final int TREK_CAP = 25;
    //extra trek allowance: the strike leaves a doorway chokepoint fight (or a rearmable trap) behind
    private static final float DOOR_BONUS = 2f;
    //fraction of current HP the walk may be expected to cost, like Cover's approach bill
    private static final float MAX_HP_SPEND = 0.25f;
    //patience caps before giving up on a mark
    private static final int MAX_WAITS = 12;
    private static final int MAX_HIDE_MOVES = 12;

    private Mob target = null;
    private Mob givenUpOn = null;
    private int waits = 0;
    private int hideMoves = 0;
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

        //in reach and truly visible - adjacent yet unseen behind the door means attacking would just open it
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
            //aware: standing ground just trades misses - walk behind a door and it will follow through blind
            int spot = ambushSpot(hero, target, s);
            if (spot == -1) {
                giveUp("no door close enough");
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

        if (hidden(hero)) {
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
            giveUp("no door close enough");
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
        rearmDoor = rearmFrom = -1;
    }

    //out of the mark's sight, beside a closed door it will have to come through
    private boolean hidden( Hero hero ) {
        return !sees(target, hero.pos) && besideClosedDoor(hero.pos);
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

    //whether this mob's fov covers the cell; it only refreshes on the mob's turn, so up to a turn stale
    private static boolean sees( Mob mob, int cell ) {
        return mob.fieldOfView != null
                && mob.fieldOfView.length == Dungeon.level.length()
                && mob.fieldOfView[cell];
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

    //a hiding spot exists within the walk this mob's evasion justifies
    static boolean worthAmbushing( Hero hero, Mob mob, BotPaths.Snapshot s ) {
        return ambushSpot(hero, mob, s) != -1;
    }

    //worthAmbushing, plus a propped-open door beside the hero counts like a spot two steps away
    private boolean canSetUp( Hero hero, Mob mob, BotPaths.Snapshot s ) {
        if (tooFast(hero, mob)) return false;
        if (worthAmbushing(hero, mob, s)) return true;
        return propOpenDoorBeside(hero) != -1 && maxTrek(hero, mob) >= 2;
    }

    //a mark acting more often than the hero closes the gap mid-trek and can strike through the door unsurprised
    private static boolean tooFast( Hero hero, Mob mob ) {
        return mob.speed() > Math.min(hero.speed(), 1f);
    }

    //longest walk a trap justifies: one guaranteed hit saves 1/p - 1 turns of misses, a kill up to 1/p more
    static int maxTrek( Hero hero, Mob mob ) {
        float p = hitChance(hero, mob);
        if (p <= 0) return TREK_CAP;
        float saved = 1f / p - 1f;
        if (saved < 1f) return 0; //hit more often than not: no trap
        saved += killChance(hero, mob) / p;
        return (int)Math.min(TREK_CAP, saved + DOOR_BONUS);
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

    private static int ambushSpot( Hero hero, Mob mob, BotPaths.Snapshot s ) {
        if (tooFast(hero, mob)) return -1;

        int trek = maxTrek(hero, mob);
        if (trek <= 0) return -1;

        Level level = Dungeon.level;
        List<Integer> candidates = new ArrayList<>();
        for (int c = 0; c < s.dist.length; c++) {
            if (!s.pass[c] || s.hazard[c] || s.dist[c] > trek || Bot.isBlacklisted(c)) continue;

            int terrain = level.map[c];
            if (terrain == Terrain.DOOR || terrain == Terrain.OPEN_DOOR) continue;

            //cheap prefilter without the route in hand; verified again below
            if (!worksAsAmbush(hero, mob, c, s, null)) continue;

            candidates.add(c);
        }
        //nearest spot whose actual walk crosses the arming door and survives the chasers' reach
        candidates.sort(Comparator.comparingInt(a -> s.dist[a]));
        for (int c : candidates) {
            int[] path = route(c, s);
            if (path == null) continue;
            if (!worksAsAmbush(hero, mob, c, s, path)) continue;
            return c;
        }
        return -1;
    }

    //whether c hides from this mob behind an adjacent door; with a route given, the arming door must lie on it
    private static boolean worksAsAmbush( Hero hero, Mob mob, int c, BotPaths.Snapshot s, int[] path ) {
        Level level = Dungeon.level;
        for (int offset : PathFinder.NEIGHBOURS8) {
            int d = c + offset;
            if (d < 0 || d >= level.length()) continue;

            int terrain = level.map[d];
            boolean closed = terrain == Terrain.DOOR;
            //open but unpropped: it shuts once the hero crosses it
            boolean shuts = terrain == Terrain.OPEN_DOOR
                    && level.heaps.get(d) == null
                    && (Actor.findChar(d) == null || Actor.findChar(d) == hero);
            if (!closed && !shuts) continue;

            //must be the far side of the door - merely out of the mark's short, stale fov just parades past it
            if (!acrossDoor(mob, d, c)) continue;

            //a shut door the mark can still see past is no hiding place
            if (closed && sees(mob, c)) continue;

            //already settled beside a shut door on the far side: the mark has to come through it blind
            if (closed && c == hero.pos) {
                return true;
            }

            //the hero must cross the door himself, without the mark plausibly reaching the doorway strictly first
            boolean beatenToDoor = level.distance(mob.pos, d) / mob.speed()
                    < s.dist[d] / hero.speed();
            if (!beatenToDoor && s.dist[d] < s.dist[c]
                    && (path == null || onRoute(path, d))) {
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

    //the hero's route to a spot, walked backward along the distance map; one shortest route of possibly several
    private static int[] route( int spot, BotPaths.Snapshot s ) {
        int len = s.dist[spot];
        if (len < 0 || len == Integer.MAX_VALUE) return null;
        int[] path = new int[len + 1];
        path[len] = spot;
        int cur = spot;
        for (int i = len - 1; i >= 0; i--) {
            int next = -1;
            for (int offset : PathFinder.NEIGHBOURS8) {
                int n = cur + offset;
                if (n >= 0 && n < s.dist.length && s.dist[n] == i && s.pass[n]) {
                    next = n;
                    break;
                }
            }
            if (next == -1) return null;
            path[i] = next;
            cur = next;
        }
        return path;
    }

    private static boolean onRoute( int[] path, int cell ) {
        for (int p : path) {
            if (p == cell) return true;
        }
        return false;
    }
}
