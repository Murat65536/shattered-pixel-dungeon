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
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Piranha;
import com.watabou.utils.Random;

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

		//interacts now when possible, otherwise moves one safe step toward the target
		protected static boolean issueHandle( Hero hero, String behavior, int cell, BotPaths.Snapshot s ) {
			Char target = Actor.findChar(cell);
			boolean canActNow = cell == hero.pos || Dungeon.level.adjacent(hero.pos, cell)
					|| target instanceof Mob && hero.fieldOfView[cell] && hero.canAttack(target);
			if (!canActNow) return issueMove(hero, behavior, cell, s);
			return issue(hero, behavior, cell, false);
		}

		//moves exactly one safe tile, without triggering an incidental heap, stair,
		//alchemy pot, or other action attached to that intermediate cell
		protected static boolean issueMove( Hero hero, String behavior, int destination,
		                                    BotPaths.Snapshot s ) {
			int step = BotPaths.nextStepTo(destination, s);
			return step != -1 && issue(hero, behavior, step, true);
		}

		private static boolean issue( Hero hero, String behavior, int cell, boolean move ) {
			if (!Bot.guardIssue(hero, behavior, cell)) return false;
			Bot.log("%s -> %d", behavior, cell);
			if (move) {
				hero.curAction = new HeroAction.Move(cell);
				hero.lastAction = null;
				hero.next();
				return true;
			} else if (hero.handle(cell)) {
				hero.next();
				return true;
			}
			return false;
		}

		//uses Char.hitChance() to get the exact hit probability
		protected static float hitChance( Char char1, Char char2 ) {
			return Char.hitChance(char1, char2, 1f);
		}

		//average of a few damage rolls on a private seeded generator: stable between turns, game rng untouched
		protected static float avgDamage( Mob mob ) {
			Random.pushGenerator(mob.id());
			float total = 0;
			final int samples = 10;
			for (int i = 0; i < samples; i++) {
				total += mob.damageRoll();
			}
			Random.popGenerator();
			return Math.max(1f, total / samples);
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

		//a threat an attack can actually affect. keep this separate from threat():
		//invulnerable enemies still matter when fleeing, taking cover, and routing
		protected static boolean attackable( Hero hero, Mob mob ) {
			return attackable(mob, hero.getClass());
		}

		protected static boolean attackable( Mob mob, Class<?> effect ) {
			return threat(mob) && mob.invisible == 0 && !mob.isInvulnerable(effect);
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
			new Fight(),
			new Eat(),
			new Equip(),
			new DrinkId(),
			new Scrolls(),
			new Loot(),
			new Descend(),
			new Unlock(),
			new Explore(),
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
