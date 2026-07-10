package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
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
                || target.state != target.HUNTING && target.state != target.WANDERING)) {
            target = null;
            waits = 0;
            hideMoves = 0;
            rearmDoor = rearmFrom = -1;
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
            int spot = ambushSpot(hero, target, s);
            if (spot == -1) {
                giveUp("no door close enough");
                return false;
            }
            if (spot != hero.pos) {
                return hideMove(hero, spot);
            }
        }

        //never sit in ambush while something better fought head-on is in reach;
        //fellow slippery chasers don't count, or wraith packs would break the plan.
        //but a chaser that is aware and already in position to strike is past
        //ambushing - resting just eats its hits, so step aside and let the
        //fighting behaviors deal with it (pack-mates coming through the door
        //blind stay harmless: they can't see the hero, so they can't attack)
        for (Mob mob : hero.getVisibleEnemies()) {
            if (mob == target || !threat(mob)) continue;
            if (hero.canAttack(mob)
                    && (mob.surprisedBy(hero, true) || !canSetUp(hero, mob, s))) {
                return false;
            }
            if (!mob.surprisedBy(hero, true) && mob.canAttackTarget(hero)
                    && sees(mob, hero.pos)) {
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

        int spot = ambushSpot(hero, target, s);
        if (spot == -1) {
            //beside a closed door with no better spot: the mark "seeing" this cell
            //may just be stale sight from before the door shut (a mob's fov only
            //refreshes on its own turn); hold position rather than give up.
            //but a mark that is aware and lined up to strike right now is no
            //stale reading - sitting still would just eat hits. judged by the
            //mark's own sight, not the hero's: it can be hitting from a cell
            //the hero can't see (fov is not symmetric around door corners)
            boolean underFire = !target.surprisedBy(hero, true)
                    && target.canAttackTarget(hero)
                    && sees(target, hero.pos);
            if (underFire) {
                giveUp("it has me lined up");
                return false;
            }
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
        return !sees(target, hero.pos) && besideClosedDoor(hero.pos);
    }

    //whether this mob's field of view covers the cell; a mob's fov only
    //refreshes on its own turn, so this can be up to one turn stale
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

    //like worthAmbushing, but a propped-open door beside the hero also counts:
    //re-arming it takes two turns, the same as a hiding spot two steps away
    private boolean canSetUp( Hero hero, Mob mob, BotPaths.Snapshot s ) {
        if (tooFast(hero, mob)) return false;
        if (worthAmbushing(hero, mob, s)) return true;
        return propOpenDoorBeside(hero) != -1;
    }

    //a mark that acts more often than the hero breaks the plan twice over: it
    //closes the gap during the trek, and it can step through the door and still
    //attack before the hero's turn - the surprise strike is no longer guaranteed.
    //the trek runs at hero.speed(), but the wait beside the door is always one
    //turn per rest, so the slower of the two paces is what the mark must not beat
    private static boolean tooFast( Hero hero, Mob mob ) {
        return mob.speed() > Math.min(hero.speed(), 1f);
    }

    private static int ambushSpot(Hero hero, Mob mob, BotPaths.Snapshot s ) {
        if (tooFast(hero, mob)) return -1;

        Level level = Dungeon.level;
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int c = 0; c < s.dist.length; c++) {
            if (!s.pass[c] || s.hazard[c] || s.dist[c] >= bestDist || Bot.isBlacklisted(c)) continue;

            int terrain = level.map[c];
            if (terrain == Terrain.DOOR || terrain == Terrain.OPEN_DOOR) continue;

            if (!worksAsAmbush(hero, mob, c, s)) continue;

            best = c;
            bestDist = s.dist[c];
        }
        return bestDist <= TREK_CAP ? best : -1;
    }

    private static boolean worksAsAmbush( Hero hero, Mob mob, int c, BotPaths.Snapshot s ) {
        Level level = Dungeon.level;
        for (int offset : PathFinder.NEIGHBOURS8) {
            int d = c + offset;
            if (d < 0 || d >= level.length()) continue;

            int terrain = level.map[d];
            if (terrain == Terrain.DOOR) {
                //door already shut: hidden means simply out of the mob's sight
                if (mob.fieldOfView == null || mob.fieldOfView.length != level.length()
                        || !mob.fieldOfView[c]) {
                    return true;
                }
            } else if (terrain == Terrain.OPEN_DOOR
                    && level.heaps.get(d) == null
                    && (Actor.findChar(d) == null || Actor.findChar(d) == hero)) {
                //open but nothing propping it: it shuts once the hero crosses it.
                //hidden means the far side of the door from the mob - and "side"
                //is set by the wall the door sits in, so compare along the
                //passage axis only. walls east/west of the door mean the passage
                //runs north-south (sides split by y); otherwise it runs east-west
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
                //the race for the door: the hero must cross it and step off (only
                //then does it shut) before the mark can be there. the mark's
                //straight-line steps are the best it could possibly manage, so
                //beating that bound means winning for sure; a tie loses - the
                //mark reaches the doorway with the hero still in sight
                boolean doorShutsFirst = level.distance(mob.pos, d) / mob.speed()
                        > s.dist[c] / hero.speed();
                if (mobSide != 0 && cSide != 0 && mobSide != cSide
                        && doorShutsFirst && s.dist[d] < s.dist[c]) {
                    return true;
                }
            }
        }
        return false;
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
