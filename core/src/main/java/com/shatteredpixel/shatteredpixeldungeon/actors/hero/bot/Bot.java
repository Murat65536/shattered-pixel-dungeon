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
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.InventoryPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;

import java.util.HashSet;

//an autonomous player: decides an action whenever the hero becomes ready for input.
//decision logic runs on the actor thread (via Hero.ready() -> move()), while window
//automation and resuming a parked hero happen on the render thread (via onRenderTick()).
public class Bot {

	//toggled in-game with the bot keybind; intentionally not persisted
	public static volatile boolean enabled = false;

	//echo each decision to the in-game log as well as stdout
	public static boolean chatty = true;

	//when stuck, read level.secret[] to walk straight to hidden doors instead of
	//sweeping the whole floor with searches. a cheat, but it prevents soft-locks
	public static boolean CHEAT_SECRETS = true;

	//written by the render thread each frame, read by the actor thread; the actor thread
	//must not inspect scene members itself
	static volatile boolean uiBlocking = false;
	//a decision was skipped because a window was up; render thread kicks once it clears
	static volatile boolean deferred = false;

	// *** actor thread: decision entry point, called from Hero.ready() ***

	public static void move() {
		if (!enabled) return;

		Hero hero = Dungeon.hero;
		if (hero == null || !hero.isAlive() || !hero.ready || hero.curAction != null
				|| Dungeon.level == null) {
			return;
		}

		if (uiBlocking) {
			deferred = true;
			return;
		}

		BotBrain.decide(hero);
	}

	// *** render thread ***

	public static void onRenderTick() {
		uiBlocking = GameScene.interfaceBlockingHero();
		if (!enabled) return;

		runPendingUse();
		BotWindows.process();

		if (deferred && !uiBlocking) {
			deferred = false;
			kick();
		}
	}

	public static void toggle() {
		enabled = !enabled;
		if (enabled) {
			GLog.p("Bot: ON");
			kick();
		} else {
			pendingUse = null;
			GLog.n("Bot: OFF");
			GameScene.cancel();
		}
	}

	//resumes a hero that is idle-parked waiting for input, exactly as tapping a cell would.
	//an idle hero leaves the actor thread parked with Actor.current still set, so nothing
	//re-runs Hero.ready() until someone calls hero.next()
	static void kick() {
		Hero hero = Dungeon.hero;
		if (hero != null && hero.isAlive() && hero.ready && hero.curAction == null) {
			hero.next();
		}
	}

	public static void disable( String reason ) {
		if (!enabled) return;
		enabled = false;
		pendingUse = null;
		log("disabled: %s", reason);
		GLog.n("Bot: OFF (%s)", reason);
	}

	// *** deferred item use: decided on the actor thread, run on the render thread ***

	//using an item can open selection prompts and windows, which are only safe to
	//touch from the render thread, so the bot uses items the way the player does

	private static class UseIntent {
		final Item item;
		final String action;
		final Item answer; //what to feed a selection prompt the use opens, if any

		UseIntent( Item item, String action, Item answer ) {
			this.item = item;
			this.action = action;
			this.answer = answer;
		}
	}

	private static volatile UseIntent pendingUse = null;

	//actor thread; the hero stays parked until the render thread performs the use
	static boolean requestUse( Item item, String action, Item answer, String note ) {
		log("use -> %s", note);
		pendingUse = new UseIntent(item, action, answer);
		return true;
	}

	//render thread
	private static void runPendingUse() {
		UseIntent use = pendingUse;
		if (use == null) return;

		Hero hero = Dungeon.hero;
		if (hero == null || !hero.isAlive()) {
			pendingUse = null;
			return;
		}
		if (!hero.ready || uiBlocking) return; //wait for a clean moment

		pendingUse = null;
		use.item.execute(hero, use.action);

		//the use may have opened an item selection prompt: either the inventory
		//pane's selector, or a WndBag window on small-screen UI
		WndBag.ItemSelector selector = InventoryPane.currentSelector();
		if (selector != null && use.answer != null && selector.itemSelectable(use.answer)) {
			InventoryPane.answerSelection(use.answer);
		} else if (selector == null) {
			Window front = GameScene.frontWindow();
			if (front instanceof WndBag) {
				BotWindows.answerBagWindow((WndBag) front, use.answer);
			}
		}

		//if the use fizzled without spending time (e.g. while blinded), resume the loop
		if (hero.ready && hero.curAction == null && !GameScene.interfaceBlockingHero()) {
			kick();
		}
	}

	public static void log( String text, Object... args ) {
		String msg = args.length == 0 ? text : String.format(text, args);
		System.out.println("[bot] " + msg);
		if (chatty) {
			GLog.i("bot: " + msg);
		}
	}

	// *** anti-stuck guard; all state below is touched by the actor thread only ***

	//failed actions bounce straight back to Hero.ready() without game time passing,
	//so a deterministic decision loop can livelock; these guards break such loops

	private static final int NO_PROGRESS_REST    = 8;
	private static final int NO_PROGRESS_DISABLE = 25;
	private static final int SIG_REPEAT_LIMIT    = 3;
	private static final int DEPTH_BUDGET        = 3000;
	private static final int IDLE_LIMIT          = 50;

	private static float lastNow = Float.NaN;
	private static int noProgress = 0;
	private static String lastSig = null;
	private static int sigRepeats = 0;
	private static int guardDepth = -1;
	private static int guardBranch = -1;
	private static int depthDecisions = 0;
	private static int idleStreak = 0;
	private static final HashSet<Integer> blacklist = new HashSet<>();
	private static final HashSet<Integer> searchedCells = new HashSet<>();

	//returns false when the guard consumed this decision (forced a wait or disabled)
	static boolean guardTick( Hero hero ) {
		if (Dungeon.depth != guardDepth || Dungeon.branch != guardBranch) {
			guardDepth = Dungeon.depth;
			guardBranch = Dungeon.branch;
			blacklist.clear();
			searchedCells.clear();
			depthDecisions = 0;
			idleStreak = 0;
			lastSig = null;
			sigRepeats = 0;
			BotItems.nextIdDrinkAt = 0;
			BotItems.nextIdReadAt = 0;
		}

		depthDecisions++;
		if (depthDecisions > 2*DEPTH_BUDGET) {
			disable("decision budget exhausted on this floor");
			return false;
		}

		float now = Actor.now();
		if (now != lastNow) {
			lastNow = now;
			noProgress = 0;
		} else {
			noProgress++;
			if (noProgress >= NO_PROGRESS_DISABLE) {
				disable("stuck, game time is not advancing");
				return false;
			}
			if (noProgress >= NO_PROGRESS_REST) {
				log("no progress x%d, forcing a wait", noProgress);
				hero.rest(false);
				return false;
			}
		}
		return true;
	}

	//when a floor eats the decision budget, only fighting and descending remain enabled
	static boolean descendOnly() {
		return depthDecisions > DEPTH_BUDGET;
	}

	//call before issuing; returns false (and blacklists the target) when this exact
	//decision keeps repeating without game time advancing
	static boolean guardIssue( String behavior, int target ) {
		String sig = behavior + ":" + target;
		if (noProgress > 0 && sig.equals(lastSig)) {
			sigRepeats++;
			if (sigRepeats >= SIG_REPEAT_LIMIT) {
				blacklist.add(target);
				log("%s keeps failing on cell %d, blacklisting it this floor", behavior, target);
				sigRepeats = 0;
				lastSig = null;
				return false;
			}
		} else {
			sigRepeats = 0;
		}
		lastSig = sig;
		return true;
	}

	static boolean isBlacklisted( int cell ) {
		return blacklist.contains(cell);
	}

	static void markSearched( int cell ) {
		searchedCells.add(cell);
	}

	static boolean hasSearched( int cell ) {
		return searchedCells.contains(cell);
	}

	static void issuedAction() {
		idleStreak = 0;
	}

	//returns false when the idle streak used up the bot's patience and it shut off
	static boolean idled() {
		idleStreak++;
		if (idleStreak >= IDLE_LIMIT) {
			disable("nothing left to do");
			return false;
		}
		return true;
	}
}
