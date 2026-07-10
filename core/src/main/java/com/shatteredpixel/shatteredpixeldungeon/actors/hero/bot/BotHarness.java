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
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.ActionIndicator;
import com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed;
import com.watabou.noosa.Game;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

//measurement harness: plays N bot runs back to back and appends one CSV row per
//run (seed, result, cause of death, depth reached...), so bot changes can be
//compared on data instead of anecdotes. activated from the command line:
//
//  gradlew desktop:debug -Dbot.runs=20 [-Dbot.seed=ABCDEFGHI] [-Dbot.class=warrior] [-Dbot.out=path.csv]
//
//with bot.seed set, run i plays seed+i (a reproducible ladder for A/B comparisons);
//without it every run gets a fresh random seed. runs are marked as custom-seeded so
//they never earn badges or rankings. pressing the bot key mid-run aborts the harness.
//
//lifecycle: tick() runs every frame (hooked into PixelScene.update); the first run
//starts once the title scene is up. Dungeon.fail/win report the run's end,
//Bot.disable reports a stall (budget exhausted, stuck...), and after a short pause
//the next run is launched the same way HeroSelectScene's start button does it
public class BotHarness {

	private enum State { OFF, ARMED, RUNNING, ENDED, DONE }
	private static volatile State state = State.OFF;
	private static boolean configured = false;

	//config, read from system properties on the first tick
	private static int totalRuns = 0;
	private static long baseSeed = -1; //-1: random seed each run
	private static HeroClass heroClass = HeroClass.WARRIOR;
	private static File outFile = null;

	private static int runsDone = 0;
	private static long runSeed = 0;
	private static long runStartMs = 0;
	//real time to launch the next run at, set when a run ends (actor thread
	//may write it while the render thread polls it)
	private static volatile long restartAtMs = 0;

	//running per-session totals for the end-of-harness summary
	private static int wins = 0, stalls = 0, depthSum = 0;

	public static boolean active() {
		return state == State.RUNNING || state == State.ENDED;
	}

	// *** render thread: frame driver, called from PixelScene.update ***

	public static void tick() {
		if (!configured) configure();

		switch (state) {
			case OFF: case DONE:
				return;

			case ARMED:
				//wait for boot to reach the title screen, then take over
				if (Game.scene() instanceof TitleScene) {
					startRun();
				}
				return;

			case RUNNING:
				//the bot key was pressed mid-run: the user wants their game back
				if (!Bot.enabled && Dungeon.hero != null && Dungeon.hero.isAlive()) {
					log("aborted by user, leaving the current game as it is");
					state = State.DONE;
				}
				return;

			case ENDED:
				if (System.currentTimeMillis() >= restartAtMs) {
					if (runsDone < totalRuns) {
						startRun();
					} else {
						summarize();
						state = State.DONE;
					}
				}
		}
	}

	private static void configure() {
		configured = true;

		String runs = System.getProperty("bot.runs");
		if (runs == null) return; //normal play session, harness stays off

		try {
			totalRuns = Integer.parseInt(runs);
		} catch (NumberFormatException e) {
			log("bot.runs is not a number: %s", runs);
			return;
		}
		if (totalRuns <= 0) return;

		//any nonempty text converts: seed codes and numbers directly, other text by hash
		String seed = System.getProperty("bot.seed", "");
		if (!seed.isEmpty()) {
			baseSeed = DungeonSeed.convertFromText(seed);
		}

		String cls = System.getProperty("bot.class", "warrior");
		try {
			heroClass = HeroClass.valueOf(cls.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			log("bot.class is not a hero class: %s", cls);
			totalRuns = 0;
			return;
		}

		outFile = new File(System.getProperty("bot.out", "bot-harness.csv"));

		log("%d runs of %s, %s seeds, logging to %s", totalRuns, heroClass.name(),
				baseSeed == -1 ? "random" : "seed ladder from " + DungeonSeed.convertToCode(baseSeed),
				outFile.getAbsolutePath());
		state = State.ARMED;
	}

	//starts a fresh run exactly the way HeroSelectScene's start button does,
	//except the seed is chosen by the harness. Dungeon.initSeed is skipped on
	//purpose: the seed statics are set directly, leaving user settings untouched.
	//all game state is mutated in beforeCreate, i.e. after the old GameScene is
	//destroyed: nulling the hero any earlier crashes the scene's own UI, which
	//keeps dereferencing Dungeon.hero until the end of the frame
	private static void startRun() {
		int slot = GamesInProgress.firstEmpty();
		if (slot == -1) {
			log("no empty save slot, refusing to overwrite existing games");
			summarize();
			state = State.DONE;
			return;
		}

		runSeed = baseSeed == -1 ? DungeonSeed.randomSeed()
				: (baseSeed + runsDone) % DungeonSeed.TOTAL_SEEDS;

		runStartMs = System.currentTimeMillis();
		state = State.RUNNING;
		log("run %d/%d, seed %s", runsDone + 1, totalRuns, DungeonSeed.convertToCode(runSeed));

		Game.switchScene(InterlevelScene.class, new Game.SceneChangeCallback() {
			@Override
			public void beforeCreate() {
				Dungeon.hero = null;
				Dungeon.daily = Dungeon.dailyReplay = false;
				//a nonempty custom seed marks the run as seeded: no badges, no rankings
				Dungeon.customSeedText = DungeonSeed.convertToCode(runSeed);
				Dungeon.seed = runSeed;

				GamesInProgress.selectedClass = heroClass;
				GamesInProgress.curSlot = slot;
				ActionIndicator.clearAction();
				InterlevelScene.mode = InterlevelScene.Mode.DESCEND;

				Bot.enabled = true;
			}

			@Override
			public void afterCreate() {}
		});
	}

	// *** run endings; fail() arrives on the actor thread, win() on the render thread ***

	//hooked into Dungeon.fail and Dungeon.win: the run ended for real
	public static void onGameEnd( boolean win, Object cause ) {
		if (state != State.RUNNING) return;
		state = State.ENDED;

		record(win ? "win" : "death", causeName(cause));
		if (win) wins++;

		//the game itself handles save deletion on death and victory;
		//disable() also clears any queued item use, and the state check
		//above keeps the resulting onBotDisabled call from double-logging
		Bot.disable("run over");
		//give the death/victory processing a moment to settle
		restartAtMs = System.currentTimeMillis() + 4000;
	}

	//hooked into Bot.disable: the bot gave up (stuck, budget exhausted, nothing
	//to do) but the hero is still alive, so the run ends as a stall
	public static void onBotDisabled( String reason ) {
		if (state != State.RUNNING) return;
		state = State.ENDED;

		record("stall", reason);
		stalls++;

		//unlike death, nothing deletes an abandoned game; do it here or the
		//save slots silt up with zombie runs
		Dungeon.deleteGame(GamesInProgress.curSlot, true);
		restartAtMs = System.currentTimeMillis() + 2000;
	}

	private static String causeName( Object cause ) {
		if (cause == null) return "unknown";
		//mobs pass their class; Doom implementors (buffs, chasms...) pass themselves
		Class<?> type = cause instanceof Class ? (Class<?>) cause : cause.getClass();
		return type.getSimpleName();
	}

	private static void record( String result, String detail ) {
		int depth = Statistics.deepestFloor;
		int lvl = Dungeon.hero != null ? Dungeon.hero.lvl : 0;
		long realSecs = (System.currentTimeMillis() - runStartMs) / 1000;
		depthSum += depth;

		log("run %d/%d over: %s (%s), depth %d, hero level %d, %ds",
				runsDone + 1, totalRuns, result, detail, depth, lvl, realSecs);

		String row = String.format(Locale.ROOT, "%d,%s,%s,%s,%s,%d,%d,%d,%d,%d,%d",
				runsDone + 1, csv(result), csv(detail), Dungeon.customSeedText,
				heroClass.name(), depth, lvl, Statistics.enemiesSlain,
				Statistics.goldCollected, (int) Statistics.duration, realSecs);
		runsDone++;

		try {
			boolean fresh = !outFile.exists() || outFile.length() == 0;
			try (PrintWriter out = new PrintWriter(new FileWriter(outFile, true))) {
				if (fresh) {
					out.println("run,result,cause,seed,class,depth,heroLvl,slain,gold,playSecs,realSecs");
				}
				out.println(row);
			}
		} catch (IOException e) {
			log("could not write %s: %s", outFile.getAbsolutePath(), e);
		}
	}

	//end of the whole harness: print totals and close the game, so scripted
	//invocations (gradlew desktop:debug -Dbot.runs=...) terminate on their own
	private static void summarize() {
		log("finished: %d runs, %d wins, %d stalls, mean depth %.1f, results in %s",
				runsDone, wins, stalls, runsDone > 0 ? (float) depthSum / runsDone : 0f,
				outFile.getAbsolutePath());
		Game.instance.finish();
	}

	//commas and quotes in a field (stall reasons have both) would break the CSV
	private static String csv( String field ) {
		if (field.contains(",") || field.contains("\"")) {
			return "\"" + field.replace("\"", "\"\"") + "\"";
		}
		return field;
	}

	private static void log( String text, Object... args ) {
		System.out.println("[harness] " + String.format(Locale.ROOT, text, args));
	}
}
