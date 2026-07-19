package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.levels.DeadEndLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

public final class BotPathStepCheck {

	public static void main( String[] args ) {
		Level previousLevel = Dungeon.level;
		Hero previousHero = Dungeon.hero;
		try {
			DeadEndLevel level = new DeadEndLevel();
			level.setSize(31, 31);
			level.blobs = new HashMap<>();
			level.mobs = new HashSet<>();
			Arrays.fill(level.passable, true);
			Arrays.fill(level.visited, true);
			Arrays.fill(level.heroFOV, true);
			Dungeon.level = level;

			Hero hero = new Hero();
			hero.pos = 10 + 10 * level.width();
			Dungeon.hero = hero;

			int hazard = hero.pos + 1;
			int destination = hero.pos + 4;
			Blob.seed(hazard, 5, ToxicGas.class, level);

			BotPaths.Snapshot snapshot = BotPaths.snapshot(hero);
			int step = BotPaths.nextStepTo(destination, snapshot);

			assert !snapshot.pass[hazard];
			assert step != -1 && step != destination;
			assert level.adjacent(hero.pos, step) && !snapshot.hazard[step];

			level.passable[destination] = false;
			snapshot = BotPaths.snapshot(hero);
			step = BotPaths.nextStepTo(destination, snapshot);
			assert step != -1 && level.adjacent(hero.pos, step);
		} finally {
			Dungeon.hero = previousHero;
			Dungeon.level = previousLevel;
		}
	}
}
