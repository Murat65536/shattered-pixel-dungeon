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
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
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
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;

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
			Mob best = null;
			int bestDist = Integer.MAX_VALUE;
			for (Mob mob : hero.getVisibleEnemies()) {
				if (mob.alignment != Char.Alignment.ENEMY || mob.state == mob.PASSIVE) continue;
				if (Bot.isBlacklisted(mob.pos)) continue;
				boolean inReach = hero.canAttack(mob);
				if (!inReach && !s.reachable(mob.pos)) continue;
				int dist = inReach ? 0 : s.dist[mob.pos];
				if (dist < bestDist) {
					bestDist = dist;
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
				if (!heap.seen || !worthLooting(heap) || Bot.isBlacklisted(heap.pos)) continue;
				if (!s.reachable(heap.pos)) continue;
				if (s.dist[heap.pos] < bestDist) {
					bestDist = s.dist[heap.pos];
					best = heap;
				}
			}
			return best != null && issueHandle(hero, name(), best.pos);
		}

		private boolean worthLooting( Heap heap ) {
			switch (heap.type) {
				case HEAP:
				case CHEST:
				case TOMB:
				case SKELETON:
				case REMAINS:
					return true;
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
