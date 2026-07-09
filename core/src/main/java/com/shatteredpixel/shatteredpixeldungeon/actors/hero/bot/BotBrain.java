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
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Healing;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Piranha;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.KindOfWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.Waterskin;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.CrystalKey;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.GoldenKey;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfStrength;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfForce;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.watabou.utils.PathFinder;

//the bot's decision maker: first behavior in the chain that can act, acts.
//every decision must either spend game time or be caught by Bot's guards
class BotBrain {

	interface Behavior {
		String name();
		boolean tryAct( Hero hero, BotPaths.Snapshot s );
		//essential behaviors keep running when a floor's decision budget runs out
		default boolean essential() {
			return false;
		}
	}

	private static final Behavior[] CHAIN = new Behavior[]{
			new Heal(),
			new Retreat(),
			new Ambush(),
			new Fight(),
			new Eat(),
			new Equip(),
			new Loot(),
			new Unlock(),
			new Explore(),
			new DrinkId(),
			new Scrolls(),
			new Rest(),
			new Descend(),
			new Search()
	};

	static void decide( Hero hero ) {
		if (!Bot.guardTick(hero)) return;

		BotPaths.Snapshot s = BotPaths.snapshot(hero);

		boolean descendOnly = Bot.descendOnly();
		for (Behavior behavior : CHAIN) {
			if (descendOnly && !behavior.essential()) {
				continue;
			}
			if (behavior.tryAct(hero, s)) {
				Bot.issuedAction();
				return;
			}
		}

		idle(hero);
	}

	//queues an action for a cell through the same dispatch player taps use
	static boolean issueHandle( Hero hero, String behavior, int cell ) {
		if (!Bot.guardIssue(behavior, cell)) return false;
		Bot.log("%s -> %d", behavior, cell);
		if (hero.handle(cell)) {
			hero.next();
			return true;
		}
		return false;
	}

	private static void idle( Hero hero ) {
		if (!Bot.idled()) return;
		Bot.log("idle");
		hero.rest(false);
	}

	//drink healing when badly hurt and not already healing over time
	private static class Heal implements Behavior {
		@Override
		public String name() {
			return "heal";
		}

		@Override
		public boolean essential() {
			return true;
		}

		@Override
		public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
			if (hero.HP > hero.HT * BotItems.HEAL_AT || hero.buff(Healing.class) != null) {
				return false;
			}

			PotionOfHealing potion = BotItems.healingPotion(hero);
			if (potion != null) {
				Bot.log("heal -> potion of healing");
				potion.execute(hero, Potion.AC_DRINK);
				return true;
			}

			Waterskin skin = BotItems.drinkableWaterskin(hero);
			if (skin != null) {
				Bot.log("heal -> waterskin");
				skin.execute(hero, "DRINK");
				return true;
			}

			return false;
		}
	}

	//step away from adjacent enemies when nearly dead; if cornered, Fight takes over.
	//most enemies match the hero's speed, so fleeing can never actually escape them -
	//after a few steps of failed kiting, stand and fight instead of running forever
	private static class Retreat implements Behavior {

		private static final int MAX_CONSECUTIVE = 6;
		private int streak = 0;

		@Override
		public String name() {
			return "retreat";
		}

		@Override
		public boolean essential() {
			return true;
		}

		@Override
		public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
			if (hero.HP > hero.HT * BotItems.RETREAT_AT) {
				streak = 0;
				return false;
			}

			Mob threat = null;
			for (Mob mob : hero.getVisibleEnemies()) {
				if (mob.alignment == Char.Alignment.ENEMY && mob.state != mob.PASSIVE
						&& Dungeon.level.adjacent(hero.pos, mob.pos)) {
					threat = mob;
					break;
				}
			}
			if (threat == null) {
				streak = 0;
				return false;
			}

			if (streak >= MAX_CONSECUTIVE) return false;

			int step = Dungeon.flee(hero, threat.pos, Dungeon.level.passable, hero.fieldOfView, true);
			if (step == -1 || step == hero.pos) return false;

			if (issueHandle(hero, name(), step)) {
				streak++;
				return true;
			}
			return false;
		}
	}

	//approximates Char.hit(): both sides roll uniformly up to their skill
	static float hitChance( Hero hero, Mob mob ) {
		float acu = hero.attackSkill(mob);
		float def = mob.defenseSkill(hero);
		if (acu <= 0) return 0;
		if (def <= 0) return 1;
		return acu >= def ? 1f - def / (2f * acu) : acu / (2f * def);
	}

	//the flip side: chance the mob's swing lands on the hero
	static float mobHitChance( Hero hero, Mob mob ) {
		float acu = mob.attackSkill(hero);
		float def = hero.defenseSkill(mob);
		if (acu <= 0) return 0;
		if (def <= 0) return 1;
		return acu >= def ? 1f - def / (2f * acu) : acu / (2f * def);
	}

	//piranhas are bound to the water and cannot follow the hero onto land: they
	//never take ambush bait, and swimming out to brawl them is close to suicide.
	//they threaten nothing beyond arm's reach of the water, so simply leave them be
	static boolean waterBound( Mob mob ) {
		return mob instanceof Piranha;
	}

	//against hard-to-hit enemies, don't trade misses: slip behind a closed door
	//(which blocks their sight), wait beside it, and strike as they step through.
	//an enemy that hasn't seen the hero is surprised, and surprise attacks never miss
	private static class Ambush implements Behavior {

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

		static int maxTrek( Hero hero, Mob mob ) {
			float p = hitChance(hero, mob);
			if (p <= 0) return TREK_CAP;
			float hitsLeft = Math.max(1, (float)Math.ceil(mob.HP / avgDamage(hero)));
			float saved = hitsLeft * (1f / p - 1f);
			float q = Math.max(0.05f, mobHitChance(hero, mob));
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

	//attack the closest hostile that is in reach or can be walked to
	private static class Fight implements Behavior {
		@Override
		public String name() {
			return "fight";
		}

		@Override
		public boolean essential() {
			return true;
		}

		@Override
		public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
			//among enemies in reach, hit the one most likely to actually get hit:
			//surprised targets can't dodge at all, wraith-likes are near-hopeless
			Mob best = null;
			float bestScore = -1;
			for (Mob mob : hero.getVisibleEnemies()) {
				if (mob.alignment != Char.Alignment.ENEMY || mob.state == mob.PASSIVE) continue;
				if (waterBound(mob)) continue;
				if (Bot.isBlacklisted(mob.pos)) continue;
				if (!hero.canAttack(mob)) continue;
				float score = mob.surprisedBy(hero, true) ? 2f : hitChance(hero, mob);
				if (score > bestScore) {
					bestScore = score;
					best = mob;
				}
			}
			if (best != null) {
				return issueHandle(hero, name(), best.pos);
			}

			//nothing in reach: close in on the nearest reachable enemy
			int bestDist = Integer.MAX_VALUE;
			for (Mob mob : hero.getVisibleEnemies()) {
				if (mob.alignment != Char.Alignment.ENEMY || mob.state == mob.PASSIVE) continue;
				if (waterBound(mob)) continue;
				if (Bot.isBlacklisted(mob.pos)) continue;
				if (!s.reachable(mob.pos)) continue;
				if (s.dist[mob.pos] < bestDist) {
					bestDist = s.dist[mob.pos];
					best = mob;
				}
			}
			return best != null && issueHandle(hero, name(), best.pos);
		}
	}

	//eat before hunger starts to hurt
	private static class Eat implements Behavior {
		@Override
		public String name() {
			return "eat";
		}

		@Override
		public boolean essential() {
			return true;
		}

		@Override
		public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
			Hunger hunger = hero.buff(Hunger.class);
			if (hunger == null || hunger.hunger() < Hunger.HUNGRY) return false;

			Food food = BotItems.pickFood(hero, hunger.isStarving());
			if (food == null) return false;

			Bot.log("eat -> %s", food.name());
			food.execute(hero, Food.AC_EAT);
			return true;
		}
	}

	//swap to clearly better identified gear while things are calm
	private static class Equip implements Behavior {
		@Override
		public String name() {
			return "equip";
		}

		@Override
		public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
			if (hero.visibleEnemies() > 0) return false;

			MeleeWeapon weapon = BotItems.betterWeapon(hero);
			if (weapon != null) {
				Bot.log("equip -> %s", weapon.name());
				if (weapon.doEquip(hero)) return true;
			}

			Armor armor = BotItems.betterArmor(hero);
			if (armor != null) {
				Bot.log("equip -> %s", armor.name());
				if (armor.doEquip(hero)) return true;
			}

			return false;
		}
	}

	//grab the closest thing worth grabbing
	private static class Loot implements Behavior {
		@Override
		public String name() {
			return "loot";
		}

		@Override
		public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
			Heap best = null;
			int bestDist = Integer.MAX_VALUE;
			for (Heap heap : Dungeon.level.heaps.valueList()) {
				if (!heap.seen || !worthLooting(hero, heap) || Bot.isBlacklisted(heap.pos)) continue;
				if (!s.reachable(heap.pos)) continue;
				if (s.dist[heap.pos] < bestDist) {
					bestDist = s.dist[heap.pos];
					best = heap;
				}
			}
			return best != null && issueHandle(hero, name(), best.pos);
		}

		private boolean worthLooting( Hero hero, Heap heap ) {
			switch (heap.type) {
				case HEAP:
				case CHEST:
				case SKELETON:
				case REMAINS:
					return true;
				//graves spawn a pack of wraiths; only disturb them at fighting strength.
				//Rest will top the hero up first, since tombs are skipped while hurt
				case TOMB:
					return hero.HP >= hero.HT * 0.7f;
				//opening a locked chest without its key is a no-op loop; gate on the key
				case LOCKED_CHEST:
					return Notes.keyCount(new GoldenKey(Dungeon.depth)) > 0;
				case CRYSTAL_CHEST:
					return Notes.keyCount(new CrystalKey(Dungeon.depth)) > 0;
				default:
					//FOR_SALE is never bought
					return false;
			}
		}
	}

	//spend collected keys on locked doors blocking the way
	private static class Unlock implements Behavior {
		@Override
		public String name() {
			return "unlock";
		}

		@Override
		public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
			boolean hasIron = Notes.keyCount(new IronKey(Dungeon.depth)) > 0;
			boolean hasCrystal = Notes.keyCount(new CrystalKey(Dungeon.depth)) > 0;
			if (!hasIron && !hasCrystal) return false;

			int best = -1;
			int bestDist = Integer.MAX_VALUE;
			for (int c = 0; c < s.dist.length; c++) {
				int terrain = Dungeon.level.map[c];
				boolean openable = (terrain == Terrain.LOCKED_DOOR && hasIron)
						|| (terrain == Terrain.CRYSTAL_DOOR && hasCrystal);
				if (!openable) continue;
				if (!(Dungeon.level.visited[c] || Dungeon.level.mapped[c])) continue;
				if (Bot.isBlacklisted(c)) continue;
				int dist = BotPaths.adjacentDist(c, s);
				if (dist < bestDist) {
					bestDist = dist;
					best = c;
				}
			}
			return best != -1 && issueHandle(hero, name(), best);
		}
	}

	//walk toward the nearest spot that reveals unexplored map
	private static class Explore implements Behavior {
		@Override
		public String name() {
			return "explore";
		}

		@Override
		public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
			int cell = BotPaths.nearestFrontier(hero, s);
			return cell != -1 && issueHandle(hero, name(), cell);
		}
	}

	//identify potions the classic way once the floor is quiet: drink them.
	//drunk at high HP so the nasty ones are survivable; spaced out so several
	//bad effects can't stack
	private static class DrinkId implements Behavior {
		@Override
		public String name() {
			return "drink-id";
		}

		@Override
		public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
			if (hero.visibleEnemies() > 0 || hero.isStarving()) return false;

			//strength potions are pure upside; drink as soon as they are known
			PotionOfStrength strength = hero.belongings.getItem(PotionOfStrength.class);
			if (strength != null && strength.isKnown()) {
				Bot.log("drink -> potion of strength");
				strength.execute(hero, Potion.AC_DRINK);
				return true;
			}

			if (hero.HP < hero.HT * 0.8f) return false;
			if (Actor.now() < BotItems.nextIdDrinkAt) return false;

			Potion unknown = BotItems.unknownPotion(hero);
			if (unknown == null) return false;

			BotItems.nextIdDrinkAt = Actor.now() + 30;
			Bot.log("drink-id -> unknown potion");
			unknown.execute(hero, Potion.AC_DRINK);
			return true;
		}
	}

	//scrolls, in decreasing order of certainty: known upgrades onto equipped gear,
	//known identifies onto mystery items, then reading unknowns to learn them.
	//scrolls go through Bot.requestUse because they can open item selection prompts
	private static class Scrolls implements Behavior {
		@Override
		public String name() {
			return "scrolls";
		}

		@Override
		public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
			if (hero.visibleEnemies() > 0 || hero.isStarving()) return false;

			ScrollOfUpgrade upgrade = hero.belongings.getItem(ScrollOfUpgrade.class);
			if (upgrade != null && upgrade.isKnown()) {
				Item target = BotItems.upgradeTarget(hero);
				if (target != null) {
					return Bot.requestUse(upgrade, Scroll.AC_READ, target,
							"scroll of upgrade on " + target.name());
				}
			}

			ScrollOfIdentify identify = hero.belongings.getItem(ScrollOfIdentify.class);
			if (identify != null && identify.isKnown()) {
				Item target = BotItems.firstUnidentified(hero);
				if (target != null) {
					return Bot.requestUse(identify, Scroll.AC_READ, target,
							"scroll of identify on " + target.name());
				}
			}

			if (hero.HP < hero.HT * 0.8f) return false;
			if (Actor.now() < BotItems.nextIdReadAt) return false;

			Scroll unknown = BotItems.unknownScroll(hero);
			if (unknown == null) return false;

			BotItems.nextIdReadAt = Actor.now() + 30;
			return Bot.requestUse(unknown, Scroll.AC_READ, null, "unknown scroll");
		}
	}

	//nap after clearing a floor to head downstairs at full strength.
	//while resting the bot is dormant; regen at full HP, damage, or a newly
	//visible enemy all interrupt back into Hero.ready() and wake it
	private static class Rest implements Behavior {
		@Override
		public String name() {
			return "rest";
		}

		@Override
		public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
			if (hero.HP > hero.HT * BotItems.REST_AT) return false;
			if (hero.visibleEnemies() > 0) return false;
			//regen does not tick while starving; resting would never end
			if (hero.isStarving()) return false;
			if (Dungeon.bossLevel()) return false;

			Bot.log("rest");
			hero.rest(true);
			return true;
		}
	}

	//take the stairs down once there is nothing else left to do
	private static class Descend implements Behavior {
		@Override
		public String name() {
			return "descend";
		}

		@Override
		public boolean essential() {
			return true;
		}

		@Override
		public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
			if (Dungeon.level.locked) return false;
			int exit = BotPaths.regularExit();
			if (exit == -1 || Bot.isBlacklisted(exit)) return false;
			if (hero.pos != exit && !s.reachable(exit)) return false;
			return issueHandle(hero, name(), exit);
		}
	}

	//last resort when nothing is explorable and the way down is missing or cut off:
	//hunt for secret doors. see Bot.CHEAT_SECRETS for how honest this is
	private static class Search implements Behavior {
		@Override
		public String name() {
			return "search";
		}

		@Override
		public boolean tryAct( Hero hero, BotPaths.Snapshot s ) {
			int spot;
			if (Bot.CHEAT_SECRETS) {
				spot = BotPaths.nearestSearchSpot(hero, s);
			} else {
				spot = Bot.hasSearched(hero.pos) ? BotPaths.nearestUnsearched(hero, s) : hero.pos;
			}
			if (spot == -1) return false;

			if (spot == hero.pos) {
				Bot.markSearched(hero.pos);
				Bot.log("search");
				hero.search(true);
				return true;
			}
			return issueHandle(hero, name(), spot);
		}
	}
}
