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
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.watabou.utils.PathFinder;

//map queries for the bot: what is reachable, where is unexplored, where are the stairs.
//actor thread only, like all other PathFinder users
class BotPaths {

	static class Snapshot {
		final boolean[] pass;
		final int[] dist;

		Snapshot( boolean[] pass, int[] dist ) {
			this.pass = pass;
			this.dist = dist;
		}

		boolean reachable( int cell ) {
			return dist[cell] < Integer.MAX_VALUE;
		}
	}

	//distances from the hero over cells that are passable and already known,
	//mirroring the mask Hero.getCloser walks with
	static Snapshot snapshot( Hero hero ) {
		Level level = Dungeon.level;
		int length = level.length();
		boolean[] pass = new boolean[length];
		for (int i = 0; i < length; i++) {
			pass[i] = level.passable[i] && (level.visited[i] || level.mapped[i]);
		}
		//the hero may be standing somewhere the mask excludes (e.g. a just-revealed trap)
		pass[hero.pos] = true;
		PathFinder.buildDistanceMap(hero.pos, pass);
		int[] dist = PathFinder.distance.clone();
		return new Snapshot(pass, dist);
	}

	//nearest known cell from which something unexplored could be seen, or -1
	static int nearestFrontier( Hero hero, Snapshot s ) {
		Level level = Dungeon.level;
		int best = -1;
		int bestDist = Integer.MAX_VALUE;
		for (int c = 0; c < s.dist.length; c++) {
			if (c == hero.pos || !s.pass[c] || s.dist[c] >= bestDist || Bot.isBlacklisted(c)) {
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
	static int regularExit() {
		for (LevelTransition transition : Dungeon.level.transitions) {
			if (transition.type == LevelTransition.Type.REGULAR_EXIT) {
				return transition.cell();
			}
		}
		return -1;
	}

	//shortest distance to any walkable neighbor of a cell that is itself not
	//walkable (locked doors and the like), or MAX_VALUE
	static int adjacentDist( int cell, Snapshot s ) {
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
	static int nearestSearchSpot( Hero hero, Snapshot s ) {
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

	//nearest cell to lie in wait on: walkable, beside a door, and hidden from the
	//mob once that door is shut. the door may still be standing open: doors close
	//behind whoever walks over them, so a spot on the far side of an open door
	//counts as long as the hero's way there actually crosses it.
	//-1 when none within maxTrek steps (the caller decides what walk is worth it)
	static int ambushSpot( Hero hero, Mob mob, Snapshot s, int maxTrek ) {
		Level level = Dungeon.level;
		int best = -1;
		int bestDist = Integer.MAX_VALUE;
		for (int c = 0; c < s.dist.length; c++) {
			if (!s.pass[c] || s.dist[c] >= bestDist || Bot.isBlacklisted(c)) continue;

			int terrain = level.map[c];
			if (terrain == Terrain.DOOR || terrain == Terrain.OPEN_DOOR) continue;

			if (!worksAsAmbush(hero, mob, c)) continue;

			best = c;
			bestDist = s.dist[c];
		}
		return bestDist <= maxTrek ? best : -1;
	}

	private static boolean worksAsAmbush( Hero hero, Mob mob, int c ) {
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
				//the spot works if that hides it from the mob, and getting there
				//from here really does cross it - trivially so when the hero is
				//standing in the doorway itself (stepping off shuts it)
				if (!lineFree(mob.pos, c, d)
						&& (hero.pos == d || !lineFree(hero.pos, c, d))) {
					return true;
				}
			}
		}
		return false;
	}

	//straight-line sight approximation: walks a bresenham line and reports whether
	//it is clear of los-blocking terrain, treating closedDoor as shut. cruder than
	//the game's shadowcasting, but only used to judge cells right next to a door
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

	//honest-mode fallback: nearest walkable cell the bot has not searched from yet
	static int nearestUnsearched( Hero hero, Snapshot s ) {
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
