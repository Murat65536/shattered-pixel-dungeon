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
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors.*;
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
public class BotBrain {

	public abstract static class Behavior {
		protected abstract String name();
		protected abstract boolean tryAct( Hero hero, BotPaths.Snapshot s );
		//essential behaviors keep running when a floor's decision budget runs out
		protected boolean essential() {
			return false;
		}

		//queues an action for a cell through the same dispatch player taps use
		protected static boolean issueHandle( Hero hero, String behavior, int cell ) {
			if (!Bot.guardIssue(hero, behavior, cell)) return false;
			Bot.log("%s -> %d", behavior, cell);
			if (hero.handle(cell)) {
				hero.next();
				return true;
			}
			return false;
		}

		//uses Char.hitChance() to get the exact hit probability
		protected static float hitChance( Char char1, Char char2 ) {
			return Char.hitChance(char1, char2, 1f);
		}

		//piranhas are bound to the water and cannot follow the hero onto land: they
		//never take ambush bait, and swimming out to brawl them is close to suicide.
		//they threaten nothing beyond arm's reach of the water, so simply leave them be
		protected static boolean waterBound( Mob mob ) {
			return mob instanceof Piranha;
		}

		//an enemy worth fighting or fleeing: hostile, not passive, and able to
		//leave the water. the shared first cut every combat behavior applies
		//before its own criteria
		protected static boolean threat( Mob mob ) {
			return mob.alignment == Char.Alignment.ENEMY
					&& mob.state != mob.PASSIVE && !waterBound(mob);
		}
	}

	private static final Behavior[] CHAIN = new Behavior[]{
			new Heal(),
			new Escape(),
			new Douse(),
			new Retreat(),
			new Cover(),
			new Shoot(),
			new Ambush(),
			new Funnel(),
			new Fight("fight", true),
			new Eat(),
			new Equip(),
			new DrinkId(),
			new Scrolls(),
			new Loot(),
			new Fight("cull", false),
			new Unlock(),
			new Explore(),
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

	private static void idle( Hero hero ) {
		if (!Bot.idled()) return;
		Bot.log("idle");
		hero.rest(false);
	}
}
