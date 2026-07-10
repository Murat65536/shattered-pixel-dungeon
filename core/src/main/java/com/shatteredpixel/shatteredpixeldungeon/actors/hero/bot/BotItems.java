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
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.Waterskin;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.food.MysteryMeat;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfDisintegration;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfFrost;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfLightning;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfPrismaticLight;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.SpiritBow;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.MissileWeapon;

import java.util.Arrays;
import java.util.List;

//inventory queries for the bot's survival decisions.
//items are used through Item.execute(hero, action), which spends time and resumes the
//actor loop itself (via the operate animation -> Char.onOperateComplete -> next());
//callers must not also call hero.next()
public class BotItems {

	//drink a healing potion below this fraction of max HP
	public static final float HEAL_AT    = 0.30f;
	//run from adjacent enemies below this fraction of max HP
	public static final float RETREAT_AT = 0.20f;
	//rest after clearing a floor below this fraction of max HP
	public static final float REST_AT    = 0.70f;

	//a healing potion that is safe to drink knowingly, or null
	public static PotionOfHealing healingPotion(Hero hero) {
		PotionOfHealing potion = hero.belongings.getItem(PotionOfHealing.class);
		return potion != null && potion.isKnown() ? potion : null;
	}

	//the waterskin, if it holds any dew, or null. Waterskin offers its (private)
	//DRINK action only while volume > 0
	public static Waterskin drinkableWaterskin(Hero hero) {
		Waterskin skin = hero.belongings.getItem(Waterskin.class);
		return skin != null && skin.actions(hero).contains("DRINK") ? skin : null;
	}

	//least filling food first, to waste as little of it as possible;
	//risky food only when starving leaves no choice
	public static Food pickFood(Hero hero, boolean starving) {
		Food best = null;
		for (Food food : hero.belongings.getAllItems(Food.class)) {
			if (!starving && food instanceof MysteryMeat) continue;
			if (best == null || food.energy < best.energy) best = food;
		}
		return best;
	}

	//use-to-identify pacing: no sooner than these game times (reset each floor)
	public static float nextIdDrinkAt = 0;
	public static float nextIdReadAt = 0;
	public static float nextIdZapAt = 0;

	public static Potion unknownPotion(Hero hero) {
		for (Potion potion : hero.belongings.getAllItems(Potion.class)) {
			if (!potion.isKnown()) return potion;
		}
		return null;
	}

	public static Scroll unknownScroll(Hero hero) {
		for (Scroll scroll : hero.belongings.getAllItems(Scroll.class)) {
			if (!scroll.isKnown()) return scroll;
		}
		return null;
	}

	//what a scroll of upgrade should land on: the weaker piece of equipped gear
	public static Item upgradeTarget(Hero hero) {
		Item weapon = hero.belongings.weapon;
		Armor armor = hero.belongings.armor;
		if (weapon != null && armor != null) {
			return weapon.level() <= armor.level() ? weapon : armor;
		}
		return weapon != null ? weapon : armor;
	}

	public static Item firstUnidentified(Hero hero) {
		for (Item item : hero.belongings) {
			if (!item.isIdentified()) return item;
		}
		return null;
	}

	static int score( MeleeWeapon weapon ) {
		return weapon.tier * 100 + weapon.level();
	}

	static int score( Armor armor ) {
		return armor.tier * 100 + armor.level();
	}

	//a backpack weapon that clearly beats the equipped one and is safe to swap to.
	//never proposes swapping away a mage's staff, and a known-cursed equipped
	//weapon can't be removed at all
	public static MeleeWeapon betterWeapon(Hero hero) {
		if (hero.belongings.weapon instanceof MagesStaff) return null;
		if (hero.belongings.weapon != null && hero.belongings.weapon.cursed) return null;

		int currentScore = -1;
		if (hero.belongings.weapon instanceof MeleeWeapon) {
			currentScore = score((MeleeWeapon) hero.belongings.weapon);
		} else if (hero.belongings.weapon != null) {
			//equipped something exotic; leave it alone
			return null;
		}

		MeleeWeapon best = null;
		for (MeleeWeapon weapon : hero.belongings.getAllItems(MeleeWeapon.class)) {
			if (weapon.isEquipped(hero) || weapon instanceof MagesStaff) continue;
			if (!weapon.isIdentified() || weapon.cursed || weapon.STRReq() > hero.STR()) continue;
			if (score(weapon) > currentScore && (best == null || score(weapon) > score(best))) {
				best = weapon;
			}
		}
		return best;
	}

	//a ranged attack the hero could make right now: the item to use and the
	//action that fires it. the item still has to be aimed at a target
	public static class RangedAttack {
		public final Item item;
		public final String action;

		RangedAttack( Item item, String action ) {
			this.item = item;
			this.action = action;
		}
	}

	//wands whose zap just damages the target; utility wands (corruption, regrowth,
	//warding...) need judgement the bot doesn't have, and area wands (fireblast,
	//corrosion) spread hazards the bot would then have to path around
	private static final List<Class<? extends Wand>> DAMAGE_WANDS = Arrays.asList(
			WandOfMagicMissile.class, WandOfDisintegration.class, WandOfFrost.class,
			WandOfPrismaticLight.class, WandOfLightning.class );

	//an unknown wand worth test-zapping at an enemy. a wand's type is printed on
	//its label before identification; what identifying reveals is its level and
	//whether it is cursed. so every zap builds toward the identify, and the first
	//may reveal a curse the hard way, after which the wand is left alone.
	//peeks at curCharges (displayed as "?" until identified) because zapping an
	//empty wand fizzles without spending time - a livelock, not just a wasted turn
	public static Wand idZapWand(Hero hero) {
		for (Wand wand : hero.belongings.getAllItems(Wand.class)) {
			if (wand.isIdentified() || (wand.cursedKnown && wand.cursed)) continue;
			if (wand.curCharges <= 0) continue;
			//lightning arcs through water, hero included
			if (wand instanceof WandOfLightning && Dungeon.level.water[hero.pos]) continue;
			return wand;
		}
		return null;
	}

	//the best ranged attack in the inventory, or null. renewable sources first:
	//the spirit bow conjures its own arrows and wands recharge on their own,
	//while every throw grinds down a missile's durability
	public static RangedAttack rangedAttack(Hero hero) {
		SpiritBow bow = hero.belongings.getItem(SpiritBow.class);
		if (bow != null) {
			return new RangedAttack(bow, SpiritBow.AC_SHOOT);
		}

		//the staff offers ZAP only while its imbued wand has a charge
		if (hero.belongings.weapon instanceof MagesStaff
				&& !hero.belongings.weapon.cursed
				&& hero.belongings.weapon.actions(hero).contains(MagesStaff.AC_ZAP)) {
			return new RangedAttack(hero.belongings.weapon, MagesStaff.AC_ZAP);
		}

		for (Wand wand : hero.belongings.getAllItems(Wand.class)) {
			if (!wand.isIdentified() || wand.cursed || wand.curCharges <= 0) continue;
			if (!DAMAGE_WANDS.contains(wand.getClass())) continue;
			//lightning arcs through water, hero included
			if (wand instanceof WandOfLightning && Dungeon.level.water[hero.pos]) continue;
			return new RangedAttack(wand, Wand.AC_ZAP);
		}

		//hardest-hitting throwable the hero is strong enough for
		MissileWeapon best = null;
		for (MissileWeapon missile : hero.belongings.getAllItems(MissileWeapon.class)) {
			if (missile.STRReq() > hero.STR()) continue;
			if (lastThrowConfirms(missile)) continue;
			if (best == null || missile.tier > best.tier) best = missile;
		}
		return best != null ? new RangedAttack(best, Item.AC_THROW) : null;
	}

	//mirrors MissileWeapon.doThrow's warning: the last throw of an upgraded or
	//enchanted missile that is about to break opens a confirmation window instead
	//of the targeting prompt, which would strand the bot's queued aim. such a
	//missile is precious enough to keep anyway
	private static boolean lastThrowConfirms(MissileWeapon missile) {
		return ((missile.levelKnown && missile.level() > 0) || missile.hasGoodEnchant()
					|| missile.masteryPotionBonus || missile.enchantHardened)
				&& !missile.extraThrownLeft
				&& missile.quantity() == 1
				&& missile.durabilityLeft() <= missile.durabilityPerUse();
	}

	//same idea for armor. armor with the warrior's seal attached is never swapped:
	//that would open a seal-transfer prompt, and windows can't be opened safely
	//from the actor thread
	public static Armor betterArmor(Hero hero) {
		Armor current = hero.belongings.armor;
		if (current != null && (current.cursed || current.checkSeal() != null)) return null;

		int currentScore = current == null ? -1 : score(current);

		Armor best = null;
		for (Armor armor : hero.belongings.getAllItems(Armor.class)) {
			if (armor.isEquipped(hero)) continue;
			if (!armor.isIdentified() || armor.cursed || armor.STRReq() > hero.STR()) continue;
			if (score(armor) > currentScore && (best == null || score(armor) > score(best))) {
				best = armor;
			}
		}
		return best;
	}
}
