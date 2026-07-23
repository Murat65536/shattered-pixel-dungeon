package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors.Fight;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.levels.DeadEndLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

public final class EnemyAwarenessCheck {

    public static void main( String[] args ) {
        Level previousLevel = Dungeon.level;
        boolean previouslyEnabled = Bot.enabled;
        try {
            DeadEndLevel level = new DeadEndLevel();
            level.setSize(31, 31);
            level.mobs = new HashSet<>();
            level.blobs = new HashMap<>();
            Arrays.fill(level.passable, true);
            Arrays.fill(level.visited, true);
            Dungeon.level = level;

            Hero hero = new Hero();
            Rat enemy = new Rat();
            Rat ally = new Rat();
            ally.alignment = Char.Alignment.ALLY;
            level.mobs.add(enemy);
            level.mobs.add(ally);

            Bot.enabled = false;
            assert hero.getVisibleEnemies().isEmpty();

            Bot.enabled = true;
            assert hero.getVisibleEnemies().size() == 1;
            assert hero.getVisibleEnemies().get(0) == enemy;
            assert BotBrain.Behavior.attackable(hero, enemy);

            Rat meleeInvulnerable = new Rat() {
                @Override
                public boolean isInvulnerable(Class effect) {
                    return effect == Hero.class;
                }
            };
            assert !BotBrain.Behavior.attackable(hero, meleeInvulnerable);
            assert BotBrain.Behavior.attackable(meleeInvulnerable, EnemyAwarenessCheck.class);

            enemy.pos = 42;
            assert hero.getVisibleEnemies().get(0).pos == 42;
            assert !new Fight().tryAct(hero, BotPaths.snapshot(hero));
        } finally {
            Bot.enabled = previouslyEnabled;
            Dungeon.level = previousLevel;
        }
    }
}
