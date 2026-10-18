package forge.ai.cedh;

import java.util.function.Predicate;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;

/**
 * One action of a line. Steps are guarded by the board, not sequenced: the planner takes the first step whose
 * {@link #when()} holds, so a loop step is simply chosen again after it resolves.
 */
public sealed interface ComboStep permits ComboStep.Cast, ComboStep.Activate, ComboStep.TapForMana {
    /** Board-state guard; the step is skipped while false. */
    Predicate<Player> when();

    /** Counted in the line's affordability check while {@link #when()} holds. Self-funding loop steps are not. */
    boolean setup();

    /** Cast {@code card} from hand, announcing {@code x} (0 if the card has no X). */
    record Cast(String card, int x, Predicate<Player> when, boolean setup) implements ComboStep {
        static Cast fromHand(String card) {
            ComboPiece inHand = ComboPiece.inHand(card);
            return new Cast(card, 0, p -> inHand.find(p) != null, true);
        }
    }

    /** Activate the {@code api} ability of our permanent {@code card}; {@code target} is null for untargeted ones. */
    record Activate(String card, ApiType api, ComboTarget target, Predicate<Player> when, boolean setup) implements ComboStep {}

    /**
     * Tap one untapped permanent of ours matching {@code source} for mana (an ability whose only cost is {T}); the
     * mana floats for the next step. Loop fuel, so never part of the affordability check.
     */
    record TapForMana(Predicate<Card> source, Predicate<Player> when) implements ComboStep {
        @Override
        public boolean setup() {
            return false;
        }
    }
}
