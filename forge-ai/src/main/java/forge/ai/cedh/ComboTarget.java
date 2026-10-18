package forge.ai.cedh;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
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

    /** Us (Great Oak Guardian untaps our own creatures). */
    static ComboTarget self() {
        return ai -> ai;
    }

    /**
     * The opponent who draws next: the active player if they haven't reached their draw step, else the next opponent
     * in turn order. Null if their library is already empty: they lose on that draw, and the next opponent's library
     * waits for the next turn (see the Knacksaw line in {@link ComboLibrary}).
     */
    static ComboTarget nextOpponentToDraw() {
        return ai -> {
            Game game = ai.getGame();
            PhaseHandler ph = game.getPhaseHandler();
            Player active = ph.getPlayerTurn();
            PlayerCollection order = game.getPlayersInTurnOrder(active);
            if (ph.getPhase() == null || !ph.getPhase().isBefore(PhaseType.DRAW)) {
                order.remove(active);
                order.add(active);
            }
            for (Player p : order) {
                if (p.isOpponentOf(ai)) {
                    return p.getCardsIn(ZoneType.Library).isEmpty() ? null : p;
                }
            }
            return null;
        };
    }
}
