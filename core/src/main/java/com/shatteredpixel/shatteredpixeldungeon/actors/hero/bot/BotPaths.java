/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blizzard;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ConfusionGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.CorrosiveGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Electricity;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Freezing;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.GooWarn;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Inferno;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ParalyticGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.StenchGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Piranha;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SentryRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.ToxicGasRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.watabou.utils.PathFinder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

//map queries for the bot: what is reachable, where is unexplored, where are the stairs.
//actor thread only, like all other PathFinder users
public class BotPaths {

	public static class Snapshot {
		public final boolean[] pass;
		public final int[] dist;
		//cells covered by a gas or cloud that hurts to stand in (see HAZARD_BLOBS)
		public final boolean[] hazard;
		//locked doors and exits the hero knows about
		public final List<Integer> lockedDoors;

		Snapshot( boolean[] pass, int[] dist, boolean[] hazard, List<Integer> lockedDoors ) {
			this.pass = pass;
			this.dist = dist;
			this.hazard = hazard;
			this.lockedDoors = lockedDoors;
		}

		public boolean reachable(int cell) {
			return dist[cell] < Integer.MAX_VALUE;
		}
	}

	//the lowest-risk route to every cell, with ties favoring fewer steps
	public static class RouteMap {
		public final int[] risk;
		public final int[] steps;
		public final int[][] previous;

		RouteMap( int length, int maxSteps ) {
			risk = new int[length];
			steps = new int[length];
			previous = new int[maxSteps + 1][length];
			Arrays.fill(risk, Integer.MAX_VALUE);
			Arrays.fill(steps, Integer.MAX_VALUE);
			for (int[] row : previous) Arrays.fill(row, -1);
		}
	}

	public static RouteMap safestRoutes( Snapshot s, int start, int maxSteps, int[] danger ) {
		RouteMap routes = new RouteMap(s.dist.length, maxSteps);
		int[][] risks = new int[maxSteps + 1][s.dist.length];
		for (int[] row : risks) Arrays.fill(row, Integer.MAX_VALUE);
		risks[0][start] = 0;

		for (int step = 1; step <= maxSteps; step++) {
			for (int cell = 0; cell < s.dist.length; cell++) {
				if (risks[step - 1][cell] == Integer.MAX_VALUE) continue;
				for (int offset : PathFinder.NEIGHBOURS8) {
					int next = cell + offset;
					if (next < 0 || next >= s.dist.length
							|| !Dungeon.level.adjacent(cell, next) || !s.pass[next]) continue;
					int risk = risks[step - 1][cell] + danger[next];
					if (risk < risks[step][next]) {
						risks[step][next] = risk;
						routes.previous[step][next] = cell;
					}
				}
			}
		}

		for (int cell = 0; cell < s.dist.length; cell++) {
			for (int step = 0; step <= maxSteps; step++) {
				if (risks[step][cell] < routes.risk[cell]) {
					routes.risk[cell] = risks[step][cell];
					routes.steps[cell] = step;
				}
			}
		}
		return routes;
	}

	public static int predecessor( int cell, RouteMap routes ) {
		return routes.steps[cell] == 0 || routes.steps[cell] == Integer.MAX_VALUE
				? -1 : routes.previous[routes.steps[cell]][cell];
	}

	public static int nextStep( int cell, RouteMap routes ) {
		if (routes.steps[cell] == 0 || routes.steps[cell] == Integer.MAX_VALUE) return -1;
		int step = routes.steps[cell];
		while (step > 1) {
			cell = routes.previous[step][cell];
			step--;
		}
		return cell;
	}

	//distances from the hero over cells that are passable and already known,
	//mirroring the mask Hero.getCloser walks with
	static Snapshot snapshot( Hero hero ) {
		Level level = Dungeon.level;
		int length = level.length();
		boolean[] pass = new boolean[length];
		boolean[] hazard = hazards(hero);
		List<Integer> lockedDoors = new ArrayList<>();
		for (int i = 0; i < length; i++) {
			boolean known = level.visited[i] || level.mapped[i];
			pass[i] = level.passable[i] && known && !hazard[i] && !Bot.isBlacklisted(i);
			if (known && (level.map[i] == Terrain.LOCKED_DOOR
					|| level.map[i] == Terrain.CRYSTAL_DOOR
					|| level.map[i] == Terrain.LOCKED_EXIT)) {
				lockedDoors.add(i);
			}
		}

		//a SentryRoom turret is no enemy at all (a neutral, invulnerable NPC), so
		//no fight behavior will ever see it - it simply executes whoever lingers
		//on its watched carpet. those cells are walled off outright: hazard alone
		//wouldn't help, since the treasure past them sits on a safe pedestal and
		//only the walk is lethal. dashing in under haste is a future project
		boolean[] watched = sentryWatched(level);
		if (watched != null && !watched[hero.pos]) {
			for (int i = 0; i < length; i++) {
				if (watched[i]) {
					hazard[i] = true;
					pass[i] = false;
				}
			}
		} else if (watched != null) {
			//already standing in the zone (the bot never walks in, but the player
			//may have): keep it walkable so the way out exists, just marked
			for (int i = 0; i < length; i++) {
				if (watched[i]) hazard[i] = true;
			}
		}

		//the hero may be standing somewhere the mask excludes (e.g. a just-revealed trap)
		pass[hero.pos] = true;

		//the hero cannot walk through chars he can see (Dungeon.findPassable blocks
		//their cells), so a mob plugging a corridor really cuts off everything
		//behind it. a snapshot that ignored that kept issuing walks the engine
		//refuses - they spend no game time and just trip the no-progress guard
		List<Integer> charCells = new ArrayList<>();
		for (Char ch : Actor.chars()) {
			if (ch != hero && level.heroFOV[ch.pos] && pass[ch.pos]) {
				pass[ch.pos] = false;
				charCells.add(ch.pos);
			}
		}

		PathFinder.buildDistanceMap(hero.pos, pass);
		int[] dist = PathFinder.distance.clone();

		//chars still make valid walk targets (closing to melee walks at a mob;
		//the engine allows that by seeding its path scan on the destination), so
		//their cells keep the distance of stepping in from the best approach.
		//a mob standing directly behind another stays unreachable, as it should
		for (int c : charCells) {
			int best = Integer.MAX_VALUE;
			for (int offset : PathFinder.NEIGHBOURS8) {
				int n = c + offset;
				if (n >= 0 && n < length && dist[n] < best) {
					best = dist[n];
				}
			}
			if (best < Integer.MAX_VALUE) dist[c] = best + 1;
		}

		return new Snapshot(pass, dist, hazard, lockedDoors);
	}

	//the first safe step toward a destination. non-walkable targets such as locked
	//doors route to their nearest reachable neighbor; callers interact once adjacent
	static int nextStepTo( int target, Snapshot s ) {
		Level level = Dungeon.level;
		if (target < 0 || target >= s.dist.length) return -1;

		int cell = target;
		if (!s.reachable(cell)) {
			int destination = cell;
			int bestDist = Integer.MAX_VALUE;
			for (int offset : PathFinder.NEIGHBOURS8) {
				int neighbor = destination + offset;
				if (neighbor >= 0 && neighbor < s.dist.length
						&& level.adjacent(destination, neighbor) && s.dist[neighbor] < bestDist) {
					cell = neighbor;
					bestDist = s.dist[neighbor];
				}
			}
			if (bestDist == Integer.MAX_VALUE) return -1;
		}

		while (s.dist[cell] > 1) {
			int previous = -1;
			for (int offset : PathFinder.NEIGHBOURS8) {
				int neighbor = cell + offset;
				if (neighbor >= 0 && neighbor < s.dist.length
						&& level.adjacent(cell, neighbor)
						&& s.dist[neighbor] == s.dist[cell] - 1) {
					previous = neighbor;
					break;
				}
			}
			if (previous == -1) return -1;
			cell = previous;
		}
		return s.dist[cell] == 1 ? cell : -1;
	}

	//cells a discovered SentryRoom sentry would zap the hero on, mirroring its
	//trigger: standing on the room's carpet (EMPTY_SP) in its line of sight.
	//null when this floor has no discovered sentry
	private static boolean[] sentryWatched( Level level ) {
		boolean[] watched = null;
		for (Mob mob : level.mobs) {
			if (!(mob instanceof SentryRoom.Sentry)) continue;
			//not discovered yet: the bot doesn't act on turrets it hasn't seen
			if (!level.visited[mob.pos] && !level.mapped[mob.pos]) continue;
			for (int c = 0; c < level.length(); c++) {
				if (level.map[c] != Terrain.EMPTY_SP) continue;
				if (level.distance(mob.pos, c) > mob.viewDistance) continue;
				if (!lineFree(mob.pos, c, -1)) continue;
				if (watched == null) watched = new boolean[level.length()];
				watched[c] = true;
			}
		}
		return watched;
	}

	//blobs that damage or disable whoever stands in them; benign ones (smoke,
	//storm clouds, regrowth) are not listed and get walked through freely
	private static final List<Class<? extends Blob>> HAZARD_BLOBS = Arrays.asList(
			ToxicGas.class, CorrosiveGas.class, ConfusionGas.class, ParalyticGas.class,
			StenchGas.class, Electricity.class, Fire.class, Inferno.class,
			Blizzard.class, Freezing.class, GooWarn.class );

	//where hazardous blobs sit, limited to what the hero can actually see or
	//remembers seeing - gas in a never-seen corridor is not knowledge the bot
	//should act on, but gas that merely left his sight is not gone either. cells
	//out of view carry the remembered cloud, decayed forward each turn by the
	//same rule real gas disperses by (see decayGasMemory), so they stay off-limits
	//about as long as the real cloud lasts and then free up on their own. the
	//hero's own cell always counts as seen: whatever is there, he can feel
	private static boolean[] hazards( Hero hero ) {
		Level level = Dungeon.level;
		int length = level.length();
		boolean[] hazard = new boolean[length];
		int[] strength = new int[length];
		for (Class<? extends Blob> type : HAZARD_BLOBS) {
			Blob blob = level.blobs.get(type);
			if (blob == null || blob.volume <= 0 || blob.cur == null) continue;
			for (int c = 0; c < length; c++) {
				if (blob.cur[c] > 0 && (level.heroFOV[c] || c == hero.pos)) {
					hazard[c] = true;
					strength[c] = Math.max(strength[c], blob.cur[c]);
				}
			}
		}

		int[] memory = Bot.gasMemory(length);
		boolean[] vents = Bot.gasVents();
		decayGasMemory(level, memory, vents);

		//truth beats prediction wherever the hero can see; elsewhere whatever is
		//left of the remembered cloud keeps its cells off-limits
		Blob venting = level.blobs.get(ToxicGasRoom.ToxicGasSeed.class);
		boolean ventsLive = venting != null && venting.volume > 0 && venting.cur != null;
		for (int c = 0; c < length; c++) {
			if (level.heroFOV[c] || c == hero.pos) {
				memory[c] = strength[c];
				if (ventsLive && venting.cur[c] > 0) vents[c] = true;
			} else if (memory[c] > 0) {
				hazard[c] = true;
			}
		}
		return hazard;
	}

	//gas a toxic vault's vents keep themselves topped up to: ToxicGasRoom seeds
	//12 more whenever a vent cell's gas drops to 9x12 or less
	private static final int VENT_GAS = 108;

	//advance the remembered cloud to the current game time: one step per whole
	//elapsed turn of the rule Blob.evolve spreads and fades gas by - each open
	//cell becomes the average of itself and its open cardinal neighbors, minus
	//one. known vault vents re-gas themselves, like the real thing, so a seen
	//ToxicGasRoom stays remembered as hazardous forever; everything else thins
	//out and expires on roughly the clock the real cloud does. fire, freezing
	//and electricity really fade by flat -1 a turn instead of dispersing, but
	//they are weak and short-lived and this errs on the careful side for them
	private static void decayGasMemory( Level level, int[] memory, boolean[] vents ) {
		int steps = (int)(Actor.now() - Bot.gasSimTime());
		if (steps <= 0) return;
		Bot.gasSimAdvanced(steps);
		//normally 1-2; a long gap only happens when the bot was toggled off for a
		//while, and by 100 steps the field has either emptied (the loop below
		//breaks) or settled into the steady state its vents hold it at
		steps = Math.min(steps, 100);

		int length = level.length();
		int w = level.width();
		boolean[] solid = level.solid;
		for (int step = 0; step < steps; step++) {
			int volume = 0;
			int[] next = new int[length];
			for (int c = w; c < length - w; c++) {
				int x = c % w;
				if (x == 0 || x == w-1 || solid[c]) continue;
				int count = 1;
				int sum = memory[c];
				if (!solid[c-1]) { sum += memory[c-1]; count++; }
				if (!solid[c+1]) { sum += memory[c+1]; count++; }
				if (!solid[c-w]) { sum += memory[c-w]; count++; }
				if (!solid[c+w]) { sum += memory[c+w]; count++; }
				int value = sum >= count ? (sum / count) - 1 : 0;
				if (vents[c]) value = Math.max(value, VENT_GAS);
				next[c] = value;
				volume += value;
			}
			System.arraycopy(next, 0, memory, 0, length);
			if (volume == 0) break;
		}
	}

	//nearest reachable cell clear of hazardous blobs, ties broken toward cells
	//with fewer gassy neighbors (gas spreads; the edge of a cloud won't stay
	//clear for long). -1 when the whole known floor is covered
	public static int safeCell(Hero hero, Snapshot s) {
		int best = -1;
		int bestDist = Integer.MAX_VALUE;
		int bestAdj = Integer.MAX_VALUE;
		for (int c = 0; c < s.dist.length; c++) {
			if (c == hero.pos || !s.pass[c] || s.hazard[c] || Bot.isBlacklisted(c)) continue;
			if (!s.reachable(c) || s.dist[c] > bestDist) continue;
			if (Actor.findChar(c) != null) continue;
			int adj = 0;
			for (int offset : PathFinder.NEIGHBOURS8) {
				int n = c + offset;
				if (n >= 0 && n < s.dist.length && s.hazard[n]) adj++;
			}
			if (s.dist[c] < bestDist || adj < bestAdj) {
				best = c;
				bestDist = s.dist[c];
				bestAdj = adj;
			}
		}
		return best;
	}

	//nearest reachable water cell to wash a debuff off in: walkable, safe, free,
	//and not within lunging range of a visible piranha (they swim at double speed;
	//wading in next to one trades the debuff for something worse). -1 when none;
	//whether the nearest one is close enough to be worth it is the caller's call
	public static int waterCell( Hero hero, Snapshot s ) {
		Level level = Dungeon.level;
		List<Mob> piranhas = new ArrayList<>();
		for (Mob mob : level.mobs) {
			if (mob instanceof Piranha && mob.isAlive() && level.heroFOV[mob.pos]) {
				piranhas.add(mob);
			}
		}
		int best = -1;
		int bestDist = Integer.MAX_VALUE;
		for (int c = 0; c < s.dist.length; c++) {
			if (!level.water[c] || c == hero.pos) continue;
			if (!s.pass[c] || s.hazard[c] || s.dist[c] >= bestDist
					|| Bot.isBlacklisted(c)) continue;
			if (Actor.findChar(c) != null) continue;
			boolean risky = false;
			for (Mob piranha : piranhas) {
				if (level.distance(piranha.pos, c) <= 6) {
					risky = true;
					break;
				}
			}
			if (risky) continue;
			best = c;
			bestDist = s.dist[c];
		}
		return best;
	}

	//nearest known cell from which something unexplored could be seen, or -1
	public static int nearestFrontier(Hero hero, Snapshot s) {
		Level level = Dungeon.level;
		int best = -1;
		int bestDist = Integer.MAX_VALUE;
		for (int c = 0; c < s.dist.length; c++) {
			if (c == hero.pos || !s.pass[c] || s.hazard[c] || s.dist[c] >= bestDist
					|| Bot.isBlacklisted(c)) {
				continue;
			}
			for (int offset : PathFinder.NEIGHBOURS8) {
				int n = c + offset;
				if (n >= 0 && n < s.dist.length
						&& !level.visited[n] && !level.mapped[n] && level.discoverable[n]) {
					best = c;
					bestDist = s.dist[c];
					break;
				}
			}
		}
		return best;
	}

	//cell of this level's regular exit, or -1. deliberately not Level.exit(): that
	//falls back to returning other transitions when there is no regular exit
	public static int regularExit() {
		for (LevelTransition transition : Dungeon.level.transitions) {
			if (transition.type == LevelTransition.Type.REGULAR_EXIT) {
				return transition.cell();
			}
		}
		return -1;
	}

	//shortest distance to any walkable neighbor of a cell that is itself not
	//walkable (locked doors and the like), or MAX_VALUE
	public static int adjacentDist(int cell, Snapshot s) {
		int best = Integer.MAX_VALUE;
		for (int offset : PathFinder.NEIGHBOURS8) {
			int n = cell + offset;
			if (n >= 0 && n < s.dist.length && s.dist[n] < best) {
				best = s.dist[n];
			}
		}
		return best;
	}

	//nearest cell to stand on and search from: walkable, unsearched, and next to a
	//findable secret (reads level.secret[] - see Bot.CHEAT_SECRETS). -1 when none
	public static int nearestSearchSpot(Hero hero, Snapshot s) {
		Level level = Dungeon.level;
		int best = -1;
		int bestDist = Integer.MAX_VALUE;
		for (int c = 0; c < s.dist.length; c++) {
			if (!level.secret[c]) continue;
			//secrets that searching can never reveal are not worth standing next to
			Trap trap = level.traps.get(c);
			if (trap != null && !trap.canBeSearched) continue;
			for (int offset : PathFinder.NEIGHBOURS8) {
				int n = c + offset;
				if (n < 0 || n >= s.dist.length) continue;
				if (!s.pass[n] || Bot.hasSearched(n) || Bot.isBlacklisted(n)) continue;
				if (s.dist[n] < bestDist) {
					bestDist = s.dist[n];
					best = n;
				}
			}
		}
		return best;
	}

	//whether anything on this list of shooters has a clear line to the cell
	public static boolean exposedTo( List<Mob> shooters, int cell ) {
		for (Mob mob : shooters) {
			if (lineFree(mob.pos, cell, -1)) return true;
		}
		return false;
	}

	//nearest reachable cell no shooter has a straight line to, ties broken toward
	//fewer attack slots (once they walk over, better to meet them in a choke).
	//-1 when nothing within maxTrek steps is out of their sight
	public static int coverSpot( Hero hero, Snapshot s, List<Mob> shooters, int maxTrek ) {
		int best = -1;
		int bestDist = Integer.MAX_VALUE;
		int bestSlots = Integer.MAX_VALUE;
		for (int c = 0; c < s.dist.length; c++) {
			if (c == hero.pos || !s.pass[c] || s.hazard[c] || s.dist[c] > maxTrek
					|| s.dist[c] > bestDist || Bot.isBlacklisted(c)) continue;
			if (Actor.findChar(c) != null) continue;
			if (exposedTo(shooters, c)) continue;
			int slots = attackSlots(c);
			if (s.dist[c] < bestDist || slots < bestSlots) {
				best = c;
				bestDist = s.dist[c];
				bestSlots = slots;
			}
		}
		return best;
	}

	//whether a straight line between the cells is clear of los-blocking terrain
	public static boolean lineFree( int from, int to ) {
		return lineFree(from, to, -1);
	}

	//straight-line sight approximation: walks a bresenham line and reports whether
	//it is clear of los-blocking terrain, treating closedDoor as shut. cruder than
	//the game's shadowcasting, but good enough for judging ambush cells and
	//ranged-attack cover; a rare wrong call just costs one extra reposition
	private static boolean lineFree( int from, int to, int closedDoor ) {
		Level level = Dungeon.level;
		int w = level.width();
		int x = from % w, y = from / w;
		int x1 = to % w, y1 = to / w;
		int dx = Math.abs(x1 - x), dy = Math.abs(y1 - y);
		int sx = x < x1 ? 1 : -1, sy = y < y1 ? 1 : -1;
		int err = dx - dy;
		while (x != x1 || y != y1) {
			int e2 = 2 * err;
			if (e2 > -dy) { err -= dy; x += sx; }
			if (e2 < dx)  { err += dx; y += sy; }
			int cell = x + y * w;
			if (cell == to) break;
			if (cell == closedDoor || level.losBlocking[cell]) return false;
		}
		return true;
	}

	//how many cells a melee attacker could stand on to strike someone at this cell.
	//8 in the open, 2 in a corridor or doorway, 1 in a dead end
	public static int attackSlots(int c) {
		Level level = Dungeon.level;
		int slots = 0;
		for (int offset : PathFinder.NEIGHBOURS8) {
			int n = c + offset;
			if (n >= 0 && n < level.length() && level.passable[n]) slots++;
		}
		return slots;
	}

	//how many cells beside c these mobs could actually stand on to strike, given
	//they have to path there around c rather than through it. unlike attackSlots
	//this tells corridors apart: with the whole pack on one end the far slot only
	//counts if a flanking route no longer than maxSteps exists, so 1 means a true
	//one-at-a-time squeeze while a doorway between two occupied rooms is still 2
	public static int reachableAttackSlots(int c, List<Mob> mobs, int maxSteps) {
		Level level = Dungeon.level;
		int length = level.length();
		int[] steps = new int[length];
		Arrays.fill(steps, Integer.MAX_VALUE);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		for (Mob mob : mobs) {
			steps[mob.pos] = 0;
			queue.add(mob.pos);
		}
		while (!queue.isEmpty()) {
			int cell = queue.poll();
			if (steps[cell] >= maxSteps) continue;
			for (int offset : PathFinder.NEIGHBOURS8) {
				int n = cell + offset;
				if (n < 0 || n >= length || n == c || !level.passable[n]
						|| steps[n] <= steps[cell] + 1) continue;
				steps[n] = steps[cell] + 1;
				queue.add(n);
			}
		}
		int slots = 0;
		for (int offset : PathFinder.NEIGHBOURS8) {
			int n = c + offset;
			if (n >= 0 && n < length && steps[n] < Integer.MAX_VALUE) slots++;
		}
		return slots;
	}

	//nearest free cell where the hunters can only ever bring one attacker to bear
	//(see reachableAttackSlots), ties broken toward the tighter squeeze. -1 when
	//none within maxTrek steps
	public static int chokePoint(Hero hero, Snapshot s, List<Mob> hunters, int maxTrek, int flankSteps) {
		int best = -1;
		int bestDist = Integer.MAX_VALUE;
		int bestSlots = Integer.MAX_VALUE;
		for (int c = 0; c < s.dist.length; c++) {
			if (c == hero.pos || !s.pass[c] || s.hazard[c] || s.dist[c] > maxTrek
					|| s.dist[c] > bestDist || Bot.isBlacklisted(c)) continue;
			if (Actor.findChar(c) != null) continue;
			if (attackSlots(c) > 2) continue; //cheap prefilter before the flood fill
			int slots = reachableAttackSlots(c, hunters, flankSteps);
			if (slots > 1) continue;
			if (s.dist[c] < bestDist || slots < bestSlots) {
				best = c;
				bestDist = s.dist[c];
				bestSlots = slots;
			}
		}
		return best;
	}

	//honest-mode fallback: nearest walkable cell the bot has not searched from yet
	public static int nearestUnsearched(Hero hero, Snapshot s) {
		int best = -1;
		int bestDist = Integer.MAX_VALUE;
		for (int c = 0; c < s.dist.length; c++) {
			if (c == hero.pos || !s.pass[c] || Bot.hasSearched(c) || Bot.isBlacklisted(c)) continue;
			if (s.dist[c] < bestDist) {
				bestDist = s.dist[c];
				best = c;
			}
		}
		return best;
	}
}
