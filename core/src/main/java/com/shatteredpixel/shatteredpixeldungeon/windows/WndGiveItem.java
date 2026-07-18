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

package com.shatteredpixel.shatteredpixeldungeon.windows;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.items.EnergyCrystal;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.Key;
import com.shatteredpixel.shatteredpixeldungeon.journal.Catalog;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingListPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.utils.Reflection;

import java.util.ArrayList;

public class WndGiveItem extends Window {

	private static final int WIDTH_P = 126;
	private static final int HEIGHT_P = 180;
	private static final int WIDTH_L = 216;
	private static final int HEIGHT_L = 130;

	public WndGiveItem() {
		int width = PixelScene.landscape() ? WIDTH_L : WIDTH_P;
		int height = PixelScene.landscape() ? HEIGHT_L : HEIGHT_P;

		ScrollingListPane list = new ScrollingListPane();
		add(list);
		list.addTitle(Messages.get(this, "title"));

		for (Catalog catalog : Catalog.values()) {
			ArrayList<Class<? extends Item>> itemClasses = itemClasses(catalog);
			if (itemClasses.isEmpty()) continue;

			list.addTitle(Messages.titleCase(catalog.title()));
			for (Class<? extends Item> itemClass : itemClasses) {
				Item preview = Reflection.newInstance(itemClass);
				if (preview == null) continue;

				list.addItem(new ScrollingListPane.ListItem(
						new ItemSprite(preview), Messages.titleCase(preview.trueName())) {
					@Override
					public boolean onClick(float x, float y) {
						if (!inside(x, y)) return false;
						give(itemClass);
						return true;
					}
				});
			}
		}

		resize(width, height);
		list.setRect(0, 0, width, height);
	}

	@SuppressWarnings("unchecked")
	static ArrayList<Class<? extends Item>> itemClasses(Catalog catalog) {
		ArrayList<Class<? extends Item>> result = new ArrayList<>();
		for (Class<?> itemClass : catalog.items()) {
			if (Item.class.isAssignableFrom(itemClass)) {
				result.add((Class<? extends Item>) itemClass);
			}
		}
		return result;
	}

	static void give(Class<? extends Item> itemClass) {
		Item item = Reflection.newInstance(itemClass);
		if (item == null) return;
		item.identify();

		if (item instanceof Key) {
			((Key) item).depth = Dungeon.depth;
			Notes.add((Key) item);
			GameScene.updateKeyDisplay();
		} else if (item instanceof Gold) {
			Dungeon.gold += item.quantity();
		} else if (item instanceof EnergyCrystal) {
			Dungeon.energy += item.quantity();
		} else if (!item.collect()) {
			Dungeon.level.drop(item, Dungeon.hero.pos).sprite.drop();
		}

		GLog.i(Messages.capitalize(Messages.get(Dungeon.hero, "you_now_have", item.name())));
	}
}
