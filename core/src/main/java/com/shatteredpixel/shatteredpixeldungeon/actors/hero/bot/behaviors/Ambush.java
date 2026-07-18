package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.CloakOfShadows;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Ambush extends BotBrain.Behavior {
    private static final int MAX_ROUTE_STEPS = 12;
    private static final int COST_SCALE = 100;
    private static final float CLOAK_CHARGE_PENALTY = 0.10f;

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
        CloakOfShadows cloak = hero.belongings.getItem(CloakOfShadows.class);
        boolean canCloak = hero.invisible == 0 && hero.canSurpriseAttack()
                && cloak != null && cloak.actions(hero).contains(CloakOfShadows.AC_STEALTH);

        List<Mob> hunters = new ArrayList<>();
        for (Mob mob : hero.getVisibleEnemies()) {
            if (threat(mob) && mob.state == mob.HUNTING && mob.invisible == 0) hunters.add(mob);
        }

        BotPaths.RouteMap routeMap = null;
        Mob target = null;
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
            if (routeMap == null) routeMap = routes(hero, s, hunters);
            if (!(canCloak && sees(hero, mob.pos) && hero.canAttack(mob))
                    && !canSetUp(hero, mob, s, routeMap)) continue;
            int dist = s.dist[mob.pos];
            if (dist < bestDist) {
                bestDist = dist;
                target = mob;
            }
        }
        if (target == null) return false;
        //in reach and truly visible - adjacent yet unseen behind the door means attacking would just open it
        if (sees(hero, target.pos) && (hero.canAttack(target) || Dungeon.level.adjacent(hero.pos, target.pos))) {
            //unaware: spring the trap, the hit is guaranteed
            if (target.surprisedBy(hero, true)) {
                return issueHandle(hero, name(), target.pos);
            }
            //aware: standing ground just trades misses - duck out of its sight and it will blunder into reach blind
            int spot = ambushSpot(hero, target, s, routeMap);
            float walkCost = spot == -1 ? Float.POSITIVE_INFINITY
                    : routeMap.risk[spot] / (float) COST_SCALE;
            float cloakCost = canCloak ? hero.HT * CLOAK_CHARGE_PENALTY
                    : Float.POSITIVE_INFINITY;
            if (cloakCost < walkCost) {
                return Bot.requestUse(cloak, CloakOfShadows.AC_STEALTH, null, "cloak ambush");
            }
            if (spot == -1) return false;
            if (spot != hero.pos) {
                return stageAmbush(hero, target, spot, routeMap);
            }
        }

        for (Mob mob : hero.getVisibleEnemies()) {
            if (mob == target || !threat(mob)) continue;
            //never walk away from a guaranteed hit; aware attackers are priced into the route below
            if (hero.canAttack(mob) && mob.surprisedBy(hero, true)) {
                return false;
            }
        }

        if (hidden(hero, target, routeMap)) {
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
            if (inPosition(hero, target)) {
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
    private boolean stageAmbush( Hero hero, Mob mob, int spot, BotPaths.RouteMap routes ) {
        int lead = doorAmbushLead(hero, mob, spot, routes);
        if (lead == -1) lead = BotPaths.predecessor(spot, routes);
        if (lead == -1) return false;
        if (hero.pos != lead) {
            int step = BotPaths.nextStep(spot, routes);
            return step != -1 && issueHandle(hero, name() + "-hide", step);
        }
        if (!Dungeon.level.adjacent(hero.pos, mob.pos)) return holdPosition(hero);
        return issueHandle(hero, name() + "-hide", spot);
    }

    //stay put a turn waiting for the mark, but never give a hunter a free shot
    private boolean holdPosition( Hero hero ) {
        for (Mob mob : hero.getVisibleEnemies()) {
            //cached fov may predate a just-closed door; judge the mob's next act
            if (threat(mob) && mob.state == mob.HUNTING
                    && mob.canAttackTarget(hero) && freshFov(mob)[hero.pos]) {
                return false;
            }
        }
        Bot.log("ambush: waiting");
        hero.rest(false);
        return true;
    }

    //out of the mark's sight and set for a strike: beside a closed door it must
    //come through, or hidden in the open with its walk due to pass within reach
    private boolean hidden( Hero hero, Mob mob, BotPaths.RouteMap routes ) {
        if (sees(mob, hero.pos)) return false;
        if (inPosition(hero, mob)) return true;
        return worksAsLosAmbush(mob, hero.pos, routes, new HashMap<>(), new HashMap<>());
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

    //a hiding spot exists within the bounded walk
    private boolean canSetUp( Hero hero, Mob mob, BotPaths.Snapshot s, BotPaths.RouteMap routes ) {
        //flails and the like never land surprise hits: no trap pays off
        if (!hero.canSurpriseAttack()) return false;
        if (tooFast(hero, mob)) return false;
        return ambushSpot(hero, mob, s, routes) != -1;
    }

    //a mark acting more often than the hero closes the gap mid-trek and can strike through the door unsurprised
    private static boolean tooFast( Hero hero, Mob mob ) {
        return mob.speed() > Math.min(hero.speed(), 1f);
    }

    /**
     * Safest spot worth hiding on, nearest when equally safe
     */
    private int ambushSpot(Hero hero, Mob mob, BotPaths.Snapshot s, BotPaths.RouteMap routes) {

        Map<Integer, PathFinder.Path> paths = new HashMap<>();
        Map<Integer, boolean[]> fovs = new HashMap<>();

        Level level = Dungeon.level;
        float heroSpeed = hero.speed();
        float mobSpeed = mob.speed();
        int best = -1;
        int bestRisk = Integer.MAX_VALUE;
        int bestSteps = Integer.MAX_VALUE;
        for (int c = 0; c < s.dist.length; c++) {
            if (!s.pass[c] || s.hazard[c] || Bot.isBlacklisted(c)) continue;
            if (routes.steps[c] > MAX_ROUTE_STEPS
                    || routes.risk[c] > bestRisk
                    || routes.risk[c] == bestRisk && routes.steps[c] >= bestSteps) continue;
            if (mobReachesFirst(mob, c, routes.steps[c], heroSpeed, mobSpeed)) continue;

            int terrain = level.map[c];
            if (terrain == Terrain.DOOR || terrain == Terrain.OPEN_DOOR) continue;

            if (doorAmbushLead(hero, mob, c, routes) == -1
                    && !worksAsLosAmbush(mob, c, routes, paths, fovs)) {
                continue;
            }
            best = c;
            bestRisk = routes.risk[c];
            bestSteps = routes.steps[c];
        }
        return best;
    }

    //the mark's straight-line best case must not beat the hero to the actual hiding cell
    static boolean mobReachesFirst( Mob mob, int spot, int heroSteps,
                                    float heroSpeed, float mobSpeed ) {
        return Dungeon.level.distance(mob.pos, spot) / mobSpeed
                < heroSteps / heroSpeed;
    }

    //whether c hides from this mob in the open: out of its next-act fov, with
    //the walk it is expected to make ending within striking reach of c and
    //staying blind to c the whole way - a mark that regains its line mid-walk
    //stops chasing ghosts right where it stands instead of strolling into reach
    private boolean worksAsLosAmbush( Mob mob, int c, BotPaths.RouteMap routes,
                                      Map<Integer, PathFinder.Path> paths,
                                      Map<Integer, boolean[]> fovs ) {
        if (fovs.computeIfAbsent(mob.pos, pos -> freshFov(mob, pos))[c]) return false;

        Level level = Dungeon.level;
        int dest = BotPaths.predecessor(c, routes);
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
                    && fovs.computeIfAbsent(cell, pos -> freshFov(mob, pos))[c]) {
                return false;
            }
        }
        return true;
    }

    //all safest routes from the hero, scored in hundredths of expected HP lost.
    //each step pays for every melee swing or ranged shot that can land there,
    //plus the same HT/1000 per turn hunger eventually costs while starving
    //ponytail: projects current attack geometry; simulate mob turns only if per-step replanning falls short
    private BotPaths.RouteMap routes( Hero hero, BotPaths.Snapshot s, List<Mob> hunters ) {
        Level level = Dungeon.level;
        int length = level.length();
        int[] danger = new int[length];
        float moveTime = 1f / hero.speed();
        int hungerCost = Math.max(1, Math.round(moveTime * hero.HT / 1000f * COST_SCALE));
        for (int c = 0; c < length; c++) danger[c] = hungerCost;

        for (Mob mob : hunters) {
            boolean ranged = !level.adjacent(mob.pos, hero.pos)
                    && mob.canAttackTarget(hero);
            int meleeCost = attackCost(mob, hero, moveTime, false);
            int rangedCost = attackCost(mob, hero, moveTime, true);
            for (int c = 0; c < length; c++) {
                if (level.adjacent(mob.pos, c)) {
                    danger[c] += meleeCost;
                } else if (ranged && level.distance(mob.pos, c) <= mob.viewDistance
                        && BotPaths.lineFree(mob.pos, c)) {
                    danger[c] += rangedCost;
                }
            }
        }

        return BotPaths.safestRoutes(s, hero.pos, MAX_ROUTE_STEPS, danger);
    }

    private static int attackCost( Mob mob, Hero hero, float time, boolean ranged ) {
        float accuracy = Char.hitChance(mob, hero, ranged ? 2f : 1f);
        return Math.max(1, Math.round(time / mob.attackDelay()
                * accuracy * avgDamage(mob) * COST_SCALE));
    }

    //the mob's fov as it will be on its next act, recomputed from where it
    //stands now; its own fieldOfView (see sees()) is up to a turn stale - fine
    //for reading what it saw, useless for predicting what it is about to see
    private static boolean[] freshFov( Mob mob ) {
        boolean[] fov = new boolean[Dungeon.level.length()];
        Dungeon.level.updateFieldOfView(mob, fov);
        return fov;
    }

    //predict the same sight the mob will have after walking to pos
    static boolean[] freshFov( Mob mob, int pos ) {
        int actualPos = mob.pos;
        try {
            mob.pos = pos;
            return freshFov(mob);
        } finally {
            mob.pos = actualPos;
        }
    }

    //the adjacent door c hides behind, or -1 when c is not a door ambush
    private static int doorAmbushLead( Hero hero, Mob mob, int c, BotPaths.RouteMap routes ) {
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

            //the hero must cross the door himself on the chosen route; the race
            //to the actual hiding cell was already checked by ambushSpot
            if (BotPaths.predecessor(c, routes) != d) continue;
            return d;
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
