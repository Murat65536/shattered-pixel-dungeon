package com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.behaviors;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.Bot;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotBrain;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotItems;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.bot.BotPaths;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade;

//scrolls, in decreasing order of certainty: known upgrades onto equipped gear,
//known identifies onto mystery items, then reading unknowns to learn them.
//scrolls go through Bot.requestUse because they can open item selection prompts
public class Scrolls extends BotBrain.Behavior {
    @Override
    public String name() {
        return "scrolls";
    }

    @Override
    public boolean tryAct(Hero hero, BotPaths.Snapshot s ) {
        if (hero.visibleEnemies() > 0 || hero.isStarving()) return false;

        ScrollOfUpgrade upgrade = hero.belongings.getItem(ScrollOfUpgrade.class);
        if (upgrade != null && upgrade.isKnown()) {
            Item target = BotItems.upgradeTarget(hero);
            if (target != null) {
                return Bot.requestUse(upgrade, Scroll.AC_READ, target,
                        "scroll of upgrade on " + target.name());
            }
        }

        ScrollOfIdentify identify = hero.belongings.getItem(ScrollOfIdentify.class);
        if (identify != null && identify.isKnown()) {
            Item target = BotItems.firstUnidentified(hero);
            if (target != null) {
                return Bot.requestUse(identify, Scroll.AC_READ, target,
                        "scroll of identify on " + target.name());
            }
        }

        if (hero.HP < hero.HT * 0.8f) return false;
        if (Actor.now() < BotItems.nextIdReadAt) return false;

        Scroll unknown = BotItems.unknownScroll(hero);
        if (unknown == null) return false;

        BotItems.nextIdReadAt = Actor.now() + 30;
        return Bot.requestUse(unknown, Scroll.AC_READ, null, "unknown scroll");
    }
}
