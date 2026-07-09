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
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.InventoryPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndResurrect;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndUpgrade;
import com.watabou.noosa.Game;
import com.watabou.noosa.Group;
import com.watabou.utils.Random;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

//keeps a hands-off run moving by answering whatever UI pops up: dismisses text
//windows, picks options, confirms resurrection, and answers item selection prompts.
//render thread only. windows the engine can't crack disable the bot with a message
//rather than hanging the run
class BotWindows {

	private static float cooldown = 0;

	private static Window lastWindow = null;
	private static int sameWindowTicks = 0;

	static void process() {
		cooldown -= Game.elapsed;
		if (cooldown > 0) return;

		Window window = GameScene.frontWindow();
		if (window == null) {
			lastWindow = null;
			answerSelectorPrompt();
			return;
		}

		cooldown = 0.25f;
		if (window == lastWindow) {
			sameWindowTicks++;
		} else {
			lastWindow = window;
			sameWindowTicks = 0;
		}

		//10s on one window means every trick below has failed
		if (sameWindowTicks >= 40) {
			Bot.disable("stuck on window " + window.getClass().getSimpleName());
			return;
		}

		if (window instanceof WndResurrect) {
			if (sameWindowTicks == 0) Bot.log("windows: confirming resurrect");
			click(field(window, WndResurrect.class, "btnContinue"));
			return;
		}

		if (window instanceof WndOptions) {
			pickOption((WndOptions) window);
			return;
		}

		//upgrade confirmation: must confirm, never dismiss — both back-press and the
		//cancel button re-open the item selector, which would loop with the bot's own
		//answer forever. the confirm button enables once the hero is ready, so wait
		//for it rather than escalating (the 10s stuck guard still backstops this)
		if (window instanceof WndUpgrade) {
			Button confirm = field(window, WndUpgrade.class, "btnUpgrade");
			if (confirm != null && confirm.active) {
				Bot.log("windows: confirming upgrade");
				click(confirm);
			}
			return;
		}

		//item selection as a window (small-screen UI, or the pane being unusable);
		//answer it once, and if it somehow lingers let the escalation cancel it
		if (window instanceof WndBag && sameWindowTicks == 0
				&& answerBagWindow((WndBag) window, null)) {
			return;
		}

		//generic escalation: polite back-press, then the first button, then force-close
		if (sameWindowTicks == 0) {
			Bot.log("windows: dismissing %s", window.getClass().getSimpleName());
		}
		if (sameWindowTicks < 3) {
			window.onBackPressed();
		} else if (sameWindowTicks < 6) {
			ArrayList<RedButton> buttons = redButtons(window);
			if (!buttons.isEmpty()) click(buttons.get(0));
			else window.onBackPressed();
		} else {
			window.hide();
		}
	}

	//options windows always carry a decision; back-pressing them away would stall
	//quests and prompts, so pick something. random per the bot's mandate, except
	//resurrection confirms, where only one answer keeps the run going
	private static void pickOption( WndOptions window ) {
		ArrayList<RedButton> buttons = redButtons(window);
		ArrayList<Integer> choices = new ArrayList<>();
		for (int i = 0; i < buttons.size(); i++) {
			if (buttons.get(i).active) choices.add(i);
		}
		if (choices.isEmpty()) {
			window.hide();
			return;
		}

		int choice;
		if (window.getClass().getEnclosingClass() == WndResurrect.class) {
			choice = 0;
		} else {
			choice = choices.get(Random.Int(choices.size()));
		}

		Bot.log("windows: option %d on %s", choice, window.getClass().getSimpleName());

		//same order the buttons themselves use
		window.hide();
		try {
			Method onSelect = WndOptions.class.getDeclaredMethod("onSelect", int.class);
			onSelect.setAccessible(true);
			onSelect.invoke(window, choice);
		} catch (Exception e) {
			Bot.log("windows: onSelect failed: %s", e);
		}
	}

	//item selection prompts (inventory pane) block the hero without any window
	//showing. a planned answer is handled in Bot.runPendingUse; anything else gets
	//the first fitting item, or is cancelled
	private static void answerSelectorPrompt() {
		WndBag.ItemSelector selector = InventoryPane.currentSelector();
		if (selector == null || Dungeon.hero == null) return;

		cooldown = 0.25f;

		Item pick = firstSelectable(selector);
		Bot.log("windows: item prompt -> %s", pick == null ? "cancel" : pick.name());
		InventoryPane.answerSelection(pick);
	}

	//answers a WndBag selection window the way clicking one of its item slots would.
	//returns false when the window is plain inventory browsing with nothing to answer
	static boolean answerBagWindow( WndBag window, Item pick ) {
		WndBag.ItemSelector selector = window.getSelector();
		if (selector == null) return false;

		if (pick == null || !selector.itemSelectable(pick)) {
			pick = firstSelectable(selector);
		}

		Bot.log("windows: item prompt -> %s", pick == null ? "cancel" : pick.name());
		if (pick == null) {
			//proper cancel: onSelect(null) then hide
			window.onBackPressed();
		} else {
			if (selector.hideAfterSelecting()) window.hide();
			selector.onSelect(pick);
		}
		return true;
	}

	private static Item firstSelectable( WndBag.ItemSelector selector ) {
		if (Dungeon.hero == null) return null;
		for (Item item : Dungeon.hero.belongings) {
			if (selector.itemSelectable(item)) return item;
		}
		return null;
	}

	private static ArrayList<RedButton> redButtons( Window window ) {
		ArrayList<RedButton> buttons = new ArrayList<>();
		try {
			Field members = Group.class.getDeclaredField("members");
			members.setAccessible(true);
			for (Object member : (ArrayList<?>) members.get(window)) {
				if (member instanceof RedButton) buttons.add((RedButton) member);
			}
		} catch (Exception e) {
			Bot.log("windows: member walk failed: %s", e);
		}
		return buttons;
	}

	private static Button field( Window window, Class<?> owner, String name ) {
		try {
			Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return (Button) field.get(window);
		} catch (Exception e) {
			Bot.log("windows: field %s failed: %s", name, e);
			return null;
		}
	}

	private static void click( Button button ) {
		if (button == null) return;
		try {
			Method onClick = Button.class.getDeclaredMethod("onClick");
			onClick.setAccessible(true);
			onClick.invoke(button);
		} catch (Exception e) {
			Bot.log("windows: click failed: %s", e);
		}
	}
}
