package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.watabou.utils.PathFinder;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class Ambush extends BotBrain.Behavior {
    private static final int MAX_HIDE_MOVES = 12;

    private Mob target = null;
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
        //drop the mark once it dies, flees, or falls asleep; a wandering mark hunts again soon enough
        if (target != null && (!target.isAlive() || !Dungeon.level.mobs.contains(target)
                || target.state != target.HUNTING && target.state != target.WANDERING)) {
            reset();
        }

        Routes routeMap = null;

        if (target == null) {
            int bestDist = Integer.MAX_VALUE;
            for (Mob mob : hero.getVisibleEnemies()) {
                if (!threat(mob) ||
                        mob.invisible > 0 || //can't be struck (see Fight), so can't be ambushed
                        mob.state != mob.HUNTING ||
                        Bot.isBlacklisted(mob.pos) ||
                        !hero.canAttack(mob) && !s.reachable(mob.pos) ||
                        mob.surprisedBy(hero, true) && (hero.canAttack(mob) || mob.state == mob.SLEEPING)) {
                    continue;
                }
                if (routeMap == null) routeMap = routes(hero, s);
                if (!canSetUp(hero, mob, s, routeMap)) continue;
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
        if (routeMap == null) routeMap = routes(hero, s);

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
                hideMoves = 0;
                lastKnown = -1;
                return issueHandle(hero, name(), struck.pos);
            }
            //aware: standing ground just trades misses - duck out of its sight and it will blunder into reach blind
            int spot = ambushSpot(hero, target, s, routeMap);
            if (spot == -1) {
                return false;
            }
            if (spot != hero.pos) {
                return stageAmbush(hero, target, spot, routeMap);
            }
        }

        for (Mob mob : hero.getVisibleEnemies()) {
            if (mob == target || !threat(mob)) continue;
            //prefer a fight already in reach over sitting in ambush (fellow slippery chasers don't count)
            if (hero.canAttack(mob)
                    && (mob.surprisedBy(hero, true) || !canSetUp(hero, mob, s, routeMap))) {
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

        if (hidden(hero, routeMap)) {
            return holdPosition(hero);
        }

        int spot = ambushSpot(hero, target, s, routeMap);

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
                return false;
            }
            //beside a closed door with no better spot: possibly stale sight again, hold rather than give up
            if (besideClosedDoor(hero.pos)) {
                return holdPosition(hero);
            }
            return false;
        }
        if (spot == hero.pos) {
            //already in place, the mark just hasn't lost sight yet
            return holdPosition(hero);
        }
        return stageAmbush(hero, target, spot, routeMap);
    }

    //wait on the last visible tile, then duck behind the door/obstacle once the mark is in reach
    private boolean stageAmbush( Hero hero, Mob mob, int spot, Routes routes ) {
        int lead = doorAmbushLead(hero, mob, spot, routes);
        if (lead == -1) lead = predecessor(spot, routes);
        if (lead == -1) return false;
        if (hero.pos != lead) {
            int step = nextStep(spot, routes);
            return step != -1 && hideMove(hero, step);
        }
        if (!Dungeon.level.adjacent(hero.pos, mob.pos)) return holdPosition(hero);
        return hideMove(hero, spot);
    }

    //a mark that keeps every hiding spot in sight isn't worth chasing spots for
    private boolean hideMove( Hero hero, int spot ) {
        if (++hideMoves > MAX_HIDE_MOVES) {
            return false;
        }
        return issueHandle(hero, name() + "-hide", spot);
    }

    //stay put a turn waiting for the mark; gives up once patience runs out
    private boolean holdPosition( Hero hero ) {
        Bot.log("ambush: waiting");
        hero.rest(false);
        return true;
    }

    private void reset() {
        target = null;
        hideMoves = 0;
        lastKnown = -1;
        rearmDoor = rearmFrom = -1;
    }

    //out of the mark's sight and set for a strike: beside a closed door it must
    //come through, or hidden in the open with its walk due to pass within reach
    private boolean hidden( Hero hero, Routes routes ) {
        if (sees(target, hero.pos)) return false;
        if (besideClosedDoor(hero.pos)) return true;
        return worksAsLosAmbush(hero, target, hero.pos, routes, freshFov(target), new HashMap<>());
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
    private boolean canSetUp( Hero hero, Mob mob, BotPaths.Snapshot s, Routes routes ) {
        //flails and the like never land surprise hits: no trap pays off
        if (!hero.canSurpriseAttack()) return false;
        if (tooFast(hero, mob)) return false;
        if (ambushSpot(hero, mob, s, routes) != -1) return true;
        return propOpenDoorBeside(hero) != -1;
    }

    //a mark acting more often than the hero closes the gap mid-trek and can strike through the door unsurprised
    private static boolean tooFast( Hero hero, Mob mob ) {
        return mob.speed() > Math.min(hero.speed(), 1f);
    }

    /**
     * Safest spot worth hiding on, nearest when equally safe
     */
    private int ambushSpot(Hero hero, Mob mob, BotPaths.Snapshot s, Routes routes) {

        boolean[] mobFov = freshFov(mob);
        Map<Integer, PathFinder.Path> paths = new HashMap<>();

        Level level = Dungeon.level;
        int best = -1;
        int bestRisk = Integer.MAX_VALUE;
        int bestSteps = Integer.MAX_VALUE;
        int movesLeft = MAX_HIDE_MOVES - hideMoves;
        for (int c = 0; c < s.dist.length; c++) {
            if (!s.pass[c] || s.hazard[c] || Bot.isBlacklisted(c)) continue;
            if (routes.steps[c] > movesLeft
                    || routes.risk[c] > bestRisk
                    || routes.risk[c] == bestRisk && routes.steps[c] >= bestSteps) continue;

            int terrain = level.map[c];
            if (terrain == Terrain.DOOR || terrain == Terrain.OPEN_DOOR) continue;

            if (doorAmbushLead(hero, mob, c, routes) == -1
                    && !worksAsLosAmbush(hero, mob, c, routes, mobFov, paths)) {
                continue;
            }
            best = c;
            bestRisk = routes.risk[c];
            bestSteps = routes.steps[c];
        }
        return best;
    }

    //whether c hides from this mob in the open: out of its next-act fov, with
    //the walk it is expected to make ending within striking reach of c and
    //staying blind to c the whole way - a mark that regains its line mid-walk
    //stops chasing ghosts right where it stands instead of strolling into reach
    private boolean worksAsLosAmbush( Hero hero, Mob mob, int c, Routes routes,
                                      boolean[] mobFov, Map<Integer, PathFinder.Path> paths ) {
        if (mobFov[c]) return false;

        Level level = Dungeon.level;
        //the mark walks to its last knowledge of the hero: the cell it watches
        //him duck out of sight from, or the remembered one if he is already gone
        int dest = mobFov[hero.pos] ? predecessor(c, routes) : lastKnown;
        if (dest == -1 || dest == c || !level.adjacent(dest, c)) return false;

        //already within reach and about to go blind: the strike lands next turn
        if (mob.pos == dest || level.adjacent(mob.pos, c)) return true;

        if (!paths.containsKey(dest)) {
            paths.put(dest, PathFinder.find(mob.pos, dest, level.passable));
        }
        PathFinder.Path path = paths.get(dest);
        //no way over, or not before patience runs out
        if (path == null) return false;

        for (int cell : path) {
            if (level.distance(cell, c) <= 1) continue; //in reach: the strike lands first
            if (level.distance(cell, c) <= mob.viewDistance
                    && BotPaths.lineFree(cell, c)) {
                return false;
            }
        }
        return true;
    }

    //the cell before c on the hero's safest walk there - the last place the
    //mark watches the hero on before he ducks out of sight
    private static int predecessor( int c, Routes routes ) {
        return routes.steps[c] == 0 || routes.steps[c] == Integer.MAX_VALUE
                ? -1 : routes.previous[routes.steps[c]][c];
    }

    //the first cell on the safest route from the hero to c
    private static int nextStep( int c, Routes routes ) {
        if (routes.steps[c] == 0 || routes.steps[c] == Integer.MAX_VALUE) return -1;
        int step = routes.steps[c];
        while (step > 1) {
            c = routes.previous[step][c];
            step--;
        }
        return c;
    }

    //all safest routes from the hero: fewest melee attack opportunities first,
    //then fewest steps. rebuilt every turn so moving hunters change the route
    private Routes routes( Hero hero, BotPaths.Snapshot s ) {
        Level level = Dungeon.level;
        int length = level.length();
        int[] danger = new int[length];
        for (Mob mob : hero.getVisibleEnemies()) {
            if (!threat(mob) || mob.state != mob.HUNTING || mob.invisible > 0) continue;
            for (int offset : PathFinder.NEIGHBOURS8) {
                int c = mob.pos + offset;
                if (c >= 0 && c < length && level.adjacent(mob.pos, c)) danger[c]++;
            }
        }

        int maxSteps = Math.max(0, MAX_HIDE_MOVES - hideMoves);
        Routes routes = new Routes(length, maxSteps);
        int[][] risks = new int[maxSteps + 1][length];
        for (int[] row : risks) Arrays.fill(row, Integer.MAX_VALUE);
        risks[0][hero.pos] = 0;

        for (int step = 1; step <= maxSteps; step++) {
            for (int cell = 0; cell < length; cell++) {
                if (risks[step - 1][cell] == Integer.MAX_VALUE) continue;
                for (int offset : PathFinder.NEIGHBOURS8) {
                    int next = cell + offset;
                    if (next < 0 || next >= length
                            || !level.adjacent(cell, next) || !s.pass[next]) continue;
                    int risk = risks[step - 1][cell] + danger[next];
                    if (risk < risks[step][next]) {
                        risks[step][next] = risk;
                        routes.previous[step][next] = cell;
                    }
                }
            }
        }

        for (int cell = 0; cell < length; cell++) {
            for (int step = 0; step <= maxSteps; step++) {
                if (risks[step][cell] < routes.risk[cell]) {
                    routes.risk[cell] = risks[step][cell];
                    routes.steps[cell] = step;
                }
            }
        }
        return routes;
    }

    private static class Routes {
        final int[] risk;
        final int[] steps;
        final int[][] previous;

        Routes( int length, int maxSteps ) {
            risk = new int[length];
            steps = new int[length];
            previous = new int[maxSteps + 1][length];
            Arrays.fill(risk, Integer.MAX_VALUE);
            Arrays.fill(steps, Integer.MAX_VALUE);
            for (int[] row : previous) Arrays.fill(row, -1);
        }
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

    //the adjacent door c hides behind, or -1 when c is not a door ambush
    private static int doorAmbushLead( Hero hero, Mob mob, int c, Routes routes ) {
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
                return d;
            }

            //the hero must cross the door himself on the chosen route,
            //without the mark plausibly reaching the doorway strictly first
            if (predecessor(c, routes) != d) continue;
            boolean beatenToDoor = level.distance(mob.pos, d) / mob.speed()
                    < (routes.steps[c] - 1) / hero.speed();
            if (!beatenToDoor) {
                return d;
            }
        }
        return -1;
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
