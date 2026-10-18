package forge.ai.cedh;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import forge.game.player.Player;

/**
 * A known win line. The line is live when every piece is in one of its zones and {@code condition} (an extra
 * board-state check, e.g. "library is empty") holds; it is then played step by step (see {@link ComboStep}).
 * {@code triggerTargets} maps a trigger's host card name to the target its trigger should take.
 */
public record ComboLine(String id, List<ComboPiece> pieces, Predicate<Player> condition, List<ComboStep> steps,
        Map<String, ComboTarget> triggerTargets) {

    /** All cards in hand, cast in this order. */
    static ComboLine castInOrder(String id, List<String> castOrder, Predicate<Player> condition) {
        return new ComboLine(id,
                castOrder.stream().map(ComboPiece::inHand).toList(),
                condition,
                castOrder.stream().<ComboStep>map(ComboStep.Cast::fromHand).toList(),
                Map.of());
    }

    public String winCard() {
        return pieces.get(pieces.size() - 1).card();
    }
}
