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

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.Waterskin;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.food.MysteryMeat;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;

//inventory queries for the bot's survival decisions.
//items are used through Item.execute(hero, action), which spends time and resumes the
//actor loop itself (via the operate animation -> Char.onOperateComplete -> next());
//callers must not also call hero.next()
class BotItems {

	//drink a healing potion below this fraction of max HP
	static final float HEAL_AT    = 0.30f;
	//run from adjacent enemies below this fraction of max HP
	static final float RETREAT_AT = 0.20f;
	//rest after clearing a floor below this fraction of max HP
	static final float REST_AT    = 0.70f;

	//a healing potion that is safe to drink knowingly, or null
	static PotionOfHealing healingPotion( Hero hero ) {
		PotionOfHealing potion = hero.belongings.getItem(PotionOfHealing.class);
		return potion != null && potion.isKnown() ? potion : null;
	}

	//the waterskin, if it holds any dew, or null. Waterskin offers its (private)
	//DRINK action only while volume > 0
	static Waterskin drinkableWaterskin( Hero hero ) {
		Waterskin skin = hero.belongings.getItem(Waterskin.class);
		return skin != null && skin.actions(hero).contains("DRINK") ? skin : null;
	}

	//least filling food first, to waste as little of it as possible;
	//risky food only when starving leaves no choice
	static Food pickFood( Hero hero, boolean starving ) {
		Food best = null;
		for (Food food : hero.belongings.getAllItems(Food.class)) {
			if (!starving && food instanceof MysteryMeat) continue;
			if (best == null || food.energy < best.energy) best = food;
		}
		return best;
	}

	//use-to-identify pacing: no sooner than these game times (reset each floor)
	static float nextIdDrinkAt = 0;
	static float nextIdReadAt = 0;

	static Potion unknownPotion( Hero hero ) {
		for (Potion potion : hero.belongings.getAllItems(Potion.class)) {
			if (!potion.isKnown()) return potion;
		}
		return null;
	}

	static Scroll unknownScroll( Hero hero ) {
		for (Scroll scroll : hero.belongings.getAllItems(Scroll.class)) {
			if (!scroll.isKnown()) return scroll;
		}
		return null;
	}

	//what a scroll of upgrade should land on: the weaker piece of equipped gear
	static Item upgradeTarget( Hero hero ) {
		Item weapon = hero.belongings.weapon;
		Armor armor = hero.belongings.armor;
		if (weapon != null && armor != null) {
			return weapon.level() <= armor.level() ? weapon : armor;
		}
		return weapon != null ? weapon : armor;
	}

	static Item firstUnidentified( Hero hero ) {
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
	static MeleeWeapon betterWeapon( Hero hero ) {
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

	//same idea for armor. armor with the warrior's seal attached is never swapped:
	//that would open a seal-transfer prompt, and windows can't be opened safely
	//from the actor thread
	static Armor betterArmor( Hero hero ) {
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
