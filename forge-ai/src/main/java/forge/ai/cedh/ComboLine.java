package forge.ai.cedh;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import forge.game.player.Player;

/**
 * A known win line. The line is live when every piece is in one of its zones and {@code condition} (an extra
 * board-state check, e.g. "library is empty") holds; it is then played step by step (see {@link ComboStep}).
 * {@code triggerTargets} maps a trigger's host card name to the target its trigger should take; {@code exileChoices}
 * maps a host card name to the card it should exile from hand (Isochron Scepter imprints Dramatic Reversal);
 * {@code soulbondPartners} maps a soulbond card name to the creature it should pair with (Deadeye Navigator with the
 * creature it flickers); {@code choices} makes the resolution-time choices while the line is being played (the
 * Scholar loop's targets, sacrifice and Inalla's {1}).
 */
public record ComboLine(String id, List<ComboPiece> pieces, Predicate<Player> condition, List<ComboStep> steps,
        Map<String, ComboTarget> triggerTargets, Map<String, String> exileChoices,
        Map<String, String> soulbondPartners, ComboChoices choices) {

    /** All cards in hand, cast in this order. */
    static ComboLine castInOrder(String id, List<String> castOrder, Predicate<Player> condition) {
        return new ComboLine(id,
                castOrder.stream().map(ComboPiece::inHand).toList(),
                condition,
                castOrder.stream().<ComboStep>map(ComboStep.Cast::fromHand).toList(),
                Map.of(), Map.of(), Map.of(), ComboChoices.NONE);
    }

    public String winCard() {
        return pieces.get(pieces.size() - 1).card();
    }
}
