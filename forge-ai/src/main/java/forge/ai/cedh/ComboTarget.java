package forge.ai.cedh;

import forge.game.GameEntity;
import forge.game.player.Player;
import forge.game.player.PlayerPredicates;
import forge.game.zone.ZoneType;

/** Picks the target of a step or trigger from the current board. */
@FunctionalInterface
public interface ComboTarget {
    /** Target for the step or trigger, or null if there is none (the step is then not playable). */
    GameEntity pick(Player ai);

    /** Our permanent with that name. */
    static ComboTarget ourCard(String name) {
        return ai -> ComboPlanner.findByName(ai.getCardsIn(ZoneType.Battlefield), name);
    }

    /** The opponent still in the game with the lowest life. */
    static ComboTarget weakestOpponent() {
        return ai -> ai.getOpponents().min(PlayerPredicates.compareByLife());
    }
}
