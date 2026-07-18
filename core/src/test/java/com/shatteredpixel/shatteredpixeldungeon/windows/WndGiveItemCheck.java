package com.shatteredpixel.shatteredpixeldungeon.windows;

import com.shatteredpixel.shatteredpixeldungeon.items.Amulet;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.WornShortsword;
import com.shatteredpixel.shatteredpixeldungeon.journal.Catalog;

public final class WndGiveItemCheck {

	public static void main(String[] args) {
		assert WndGiveItem.itemClasses(Catalog.MELEE_WEAPONS).contains(WornShortsword.class);
		assert WndGiveItem.itemClasses(Catalog.MISC_EQUIPMENT).contains(Amulet.class);
		assert WndGiveItem.itemClasses(Catalog.MISC_CONSUMABLES).contains(Gold.class);
		assert WndGiveItem.itemClasses(Catalog.ENCHANTMENTS).isEmpty();
	}
}
